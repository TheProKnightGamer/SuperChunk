package dev.superchunk.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code superchunk-dh.mixins.json} (the Distant Horizons batch-generator
 * hooks).
 *
 * <p>Its only job is the LOAD-TIME gate: {@link #shouldApplyMixin} refuses every mixin in that
 * config unless Distant Horizons is actually on the mod list and the DH compat is enabled, so on
 * a DH-less install (and under {@code -Dsuperchunk.compat.distantHorizons=false} /
 * {@code -Dsuperchunk.dh.batchPrefetch=false}) the hooks are never installed at all rather than
 * being installed and branching at runtime. The config itself is {@code "required": false} so the
 * DH class names that don't exist in the installed DH version — or with no DH at all — are a
 * non-event.
 *
 * <p>Deliberately separate from {@code SuperChunkMixinPlugin}: that plugin's {@code onLoad} runs
 * the unified-config write-through and the one-per-run boot lines, which must happen exactly once.
 */
public final class DhMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        DistantHorizonsCompat.logBootLine();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return DistantHorizonsCompat.applyBatchGeneratorHooks();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
