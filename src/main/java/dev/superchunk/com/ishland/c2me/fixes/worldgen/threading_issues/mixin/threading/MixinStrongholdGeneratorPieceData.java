package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.common.XPieceDataExtension;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mojmap port of upstream C2ME's MixinStrongholdGeneratorPieceData (dropped in the original
 * merge): {@code PieceWeight.placeCount} (yarn {@code PieceData.generatedCount}) is
 * shared-mutable across concurrently-generating strongholds (the PieceWeight instances are
 * the STATIC {@code STRONGHOLD_PIECE_WEIGHTS} templates) — moved to a per-thread slot via
 * the retained {@link XPieceDataExtension} duck, same pattern as the vendored
 * MixinNetherFortressGeneratorPieceData. The in-class readers ({@code doPlace}/{@code
 * isValid}) are redirected here; outer-class accesses are redirected by
 * {@code MixinStrongholdGenerator}.
 */
@Mixin(StrongholdPieces.PieceWeight.class)
public class MixinStrongholdGeneratorPieceData implements XPieceDataExtension {

    @Unique
    private final ThreadLocal<Integer> generatedCountThreadLocal = ThreadLocal.withInitial(() -> 0);

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces$PieceWeight;placeCount:I", opcode = Opcodes.GETFIELD), require = 2)
    private int redirectGetGeneratedCount(StrongholdPieces.PieceWeight pieceWeight) {
        return this.generatedCountThreadLocal.get();
    }

    @SuppressWarnings("MixinAnnotationTarget")
    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD), require = 0, expect = 0)
    private void redirectSetGeneratedCount(StrongholdPieces.PieceWeight pieceWeight, int value) {
        if (value == 0) {
            generatedCountThreadLocal.remove();
        } else {
            this.generatedCountThreadLocal.set(value);
        }
    }

    @Override
    public ThreadLocal<Integer> c2me$getGeneratedCountThreadLocal() {
        return this.generatedCountThreadLocal;
    }

}
