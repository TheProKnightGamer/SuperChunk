package dev.superchunk.com.ishland.c2me.opts.allocs.mixin;

import com.ibm.asyncutil.util.Combinators;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Mixin(Util.class)
public abstract class MixinUtil {

    /**
     * @author ishland
     * @reason use another impl
     *
     * <p>SuperChunk: fails as soon as any input fails, like vanilla. (The Yarn-to-Mojmap port had
     * swapped this body with {@link #sequence}'s: upstream {@code combine} is Mojmap
     * {@code sequenceFailFast}, {@code combineSafe} is {@code sequence}. With them swapped, a reload
     * listener that threw in its prepare stage never reached the barrier, so
     * {@code SimpleReloadInstance}'s fail-fast wait never completed and the reload hung.)
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public static <V> CompletableFuture<List<V>> sequenceFailFast(List<? extends CompletableFuture<? extends V>> futures) {
        final CompletableFuture<List<V>> future = Combinators.collect((List<? extends CompletableFuture<V>>) futures, Collectors.<V>toList()).toCompletableFuture();
        BiConsumer<Object, Throwable> action = (v, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
            }
        };
        for (CompletableFuture<? extends V> completableFuture : futures) {
            completableFuture.whenComplete(action);
        }
        return future;
    }

    /**
     * @author ishland
     * @reason use another impl
     *
     * <p>SuperChunk: completes once every input has, like vanilla's {@code allOf}-based version
     * (the collect chain only completes when all inputs have).
     */
    @Overwrite
    public static <V> CompletableFuture<List<V>> sequence(List<? extends CompletableFuture<V>> futures) {
        return Combinators.collect(futures, Collectors.<V>toList()).toCompletableFuture();
    }

    /**
     * @author ishland
     * @reason use another impl
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public static <V> CompletableFuture<List<V>> sequenceFailFastAndCancel(List<? extends CompletableFuture<? extends V>> futures) {
        final CompletableFuture<List<V>> future = Combinators.collect((List<? extends CompletableFuture<V>>) futures, Collectors.<V>toList()).toCompletableFuture();
        BiConsumer<V, Throwable> action = (v, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                futures.forEach(f -> f.cancel(false));
            }
        };
        for (CompletableFuture<? extends V> completableFuture : futures) {
            completableFuture.whenComplete(action);
        }
        return future;
    }

}
