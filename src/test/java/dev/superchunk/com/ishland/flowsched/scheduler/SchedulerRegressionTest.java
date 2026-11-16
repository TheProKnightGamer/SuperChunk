package dev.superchunk.com.ishland.flowsched.scheduler;

import dev.superchunk.com.ishland.flowsched.executor.ExecutorManager;
import dev.superchunk.com.ishland.flowsched.executor.LockToken;
import dev.superchunk.com.ishland.flowsched.executor.Task;
import dev.superchunk.com.ishland.flowsched.structs.DynamicPriorityQueue;
import dev.superchunk.com.ishland.flowsched.structs.OneTaskAtATimeExecutor;
import io.reactivex.rxjava3.core.Completable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Standalone regression checks; no Minecraft bootstrap or external test framework is needed. */
public final class SchedulerRegressionTest {
    public static void main(String[] args) throws Exception {
        checkTicketTargets();
        checkBrokenHolderFutures();
        checkBrokenSchedulerFutures();
        checkBrokenDependencyBlocker();
        checkSerialExecutor();
        checkWorkerLocks();
        checkDeferredReleasesAndFailures();
        checkStarvationFloor();
        checkSettledTicketTicks();
        checkTicketChurn();
        checkDependencySlots();
        System.out.println("Scheduler regression checks passed");
    }

    private static void checkTicketTargets() {
        Random random = new Random(0x51cedL);
        // Include nonzero initial statuses and temporarily negative counts: a concurrent remove
        // can acquire the holder monitor before its matching add updates the unchecked count.
        for (Status initial : Status.ALL) {
            TicketSet<Integer, Object, Object> tickets = new TicketSet<>(initial, new ObjectFactory.DefaultObjectFactory());
            int[] counts = new int[Status.ALL.length];
            require(tickets.getTargetStatus() == initial, "initial target");
            for (int step = 0; step < 100_000; step++) {
                int index = random.nextInt(counts.length);
                ItemTicket<Integer, Object, Object> ticket = ticket(index, index);
                if (random.nextBoolean()) {
                    tickets.addUnchecked(ticket);
                    counts[index]++;
                } else {
                    tickets.removeUnchecked(ticket);
                    counts[index]--;
                }
                int expected = 0;
                for (int i = 1; i < counts.length; i++) {
                    if (counts[i] > 0) expected = i;
                }
                require(tickets.getTargetStatus().ordinal() == expected, "target after unchecked mutation");
            }
        }

        TicketSet<Integer, Object, Object> tickets = new TicketSet<>(Status.ALL[0], new ObjectFactory.DefaultObjectFactory());
        List<ItemTicket<Integer, Object, Object>> pending = new ArrayList<>();
        for (int status = 0; status < Status.ALL.length; status++) {
            for (int source = 0; source < 20; source++) {
                ItemTicket<Integer, Object, Object> ticket = ticket(status, source);
                require(tickets.checkAdd(ticket), "new ticket rejected");
                require(!tickets.checkAdd(ticket(status, source)), "duplicate ticket accepted");
                tickets.addUnchecked(ticket);
                pending.add(ticket);
            }
        }
        while (!pending.isEmpty()) {
            ItemTicket<Integer, Object, Object> ticket = pending.remove(random.nextInt(pending.size()));
            require(tickets.checkRemove(ticket), "ticket removal failed");
            require(!tickets.checkRemove(ticket), "duplicate removal accepted");
            tickets.removeUnchecked(ticket);
            int expected = pending.stream().mapToInt(t -> t.getTargetStatus().ordinal()).max().orElse(0);
            require(tickets.getTargetStatus().ordinal() == expected, "target after removing checked ticket");
        }
        tickets.assertEmpty();
    }

    private static ItemTicket<Integer, Object, Object> ticket(int status, int source) {
        return new ItemTicket<>(ItemTicket.TicketType.DEPENDENCY, source, Status.ALL[status], null);
    }

    private static void checkBrokenHolderFutures() {
        ItemHolder<Integer, Object, Object, Object> holder = new ItemHolder<>(
                Status.ALL[0], 42, new ObjectFactory.DefaultObjectFactory(), Runnable::run);
        ItemTicket<Integer, Object, Object> ticket = ticket(7, 42);
        holder.addTicket(ticket);
        List<CompletableFuture<Void>> original = new ArrayList<>();
        for (int status = 1; status <= 7; status++) {
            original.add(holder.getFutureForStatus0(Status.ALL[status]));
        }
        holder.setStatus(Status.ALL[1], false);
        holder.setStatus(Status.ALL[2], false);

        List<CompletableFuture<Void>> callbacks = new ArrayList<>();
        for (int status = 3; status <= 7; status++) {
            CompletableFuture<Void> future = holder.getFutureForStatus(Status.ALL[status]);
            require(!future.isDone(), "higher status completed before the holder failed");
            callbacks.add(future.handle((unused, failure) -> {
                require(!Thread.holdsLock(holder), "failure callback ran under the holder monitor");
                // Real dependents can query their holder while reacting to the failure.
                require(holder.getTargetStatus() == Status.ALL[7], "failure changed the ticket target");
                return null;
            }));
        }

        holder.setFlag(ItemHolder.FLAG_BROKEN);
        holder.failPendingFuturesAbove(holder.getStatus());
        for (CompletableFuture<Void> callback : callbacks) requireSucceeded(callback);
        for (int status = 1; status <= 7; status++) {
            if (status <= 2) {
                requireSucceeded(original.get(status - 1));
                requireSucceeded(holder.getFutureForStatus(Status.ALL[status]));
            } else {
                requireUnloaded(original.get(status - 1));
                requireUnloaded(holder.getFutureForStatus(Status.ALL[status]));
            }
        }
        // Repeated dirty ticks must leave the same failure visible to later callers.
        holder.failPendingFuturesAbove(holder.getStatus());
        requireUnloaded(holder.getFutureForStatus(Status.ALL[7]));
        holder.validateAllFutures();

        holder.removeTicket(ticket);
        holder.setStatus(Status.ALL[1], false);
        holder.setStatus(Status.ALL[0], false);
        holder.clearFlag(ItemHolder.FLAG_BROKEN);
        holder.addTicket(ticket);
        List<CompletableFuture<Void>> reloaded = new ArrayList<>();
        for (int status = 1; status <= 7; status++) {
            CompletableFuture<Void> future = holder.getFutureForStatus0(Status.ALL[status]);
            require(future != original.get(status - 1), "reload retained an earlier status future");
            require(!future.isDone(), "reload inherited an earlier completion or failure");
            reloaded.add(future);
        }
        for (int status = 1; status <= 7; status++) holder.setStatus(Status.ALL[status], false);
        for (CompletableFuture<Void> future : reloaded) requireSucceeded(future);
        for (int status = 3; status <= 7; status++) requireUnloaded(original.get(status - 1));
        holder.validateAllFutures();
    }

    private static void checkBrokenSchedulerFutures() {
        // Drive the actual scheduler through a failing upgrade, without thread timing deciding
        // whether observers subscribe before or after MARK_BROKEN.
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        RuntimeException upgradeFailure = new RuntimeException("intentional status-3 upgrade failure");
        AtomicInteger failuresHandled = new AtomicInteger();
        StatusAdvancingScheduler<Integer, Object, Object, Object> scheduler = new StatusAdvancingScheduler<>() {
            @Override protected Executor getBackgroundExecutor() { return work::addLast; }
            @Override protected Status getUnloadedStatus() { return Status.ALL[0]; }
            @Override protected Object makeContext(ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus,
                    KeyStatusPair<Integer, Object, Object>[] dependencies, boolean isUpgrade) {
                if (nextStatus == Status.ALL[3]) throw upgradeFailure;
                return new Object();
            }
            @Override protected ExceptionHandlingAction handleTransactionException(
                    ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus, boolean isUpgrade, Throwable throwable) {
                require(isUpgrade && nextStatus == Status.ALL[3] && throwable == upgradeFailure,
                        "unexpected scheduler failure");
                failuresHandled.incrementAndGet();
                return ExceptionHandlingAction.MARK_BROKEN;
            }
        };
        AtomicInteger reachedTarget = new AtomicInteger();
        ItemHolder<Integer, Object, Object, Object> holder = scheduler.addTicket(42, Status.ALL[7], reachedTarget::incrementAndGet);
        List<CompletableFuture<Void>> observers = new ArrayList<>();
        for (int status = 1; status <= 7; status++) observers.add(holder.getFutureForStatus(Status.ALL[status]));
        int tasks = 0;
        while (!work.isEmpty()) {
            require(++tasks < 1_000, "broken holder never stopped scheduling work");
            work.removeFirst().run();
        }
        require(failuresHandled.get() == 1, "upgrade failure was not handled exactly once");
        require(holder.getStatus() == Status.ALL[2], "failed upgrade advanced the holder");
        require((holder.getFlags() & ItemHolder.FLAG_BROKEN) != 0, "failed upgrade did not mark the holder broken");
        require(reachedTarget.get() == 0, "broken holder ran a successful target callback");
        for (int status = 1; status <= 7; status++) {
            if (status <= 2) requireSucceeded(observers.get(status - 1));
            else requireUnloaded(observers.get(status - 1));
        }
        requireUnloaded(holder.getFutureForStatus(Status.ALL[7]));
    }

    private static void checkBrokenDependencyBlocker() {
        // Item 1 needs item 2 at status 4 to reach 5; item 2's upgrade to 4 fails. Item 2 is broken
        // and its futures fail. Item 1 is not broken but parks in its upgrade to 5: findBrokenBlocker
        // must name item 2, and failWaitersBehind must fail item 1's futures too (pending, they froze
        // the server thread and starved Chunky). Item 3 needs item 1 at 6 and starts after the break;
        // the periodic walk must fail it. Item 4 is healthy and must be left alone.
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        RuntimeException upgradeFailure = new RuntimeException("intentional item-2 upgrade failure");
        StatusAdvancingScheduler<Integer, Object, Object, Object> scheduler = new StatusAdvancingScheduler<>() {
            @Override protected Executor getBackgroundExecutor() { return work::addLast; }
            @Override protected DepStatus getUnloadedStatus() { return DepStatus.ALL[0]; }
            @Override protected Object makeContext(ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus,
                    KeyStatusPair<Integer, Object, Object>[] dependencies, boolean isUpgrade) {
                if (holder.getKey() == 2 && nextStatus == DepStatus.ALL[4]) throw upgradeFailure;
                return new Object();
            }
            @Override protected ExceptionHandlingAction handleTransactionException(
                    ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus, boolean isUpgrade, Throwable throwable) {
                return ExceptionHandlingAction.MARK_BROKEN;
            }
        };
        int brokenBefore = ItemHolder.brokenItemCount();
        ItemHolder<Integer, Object, Object, Object> dependent = scheduler.addTicket(1, DepStatus.ALL[7], () -> { });
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[7], 64) == null, "healthy in-flight item reported blocked");
        int tasks = 0;
        while (!work.isEmpty()) {
            require(++tasks < 1_000, "scheduler never went idle");
            work.removeFirst().run();
        }
        ItemHolder<Integer, Object, Object, Object> broken = scheduler.getHolder(2);
        require(broken != null && (broken.getFlags() & ItemHolder.FLAG_BROKEN) != 0, "dependency was not marked broken");
        require(broken.getStatus() == DepStatus.ALL[3], "broken dependency is not at status 3");
        require(ItemHolder.brokenItemCount() == brokenBefore + 1, "broken item count did not rise by one");
        require(dependent.getStatus() == DepStatus.ALL[4], "dependent is not parked below its broken dependency");
        requireUnloaded(dependent.getFutureForStatus(DepStatus.ALL[7]));
        requireUnloaded(dependent.getFutureForStatus(DepStatus.ALL[5]));
        requireSucceeded(dependent.getFutureForStatus(DepStatus.ALL[4]));
        scheduler.addTicket(1, "late waiter", DepStatus.ALL[7], () -> { });
        requireUnloaded(dependent.getFutureForStatus(DepStatus.ALL[7]));
        ItemHolder<Integer, Object, Object, Object> chained = scheduler.addTicket(3, DepStatus.ALL[7], () -> { });
        ItemHolder<Integer, Object, Object, Object> healthy = scheduler.addTicket(4, DepStatus.ALL[7], () -> { });
        while (!work.isEmpty()) {
            require(++tasks < 2_000, "scheduler never went idle");
            work.removeFirst().run();
        }
        require(chained.getStatus() == DepStatus.ALL[4], "chained item is not parked below the stuck one");
        require(!chained.getFutureForStatus(DepStatus.ALL[7]).isDone(), "test premise: the chained item's future is pending");
        require(scheduler.failWaitersBehindBroken() == 1, "the periodic walk did not fail exactly the chained item");
        requireUnloaded(chained.getFutureForStatus(DepStatus.ALL[7]));
        requireSucceeded(healthy.getFutureForStatus(DepStatus.ALL[7]));
        require(scheduler.failWaitersBehindBroken() == 0, "a second walk failed something again");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[7], 64) == 2, "blocker not found through the dependency");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[5], 64) == 2, "blocker not found for the next status");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[4], 64) == null, "a reached status reported blocked");
        require(scheduler.findBrokenBlocker(2, DepStatus.ALL[7], 64) == 2, "a broken item is its own blocker");
        require(scheduler.findBrokenBlocker(5, DepStatus.ALL[7], 64) == null, "an absent item reported blocked");
        require(scheduler.findBrokenBlocker(3, DepStatus.ALL[7], 64) == 2, "blocker not found through a stuck item");
        require(scheduler.findBrokenBlocker(4, DepStatus.ALL[7], 64) == null, "a healthy item reported blocked");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[7], 0) == null, "the node budget was not honoured");
    }

    /** Like {@link Status}, but status 5 of item 1 needs item 2 at status 4, and of item 3 item 1 at 6. */
    private record DepStatus(int ordinal) implements ItemStatus<Integer, Object, Object> {
        private static final DepStatus[] ALL = new DepStatus[16];
        static {
            for (int i = 0; i < ALL.length; i++) ALL[i] = new DepStatus(i);
        }
        @Override public DepStatus[] getAllStatuses() { return ALL; }
        @Override public Completable upgradeToThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable postUpgradeToThis(Object context) { return Completable.complete(); }
        @Override public Completable preDowngradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable downgradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @SuppressWarnings("unchecked")
        @Override public KeyStatusPair<Integer, Object, Object>[] getDependencies(ItemHolder<Integer, Object, Object, ?> holder) {
            if (this.ordinal == 5 && holder.getKey() == 1) {
                return new KeyStatusPair[]{new KeyStatusPair<>(2, ALL[4])};
            }
            if (this.ordinal == 5 && holder.getKey() == 3) {
                return new KeyStatusPair[]{new KeyStatusPair<>(1, ALL[6])};
            }
            return EMPTY_DEPENDENCIES;
        }
    }

    private static void requireSucceeded(CompletableFuture<Void> future) {
        require(future.isDone(), "status future never completed");
        future.join();
    }

    private static void requireUnloaded(CompletableFuture<Void> future) {
        require(future.isDone(), "broken holder abandoned a pending status future");
        try {
            future.join();
            throw new AssertionError("broken holder completed a higher status successfully");
        } catch (CompletionException failure) {
            require(failure.getCause() == ItemHolder.UNLOADED_EXCEPTION,
                    "broken holder did not expose the canonical unloaded exception");
        }
    }

    private static void checkSerialExecutor() throws Exception {
        ExecutorService backing = Executors.newFixedThreadPool(4);
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            OneTaskAtATimeExecutor serial = new OneTaskAtATimeExecutor(new ConcurrentLinkedQueue<>(), backing);
            int taskCount = 40_000;
            AtomicIntegerArray executions = new AtomicIntegerArray(taskCount);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger overlaps = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(taskCount);
            for (int producer = 0; producer < 4; producer++) {
                int offset = producer * (taskCount / 4);
                producers.execute(() -> {
                    await(start);
                    for (int i = 0; i < taskCount / 4; i++) {
                        int id = offset + i;
                        serial.execute(() -> {
                            if (active.incrementAndGet() != 1) overlaps.incrementAndGet();
                            executions.incrementAndGet(id);
                            active.decrementAndGet();
                            done.countDown();
                        });
                        if ((i & 31) == 0) Thread.yield();
                    }
                });
            }
            start.countDown();
            require(done.await(20, TimeUnit.SECONDS), "serial executor lost a wakeup");
            require(overlaps.get() == 0, "serial executor ran concurrent tasks");
            for (int i = 0; i < taskCount; i++) require(executions.get(i) == 1, "task did not execute exactly once");
            // Repeatedly transition from idle to running, alongside the saturated-queue case.
            for (int i = 0; i < 1_000; i++) {
                CountDownLatch idleTask = new CountDownLatch(1);
                serial.execute(idleTask::countDown);
                require(idleTask.await(5, TimeUnit.SECONDS), "idle executor did not restart");
            }
        } finally {
            producers.shutdownNow();
            backing.shutdownNow();
            require(backing.awaitTermination(5, TimeUnit.SECONDS), "backing executor did not stop");
        }
    }

    private static void checkWorkerLocks() throws Exception {
        List<Thread> workers = new ArrayList<>();
        ExecutorManager manager = new ExecutorManager(4, worker -> {
            worker.setDaemon(true);
            workers.add(worker);
        });
        try {
            int taskCount = 8_000;
            AtomicIntegerArray active = new AtomicIntegerArray(4);
            AtomicInteger overlaps = new AtomicInteger();
            AtomicIntegerArray executions = new AtomicIntegerArray(taskCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            LockToken[] tokens = {new LockToken() {}, new LockToken() {}, new LockToken() {}, new LockToken() {}};
            CountDownLatch done = new CountDownLatch(taskCount);
            for (int index = 0; index < taskCount; index++) {
                final int id = index;
                final int token = index % tokens.length;
                // Mix lock-free work, shared single locks, and overlapping lock pairs.
                final int lockCount = index % 3;
                final int second = (token + 1) % tokens.length;
                final LockToken[] locks = switch (lockCount) {
                    case 0 -> new LockToken[0];
                    case 1 -> new LockToken[] {tokens[token]};
                    default -> new LockToken[] {tokens[token], tokens[second]};
                };
                manager.schedule(new Task() {
                    @Override
                    public void run(Runnable releaseLocks) {
                        if (lockCount > 0 && active.incrementAndGet(token) != 1) overlaps.incrementAndGet();
                        if (lockCount > 1 && active.incrementAndGet(second) != 1) overlaps.incrementAndGet();
                        executions.incrementAndGet(id);
                        Thread.yield();
                        if (lockCount > 1) active.decrementAndGet(second);
                        if (lockCount > 0) active.decrementAndGet(token);
                        releaseLocks.run();
                        releaseLocks.run(); // Repeated releases must remain harmless.
                        done.countDown();
                    }

                    @Override public void propagateException(Throwable throwable) { failure.compareAndSet(null, throwable); }
                    @Override public LockToken[] lockTokens() { return locks; }
                    @Override public int priority() { return id % 16; }
                });
            }
            require(done.await(20, TimeUnit.SECONDS), "worker tasks did not finish");
            require(failure.get() == null, "worker failed: " + failure.get());
            require(overlaps.get() == 0, "tasks shared an exclusive lock");
            for (int i = 0; i < taskCount; i++) require(executions.get(i) == 1, "worker task did not execute exactly once");
        } finally {
            manager.shutdown();
            for (Thread worker : workers) {
                worker.join(5_000);
                require(!worker.isAlive(), "worker did not stop");
            }
        }
    }

    private static void checkDeferredReleasesAndFailures() throws Exception {
        List<Thread> workers = new ArrayList<>();
        // A single worker makes the queue barriers deterministic: passing a barrier
        // means every earlier task was either run or parked on an occupied lock.
        ExecutorManager manager = new ExecutorManager(1, worker -> {
            worker.setDaemon(true);
            workers.add(worker);
        });
        try {
            LockToken[] noLocks = new LockToken[0];
            LockToken[] sharedLock = {new LockToken() {}};
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicReference<Runnable> freeRelease = new AtomicReference<>();
            AtomicReference<Runnable> firstRelease = new AtomicReference<>();
            AtomicReference<Runnable> secondRelease = new AtomicReference<>();
            CountDownLatch secondStarted = new CountDownLatch(1);
            manager.schedule(task(noLocks, freeRelease::set, unexpected::set));
            manager.schedule(task(sharedLock, firstRelease::set, unexpected::set));
            manager.schedule(task(sharedLock, release -> {
                secondRelease.set(release);
                secondStarted.countDown();
            }, unexpected::set));
            workerBarrier(manager);
            require(freeRelease.get() != null && firstRelease.get() != null, "deferred tasks did not run");
            require(secondStarted.getCount() == 1, "returning from run released an asynchronous task's lock");

            // Invoke the retained lock-free callback from another thread after its
            // originating task has returned. It must not release a later task's locks.
            freeRelease.get().run();
            freeRelease.get().run();
            workerBarrier(manager);
            require(secondStarted.getCount() == 1, "lock-free callback released another task's lock");
            firstRelease.get().run();
            require(secondStarted.await(5, TimeUnit.SECONDS), "deferred lock release did not wake its waiter");

            CountDownLatch thirdRan = new CountDownLatch(1);
            manager.schedule(task(sharedLock, release -> {
                release.run();
                thirdRan.countDown();
            }, unexpected::set));
            firstRelease.get().run(); // Old callback must not release the second task's ownership.
            freeRelease.get().run();
            workerBarrier(manager);
            require(thirdRan.getCount() == 1, "stale callback released a successor's lock");
            secondRelease.get().run();
            secondRelease.get().run();
            require(thirdRan.await(5, TimeUnit.SECONDS), "second deferred release did not wake its waiter");

            for (LockToken[] locks : new LockToken[][] {noLocks, sharedLock}) {
                for (boolean releaseBeforeThrow : new boolean[] {false, true}) {
                    RuntimeException expected = new RuntimeException("intentional task failure");
                    AtomicReference<Throwable> propagated = new AtomicReference<>();
                    AtomicInteger reports = new AtomicInteger();
                    CountDownLatch successorRan = new CountDownLatch(1);
                    manager.schedule(task(locks, release -> {
                        if (releaseBeforeThrow) {
                            release.run();
                            release.run();
                        }
                        throw expected;
                    }, throwable -> {
                        propagated.set(throwable);
                        reports.incrementAndGet();
                    }));
                    manager.schedule(task(locks, release -> {
                        release.run();
                        successorRan.countDown();
                    }, unexpected::set));
                    require(successorRan.await(5, TimeUnit.SECONDS), "throwing task stranded its successor");
                    require(propagated.get() == expected && reports.get() == 1,
                            "task failure was not propagated exactly once");
                    require(expected.getSuppressed().length == 0, "exception cleanup failed or released locks twice");
                }
            }
            require(unexpected.get() == null, "unexpected deferred-task failure: " + unexpected.get());
        } finally {
            manager.shutdown();
            for (Thread worker : workers) {
                worker.join(5_000);
                require(!worker.isAlive(), "deferred-test worker did not stop");
            }
        }
    }

    private static Task task(LockToken[] locks, Consumer<Runnable> action, Consumer<Throwable> failure) {
        return new Task() {
            @Override public void run(Runnable releaseLocks) { action.accept(releaseLocks); }
            @Override public void propagateException(Throwable throwable) { failure.accept(throwable); }
            @Override public LockToken[] lockTokens() { return locks; }
            @Override public int priority() { return 0; }
        };
    }

    /**
     * Priorities at or above the starvation floor get every 16th dequeue even while higher
     * priorities keep the queue busy; priorities below the floor keep strict order.
     */
    private static void checkStarvationFloor() {
        DynamicPriorityQueue<Integer> strict = new DynamicPriorityQueue<>(8);
        for (int i = 0; i < 100; i++) strict.enqueue(10_000 + i, 7);
        for (int i = 0; i < 1000; i++) strict.enqueue(i, 1);
        for (int i = 0; i < 1000; i++) {
            require(strict.dequeue() == i, "without a floor the order is strict");
        }

        DynamicPriorityQueue<Integer> queue = new DynamicPriorityQueue<>(8);
        queue.setStarvationFloor(7);
        for (int i = 0; i < 100; i++) queue.enqueue(10_000 + i, 7);
        for (int i = 0; i < 50; i++) queue.enqueue(20_000 + i, 6);
        for (int i = 0; i < 1000; i++) queue.enqueue(i, 1);
        int nextHigh = 0;
        int nextLow = 10_000;
        for (int n = 1; n <= 960; n++) {
            int got = queue.dequeue();
            if (n % 16 == 0) {
                require(got == nextLow++, "every 16th dequeue serves the starved priority, oldest first");
            } else {
                require(got == nextHigh++, "other dequeues keep strict priority order");
            }
        }
        require(nextLow == 10_060 && nextHigh == 900, "60 starved and 900 urgent tasks served");
        // the remaining 100 urgent tasks, then priority 6 (below the floor, never served early), then the rest
        int served6 = 0;
        int served7 = 0;
        Integer got;
        boolean six = false;
        while ((got = queue.dequeue()) != null) {
            if (got >= 20_000) {
                six = true;
                require(nextHigh == 1000, "priority 6 waits for all urgent tasks");
                served6++;
            } else if (got >= 10_000) {
                served7++;
            } else {
                require(!six, "urgent tasks come before priority 6");
                nextHigh++;
            }
        }
        require(nextHigh == 1000 && served6 == 50 && served7 == 40 && queue.size() == 0, "queue drains completely");
    }

    private static StatusAdvancingScheduler<Integer, Object, Object, Object> plainScheduler(Executor background) {
        return new StatusAdvancingScheduler<>() {
            @Override protected Executor getBackgroundExecutor() { return background; }
            @Override protected Status getUnloadedStatus() { return Status.ALL[0]; }
            @Override protected Object makeContext(ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus,
                    KeyStatusPair<Integer, Object, Object>[] dependencies, boolean isUpgrade) {
                return new Object();
            }
            @Override protected ExceptionHandlingAction handleTransactionException(
                    ItemHolder<Integer, Object, Object, Object> holder,
                    ItemStatus<Integer, Object, Object> nextStatus, boolean isUpgrade, Throwable throwable) {
                throw new AssertionError("unexpected scheduler failure", throwable);
            }
        };
    }

    private static void drain(ArrayDeque<Runnable> work) {
        int tasks = 0;
        while (!work.isEmpty()) {
            require(++tasks < 100_000, "scheduler never stopped scheduling work");
            work.removeFirst().run();
        }
    }

    private static void checkSettledTicketTicks() {
        // A ticket change on an item that is at its target and keeps it queues no tick; its callback
        // still runs, and every change of the target is still acted on, down to unloading.
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        StatusAdvancingScheduler<Integer, Object, Object, Object> scheduler = plainScheduler(work::addLast);
        Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
        AtomicInteger fired = new AtomicInteger();
        ItemHolder<Integer, Object, Object, Object> holder =
                scheduler.addTicket(7, a, Status.ALL[5], fired::incrementAndGet);
        drain(work);
        require(holder.getStatus() == Status.ALL[5] && fired.get() == 1, "first ticket reaches its status");

        scheduler.addTicket(7, b, Status.ALL[3], fired::incrementAndGet); // below the target
        require(fired.get() == 2, "a ticket for a reached status runs its callback at once");
        scheduler.addTicket(7, c, Status.ALL[5], fired::incrementAndGet); // at the target
        require(fired.get() == 3, "a ticket at the target runs its callback at once");
        if (StatusAdvancingScheduler.SKIP_SETTLED_TICKS) {
            require(work.isEmpty(), "settled ticket adds queue no tick");
        }
        drain(work);
        scheduler.removeTicket(7, ItemTicket.TicketType.EXTERNAL, b, Status.ALL[3]);
        scheduler.removeTicket(7, ItemTicket.TicketType.EXTERNAL, c, Status.ALL[5]);
        if (StatusAdvancingScheduler.SKIP_SETTLED_TICKS) {
            require(work.isEmpty(), "settled ticket removes queue no tick");
        }
        drain(work);
        require(holder.getStatus() == Status.ALL[5], "item keeps its status while a ticket holds it");

        scheduler.addTicket(7, d, Status.ALL[9], fired::incrementAndGet); // raises the target
        require(!work.isEmpty(), "raising the target queues a tick");
        drain(work);
        require(holder.getStatus() == Status.ALL[9] && fired.get() == 4, "raised target is reached");
        scheduler.removeTicket(7, ItemTicket.TicketType.EXTERNAL, d, Status.ALL[9]); // lowers it again
        require(!work.isEmpty(), "lowering the target queues a tick");
        drain(work);
        require(holder.getStatus() == Status.ALL[5], "item returns to the remaining ticket's status");
        scheduler.removeTicket(7, ItemTicket.TicketType.EXTERNAL, a, Status.ALL[5]);
        drain(work);
        require(scheduler.itemCount() == 0, "item unloads once its last ticket is gone");

        // The same while an upgrade is in flight: the item is parked in its upgrade to status 12.
        io.reactivex.rxjava3.subjects.CompletableSubject gate = io.reactivex.rxjava3.subjects.CompletableSubject.create();
        Status.upgradeGate = gate;
        try {
            AtomicInteger top = new AtomicInteger();
            AtomicInteger low = new AtomicInteger();
            holder = scheduler.addTicket(8, a, Status.ALL[12], top::incrementAndGet);
            drain(work);
            require(holder.getStatus() == Status.ALL[11] && top.get() == 0, "item waits in its upgrade to 12");
            scheduler.addTicket(8, b, Status.ALL[5], low::incrementAndGet);  // reached already
            require(low.get() == 1, "a ticket for a reached status runs its callback during an upgrade");
            scheduler.addTicket(8, c, Status.ALL[12], top::incrementAndGet); // the status being upgraded to
            require(top.get() == 0, "a ticket for the status in flight waits for it");
            if (StatusAdvancingScheduler.SKIP_SETTLED_TICKS) {
                require(work.isEmpty(), "ticket adds during an upgrade queue no tick");
            }
            drain(work);
            scheduler.removeTicket(8, ItemTicket.TicketType.EXTERNAL, b, Status.ALL[5]);
            if (StatusAdvancingScheduler.SKIP_SETTLED_TICKS) {
                require(work.isEmpty(), "ticket removes during an upgrade queue no tick");
            }
            drain(work);
            gate.onComplete();
            drain(work);
            require(holder.getStatus() == Status.ALL[12] && top.get() == 2, "both tickets at 12 get their callback");
            Status.upgradeGate = null;
            scheduler.removeTicket(8, ItemTicket.TicketType.EXTERNAL, c, Status.ALL[12]);
            scheduler.removeTicket(8, ItemTicket.TicketType.EXTERNAL, a, Status.ALL[12]);
            drain(work);
            require(scheduler.itemCount() == 0, "item unloads after an upgrade that tickets were changed during");

            // A target lowered while the upgrade is parked still queues its tick, and the item
            // ends at the remaining ticket's status.
            io.reactivex.rxjava3.subjects.CompletableSubject gate2 = io.reactivex.rxjava3.subjects.CompletableSubject.create();
            Status.upgradeGate = gate2;
            holder = scheduler.addTicket(9, a, Status.ALL[12], NO_CALLBACK);
            drain(work);
            scheduler.addTicket(9, b, Status.ALL[4], NO_CALLBACK);
            drain(work);
            scheduler.removeTicket(9, ItemTicket.TicketType.EXTERNAL, a, Status.ALL[12]); // target 12 -> 4
            require(!work.isEmpty(), "lowering the target during an upgrade queues a tick");
            drain(work);
            gate2.onComplete();
            drain(work);
            require(holder.getStatus() == Status.ALL[4], "item settles at the remaining ticket after the parked upgrade");
            scheduler.removeTicket(9, ItemTicket.TicketType.EXTERNAL, b, Status.ALL[4]);
            drain(work);
            require(scheduler.itemCount() == 0, "item unloads after a cancelled upgrade");
        } finally {
            Status.upgradeGate = null;
        }
    }

    private static final Runnable NO_CALLBACK = () -> { };

    private static void checkTicketChurn() throws Exception {
        // Threads add and remove tickets of random statuses on a few items while a pool runs the
        // ticks. A tick left out wrongly shows as a callback that never runs or an item that never unloads.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            StatusAdvancingScheduler<Integer, Object, Object, Object> scheduler = plainScheduler(pool);
            final int threads = 6;
            final int keys = 12;
            AtomicInteger pendingCallbacks = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final long seed = 0x7ea5L + t;
                Thread thread = new Thread(() -> {
                    try {
                        Random random = new Random(seed);
                        ArrayList<Object[]> held = new ArrayList<>();
                        for (int step = 0; step < 15_000; step++) {
                            if (held.size() < 6 && random.nextInt(3) != 0) {
                                int key = random.nextInt(keys);
                                int status = 1 + random.nextInt(9);
                                Object source = new Object();
                                CountDownLatch reached = new CountDownLatch(1);
                                pendingCallbacks.incrementAndGet();
                                scheduler.addTicket(key, source, Status.ALL[status], () -> {
                                    pendingCallbacks.decrementAndGet();
                                    reached.countDown();
                                });
                                held.add(new Object[] {key, status, source, reached});
                            } else if (!held.isEmpty()) {
                                Object[] ticket = held.remove(random.nextInt(held.size()));
                                // a held ticket's status must be reached, so its callback must run
                                require(((CountDownLatch) ticket[3]).await(20, TimeUnit.SECONDS),
                                        "a held ticket's callback never ran");
                                scheduler.removeTicket((Integer) ticket[0], ItemTicket.TicketType.EXTERNAL, ticket[2],
                                        Status.ALL[(Integer) ticket[1]]);
                            }
                        }
                        for (Object[] ticket : held) {
                            require(((CountDownLatch) ticket[3]).await(20, TimeUnit.SECONDS),
                                    "a held ticket's callback never ran");
                            scheduler.removeTicket((Integer) ticket[0], ItemTicket.TicketType.EXTERNAL, ticket[2],
                                    Status.ALL[(Integer) ticket[1]]);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }, "ticket-churn-" + t);
                thread.setDaemon(true);
                thread.start();
            }
            require(done.await(120, TimeUnit.SECONDS), "ticket churn did not finish");
            if (failure.get() != null) {
                throw new AssertionError("ticket churn failed", failure.get());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (scheduler.itemCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            require(scheduler.itemCount() == 0, "items never unloaded after their tickets were removed: " + scheduler.itemCount());
            require(pendingCallbacks.get() == 0, "ticket callbacks left pending: " + pendingCallbacks.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void checkDependencySlots() throws Exception {
        // Items in a row with worldgen-like dependency rings, generated from several threads, then
        // released. Run with the dependency slots off and on: every item must reach its status
        // with its dependencies in place, the slot table must name the registered items, and
        // everything must unload afterwards.
        for (boolean slots : new boolean[] {false, true}) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                StatusAdvancingScheduler<Integer, Object, Object, Object> scheduler = new StatusAdvancingScheduler<>() {
                    @Override protected Executor getBackgroundExecutor() { return pool; }
                    @Override protected RingStatus getUnloadedStatus() { return RingStatus.ALL[0]; }
                    @Override protected int dependencySlot(Integer item, Integer dependency) {
                        int d = dependency - item + 3;
                        return slots && d >= 0 && d < 7 ? d : -1;
                    }
                    @Override protected int dependencySlotCount() { return 7; }
                    @Override protected Object makeContext(ItemHolder<Integer, Object, Object, Object> holder,
                            ItemStatus<Integer, Object, Object> nextStatus,
                            KeyStatusPair<Integer, Object, Object>[] dependencies, boolean isUpgrade) {
                        try {
                            ItemHolder<Integer, Object, Object, ?>[] held = holder.dependencyHoldersBySlot();
                            for (KeyStatusPair<Integer, Object, Object> dependency : dependencies) {
                                ItemHolder<Integer, Object, Object, Object> registered = this.getHolder(dependency.key());
                                require(registered != null && registered.getStatus().ordinal() >= dependency.status().ordinal(),
                                        "a dependency is not in place when its dependent upgrades");
                                int slot = this.dependencySlot(holder.getKey(), dependency.key());
                                if (slot >= 0) {
                                    require(held != null && held[slot] == registered, "the slot table names another item");
                                }
                            }
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        }
                        return new Object();
                    }
                    @Override protected ExceptionHandlingAction handleTransactionException(
                            ItemHolder<Integer, Object, Object, Object> holder,
                            ItemStatus<Integer, Object, Object> nextStatus, boolean isUpgrade, Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        return ExceptionHandlingAction.MARK_BROKEN;
                    }
                };
                final int items = 160;
                final int threads = 4;
                CountDownLatch reached = new CountDownLatch(items);
                CountDownLatch requested = new CountDownLatch(threads);
                for (int t = 0; t < threads; t++) {
                    final int offset = t;
                    Thread thread = new Thread(() -> {
                        for (int key = offset; key < items; key += threads) {
                            scheduler.addTicket(key, RingStatus.ALL[9], reached::countDown);
                        }
                        requested.countDown();
                    }, "ring-request-" + t);
                    thread.setDaemon(true);
                    thread.start();
                }
                require(requested.await(30, TimeUnit.SECONDS), "requests did not finish");
                require(reached.await(60, TimeUnit.SECONDS), "items never reached their status (slots " + slots + "): "
                        + reached.getCount() + " left");
                if (failure.get() != null) {
                    throw new AssertionError("dependency ring failed (slots " + slots + ")", failure.get());
                }
                for (int key = 0; key < items; key++) {
                    scheduler.removeTicket(key, RingStatus.ALL[9]);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (scheduler.itemCount() != 0 && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                require(scheduler.itemCount() == 0, "items never unloaded (slots " + slots + "): " + scheduler.itemCount());
                if (failure.get() != null) {
                    throw new AssertionError("dependency ring failed while unloading (slots " + slots + ")", failure.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Like {@link Status}, with dependencies shaped like worldgen's: until an item has reached
     * status 5 that status needs the items within 2 at status 3, and status 8 needs the items
     * within 3 at status 6 and one far item at status 2. Once reached, a status needs nothing, so
     * the scheduler drops the ring after the upgrade as it does for a generated chunk.
     */
    private record RingStatus(int ordinal) implements ItemStatus<Integer, Object, Object> {
        private static final RingStatus[] ALL = new RingStatus[16];
        static {
            for (int i = 0; i < ALL.length; i++) ALL[i] = new RingStatus(i);
        }
        @Override public RingStatus[] getAllStatuses() { return ALL; }
        @Override public Completable upgradeToThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable postUpgradeToThis(Object context) { return Completable.complete(); }
        @Override public Completable preDowngradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable downgradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @SuppressWarnings("unchecked")
        @Override public KeyStatusPair<Integer, Object, Object>[] getDependencies(ItemHolder<Integer, Object, Object, ?> holder) {
            if (holder.getStatus().ordinal() >= this.ordinal) {
                return EMPTY_DEPENDENCIES;
            }
            int key = holder.getKey();
            if (this.ordinal == 5) {
                return new KeyStatusPair[] {new KeyStatusPair<>(key - 2, ALL[3]), new KeyStatusPair<>(key - 1, ALL[3]),
                        new KeyStatusPair<>(key + 1, ALL[3]), new KeyStatusPair<>(key + 2, ALL[3])};
            }
            if (this.ordinal == 8) {
                return new KeyStatusPair[] {new KeyStatusPair<>(key - 3, ALL[6]), new KeyStatusPair<>(key - 2, ALL[6]),
                        new KeyStatusPair<>(key - 1, ALL[6]), new KeyStatusPair<>(key + 1, ALL[6]),
                        new KeyStatusPair<>(key + 2, ALL[6]), new KeyStatusPair<>(key + 3, ALL[6]),
                        new KeyStatusPair<>(key + 1000, ALL[2])};
            }
            return EMPTY_DEPENDENCIES;
        }
    }

    private static void workerBarrier(ExecutorManager manager) throws InterruptedException {
        CountDownLatch barrier = new CountDownLatch(1);
        manager.schedule(barrier::countDown, 0);
        require(barrier.await(5, TimeUnit.SECONDS), "worker did not reach queue barrier");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Status(int ordinal) implements ItemStatus<Integer, Object, Object> {
        private static final Status[] ALL = new Status[16];
        static {
            for (int i = 0; i < ALL.length; i++) ALL[i] = new Status(i);
        }
        @Override public Status[] getAllStatuses() { return ALL; }
        /** When set, the upgrade to status 12 waits for it (checkSettledTicketTicks). */
        private static volatile Completable upgradeGate;
        @Override public Completable upgradeToThis(Object context, Cancellable cancellable) {
            final Completable gate = upgradeGate;
            return this.ordinal == 12 && gate != null ? gate : Completable.complete();
        }
        @Override public Completable postUpgradeToThis(Object context) { return Completable.complete(); }
        @Override public Completable preDowngradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable downgradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public KeyStatusPair<Integer, Object, Object>[] getDependencies(ItemHolder<Integer, Object, Object, ?> holder) {
            return EMPTY_DEPENDENCIES;
        }
    }
}
