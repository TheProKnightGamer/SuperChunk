package dev.superchunk.mixin.compat;

import dev.superchunk.compat.SkeinCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Give command-block reloads an accurate result while their dimension still owns a worker. */
@Pseudo
@Mixin(targets = {
        "com.theproknightgamr.skein.SkeinCommands",
        "com.asher.skein.SkeinCommands"
}, remap = false)
public abstract class MixinSkeinCommands {
    @Inject(method = "reload", at = @At("HEAD"), cancellable = true, require = 1)
    private static void superchunk$deferReload(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) {
        if (SkeinCompat.deferConfigMutation()) {
            source.sendSuccess(() -> Component.literal("Skein: config reload queued for the next server tick, "
                    + "after all dimension workers finish."), true);
            cir.setReturnValue(1);
        }
    }
}
