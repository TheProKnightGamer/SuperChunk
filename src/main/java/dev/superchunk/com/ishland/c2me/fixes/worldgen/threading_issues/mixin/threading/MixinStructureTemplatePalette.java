package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SuperChunk, Forge 1.20.1 only: MC-271899. {@code StructureTemplate.Palette} caches its per-block
 * filter results in a plain {@code HashMap}, filled by {@code computeIfAbsent} in
 * {@code blocks(Block)}. Templates are global and shared, and feature placement runs on many worldgen
 * threads at once, so two threads placing pieces of one template race on that map: it throws
 * {@code ConcurrentModificationException}, the chunk fails ("Feature placement"), and every neighbour
 * that needs it can never finish, which stalls a pregen in its last few percent.
 *
 * <p>NeoForge 1.21.1 patches the field to {@code Maps.newConcurrentMap()} itself ("Neo: Fixes
 * MC-271899"), which is why the neo port has no mixin for this; Forge 1.20.1 does not. Same fix: swap
 * in a concurrent map once the constructor has built the original. The mapping function only reads
 * {@code blocks}, so the concurrent map's per-bin locking cannot deadlock.
 */
@Mixin(StructureTemplate.Palette.class)
public class MixinStructureTemplatePalette {

    @Mutable
    @Shadow @Final private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }

}
