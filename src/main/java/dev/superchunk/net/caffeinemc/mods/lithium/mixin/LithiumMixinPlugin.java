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
    private static final String[] MIXIN_PACKAGE_ROOTS = {"dev.superchunk.net.caffeinemc.mods.lithium.mixin.", "dev.superchunk.net.caffeinemc.mods.lithium.forge.mixin."};
    private static final Boolean DEBUG = false;

    private final Logger logger = LogManager.getLogger("SuperChunk-Lithium");

    private static LithiumConfig CONFIG;

    /**
     * Master kill-switch for the whole bundled-Lithium module (mirrors
     * {@code -Dsuperchunk.chunksystem.disable}): {@code -Dsuperchunk.lithium.disable=true}
     * short-circuits {@link #shouldApplyMixin} for every bundled mixin.
     */
    private static final boolean MASTER_DISABLED = Boolean.getBoolean("superchunk.lithium.disable");

    /**
     * Mod-ids that ARE Lithium (the original, or a fork/repackage that ships Lithium's mixins under
     * the same {@code net.caffeinemc.mods.lithium.*} / {@code me.jellysquid.mods.lithium.*} classes).
     * Any of these standing alongside SuperChunk means two copies of the same Lithium mixins would
     * target the same vanilla classes — a fatal double-application (observed as e.g. {@code Critical
     * injection failure: Redirector removeOldMapAlloc ... (0/1) succeeded} on
     * {@code alloc.nbt.CompoundTagMixin} when Radium is present). Detecting them here stands the
     * bundled copy down so the external one is authoritative. The forks keep reading
     * {@code config/lithium.properties} (so SuperChunk's C2ME-overlap pins, written there, still
     * apply) — verified compatible via server boot test.
     *
     * <p>NOTE: radium's {@code provides=["lithium"]} toml key is NOT honored for lookup by Forge
     * 1.20.1 — hence probing all three real ids.
     *
     * <p>MUST be declared ABOVE {@link #STANDALONE_LITHIUM}: static initializers run in textual
     * order, so declaring it after would leave this array null while
     * {@link #detectStandaloneLithium()} runs, NPE-ing inside the {@code catch (Throwable)} and
     * silently killing the whole mod-list probe (leaving only the class-presence fallback).
     */
    private static final String[] LITHIUM_EQUIVALENT_MODIDS = {"lithium", "radium", "canary"};

    /**
     * Non-null when a STANDALONE Lithium-family mod (a modern-Lithium port, Radium, or Canary) is
     * installed alongside SuperChunk; the value names WHICH probe hit (a mod id or a class name).
     * Computed ONCE at class-init (i.e. when Mixin first instantiates this plugin while preparing
     * {@code superchunk-lithium(-forge).mixins.json}) — that is mixin-plugin time, BEFORE mod
     * construction. Valid on Forge 1.20.1: {@code LoadingModList} is populated during mod
     * discovery, before mixin config plugins load (proven by radium itself calling
     * {@code LoadingModList.get()} from its own mixin plugin on this exact loader). When set,
     * EVERY bundled-Lithium mixin becomes a no-op so only the standalone fork applies (no
     * double-apply / {@code @Overwrite} conflicts). See {@link #shouldApplyMixin} and the
     * short-circuit in {@link #onLoad}.
     */
    private static final String STANDALONE_LITHIUM = detectStandaloneLithium();

    /** Ensures the single deferral INFO line is logged exactly once (onLoad fires per config). */
    private static final AtomicBoolean DEFER_LOGGED = new AtomicBoolean(false);

    /**
     * Whether a standalone Lithium-family mod is installed and therefore authoritative (the
     * bundled module stands down). Other SuperChunk components that would notify/consult the
     * bundled Lithium (e.g. C2ME's {@code LithiumChunkStatusTrackerInvoker}) key off this to
     * target the standalone one instead ({@code LithiumBlockTracking} keys off it implicitly:
     * standdown ⇒ the counting mixin never applies ⇒ {@code BlockStateFlags.ENABLED} false ⇒
     * the bridge probes the external fork — the EXTERNAL_COUNTS path).
     */
    public static boolean isStandaloneLithiumActive() {
        return STANDALONE_LITHIUM != null;
    }

    private static String detectStandaloneLithium() {
        // Primary: the FML loading mod list, available at mixin-plugin time and keyed by modId
        // (populated during mod discovery, before mixin config plugins load — proven by radium
        // itself calling LoadingModList.get() from its own mixin plugin on this exact loader).
        // SuperChunk declares only modId "superchunk", so any of these ids can only be a standalone
        // fork.
        try {
            net.minecraftforge.fml.loading.LoadingModList lml = net.minecraftforge.fml.loading.LoadingModList.get();
            if (lml != null) {
                // getMods() FIRST: iterating the sorted ModInfo list is the same approach C2ME's own
                // module detection (and this fork's ForgeMixinOverrides) uses, and it does not depend
                // on the fileById map being complete at this early phase. On the NeoForge tree a
                // getModFileById("radium") probe silently missed Radium — that miss is what motivated
                // the ModInfo scan; on Forge 1.20.1 fileById is built from the same mod files
                // (javap-verified on fmlloader 1.20.1-47.4.22), but the scan costs nothing, so run it
                // ahead of the by-file lookup.
                //
                // Its OWN try/catch, deliberately: it touches the CONCRETE fmlloader class
                // moddiscovery.ModInfo, so a module-graph/API surprise here must NOT skip the
                // getModFileById probe below — that probe is the production-QA-validated one
                // (PORT-NOTES "FORK STANDDOWN", radium-0.12.4) and it only uses public
                // LoadingModList surface. One shared try would let a getMods() throw swallow it.
                try {
                    java.util.Set<String> ids = java.util.Set.of(LITHIUM_EQUIVALENT_MODIDS);
                    for (net.minecraftforge.fml.loading.moddiscovery.ModInfo mi : lml.getMods()) {
                        if (ids.contains(mi.getModId())) {
                            return "modid '" + mi.getModId() + "'";
                        }
                    }
                } catch (Throwable ignored) {
                    // ModInfo not visible / API mismatch — fall through to the by-file lookup.
                }
                // Secondary (the originally QA-validated probe, kept as belt-and-braces): the
                // modId -> mod-file map. Can only ever add detections, never remove one.
                for (String id : LITHIUM_EQUIVALENT_MODIDS) {
                    if (lml.getModFileById(id) != null) {
                        return "mod file '" + id + "'";
                    }
                }
            }
        } catch (Throwable ignored) {
            // LoadingModList not ready / API mismatch — fall through to the class-presence probes.
        }
        // Fallback: class-presence probes (Class.forName with initialize=false so we only test
        // presence, never run static init). The standalone forks live outside dev.superchunk.* —
        // the bundled copy is relocated, so these packages can only come from a standalone install.
        for (String probe : new String[]{
                "me.jellysquid.mods.lithium.common.config.LithiumConfig", // radium-0.12.4
                "com.abdelaziz.canary.common.config.CanaryConfig",        // canary-0.3.3
                "net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin"    // any future modern-Lithium port
        }) {
            try {
                Class.forName(probe, false, LithiumMixinPlugin.class.getClassLoader());
                return "class " + probe;
            } catch (Throwable ignored) {
                // absent — keep probing
            }
        }
        return null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // The unified-config write-through (config/lithium.properties incl. the four C2ME-overlap
        // safety pins) must exist before LithiumConfig reads it. applyEarly() is idempotent and is
        // also called from SuperChunkMixinPlugin.onLoad() — calling it here as well removes any
        // dependence on mixin-config registration ORDER. It runs even in standdown mode: the pins
        // govern whichever Lithium-family mod reads config/lithium.properties (radium reads the
        // same file).
        try {
            dev.superchunk.config.SuperChunkConfig.applyEarly();
        } catch (Throwable t) {
            this.logger.warn("[SuperChunk] SuperChunkConfig.applyEarly() failed from LithiumMixinPlugin.onLoad", t);
        }
        if (STANDALONE_LITHIUM != null) {
            // Defer entirely to the standalone fork: do NOT load CONFIG; every shouldApplyMixin
            // returns false. Log exactly ONE deferral line across both bundled configs.
            if (DEFER_LOGGED.compareAndSet(false, true)) {
                this.logger.info("[SuperChunk] standalone Lithium-family mod detected ({}) -> SuperChunk's bundled Lithium disabled; the installed mod is authoritative", STANDALONE_LITHIUM);
            }
            return;
        }
        if (MASTER_DISABLED) {
            if (DEFER_LOGGED.compareAndSet(false, true)) {
                this.logger.info("[SuperChunk] -Dsuperchunk.lithium.disable=true -> SuperChunk's bundled Lithium disabled");
            }
            return;
        }
        if (CONFIG != null) {
            return;
        }

        try {
            // FMLPaths.CONFIGDIR (gameDir/config), NOT a cwd-relative "./config": SuperChunkConfig's
            // lithium.* write-through (incl. the four C2ME-overlap safety pins) writes to
            // FMLPaths.CONFIGDIR, so reader and writer must agree even when cwd != gameDir
            // (service-managed dedicated servers). Upstream's cwd-relative literal is a wart
            // this merged fork does not need to keep.
            CONFIG = LithiumConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("lithium.properties").toFile());
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration file for Lithium", e);
        }

        this.logger.info("Loaded configuration file for Lithium: {} options available, {} override(s) found",
                CONFIG.getOptionCount(), CONFIG.getOptionOverrideCount());
    }

    @Override
    public String getRefMapperConfig() {
        // Forge uses refmaps; the JSON "refmap" key says the same thing (belt+braces keeps plugin
        // and config agreeing).
        return PlatformRuntimeInformation.getInstance().platformUsesRefmap() ? "superchunk.refmap.json" : null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Standalone Lithium fork is authoritative: disable EVERY bundled-Lithium mixin so the two
        // copies never double-apply. (CONFIG is intentionally null in this mode — return before use.)
        if (STANDALONE_LITHIUM != null || MASTER_DISABLED) {
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
