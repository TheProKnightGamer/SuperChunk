package dev.superchunk.net.caffeinemc.mods.lithium.mixin.world.raycast;

import dev.superchunk.net.caffeinemc.mods.lithium.common.util.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.BiFunction;
import java.util.function.Function;

@SuppressWarnings("ShadowModifiers")
@Mixin(BlockGetter.class)
public interface BlockGetterMixin {

    @Shadow
    BlockState getBlockState(BlockPos pos);

    @Shadow
    @Nullable BlockHitResult clipWithInteractionOverride(Vec3 start, Vec3 end, BlockPos pos, VoxelShape shape, BlockState state);

    @Shadow
    static <T, C> T traverseBlocks(Vec3 start, Vec3 end, C context, BiFunction<C, BlockPos, T> blockHitFactory, Function<C, T> missFactory) {throw new AssertionError();}

    @Shadow
    FluidState getFluidState(BlockPos pos);

    /**
     * @author 2No2Name
     * @reason Get rid of unnecessary lambda allocation
     *
     * <p>PORT-1.20.1: neo shadowed vanilla's two synthetic {@code lambda$clip$N} methods
     * (yarn {@code method_17743/17746}) for the fallback path, but shadow-aliasing synthetic
     * lambdas does not survive the 0.8.5 interface-mixin pipeline (the alias never resolved
     * and the merged body threw {@code NoSuchMethodError} at first mob pathfinding raycast).
     * Both lambda bodies are tiny and stable, so they are INLINED verbatim from the 1.20.1
     * source instead: the miss factory is non-capturing (JVM-cached), and the vanilla-path
     * hit factory captures {@code this} exactly like vanilla's own lambda did — the
     * allocation win still comes from {@link #lithium$blockHitFactory} on the fast path.
     */
    @Overwrite
    default BlockHitResult clip(ClipContext context) {
        return traverseBlocks(context.getFrom(),
                context.getTo(),
                context,
                // This intentionally excludes custom level implementations of create / ponder due to compatibility issues
                (this instanceof ServerLevel || this instanceof Level l && l.isClientSide())
                        ? this.lithium$blockHitFactory(context)
                        : (ctx, pos) -> {
                            // [VanillaCopy] BlockGetter.clip hit lambda (1.20.1)
                            BlockState blockstate = this.getBlockState(pos);
                            FluidState fluidstate = this.getFluidState(pos);
                            Vec3 vec3 = ctx.getFrom();
                            Vec3 vec31 = ctx.getTo();
                            VoxelShape voxelshape = ctx.getBlockShape(blockstate, (BlockGetter) this, pos);
                            BlockHitResult blockhitresult = this.clipWithInteractionOverride(vec3, vec31, pos, voxelshape, blockstate);
                            VoxelShape voxelshape1 = ctx.getFluidShape(fluidstate, (BlockGetter) this, pos);
                            BlockHitResult blockhitresult1 = voxelshape1.clip(vec3, vec31, pos);
                            double d0 = blockhitresult == null ? Double.MAX_VALUE : ctx.getFrom().distanceToSqr(blockhitresult.getLocation());
                            double d1 = blockhitresult1 == null ? Double.MAX_VALUE : ctx.getFrom().distanceToSqr(blockhitresult1.getLocation());
                            return d0 <= d1 ? blockhitresult : blockhitresult1;
                        },
                (ctx) -> {
                    // [VanillaCopy] BlockGetter.clip miss lambda (1.20.1)
                    Vec3 vec3 = ctx.getFrom().subtract(ctx.getTo());
                    return BlockHitResult.miss(ctx.getTo(),
                            Direction.getNearest(vec3.x, vec3.y, vec3.z),
                            BlockPos.containing(ctx.getTo()));
                });
    }

    @Unique
    // default (implicitly public), not neo's private: runtime mixin 0.8.5 rejects non-public methods in INTERFACE
    // mixins ("Interface mixin contains a non-public method"). The lithium$ prefix keeps the
    // merged name collision-free on the BlockGetter interface.
    default BiFunction<ClipContext, BlockPos, BlockHitResult> lithium$blockHitFactory(ClipContext context) {
        return new BiFunction<>() {
            int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
            ChunkAccess chunk = null;
            final boolean handleFluids = ((ClipContextAccessor) context).getFluidHandling() != ClipContext.Fluid.NONE;

            @Override
            public BlockHitResult apply(ClipContext innerContext, BlockPos pos) {
                //[VanillaCopy] BlockView.raycast, but optional fluid handling
                BlockState blockState = this.getBlock((LevelReader) BlockGetterMixin.this, pos);
                Vec3 start = innerContext.getFrom();
                Vec3 end = innerContext.getTo();
                VoxelShape blockShape = innerContext.getBlockShape(blockState, (BlockGetter) BlockGetterMixin.this, pos);
                BlockHitResult blockHitResult = BlockGetterMixin.this.clipWithInteractionOverride(start, end, pos, blockShape, blockState);
                double d = blockHitResult == null ? Double.MAX_VALUE : innerContext.getFrom().distanceToSqr(blockHitResult.getLocation());
                double e = Double.MAX_VALUE;
                BlockHitResult fluidHitResult = null;
                if (this.handleFluids) {
                    FluidState fluidState = blockState.getFluidState();
                    VoxelShape fluidShape = innerContext.getFluidShape(fluidState, (BlockGetter) BlockGetterMixin.this, pos);
                    fluidHitResult = fluidShape.clip(start, end, pos);
                    e = fluidHitResult == null ? Double.MAX_VALUE : innerContext.getFrom().distanceToSqr(fluidHitResult.getLocation());
                }
                return d <= e ? blockHitResult : fluidHitResult;
            }

            private BlockState getBlock(LevelReader world, BlockPos blockPos) {
                if (world.isOutsideBuildHeight(blockPos.getY())) {
                    return Blocks.VOID_AIR.defaultBlockState();
                }
                int chunkX = Pos.ChunkCoord.fromBlockCoord(blockPos.getX());
                int chunkZ = Pos.ChunkCoord.fromBlockCoord(blockPos.getZ());

                // Avoid calling into the chunk manager as much as possible through managing chunks locally
                if (this.chunkX != chunkX || this.chunkZ != chunkZ) {
                    this.chunk = world.getChunk(chunkX, chunkZ);

                    this.chunkX = chunkX;
                    this.chunkZ = chunkZ;
                }

                final ChunkAccess chunk = this.chunk;

                // If the chunk is missing or out of bounds, assume that it is air
                if (chunk != null) {
                    // We operate directly on chunk sections to avoid interacting with BlockPos and to squeeze out as much
                    // performance as possible here
                    LevelChunkSection section = chunk.getSections()[Pos.SectionYIndex.fromBlockCoord(chunk, blockPos.getY())];

                    // If the section doesn't exist or is empty, assume that the block is air
                    if (section != null && !section.hasOnlyAir()) {
                        return section.getBlockState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
                    }
                }

                return Blocks.AIR.defaultBlockState();
            }
        };
    }
}
