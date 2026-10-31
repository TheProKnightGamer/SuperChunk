package dev.superchunk.com.ishland.vmp.common.chunkwatching;

import dev.superchunk.com.ishland.vmp.common.maps.AreaMap;
import dev.superchunk.io.papermc.paper.util.MCUtil;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Ported from VMP (chunkwatching.AreaPlayerChunkWatchingManager), re-based for Forge 1.20.1.
 *
 * <p>1.20.1 deltas vs the neo (1.21.1) copy — see portplans/vmp-notickvd-player.md §2.1:
 * <ul>
 *   <li>{@code ChunkTrackingView} / {@code setChunkTrackingView} do not exist on 1.20.1 — all
 *       such calls are DELETED. The area-map diff (add/remove listeners) is the sole driver of
 *       chunk send/drop, through {@code ChunkMap.updateChunkTracking}.</li>
 *   <li>{@code getViewDistance(player)} reads the {@link PlayerClientVDTracking} duck instead of
 *       {@code player.requestedViewDistance()} (method absent on 1.20.1).</li>
 * </ul>
 *
 * <p>KNOWN GAP (carried over from neo, doc corrected 2026-07-02): the general-player area map
 * (VMP's spawn-proximity overwrites) is dropped; {@code anyPlayerCloseEnoughForSpawning} /
 * {@code getPlayersCloseForSpawning} stay vanilla O(players). Only watching/sending is ported.
 */
public class AreaPlayerChunkWatchingManager {

    private final AreaMap<ServerPlayer> playerAreaMap;
    private final Object2LongOpenHashMap<ServerPlayer> positions = new Object2LongOpenHashMap<>();
    private Listener addListener = null;
    private Listener removeListener = null;

    private int watchDistance = 5;

    public AreaPlayerChunkWatchingManager() {
        this(null, null, null);
    }

    public AreaPlayerChunkWatchingManager(Listener addListener, Listener removeListener, ChunkMap tacs) {
        this.addListener = addListener;
        this.removeListener = removeListener;

        this.playerAreaMap = new AreaMap<>(
                (object, x, z) -> {
                    if (this.addListener != null) {
                        this.addListener.accept(object, x, z);
                    }
                },
                (object, x, z) -> {
                    if (this.removeListener != null) {
                        this.removeListener.accept(object, x, z);
                    }
                },
                true);
    }

    public void tick() {
        for (Object2LongMap.Entry<ServerPlayer> entry : this.positions.object2LongEntrySet()) {
            final ServerPlayer player = entry.getKey();
            final PlayerClientVDTracking vdTracking = (PlayerClientVDTracking) player;
            if (vdTracking.isClientViewDistanceChanged()) {
                vdTracking.getClientViewDistance(); // consume the changed flag (neo parity)
                this.movePlayer(entry.getLongValue(), player); // area-map diff fires send/drop listeners
            }
        }
    }

    public void setWatchDistance(int watchDistance) {
        this.watchDistance = Math.max(2, watchDistance);
        final ObjectIterator<Object2LongMap.Entry<ServerPlayer>> iterator = positions.object2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Object2LongMap.Entry<ServerPlayer> entry = iterator.next();

            this.playerAreaMap.update(
                    entry.getKey(),
                    MCUtil.getCoordinateX(entry.getLongValue()),
                    MCUtil.getCoordinateZ(entry.getLongValue()),
                    getViewDistance(entry.getKey()));
        }
    }

    public int getWatchDistance() {
        return watchDistance;
    }

    public Set<ServerPlayer> getPlayersWatchingChunk(long l) {
        return this.playerAreaMap.getObjectsInRange(l);
    }

    public Object[] getPlayersWatchingChunkArray(long coordinateKey) {
        return this.playerAreaMap.getObjectsInRangeArray(coordinateKey);
    }

    public void add(ServerPlayer player, long pos) {
        final int x = ChunkPos.getX(pos);
        final int z = ChunkPos.getZ(pos);

        this.playerAreaMap.add(player, x, z, getViewDistance(player));

        this.positions.put(player, MCUtil.getCoordinateKey(x, z));
    }

    public void remove(ServerPlayer player) {
        this.playerAreaMap.remove(player);

        this.positions.removeLong(player);
    }

    public void movePlayer(long currentPos, ServerPlayer player) {
        final int x = ChunkPos.getX(currentPos);
        final int z = ChunkPos.getZ(currentPos);

        this.playerAreaMap.update(player, x, z, getViewDistance(player));

        this.positions.put(player, MCUtil.getCoordinateKey(x, z));
    }

    /**
     * The +1 (edge chunks are required for rendering) is applied INSIDE the clamp — it must never
     * push the watched radius past {@code watchDistance}.
     *
     * <p>1.20.1-ONLY INVARIANT. On 1.20.2+ (neo, upstream VMP) the extra ring is delivered by
     * {@code ChunkTrackingView}/{@code ChunkFilter}, so a watched radius of
     * {@code watchDistance + 1} is harmless there. This port has no tracking view — the area map
     * is the sole send driver, and the two send paths both stop at {@code watchDistance}:
     * {@code ChunkMap.getPlayers()} (the MixinChunkMapGetPlayers overwrite) drops watchers
     * whose chebyshev distance exceeds {@code ChunkMap.viewDistance}, and it is what the
     * CHUNK_SENDING seat asks for its watcher list. A ring watched beyond that filter is therefore
     * unreachable: the area-map add fires {@code updateChunkTracking} while the chunk is still
     * unloaded (nothing to send, never retried), and the later CHUNK_SENDING offer finds zero
     * watchers. Since a one-chunk step adds exactly the ring at the watched radius, a player whose
     * client render distance is >= the server view distance would receive NO new chunks at all
     * while moving, until they relogged.
     */
    private int getViewDistance(ServerPlayer player) {
        return Mth.clamp(((PlayerClientVDTracking) player).superchunk$getClientViewDistanceRaw() + 1, 2, this.watchDistance);
    }

    public interface Listener {
        void accept(ServerPlayer player, int chunkX, int chunkZ);
    }
}
