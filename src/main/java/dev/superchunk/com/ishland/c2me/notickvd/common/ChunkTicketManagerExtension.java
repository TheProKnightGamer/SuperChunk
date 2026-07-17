package dev.superchunk.com.ishland.c2me.notickvd.common;

public interface ChunkTicketManagerExtension {

    long c2me$getPendingLoadsCount();

    void c2me$closeNoTickVD();

    /**
     * SuperChunk player.predictiveGen: the per-world {@link NoTickSystem}, so the predictive
     * tracker (ticked from the ChunkMap mixin) can feed corridor updates into the same
     * scheduler-thread machinery that runs {@link PlayerNoTickLoader}.
     */
    NoTickSystem superchunk$getNoTickSystem();

}
