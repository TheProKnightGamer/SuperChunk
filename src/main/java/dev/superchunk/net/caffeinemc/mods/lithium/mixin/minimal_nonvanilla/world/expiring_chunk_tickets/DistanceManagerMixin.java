package dev.superchunk.net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.world.expiring_chunk_tickets;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import dev.superchunk.net.caffeinemc.mods.lithium.common.util.collections.ChunkTicketSortedArraySet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Collections;
import java.util.Iterator;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Shadow
    private long ticketTickCounter;

    @Shadow
    @Final
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets;

    private final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> positionWithExpiringTicket = new Long2ObjectOpenHashMap<>();

    private static boolean canNoneExpire(SortedArraySet<Ticket<?>> tickets) {
        if (!tickets.isEmpty()) {
            for (Ticket<?> ticket : tickets) {
                if (canExpire(ticket)) {
                    return false;
                }
            }
        }
        return true;
    }

    // PORT-1.20.1: the getTickets computeIfAbsent lambda is lambda$getTickets$6 in mojmap dev and
    // m_183922_ in SRG production (radium-0.12.4 targets exactly this pair on this loader; lambda
    // names are not in the mcpToSrg refmap mappings, so the SRG name must be listed literally).
    // The yarn alias method_14041 is dropped. NOTE: Forge's addTicket patch adds a second
    // SortedArraySet.create lambda (lambda$addTicket$5, forcedTickets) — NOT redirected here.
    @Redirect(method = { "m_183922_", "lambda$getTickets$6" }, require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/SortedArraySet;create(I)Lnet/minecraft/util/SortedArraySet;"))
    private static SortedArraySet<Ticket<?>> useLithiumSortedArraySet(int initialCapacity) {
        return new ChunkTicketSortedArraySet<>(initialCapacity);
    }

    private static boolean canExpire(Ticket<?> ticket) {
        return ticket.getType().timeout() != 0;
    }

    /**
     * Mark all locations that have tickets that can expire as such. Allows iterating only over locations with
     * tickets that can expire when purging expired tickets.
     */
    @Inject(
            method = "addTicket(JLnet/minecraft/server/level/Ticket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/Ticket;setCreatedTick(J)V"),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void registerExpiringTicket(long position, Ticket<?> ticket, CallbackInfo ci, SortedArraySet<Ticket<?>> ticketsAtPos, int i, Ticket<?> chunkTicket) {
        if (canExpire(ticket)) {
            this.positionWithExpiringTicket.put(position, ticketsAtPos);
        }
    }

    @Inject(
            method = "removeTicket(JLnet/minecraft/server/level/Ticket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$ChunkTicketTracker;update(JIZ)V")
    )
    private void unregisterExpiringTicket(long pos, Ticket<?> ticket, CallbackInfo ci) {
        if (canExpire(ticket)) {
            SortedArraySet<Ticket<?>> ticketsAtPos = this.positionWithExpiringTicket.get(pos);
            if (canNoneExpire(ticketsAtPos)) {
                this.positionWithExpiringTicket.remove(pos);
            }
        }
    }

    // PORT-1.20.1: ordinal 0 pins the FIRST addOrGet — Forge's addTicket patch appends a second
    // one (forcedTickets.addOrGet in the isForceTicks branch) with different live locals, which
    // would break the CAPTURE_FAILHARD capture.
    @Inject(
            method = "addTicket(JLnet/minecraft/server/level/Ticket;)V",
            at = @At(
                    value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/util/SortedArraySet;addOrGet(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 0
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void updateSetMinExpiryTime(long position, Ticket<?> ticket, CallbackInfo ci, SortedArraySet<?> sortedArraySet, int i) {
        if (canExpire(ticket) && sortedArraySet instanceof ChunkTicketSortedArraySet<?> chunkTickets) {
            chunkTickets.addExpireTime(this.ticketTickCounter + ticket.getType().timeout());
        }
    }


    @Redirect(method = "purgeStaleTickets()V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/level/DistanceManager;tickets:Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;",
                    ordinal = 0
            )
    )
    private Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> getExpiringTicketsByPosition(DistanceManager chunkTicketManager) {
        return this.positionWithExpiringTicket;
    }

    @Redirect(method = "purgeStaleTickets()V",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/SortedArraySet;isEmpty()Z"
            )
    )
    private boolean retCanNoneExpire(SortedArraySet<Ticket<?>> tickets) {
        return canNoneExpire(tickets);
    }

    // PORT-1.20.1: at this injection point 1.20.1's purgeStaleTickets body also has the inner
    // Iterator and the dirty flag live — CAPTURE_FAILHARD requires capturing all four locals
    // (neo's 1.21.1 body only had the first two).
    @Inject(method = "purgeStaleTickets()V", locals = LocalCapture.CAPTURE_FAILHARD,
            at = @At(
                    value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/util/SortedArraySet;isEmpty()Z"
            )
    )
    private void removeIfEmpty(CallbackInfo ci, ObjectIterator<?> objectIterator, Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry, Iterator<Ticket<?>> ticketIterator, boolean levelDirty) {
        SortedArraySet<Ticket<?>> ticketsAtPos = entry.getValue();
        if (ticketsAtPos.isEmpty()) {
            this.tickets.remove(entry.getLongKey(), ticketsAtPos);
        }
    }

    @Redirect(method = "purgeStaleTickets()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/SortedArraySet;iterator()Ljava/util/Iterator;"
            )
    )
    private Iterator<Ticket<?>> skipIfNotExpiringNow(SortedArraySet<Ticket<?>> ticketsAtPos) {
        if (ticketsAtPos instanceof ChunkTicketSortedArraySet<?> optimizedSet && optimizedSet.getMinExpireTime() > this.ticketTickCounter) {
            return Collections.emptyIterator();
        } else {
            return ticketsAtPos.iterator();
        }
    }
}
