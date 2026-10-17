package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.asm.MakeVolatile;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

/**
 * Mojmap port of upstream C2ME's MixinStrongholdGeneratorStart (dropped in the original
 * merge): synchronizes the start piece's {@code pendingChildren} list (yarn {@code pieces})
 * and makes {@code previousPiece}/{@code portalRoomPiece} (yarn {@code lastPiece}/{@code
 * portalRoom}) volatile for cross-thread visibility — same pattern as the vendored
 * MixinNetherFortressGeneratorStart.
 */
@Mixin(StrongholdPieces.StartPiece.class)
public class MixinStrongholdGeneratorStart {

    @Mutable
    @Shadow @Final public List<StructurePiece> pendingChildren;

    @MakeVolatile
    @Shadow public StrongholdPieces.PieceWeight previousPiece;

    @MakeVolatile
    @Shadow public StrongholdPieces.PortalRoom portalRoomPiece;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.pendingChildren = Collections.synchronizedList(this.pendingChildren);
    }

}
