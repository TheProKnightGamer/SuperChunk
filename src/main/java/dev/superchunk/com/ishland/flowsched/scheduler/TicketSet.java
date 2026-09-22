package dev.superchunk.com.ishland.flowsched.scheduler;

import dev.superchunk.com.ishland.flowsched.util.Assertions;

import java.lang.invoke.VarHandle;
import java.util.Set;

public class TicketSet<K, V, Ctx> {

    private final ItemStatus<K, V, Ctx> initialStatus;
    private final Set<ItemTicket<K, V, Ctx>>[] status2Tickets;
    private final int[] status2TicketsSize;
    private volatile int targetStatus = 0;

    public TicketSet(ItemStatus<K, V, Ctx> initialStatus, ObjectFactory objectFactory) {
        this.initialStatus = initialStatus;
        this.targetStatus = initialStatus.ordinal();
        ItemStatus<K, V, Ctx>[] allStatuses = initialStatus.getAllStatuses();
        this.status2Tickets = new Set[allStatuses.length];
        for (int i = 0; i < allStatuses.length; i++) {
            this.status2Tickets[i] = objectFactory.createConcurrentSet();
        }
        this.status2TicketsSize = new int[allStatuses.length];
        VarHandle.fullFence();
    }

    public boolean checkAdd(ItemTicket<K, V, Ctx> ticket) {
        ItemStatus<K, V, Ctx> targetStatus = ticket.getTargetStatus();
        final boolean added = this.status2Tickets[targetStatus.ordinal()].add(ticket);
        return added;
    }

    /**
     * Not thread-safe
     */
    public void addUnchecked(ItemTicket<K, V, Ctx> ticket) {
        final int ordinal = ticket.getTargetStatus().ordinal();
        this.status2TicketsSize[ordinal] ++;
        this.updateTargetStatus(ordinal);
    }

    public boolean checkRemove(ItemTicket<K, V, Ctx> ticket) {
        ItemStatus<K, V, Ctx> targetStatus = ticket.getTargetStatus();
        final boolean removed = this.status2Tickets[targetStatus.ordinal()].remove(ticket);
        return removed;
    }

    /**
     * Not thread-safe
     */
    public void removeUnchecked(ItemTicket<K, V, Ctx> ticket) {
        final int ordinal = ticket.getTargetStatus().ordinal();
        this.status2TicketsSize[ordinal] --;
        this.updateTargetStatus(ordinal);
    }

    private void updateTargetStatus(int changedStatus) {
        int target = this.targetStatus;
        if (changedStatus > target && this.status2TicketsSize[changedStatus] > 0) {
            target = changedStatus;
        } else {
            // Only losing the last ticket at the current maximum requires a scan. Most
            // dependency-ticket changes leave that maximum untouched.
            while (target > 0 && this.status2TicketsSize[target] <= 0) {
                target --;
            }
        }
        if (target != this.targetStatus) {
            this.targetStatus = target;
        }
    }

    /**
     * Not thread-safe
     */
    public ItemStatus<K, V, Ctx> getTargetStatus() {
        return this.initialStatus.getAllStatuses()[this.targetStatus];
    }

    public Set<ItemTicket<K, V, Ctx>> getTicketsForStatus(ItemStatus<K, V, Ctx> status) {
        return this.status2Tickets[status.ordinal()];
    }

    void clear() {
        for (Set<ItemTicket<K, V, Ctx>> tickets : status2Tickets) {
            tickets.clear();
        }

        VarHandle.fullFence();
    }

    void assertEmpty() {
        for (Set<ItemTicket<K, V, Ctx>> tickets : status2Tickets) {
            Assertions.assertTrue(tickets.isEmpty());
        }
    }

}
