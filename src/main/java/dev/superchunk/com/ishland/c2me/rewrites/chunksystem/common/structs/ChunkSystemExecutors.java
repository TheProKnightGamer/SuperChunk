package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.structs;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public class ChunkSystemExecutors {

    /**
     * Per-thread consolidation state. The entry is created once per thread and never removed:
     * the previous set/remove pairs allocated a map entry (and a deque) for every root task,
     * and every submission from outside a root allocated an entry just to remove it again.
     */
    public static final class Consolidation {
        /** The queue being drained on this thread, or {@code null} outside a consolidating root. */
        public Queue<Runnable> current;
        /** A drained deque kept for this thread's next root; never shared with another thread. */
        private ArrayDeque<Runnable> spare;
    }

    public static final ThreadLocal<Consolidation> CONSOLIDATION = ThreadLocal.withInitial(Consolidation::new);

    public static final Executor backingBackgroundExecutor = GlobalExecutors.prioritizedScheduler.executor(15);
    public static final Scheduler backgroundScheduler = Schedulers.from(backingBackgroundExecutor);
    public static final Executor consolidatingBackgroundExecutor = command -> {
        Queue<Runnable> runnables = CONSOLIDATION.get().current;
        if (runnables == null) { // first entry
            consolidatingRoot(command);
            return;
        }
        runnables.add(command);
    };
    public static final Scheduler consolidatingBackgroundScheduler = Schedulers.from(consolidatingBackgroundExecutor);

    private static void consolidatingRoot(Runnable initialCommand) {
        backingBackgroundExecutor.execute(() -> {
            Consolidation state = CONSOLIDATION.get();
            if (state.current != null) {
                new Throwable("CONSOLIDATING_QUEUE leak").printStackTrace();
                try {
                    initialCommand.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return;
            }

            ArrayDeque<Runnable> runnables = state.spare != null ? state.spare : new ArrayDeque<>();
            state.spare = null;
            state.current = runnables;
            runnables.add(initialCommand);
            try {
                while (!runnables.isEmpty()) {
                    try {
                        runnables.remove().run();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            } finally {
                if (!runnables.isEmpty()) {
                    new Throwable("runnable leak").printStackTrace();
                    runnables.clear();
                }
                state.current = null;
                state.spare = runnables;
            }
        });
    }

}
