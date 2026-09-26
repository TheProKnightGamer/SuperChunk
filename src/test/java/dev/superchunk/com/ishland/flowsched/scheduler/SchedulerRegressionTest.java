package dev.superchunk.com.ishland.flowsched.scheduler;

import dev.superchunk.com.ishland.flowsched.executor.ExecutorManager;
import dev.superchunk.com.ishland.flowsched.executor.LockToken;
import dev.superchunk.com.ishland.flowsched.executor.Task;
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
        // and its futures fail, but item 1 is not broken: it parks in its upgrade to 5 with pending
        // futures (what froze the server thread), and findBrokenBlocker must name item 2.
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
        require(!dependent.getFutureForStatus(DepStatus.ALL[7]).isDone(), "test premise: the dependent's future is pending");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[7], 64) == 2, "blocker not found through the dependency");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[5], 64) == 2, "blocker not found for the next status");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[4], 64) == null, "a reached status reported blocked");
        require(scheduler.findBrokenBlocker(2, DepStatus.ALL[7], 64) == 2, "a broken item is its own blocker");
        require(scheduler.findBrokenBlocker(3, DepStatus.ALL[7], 64) == null, "an absent item reported blocked");
        require(scheduler.findBrokenBlocker(1, DepStatus.ALL[7], 0) == null, "the node budget was not honoured");
    }

    /** Like {@link Status}, but status 5 of item 1 needs item 2 at status 4. */
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
        @Override public Completable upgradeToThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable postUpgradeToThis(Object context) { return Completable.complete(); }
        @Override public Completable preDowngradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public Completable downgradeFromThis(Object context, Cancellable cancellable) { return Completable.complete(); }
        @Override public KeyStatusPair<Integer, Object, Object>[] getDependencies(ItemHolder<Integer, Object, Object, ?> holder) {
            return EMPTY_DEPENDENCIES;
        }
    }
}
