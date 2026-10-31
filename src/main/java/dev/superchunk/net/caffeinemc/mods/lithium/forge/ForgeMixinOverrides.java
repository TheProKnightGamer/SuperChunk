package dev.superchunk.net.caffeinemc.mods.lithium.forge;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import dev.superchunk.net.caffeinemc.mods.lithium.common.config.Option;
import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformMixinOverrides;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForgeMixinOverrides implements PlatformMixinOverrides {
    // Kept as "lithium:options" so mods that declare option overrides for radium/standalone
    // Lithium also affect the bundled module.
    protected static final String JSON_KEY_LITHIUM_OPTIONS = "lithium:options";

    /**
     * Voxy's ingest ({@code WorldConversionFactory.setupLocalPalette}) accepts the vanilla palettes,
     * plus {@code net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette} only when a mod
     * with id {@code lithium} is loaded. This bundled copy is neither: its palette lives under
     * {@code dev.superchunk.*}, so every section {@code mixin.chunk.palette} gave a hash palette throws
     * "Unknown palette type" and Voxy never ingests it (GitHub issue #8). Turning the rule off here,
     * as if Voxy declared it in {@code lithium:options}, affects only the bundled copy: a standalone
     * Lithium makes this one stand down, and Voxy recognises its palette. Kill switch:
     * {@code -Dsuperchunk.compat.voxy=false}.
     *
     * <p>Forge 1.20.1: no Voxy build for this line is known (the fix was found and verified on
     * NeoForge 1.21.1 via Roxy). Kept so a "voxy" mod id, however it arrives, gets the same
     * treatment; it costs nothing when absent, and when present it only selects vanilla's palette.
     */
    private static final boolean VOXY_COMPAT =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.voxy", "true"));

    /**
     * Entity Collision Optimization ({@code ecopt}) 1.20.1 {@code @Redirect}s the same
     * {@code Level.getEntities} call in {@code LivingEntity.pushEntities} as
     * {@code mixin.entity.collisions.unpushable_cramming}, at the same priority. Only one redirect
     * can take a call, and both are required, so the game failed to start ("Redirector
     * getOtherPushableEntities ... failed injection check", GitHub #22). Both optimize the same
     * cramming check; this leaves it to ecopt. (Its 1.21.1 build wraps the call and yields instead.)
     * Kill switch: {@code -Dsuperchunk.compat.ecopt=false}.
     */
    private static final boolean ECOPT_COMPAT =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.ecopt", "true"));

    /**
     * Create: Copycats+ and Create: Connected make mobs path over their copycat blocks as over the block they copy:
     * each wraps the {@code getBlock()} call in vanilla's {@code WalkNodeEvaluator} path-type lookup, and in
     * Radium's {@code PathNodeDefaults.getNodeType}, which {@code mixin.ai.pathing} answers from instead. That second
     * mixin names Radium's class ({@code me.jellysquid.mods.lithium.common.ai.pathing.PathNodeDefaults}); this copy
     * lives under {@code dev.superchunk.*}, so it never applied, and the cache gave copycat blocks the wrong path
     * type. Without the cache, vanilla's lookup runs with their wrap in it. Kill switch:
     * {@code -Dsuperchunk.compat.copycatPathing=false}.
     */
    private static final boolean COPYCAT_PATHING_COMPAT =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.copycatPathing", "true"));

    /**
     * Options this port carries from Lithium 0.15 that Radium, the 1.20.1 Lithium, does not have, so 1.20.1 builds
     * of mods that turn them off on 1.21.1 never had to. Spelunkery's channel flow hooks
     * {@code FlowingFluid.getSpread}, which {@code mixin.block.fluid.flow} returns early from; its 1.21.1 build
     * declares {@code "mixin.block.fluid.flow" = false}, its 1.20.1 build nothing. Kill switch:
     * {@code -Dsuperchunk.compat.newerLithiumOptOuts=false}.
     */
    private static final String[][] NEWER_LITHIUM_OPT_OUTS = {
            {"spelunkery", "mixin.block.fluid.flow"},
    };
    private static final boolean NEWER_LITHIUM_OPT_OUTS_ON =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.newerLithiumOptOuts", "true"));

    @Override
    public void applyLithiumCompat(Map<String, Option> options) {

    }

    @Override
    public List<PlatformMixinOverrides.MixinOverride> applyModOverrides() {
        List<MixinOverride> list = new ArrayList<>();

        for (ModInfo meta : FMLLoader.getLoadingModList().getMods()) {
            if (VOXY_COMPAT && "voxy".equals(meta.getModId())) {
                list.add(new MixinOverride(meta.getModId(), "mixin.chunk.palette", false));
            }
            if (ECOPT_COMPAT && "ecopt".equals(meta.getModId())) {
                list.add(new MixinOverride(meta.getModId(), "mixin.entity.collisions.unpushable_cramming", false));
            }
            if (COPYCAT_PATHING_COMPAT && ("copycats".equals(meta.getModId()) || "create_connected".equals(meta.getModId()))) {
                list.add(new MixinOverride(meta.getModId(), "mixin.ai.pathing", false));
            }
            if (NEWER_LITHIUM_OPT_OUTS_ON) {
                for (String[] optOut : NEWER_LITHIUM_OPT_OUTS) {
                    if (optOut[0].equals(meta.getModId())) {
                        list.add(new MixinOverride(meta.getModId(), optOut[1], false));
                    }
                }
            }
            // NOTE: the IModFileInfo INTERFACE (forgespi 7.x) does NOT declare getConfigElement —
            // it is declared on the concrete ModFileInfo returned by ModInfo.getOwningFile()
            // (javap-verified on fmlloader 1.20.1-47.3.0). Call it on the concrete type.
            meta.getOwningFile().getConfigElement(JSON_KEY_LITHIUM_OPTIONS).ifPresent(override -> addOverrides(list, meta, override));
            // Also read the key from the mod's own [[mods]] entry (ModInfo.getConfigElement). GUHAO's
            // VIX declares it there to turn off mixin.entity.collisions.movement, whose redirect
            // collides with its bundled ModelLoader's EntityCollisionMixin; read only at the top of
            // the file, the override was missed and the game failed to start with an injection error.
            meta.getConfigElement(JSON_KEY_LITHIUM_OPTIONS).ifPresent(override -> addOverrides(list, meta, override));
        }

        return list;
    }

    private static void addOverrides(List<MixinOverride> list, ModInfo meta, Object override) {
        if (override instanceof Map<?, ?> overrides && overrides.keySet().stream().allMatch(key -> key instanceof String)) {
            addOverrides(list, meta, "", overrides);
        } else {
            System.out.printf("[Lithium] '%s' contains invalid Lithium option overrides, ignoring", meta.getModId());
        }
    }

    /**
     * TOML reads a dotted key as nested tables, so Valkyrien Skies' {@code ["lithium:options"] mixin.ai.poi = false}
     * arrives as {@code mixin -> ai -> poi}. Rejected as a non-boolean {@code mixin}, its override was lost, and VS's
     * POI mixin then failed against {@code mixin.ai.poi}'s overwrite of {@code PoiManager.getInRange} (#27). The path
     * is joined back into the option name, which a flat quoted key ({@code "mixin.ai.poi" = false}) already is.
     */
    private static void addOverrides(List<MixinOverride> list, ModInfo meta, String prefix, Map<?, ?> overrides) {
        overrides.forEach((key, value) -> {
            Object entry = value instanceof UnmodifiableConfig table ? table.valueMap() : value;
            if (entry instanceof Map<?, ?> nested && key instanceof String) {
                addOverrides(list, meta, prefix + key + ".", nested);
                return;
            }
            if (!(entry instanceof Boolean) || !(key instanceof String)) {
                System.out.printf("[Lithium] Mod '%s' attempted to override option '%s' with an invalid value, ignoring", meta.getModId(), prefix + key);
                return;
            }

            list.add(new MixinOverride(meta.getModId(), prefix + key, (Boolean) entry));
        });
    }
}
