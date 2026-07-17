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
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public static <V> CompletableFuture<List<V>> sequenceFailFast(List<? extends CompletableFuture<? extends V>> futures) {
        return Combinators.collect((List<? extends CompletableFuture<V>>) futures, Collectors.<V>toList()).toCompletableFuture();
    }

    /**
     * @author ishland
     * @reason use another impl
     */
    @Overwrite
    public static <V> CompletableFuture<List<V>> sequence(List<? extends CompletableFuture<V>> futures) {
        final CompletableFuture<List<V>> future = Combinators.collect(futures, Collectors.<V>toList()).toCompletableFuture();
        BiConsumer<V, Throwable> action = (v, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
            }
        };
        for (CompletableFuture<V> completableFuture : futures) {
            completableFuture.whenComplete(action);
        }
        return future;
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
