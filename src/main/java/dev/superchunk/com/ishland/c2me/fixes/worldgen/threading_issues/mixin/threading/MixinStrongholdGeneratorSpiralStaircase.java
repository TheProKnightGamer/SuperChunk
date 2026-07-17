package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.common.IStrongholdGenerator;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mojmap port of upstream C2ME's MixinStrongholdGeneratorSpiralStaircase (dropped in the
 * original merge): the source stairs' {@code addChildren} (yarn {@code fillOpenings}) writes
 * the STATIC {@code imposedPiece} (forcing the next piece to be the FiveCrossing) — routed
 * to the same per-thread slot MixinStrongholdGenerator's picker reads.
 */
@Mixin(StrongholdPieces.StairsDown.class)
public class MixinStrongholdGeneratorSpiralStaircase {

    @Redirect(method = "addChildren", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.PUTSTATIC), require = 1)
    private void redirectSetImposedPiece(Class<? extends StrongholdPieces.StrongholdPiece> value) {
        IStrongholdGenerator.Holder.INSTANCE.getActivePieceTypeThreadLocal().set(value);
    }

}
