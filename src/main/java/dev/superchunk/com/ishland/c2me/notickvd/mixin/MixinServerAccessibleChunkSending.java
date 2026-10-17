package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import com.google.common.collect.ImmutableList;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.notickvd.common.IDirectChunkSending;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessibleChunkSending;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import dev.superchunk.worldgen.GhostMushroomCheck;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.shorts.ShortListIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * The chunk-system-aware CHUNK_SENDING seat (neo notickvd), targeting the Wave-C-ported
 * {@code statuses/ServerAccessibleChunkSending} (own class, remap=false). Gives the status its
 * 8-neighbor LIGHT deps and overwrites {@code upgradeToThis} to actually SEND the chunk to
 * watchers at CHUNK_SENDING (+ optional ghost-mushroom suppression).
 *
 * <p>1.20.1 deltas vs neo: {@code ChunkPyramid.GENERATION_PYRAMID.getStepTo(FULL)} →
 * {@code new WorldGenRegion(level, List&lt;ChunkAccess&gt;, ChunkStatus.FULL, -1)} (read-only use);
 * {@code invokeSendToPlayers} (1.21 {@code onChunkReadyToSend}) → the
 * {@link IDirectChunkSending} duck, which routes through {@code getPlayers}+{@code playerLoadedChunk}
 * with the duplicate-send gate's allow flag set. Gated in {@code notickvd.MixinPlugin} on the
 * chunk system being enabled.
 */
@Mixin(value = ServerAccessibleChunkSending.class, remap = false)
public class MixinServerAccessibleChunkSending {

    @Mutable
    @Shadow(remap = false)
    @Final
    private static KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] deps;

    @Inject(method = "<clinit>", at = @At("RETURN"), remap = false)
    private static void onCLInit(CallbackInfo ci) {
        NewChunkStatus depStatus = NewChunkStatus.fromVanillaStatus(ChunkStatus.LIGHT);
        deps = new KeyStatusPair[]{
                new KeyStatusPair<>(new ChunkPos(-1, -1), depStatus),
                new KeyStatusPair<>(new ChunkPos(-1, 0), depStatus),
                new KeyStatusPair<>(new ChunkPos(-1, 1), depStatus),
                new KeyStatusPair<>(new ChunkPos(0, -1), depStatus),
                new KeyStatusPair<>(new ChunkPos(0, 1), depStatus),
                new KeyStatusPair<>(new ChunkPos(1, -1), depStatus),
                new KeyStatusPair<>(new ChunkPos(1, 0), depStatus),
                new KeyStatusPair<>(new ChunkPos(1, 1), depStatus),
        };
    }

    /**
     * @author ishland
     * @reason do chunk sending
     */
    @Dynamic
    @Overwrite(remap = false)
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
        ArrayList<BlockPos> blocksToRemove = new ArrayList<>();
        if (Config.suppressGhostMushrooms) {
            ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
            ChunkState state = context.holder().getItem().get();
            WorldGenRegion chunkRegion = new WorldGenRegion(serverWorld, context.chunks(), ChunkStatus.FULL, -1); // read-only use
            ChunkAccess chunk = state.chunk();

            ChunkPos chunkPos = context.holder().getKey();

            ShortList[] postProcessingLists = chunk.getPostProcessing();
            for (int i = 0; i < postProcessingLists.length; i++) {
                if (postProcessingLists[i] != null) {
                    for (ShortListIterator iterator = postProcessingLists[i].iterator(); iterator.hasNext(); ) {
                        short short_ = iterator.nextShort();
                        BlockPos blockPos = ProtoChunk.unpackOffsetCoordinates(short_, chunk.getSectionYFromSectionIndex(i), chunkPos);
                        BlockState blockState = chunk.getBlockState(blockPos);

                        if (blockState.getBlock() == Blocks.BROWN_MUSHROOM || blockState.getBlock() == Blocks.RED_MUSHROOM) {
                            // SuperChunk: guarded. chunkRegion can only read the chunk's own 3x3
                            // neighbourhood, so a modded canSurvive hook that reaches beyond it throws and
                            // would mark the chunk broken - Twilight Forest's MushroomBlock.canSurvive ASM
                            // hook crashed the 1.21.1 port this way. See GhostMushroomCheck.
                            if (!GhostMushroomCheck.canSurvive(blockState, chunkRegion, blockPos, chunkPos)) {
                                blocksToRemove.add(blockPos);
                            }
                        }
                    }
                }
            }
        }
        return Completable
                .fromRunnable(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());

                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, (ServerAccessibleChunkSending) (Object) this, true))) {
                        if (Config.suppressGhostMushrooms) {
                            ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                            ChunkState state = context.holder().getItem().get();
                            ChunkAccess chunk = state.chunk();
                            for (BlockPos blockPos : blocksToRemove) {
                                serverWorld.setBlock(blockPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
                            }
                            for (BlockPos blockPos2 : ImmutableList.copyOf(chunk.getBlockEntitiesPos())) {
                                chunk.getBlockEntity(blockPos2);
                            }
                        }
                        sendChunkToPlayer(context.tacs(), context.holder());
                    }
                })
                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
    }

    @Unique
    private static void sendChunkToPlayer(ChunkMap tacs, ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder) {
        final ChunkAccess chunk = holder.getItem().get().chunk();
        if (chunk instanceof LevelChunk worldChunk) {
            CompletableFuture<?> postProcessingFuture = holder.getUserData().get().getPostProcessingFuture();
            if (postProcessingFuture.isDone()) {
                ((IDirectChunkSending) tacs).c2me_notickvd$sendToWatchers(worldChunk);
            } else {
                postProcessingFuture.thenAcceptAsync(
                        v -> ((IDirectChunkSending) tacs).c2me_notickvd$sendToWatchers(worldChunk),
                        ((IThreadedAnvilChunkStorage) tacs).getMainThreadExecutor());
            }
        }
    }

}
