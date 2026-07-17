package dev.superchunk.net.caffeinemc.mods.lithium.mixin;

import dev.superchunk.net.caffeinemc.mods.lithium.common.config.LithiumConfig;
import dev.superchunk.net.caffeinemc.mods.lithium.common.config.Option;
import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformRuntimeInformation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class LithiumMixinPlugin implements IMixinConfigPlugin {
    private static final String[] MIXIN_PACKAGE_ROOTS = {"dev.superchunk.net.caffeinemc.mods.lithium.mixin.", "dev.superchunk.net.caffeinemc.mods.lithium.fabric.mixin.", "dev.superchunk.net.caffeinemc.mods.lithium.neoforge.mixin."};
    private static final Boolean DEBUG = false;

    private final Logger logger = LogManager.getLogger("Lithium");

    private static LithiumConfig CONFIG;

    /**
     * True when a STANDALONE Lithium mod is installed alongside SuperChunk. Computed ONCE at
     * class-init (i.e. when Mixin first instantiates this plugin while preparing
     * {@code superchunk-lithium(.neoforge).mixins.json}) — that is mixin-plugin time, BEFORE mod
     * construction. When true, EVERY bundled-Lithium mixin becomes a no-op so only the standalone
     * Lithium applies (no double-apply / {@code @Overwrite} conflicts). See {@link #shouldApplyMixin}
     * and the short-circuit in {@link #onLoad}.
     *
     * <p>Detection is authoritative via {@link net.neoforged.fml.loading.LoadingModList} (populated
     * during mod discovery, before mixin config registration), with a class-presence fallback probing
     * the ORIGINAL {@code net.caffeinemc.mods.lithium.*} package (SuperChunk's bundled copy lives
     * under {@code dev.superchunk.net.caffeinemc.mods.lithium.*}, so that package can only come from a
     * standalone Lithium).
     */
    private static final boolean STANDALONE_LITHIUM = detectStandaloneLithium();

    /** Ensures the single deferral INFO line is logged exactly once (onLoad fires per config). */
    private static final AtomicBoolean DEFER_LOGGED = new AtomicBoolean(false);

    /**
     * Whether a standalone Lithium is installed and therefore authoritative (the bundled module
     * stands down). Other SuperChunk components that would notify/consult the bundled Lithium
     * (e.g. C2ME's {@code LithiumChunkStatusTrackerInvoker}) key off this to target the
     * standalone one instead.
     */
    public static boolean isStandaloneLithiumActive() {
        return STANDALONE_LITHIUM;
    }

    private static boolean detectStandaloneLithium() {
        // Primary: the FML loading mod list, available at mixin-plugin time and keyed by modId.
        // SuperChunk declares only modId "superchunk", so a "lithium" mod file can only be a standalone.
        try {
            net.neoforged.fml.loading.LoadingModList lml = net.neoforged.fml.loading.LoadingModList.get();
            if (lml != null && lml.getModFileById("lithium") != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // LoadingModList not ready / API mismatch — fall through to the class-presence probe.
        }
        // Fallback: the standalone lives under net.caffeinemc.mods.lithium.* (bundled is dev.superchunk.*).
        // initialize=false so we only test presence, never run its static init.
        try {
            Class.forName("net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin", false,
                    LithiumMixinPlugin.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            // absent — no standalone Lithium
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (STANDALONE_LITHIUM) {
            // Defer entirely to the standalone Lithium: do NOT load CONFIG; every shouldApplyMixin
            // returns false. Log exactly one INFO line across both bundled configs.
            if (DEFER_LOGGED.compareAndSet(false, true)) {
                this.logger.info("[SuperChunk] standalone Lithium detected -> SuperChunk's bundled Lithium disabled; standalone Lithium is authoritative");
            }
            return;
        }
        if (CONFIG != null) {
            return;
        }

        try {
            // FMLPaths.CONFIGDIR (gameDir/config), NOT a cwd-relative "./config": SuperChunkConfig's
            // lithium.* write-through (incl. the five C2ME-overlap safety pins) writes to
            // FMLPaths.CONFIGDIR, so reader and writer must agree even when cwd != gameDir
            // (service-managed dedicated servers). Upstream's cwd-relative literal is a wart
            // this merged fork does not need to keep.
            CONFIG = LithiumConfig.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("lithium.properties").toFile());
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration file for Lithium", e);
        }

        this.logger.info("Loaded configuration file for Lithium: {} options available, {} override(s) found",
                CONFIG.getOptionCount(), CONFIG.getOptionOverrideCount());
    }

    @Override
    public String getRefMapperConfig() {
        return PlatformRuntimeInformation.getInstance().platformUsesRefmap() ? "lithium.refmap.json" : null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Standalone Lithium is authoritative: disable EVERY bundled-Lithium mixin so the two
        // copies never double-apply. (CONFIG is intentionally null in this mode — return before use.)
        if (STANDALONE_LITHIUM) {
            return false;
        }
        if (DEBUG) {
            this.logger.info("Checking mixin '{}' for target '{}'", mixinClassName, targetClassName);
        }
        String mixin = null;
        for (String root : MIXIN_PACKAGE_ROOTS) {
            if (mixinClassName.startsWith(root)) {
                mixin = mixinClassName.substring(root.length());
                break;
            }
        }
        if (mixin == null) {
            this.logger.error("Expected mixin '{}' to start with any of these package roots '{}', treating as foreign and " +
                    "disabling!", mixinClassName, MIXIN_PACKAGE_ROOTS);
            return false;
        }

        Option option = CONFIG.getEffectiveOptionForMixin(mixin);

        if (option == null) {
            this.logger.error("No rules matched mixin '{}', treating as foreign and disabling!", mixin);

            return false;
        }

        if (option.isOverridden()) {
            String source = "[unknown]";

            if (option.isUserDefined()) {
                source = "user configuration";
            } else if (option.isModDefined()) {
                source = "mods [" + String.join(", ", option.getDefiningMods()) + "]";
            }

            if (option.isEnabled()) {
                this.logger.info("Force-enabling mixin '{}' as rule '{}' (added by {}) enables it", mixin,
                        option.getName(), source);
            } else {
                this.logger.info("Force-disabling mixin '{}' as rule '{}' (added by {}) disables it and children", mixin,
                        option.getName(), source);
            }
        }

        boolean enabled = option.isEnabled();
        if (DEBUG) {
            if (!enabled) {
                this.logger.info("Disabling mixin '{}' due to rule '{}'", mixin, option.getName());
            } else {
                this.logger.info("Enabling mixin '{}' due to rule '{}'", mixin, option.getName());
            }
        }
        return enabled;
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
