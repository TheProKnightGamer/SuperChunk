package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import com.google.common.collect.ImmutableList;
import dev.superchunk.com.ishland.vmp.common.playerwatching.TACSExtension;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * VMP's {@code getPlayers} overwrite, split out of {@link MixinThreadedAnvilChunkStorage} to run below the default
 * mixin priority. Mixin refuses any injector other than HEAD/RETURN/TAIL into a method overwritten by a mixin of the
 * same or higher priority, and fails the whole class. At VMP's 1050, CBC: Modern Warfare's (and SecurityCraft's)
 * camera mixin, a {@code @ModifyExpressionValue} on {@code getLastSectionPos()} here, crashed the server on the first
 * load of {@code ChunkMap}. At 900 it applies to this body, whose {@code getLastSectionPos()} call it can find, as it
 * does to vanilla's, so the distance check uses the player's camera while they look through it.
 */
@Mixin(value = ChunkMap.class, priority = 900)
public abstract class MixinChunkMapGetPlayers {

    @Shadow int viewDistance;

    /**
     * @author ishland
     * @reason use array for iteration &amp; use squares as cylinders are expensive
     */
    @Overwrite
    public List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge) {
        // NOTE: no chunkSender.isPending clause — the API is 1.20.2+; 1.20.1 has no pending set.
        final Object[] set = ((TACSExtension) this).getAreaPlayerChunkWatchingManager().getPlayersWatchingChunkArray(chunkPos.toLong());
        final ImmutableList.Builder<ServerPlayer> builder = ImmutableList.builder();
        for (Object __player : set) {
            if (__player instanceof ServerPlayer serverPlayer) {
                final SectionPos watched = serverPlayer.getLastSectionPos();
                final int cheb = Math.max(Math.abs(watched.x() - chunkPos.x), Math.abs(watched.z() - chunkPos.z));
                if (cheb > this.viewDistance) continue;
                if (!onlyOnWatchDistanceEdge || cheb == this.viewDistance) builder.add(serverPlayer);
            }
        }
        return builder.build();
    }
}
