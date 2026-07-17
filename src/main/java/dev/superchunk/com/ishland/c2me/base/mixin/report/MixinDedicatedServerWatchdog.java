package dev.superchunk.com.ishland.c2me.base.mixin.report;

import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadState;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.management.ThreadInfo;
import java.util.Map;

@Mixin(ServerWatchdog.class)
public class MixinDedicatedServerWatchdog {

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/management/ThreadInfo;getThreadId()J"))
    private void prependThreadInstrumentation(CallbackInfo ci, @Local StringBuilder stringBuilder, @Local ThreadInfo threadInfo) {
        String state = null;
        try {
            state = ThreadInstrumentation.printState(threadInfo);
        } catch (Throwable t) {
            LOGGER.error("Failed to fetch state for thread {}", threadInfo);
        }
        if (state != null) {
            stringBuilder.append(state);
        }
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;addCategory(Ljava/lang/String;)Lnet/minecraft/CrashReportCategory;", ordinal = 0))
    private void addInstrumentationData(CallbackInfo ci, @Local CrashReport crashReport) {
        CrashReportCategory section = crashReport.addCategory("Thread trace dump (obtained on a best-effort basis)", 1);
        try {
            for (Map.Entry<Thread, ThreadState> entry : ThreadInstrumentation.entrySet()) {
                try {
                    Thread thread = entry.getKey();
                    String state = ThreadInstrumentation.printState(thread.getName(), thread.threadId(), entry.getValue());
                    if (state != null) {
                        section.setDetail(thread.getName(), state);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to dumping state for thread {}", entry.getKey(), t);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to dump all known thread states", t);
        }
    }

}
