package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The block column {@code SurfaceSystem.buildSurface} works on, for {@code MixinSurfaceSystemFast}'s copy of that
 * method: vanilla's anonymous column with {@code MixinSurfaceColumnMemo}'s memo built in. A read of a height not held
 * yet reads the column's 16 blocks of that section, the way {@code ProtoChunk.getBlockState} reads each. A write is
 * vanilla's (inside the generation height: the lean {@code ProtoChunk.setBlockState} of {@link ProtoChunkWrites},
 * which skips the heightmap updates that cannot change anything, then the fluid post-processing mark) and updates the
 * memo. The memo is sound because nothing else writes the column while its surface is built.
 */
public final class SurfaceColumn implements BlockColumn {

    private final ProtoChunk chunk;
    private final Heightmap[] heightmaps;
    private final BlockPos.MutableBlockPos pos;
    private final int minBuild;
    private final BlockState[] states;
    private final int[] epochs;
    private int epoch;
    // the column's value in each heightmap, for ProtoChunkWrites.setInColumn
    private final int[] firstAvailable;

    /** For an exact {@code ProtoChunk} without below-zero retrogen, with {@link ProtoChunkWrites#bind}'s heightmaps. */
    public SurfaceColumn(ProtoChunk chunk, Heightmap[] heightmaps, BlockPos.MutableBlockPos pos) {
        this.chunk = chunk;
        this.heightmaps = heightmaps;
        this.pos = pos;
        this.minBuild = chunk.getMinBuildHeight();
        this.states = new BlockState[chunk.getHeight()];
        this.epochs = new int[chunk.getHeight()];
        this.firstAvailable = new int[heightmaps.length];
    }

    /** Vanilla's {@code pos.setX(x).setZ(z)} at the start of a column; forgets the last column. */
    public void moveTo(int x, int z) {
        this.pos.setX(x).setZ(z);
        this.epoch++;
        java.util.Arrays.fill(this.firstAvailable, Integer.MIN_VALUE);
    }

    @Override
    public BlockState getBlock(int y) {
        int i = y - this.minBuild;
        if (i < 0 || i >= this.states.length) {
            return this.chunk.getBlockState(this.pos.setY(y)); // outside the chunk: void air
        }
        if (this.epochs[i] == this.epoch) {
            return this.states[i];
        }
        LevelChunkSection section = this.chunk.getSection(this.chunk.getSectionIndex(y));
        int base = i & ~15;
        if (section.hasOnlyAir()) {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int k = base; k < base + 16; k++) {
                if (this.epochs[k] != this.epoch) {
                    this.states[k] = air;
                    this.epochs[k] = this.epoch;
                }
            }
        } else {
            int x = this.pos.getX() & 15;
            int z = this.pos.getZ() & 15;
            for (int k = base; k < base + 16; k++) {
                if (this.epochs[k] != this.epoch) {
                    this.states[k] = section.getBlockState(x, k - base, z);
                    this.epochs[k] = this.epoch;
                }
            }
        }
        return this.states[i];
    }

    @Override
    public void setBlock(int y, BlockState state) {
        // the chunk is its own generation height accessor: no below-zero retrogen
        int i = y - this.minBuild;
        if (i >= 0 && i < this.states.length) {
            ProtoChunkWrites.setInColumn(this.chunk, this.heightmaps, this.pos.setY(y), state, this.firstAvailable);
            if (!state.getFluidState().isEmpty()) {
                this.chunk.markPosForPostprocessing(this.pos);
            }
            this.states[i] = state;
            this.epochs[i] = this.epoch;
        }
    }

    @Override
    public String toString() {
        return "ChunkBlockColumn " + this.chunk.getPos();
    }
}
