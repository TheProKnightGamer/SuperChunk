package dev.superchunk.net.caffeinemc.mods.lithium.neoforge;

/**
 * Lithium init, merged into SuperChunk. Was a standalone {@code @Mod("lithium")}
 * entrypoint; now a plain init invoked from {@link dev.superchunk.SuperChunk} so the
 * merged mod exposes a single mod id.
 *
 * <p>Lithium's upstream NeoForge entrypoint constructor body is empty — all of its
 * work is driven by {@code LithiumMixinPlugin} (applied via the mixin configs) and the
 * platform services wired through {@code META-INF/services}. This {@code init()} is kept
 * for parity with the other merged entrypoints and as a hook should Lithium add
 * constructor-time setup in a future bump.
 */
public class LithiumNeoForgeMod {
    private LithiumNeoForgeMod() {}

    public static void init() {
        // No-op: Lithium initializes via its mixin config plugin + SPI platform services.
    }
}
