package dev.superchunk.com.ishland.c2me.base.mixin.access;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TickingTracker;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DistanceManager.class)
public interface IChunkTicketManager {

    @Invoker("updatePlayerTickets")
    void invokeSetWatchDistance(int viewDistance);

    @Accessor("playersPerChunk")
    Long2ObjectMap<ObjectSet<ServerPlayer>> getPlayersByChunkPos();

    @Accessor("tickets")
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> getTicketsByPosition();

    @Accessor("playerTicketManager")
    DistanceManager.PlayerTicketTracker getNearbyChunkTicketUpdater();

    @Accessor("tickingTicketsTracker")
    TickingTracker getSimulationDistanceTracker();

}
