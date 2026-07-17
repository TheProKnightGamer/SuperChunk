package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(JungleTemplePiece.class)
public class MixinJungleTempleGenerator {

    private final AtomicBoolean placedMainChestAtomic = new AtomicBoolean();
    private final AtomicBoolean placedHiddenChestAtomic = new AtomicBoolean();
    private final AtomicBoolean placedTrap1Atomic = new AtomicBoolean();
    private final AtomicBoolean placedTrap2Atomic = new AtomicBoolean();

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedMainChest:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedMainChest(JungleTemplePiece jungleTempleGenerator, boolean value) {
        this.placedMainChestAtomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedMainChest:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedMainChest(JungleTemplePiece jungleTempleGenerator) {
        return this.placedMainChestAtomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedHiddenChest:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetHiddenChest(JungleTemplePiece jungleTempleGenerator, boolean value) {
        this.placedHiddenChestAtomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedHiddenChest:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetHiddenChest(JungleTemplePiece jungleTempleGenerator) {
        return this.placedHiddenChestAtomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedTrap1:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedTrap1(JungleTemplePiece jungleTempleGenerator, boolean value) {
        this.placedTrap1Atomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedTrap1:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedTrap1(JungleTemplePiece jungleTempleGenerator) {
        return this.placedTrap1Atomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedTrap2:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedTrap2(JungleTemplePiece jungleTempleGenerator, boolean value) {
        this.placedTrap2Atomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/structures/JungleTemplePiece;placedTrap2:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedTrap2(JungleTemplePiece jungleTempleGenerator) {
        return this.placedTrap2Atomic.get();
    }

}
