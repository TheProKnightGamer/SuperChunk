package dev.superchunk.compat;

import com.bawnorton.mixinsquared.canceller.MixinCancellerRegistrar;

import java.util.List;

/** MixinSquared cancellers that name a bundled mod's mixin also cancel SuperChunk's relocated copy. */
public final class MixinSquaredBridgeTest {
    private static int checks;

    public static void main(String[] args) {
        List<String> targets = List.of("net.minecraft.util.ClassInstanceMultiMap");
        String entityByType = "dev.superchunk.net.caffeinemc.mods.lithium.mixin.collections.entity_by_type.ClassInstanceMultiMapMixin";
        check(MixinSquaredBridge.originalNames(entityByType).equals(List.of(
                        "net.caffeinemc.mods.lithium.mixin.collections.entity_by_type.ClassInstanceMultiMapMixin",
                        "me.jellysquid.mods.lithium.mixin.collections.entity_by_type.ClassInstanceMultiMapMixin")),
                "A bundled Lithium mixin maps to its own name and to the old Lithium package");
        check(MixinSquaredBridge.originalNames("dev.superchunk.com.ishland.c2me.base.mixin.MixinFoo")
                .equals(List.of("com.ishland.c2me.base.mixin.MixinFoo")), "A bundled C2ME mixin maps to its own name");
        check(MixinSquaredBridge.originalNames("dev.superchunk.mixin.MixinSurfaceSystemFast").isEmpty(),
                "SuperChunk's own mixins have no other name");
        check(MixinSquaredBridge.originalNames("net.caffeinemc.mods.lithium.mixin.collections.entity_by_type.X").isEmpty(),
                "An unrelocated name is not ours");

        MixinSquaredBridge.register();
        check(!cancels(targets, entityByType), "Nothing cancels before another canceller asks to");
        // AsyncParticles' rule (NeoForge build): Lithium's entity_by_type mixins, by prefix.
        MixinCancellerRegistrar.register((t, name) -> name.startsWith("net.caffeinemc.mods.lithium.mixin.collections.entity_by_type"));
        check(cancels(targets, entityByType), "A canceller naming Lithium's mixin cancels the bundled copy");
        check(!cancels(targets, "dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.pathing.WalkNodeEvaluatorMixin"),
                "Other bundled mixins stay");
        // AsyncParticles' rule (Forge build) names the old package.
        MixinCancellerRegistrar.register((t, name) -> name.startsWith("me.jellysquid.mods.lithium.mixin.world.raycast"));
        check(cancels(targets, "dev.superchunk.net.caffeinemc.mods.lithium.mixin.world.raycast.BlockGetterMixin"),
                "A canceller naming the old Lithium package cancels the bundled copy");
        MixinCancellerRegistrar.register((t, name) -> name.equals("com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler")
                && t.contains("net.minecraft.world.level.levelgen.synth.ImprovedNoise"));
        check(cancels(List.of("net.minecraft.world.level.levelgen.synth.ImprovedNoise"),
                "dev.superchunk.com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler"), "Target lists are passed through");
        check(!cancels(targets, "dev.superchunk.com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler"),
                "A canceller's target condition still applies");
        check(!cancels(targets, "dev.superchunk.mixin.MixinSurfaceSystemFast"), "SuperChunk's own mixins are never cancelled by name");
        System.out.println("MixinSquaredBridgeTest passed " + checks + " checks");
    }

    private static boolean cancels(List<String> targets, String mixin) {
        return MixinCancellerRegistrar.shouldCancel(targets, mixin, name -> { });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
