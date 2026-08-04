package dev.superchunk.diag;

import com.mojang.logging.LogUtils;
import dev.superchunk.com.ishland.c2me.notickvd.common.ChunkTicketManagerExtension;
import dev.superchunk.com.ishland.c2me.notickvd.common.NoTickSystem;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Diagnostic: periodic per-dimension chunk census, to distinguish a true unbounded chunk-retention
 * <em>leak</em> (item/loaded counts climb without bound while a player flies straight) from a
 * too-large-but-bounded steady state (counts plateau at the render-distance ring size while MSPT
 * and heap stay flat). Off the hot path entirely — a daemon thread wakes every
 * {@code superchunk.diag.chunkCensusMs} (default 2000) and posts a read-only snapshot task to the
 * server thread.
 *
 * <p>Columns per level: {@code item} = total chunks the FlowSched chunk system is tracking
 * ({@code itemCount()}, the same figure the F3 "C:" line shows); {@code loaded} =
 * {@code getLoadedChunksCount()}; {@code ntManaged} = notickvd {@code PlayerNoTickLoader}
 * managed-chunk count (the no-tick ring); {@code ntPending} = its outstanding spiral-iterator
 * loads. Plus global {@code mspt} and JVM heap.
 *
 * <p>Enabled by default while we chase the fly-straight leak; disable with
 * {@code -Dsuperchunk.diag.chunkCensus=false}.
 */
public final class ChunkCensus {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.diag.chunkCensus", "false"));
    private static final long PERIOD_MS = Long.getLong("superchunk.diag.chunkCensusMs", 2000L);
    private static volatile boolean started = false;

    private ChunkCensus() {
    }

    /** Idempotent; starts one daemon poller that self-idles whenever no server is running. */
    public static void start() {
        if (!ENABLED || started) {
            return;
        }
        started = true;
        Thread t = new Thread(ChunkCensus::loop, "superchunk-chunk-census");
        t.setDaemon(true);
        t.start();
        LOGGER.info("[chunk-census] enabled — every {} ms (disable: -Dsuperchunk.diag.chunkCensus=false)", PERIOD_MS);
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null && server.isRunning()) {
                    server.execute(() -> logCensus(server));
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // a diagnostic must never take down the server
            }
        }
    }

    private static void logCensus(MinecraftServer server) {
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long maxMB = rt.maxMemory() >> 20;
            double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;

            StringBuilder sb = new StringBuilder();
            for (ServerLevel level : server.getAllLevels()) {
                ServerChunkCache scc = level.getChunkSource();
                int loaded = scc.getLoadedChunksCount();
                int item = -1;
                int ntManaged = -1;
                long ntPending = -1;
                try {
                    item = ((IChunkSystemAccess) scc.chunkMap).c2me$getTheChunkSystem().itemCount();
                } catch (Throwable ignored) {
                }
                try {
                    NoTickSystem nts =
                            ((ChunkTicketManagerExtension) scc.chunkMap.getDistanceManager()).superchunk$getNoTickSystem();
                    if (nts != null) {
                        ntManaged = nts.getManagedChunksCount();
                        ntPending = nts.getPendingLoadsCount();
                    }
                } catch (Throwable ignored) {
                }
                sb.append(' ').append(level.dimension().location().getPath())
                        .append("[item=").append(item)
                        .append(" loaded=").append(loaded)
                        .append(" ntManaged=").append(ntManaged)
                        .append(" ntPending=").append(ntPending)
                        .append(']');
            }
            LOGGER.info("[chunk-census] mspt={} heap={}MB/{}MB{}",
                    String.format("%.1f", mspt), usedMB, maxMB, sb);
        } catch (Throwable ignored) {
        }
    }
}
