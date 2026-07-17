package dev.superchunk.com.ishland.c2me.opts.chunkio.mixin.hide_sync_disk_writes_behind_flag;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
public class MixinRegionBasedStorage {

    @Mutable
    @Shadow @Final private boolean sync;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onPostInit(CallbackInfo info) {
        // Accept BOTH the relocated key and upstream C2ME's documented key: the package
        // relocation mechanically prefixed this user-facing -D property, silently no-op'ing
        // operators who set the upstream name to opt back into crash-durable dsync writes.
        this.sync = Boolean.parseBoolean(System.getProperty(
                "dev.superchunk.com.ishland.c2me.chunkio.syncDiskWrites",
                System.getProperty("com.ishland.c2me.chunkio.syncDiskWrites", "false")));
    }

}
