package dev.superchunk.com.ishland.c2me.opts.scheduling.common;

import net.minecraft.server.level.ServerLevel;

public interface ServerMidTickTask {

    void executeTasksMidTick(ServerLevel world);

}
