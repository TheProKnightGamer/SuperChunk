package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.core.Direction8;
import net.minecraft.world.level.chunk.UpgradeData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.EnumSet;

@Mixin(UpgradeData.class)
public interface IUpgradeData {
    @Accessor("index")
    int[][] getCenterIndicesToUpgrade();

    @Accessor("sides")
    EnumSet<Direction8> getSidesToUpgrade();
}
