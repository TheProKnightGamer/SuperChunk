package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.gpu.dfc.OnDeviceInterp;
import dev.superchunk.worldgen.AirCells;
import dev.superchunk.worldgen.BlockFillHooks;
import dev.superchunk.worldgen.CellSignSkip;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Arrays;
import java.util.List;

/**
 * The cell sign skip ({@link CellSignSkip}) at {@code selectCellYZ}'s cell-cache fill: a cell whose
 * final density is provably all positive, or provably non-positive and aquifer air, is filled with
 * the bound instead of 128 per-block evaluations.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkCellSign implements CellSignSkip.Chunk {

    @Shadow @Final List<?> cellCaches;

    @Shadow public abstract Aquifer aquifer();

    @Shadow public abstract Blender getBlender();

    /** {@code null} until bound; {@link Boolean#FALSE} when this chunk's fill has another shape. */
    @Unique private Object superchunk$cellSignProgram;
    @Unique private boolean superchunk$provenAir;
    @Unique private boolean superchunk$provenSolid;
    @Unique private DensityFunction superchunk$veinToggle;
    /** {@code null} until bound; {@link Boolean#TRUE} when there is no vein filler; FALSE when unbounded. */
    @Unique private Object superchunk$veinProgram;

    @Override
    public Object superchunk$cellSignProgram() {
        return this.superchunk$cellSignProgram;
    }

    @Override
    public void superchunk$setCellSignProgram(Object program) {
        this.superchunk$cellSignProgram = program;
    }

    @Override
    public boolean superchunk$provenSolid() {
        return this.superchunk$provenSolid;
    }

    @Override
    public void superchunk$setVeinToggle(DensityFunction veinToggle) {
        this.superchunk$veinToggle = veinToggle;
    }

    @Override
    public boolean superchunk$takeProvenAir() {
        boolean air = this.superchunk$provenAir;
        this.superchunk$provenAir = false;
        return air;
    }

    @WrapOperation(method = "selectCellYZ", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"),
            require = 0)
    private void superchunk$cellSign(DensityFunction filler, double[] values, DensityFunction.ContextProvider context,
                                     Operation<Void> original) {
        this.superchunk$provenAir = false;
        this.superchunk$provenSolid = false;
        if (!CellSignSkip.ENABLED) {
            original.call(filler, values, context);
            return;
        }
        Object bound = this.superchunk$cellSignProgram;
        if (bound == null) {
            bound = superchunk$bind(filler);
            this.superchunk$cellSignProgram = bound;
        }
        if (!(bound instanceof CellSignSkip.Program program)) {
            original.call(filler, values, context);
            return;
        }
        IChunkNoiseSampler cell = (IChunkNoiseSampler) this;
        int w = cell.getHorizontalCellBlockCount();
        int h = cell.getVerticalCellBlockCount();
        int x0 = cell.getStartBlockX();
        int y0 = cell.getStartBlockY();
        int z0 = cell.getStartBlockZ();
        int decision = CellSignSkip.decide(program, x0, y0, z0, w, h);
        boolean air = false;
        if (decision == CellSignSkip.NON_POSITIVE) {
            air = this.aquifer() instanceof AirCells.AirPredicate predicate
                    && predicate.superchunk$airCell(x0, y0, z0, w, h);
        }
        CellSignSkip.count(decision, air);
        if (decision == CellSignSkip.UNDECIDED || (decision == CellSignSkip.NON_POSITIVE && !air)) {
            original.call(filler, values, context);
            return;
        }
        if (decision == CellSignSkip.SOLID) {
            this.superchunk$provenSolid = superchunk$veinFree(y0, h);
        }
        if (CellSignSkip.VERIFY) {
            original.call(filler, values, context);
            CellSignSkip.verify(decision, values);
            return;
        }
        Arrays.fill(values, CellSignSkip.bound(program));
        // Only the air-cell skip reads this: it answers the cell's blocks AIR without the values.
        this.superchunk$provenAir = decision == CellSignSkip.NON_POSITIVE;
    }

    /**
     * Whether the material rules after the aquifer (vanilla: only the ore vein filler) return null
     * for every block of the cell: no vein filler, or the vein toggle's bound stays under 0.4.
     */
    @Unique
    private boolean superchunk$veinFree(int y0, int h) {
        Object vein = this.superchunk$veinProgram;
        if (vein == null) {
            vein = Boolean.FALSE;
            if (((IChunkNoiseSampler) this).getBlockStateRule()
                    instanceof net.minecraft.world.level.levelgen.material.MaterialRuleList rules) {
                int count = rules.materialRuleList().size();
                if (count == 1) {
                    vein = Boolean.TRUE;
                } else if (count == 2 && this.superchunk$veinToggle != null) {
                    CellSignSkip.Program program = CellSignSkip.bindValue(this.superchunk$veinToggle);
                    if (program != null) {
                        vein = program;
                    } else {
                        CellSignSkip.standDown("ore vein toggle " + this.superchunk$veinToggle.getClass().getName());
                    }
                }
            }
            this.superchunk$veinProgram = vein;
        }
        if (vein instanceof CellSignSkip.Program program) {
            return CellSignSkip.veinFree(program, y0, h);
        }
        return vein == Boolean.TRUE;
    }

    /** The program for this chunk, or {@link Boolean#FALSE} when the skip must stand down. */
    @Unique
    private Object superchunk$bind(DensityFunction filler) {
        // One cell cache (vanilla's final density), no blending (the blended fill takes another
        // path), the CPU interpolation (no on-device GPU field), vanilla's noise aquifer and no
        // other mod in the fill: the conditions under which only the aquifer reads the values.
        // GPU routing may stay on (see CellSignSkip.bind).
        String reason = this.cellCaches.size() != 1 ? "cell caches " + this.cellCaches.size()
                : this.getBlender() != Blender.empty() ? null // blending near old chunks: expected, not logged
                : OnDeviceInterp.ENABLED ? "on-device interpolation"
                : !AirCells.ENABLED ? "air cells off"
                : !(this.aquifer() instanceof Aquifer.NoiseBasedAquifer) ? "aquifer " + this.aquifer().getClass().getName()
                : !BlockFillHooks.unhooked() ? null // BlockFillHooks names a foreign hook itself; early chunks may not know yet
                : "";
        if (!"".equals(reason)) {
            if (reason != null) {
                CellSignSkip.standDown(reason);
            }
            return Boolean.FALSE;
        }
        IChunkNoiseSampler cell = (IChunkNoiseSampler) this;
        int w = cell.getHorizontalCellBlockCount();
        CellSignSkip.Program program = CellSignSkip.bind(filler, w * cell.getVerticalCellBlockCount() * w);
        return program != null ? program : Boolean.FALSE;
    }
}
