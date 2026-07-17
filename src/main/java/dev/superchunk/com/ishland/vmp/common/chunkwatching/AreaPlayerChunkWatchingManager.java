package dev.superchunk.com.ishland.vmp.common.chunkwatching;

import dev.superchunk.com.ishland.vmp.common.maps.AreaMap;
import dev.superchunk.io.papermc.paper.util.MCUtil;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Ported from VMP (dev.superchunk.com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager).
 *
 * <p>Yarn -> Mojmap translation:
 * <ul>
 *   <li>ChunkFilter -> ChunkTrackingView (cylindrical(pos, vd) -> of(pos, vd))</li>
 *   <li>ServerPlayerEntity -> ServerPlayer (setChunkFilter -> setChunkTrackingView,
 *       getViewDistance() -> requestedViewDistance())</li>
 *   <li>ServerChunkLoadingManager -> ChunkMap</li>
 *   <li>MathHelper -> Mth</li>
 * </ul>
 *
 * <p>KNOWN GAP (doc corrected 2026-07-02 — the previous claim here was factually wrong): the
 * general-player area map (used by VMP's spawn-proximity overwrites) is dropped here, but the
 * overwrites' Mojmap targets DO exist on ChunkMap ({@code anyPlayerCloseEnoughForSpawning} /
 * {@code getPlayersCloseForSpawning} — both O(all players in dimension) per ticking chunk per
 * tick). Restoring them requires re-adding the general area map (radius = max mob-category
 * despawn range / 16) alongside playerAreaMap. Only the watching/sending feature is ported.
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
                vdTracking.getClientViewDistance();
                final long pos = entry.getLongValue();
                player.setChunkTrackingView(ChunkTrackingView.of(new ChunkPos(pos), this.getViewDistance(player)));
                this.movePlayer(pos, player);
            }
        }

    }

    public void setWatchDistance(int watchDistance) {
        this.watchDistance = Math.max(2, watchDistance);
        final ObjectIterator<Object2LongMap.Entry<ServerPlayer>> iterator = positions.object2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Object2LongMap.Entry<ServerPlayer> entry = iterator.next();

            entry.getKey().setChunkTrackingView(ChunkTrackingView.of(new ChunkPos(entry.getLongValue()), this.getViewDistance(entry.getKey())));

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

    private int getViewDistance(ServerPlayer player) {
        return Mth.clamp(player.requestedViewDistance(), 2, this.watchDistance) + 1; // edge chunks are required for rendering
    }

    public interface Listener {
        void accept(ServerPlayer player, int chunkX, int chunkZ);
    }
}
