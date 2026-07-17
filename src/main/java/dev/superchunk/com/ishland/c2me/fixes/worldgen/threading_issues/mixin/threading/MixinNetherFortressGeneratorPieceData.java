package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.common.XPieceDataExtension;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NetherFortressPieces.PieceWeight.class)
public class MixinNetherFortressGeneratorPieceData implements XPieceDataExtension {

    @Unique
    private final ThreadLocal<Integer> generatedCountThreadLocal = ThreadLocal.withInitial(() -> 0);

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/NetherFortressPieces$PieceWeight;placeCount:I", opcode = Opcodes.GETFIELD))
    private int redirectGetGeneratedCount(NetherFortressPieces.PieceWeight pieceData) {
        return this.generatedCountThreadLocal.get();
    }

    @SuppressWarnings("MixinAnnotationTarget")
    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/NetherFortressPieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD), require = 0, expect = 0)
    private void redirectSetGeneratedCount(NetherFortressPieces.PieceWeight pieceData, int value) {
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
