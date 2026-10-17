package dev.superchunk.net.caffeinemc.mods.lithium.forge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformMappingInformation;
import cpw.mods.modlauncher.api.INameMappingService;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.util.Map;

/**
 * Forge 1.20.1 runtime is SRG-named in production and mojmap-named in dev; the neo original
 * simply returned the mojmap name (NeoForge 1.21 runtime IS mojmap). This implementation keys a
 * hardcoded mojmap→SRG table on the {@code mojmap} argument (tsrg-verified names; the set is
 * exactly the reflective probes used by {@code EntityClassGroup}/{@code ProjectileEntityClassGroup}/
 * {@code PushableEntityClassGroup}) and then runs the SRG name through
 * {@link ObfuscationReflectionHelper#remapName} so DEV runs (mojmap runtime) resolve too.
 * Unknown names throw loudly — fail-fast beats a silent mis-scan of the entity class hierarchy.
 */
public class ForgeMappingInformation implements PlatformMappingInformation {

    private static final Map<String, String> MOJMAP_TO_SRG = Map.of(
            "canCollideWith", "m_7337_",
            "canBeCollidedWith", "m_5829_",
            "isPushable", "m_6094_",
            "isPickable", "m_6087_",
            "canBeHitByProjectile", "m_271807_",
            "onClimbable", "m_6147_",
            "canHitEntity", "m_5603_",
            // ReflectionUtil.REMAPPED_ON_ENTITY_COLLISION (BlockBehaviour.entityInside) — an 8th
            // reflective probe the plan's §3.2 table missed; tsrg-verified (L1 deviation note).
            "entityInside", "m_7892_"
    );

    @Override
    public String mapMethodName(String fromMappings, String clazz, String method, String argsDescriptor, String mojmap) {
        String srg = MOJMAP_TO_SRG.get(mojmap);
        if (srg == null) {
            throw new IllegalArgumentException("[SuperChunk-Lithium] no SRG mapping registered for reflective method name '"
                    + mojmap + "' (" + clazz + "." + method + argsDescriptor + ") — add it to ForgeMappingInformation");
        }
        // Dev runtime is mojmap: remapName translates SRG->mojmap there; production is a no-op
        // (SRG runtime).
        return ObfuscationReflectionHelper.remapName(INameMappingService.Domain.METHOD, srg);
    }
}
