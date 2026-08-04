package dev.superchunk.diag;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Startup GC advisory + best-effort auto-configuration of Generational ZGC.
 *
 * <p>SuperChunk streams and generates chunks fast enough that, at high render / no-tick view
 * distances (the extended-render-distance capability now allows up to 64 — a ~17k-chunk ring),
 * the JVM churns chunk data at multiple GB/s. Under a stop-the-world collector (G1, Parallel,
 * Serial, and the popular "Aikar" G1 flag set) that surfaces as periodic <em>stutter</em>: the
 * tick thread has ample headroom, but 25–250&nbsp;ms STW GC pauses freeze the whole server
 * mid-tick — NOT a memory leak. Bench: G1 pauses median&nbsp;26&nbsp;ms / max&nbsp;254&nbsp;ms vs
 * Generational&nbsp;ZGC median&nbsp;0.009&nbsp;ms / max&nbsp;0.025&nbsp;ms on the identical
 * flying-at-RD-64 workload — same MSPT, no stutter.
 *
 * <p>A mod cannot change the collector of the already-running JVM (it is fixed before the JVM
 * starts), so "automatic" means the next launch:
 * <ul>
 *   <li><b>Dedicated server</b>: if {@code user_jvm_args.txt} (read by the standard NeoForge run
 *       scripts) is present, writable, and selects no explicit collector, we append a marked ZGC
 *       block. Applies on the next restart; remove the block to revert.</li>
 *   <li><b>Client</b>: the launcher owns JVM args, so we write {@code config/superchunk-jvm-args.txt}
 *       with the exact flags to paste into the launcher's JVM/"Additional Arguments" field, and log
 *       a WARN pointing at it.</li>
 * </ul>
 * Disable the write with {@code -Dsuperchunk.gc.autoConfig=false}; silence entirely with
 * {@code -Dsuperchunk.diag.gcAdvisory=false}.
 */
public final class GcAdvisory {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ZGC_FLAGS = "-XX:+UseZGC -XX:+ZGenerational";
    private static volatile boolean done = false;

    private GcAdvisory() {
    }

    public static void maybeAdvise() {
        if (done || !Boolean.parseBoolean(System.getProperty("superchunk.diag.gcAdvisory", "true"))) {
            return;
        }
        done = true;
        try {
            boolean lowPause = false;
            StringBuilder names = new StringBuilder();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(name);
                String lower = name.toLowerCase();
                if (lower.contains("zgc") || lower.contains("z garbage") || lower.contains("shenandoah")) {
                    lowPause = true;
                }
            }
            long maxMb = Runtime.getRuntime().maxMemory() >> 20;
            if (lowPause) {
                LOGGER.info("[SuperChunk] GC: concurrent low-pause collector active ({}, heap {}MB) — good for high render distance.",
                        names, maxMb);
                return;
            }

            boolean autoWrite = Boolean.parseBoolean(System.getProperty("superchunk.gc.autoConfig", "true"));
            String serverResult = autoWrite ? tryConfigureServerArgs() : null;
            if (autoWrite) {
                writeClientHint();
            }

            if ("written".equals(serverResult)) {
                LOGGER.warn("[SuperChunk] GC advisory: stop-the-world collector active ({}). AUTO-CONFIGURED "
                                + "Generational ZGC in user_jvm_args.txt — RESTART the server to apply (remove that "
                                + "marked block to revert; disable with -Dsuperchunk.gc.autoConfig=false).",
                        names);
            } else if ("conflict".equals(serverResult)) {
                LOGGER.warn("[SuperChunk] GC advisory: stop-the-world collector active ({}) and user_jvm_args.txt "
                                + "already selects a collector — not overriding. For smooth chunk streaming replace it "
                                + "with '{}' (Java 21) and give >= 8G heap.",
                        names, ZGC_FLAGS);
            } else {
                LOGGER.warn("[SuperChunk] GC advisory: a stop-the-world collector is active ({}, heap {}MB). At high "
                                + "render/no-tick distances SuperChunk's GC pauses (tens–hundreds of ms) surface as "
                                + "periodic stutter, NOT a memory leak. For smooth chunk streaming switch to Generational "
                                + "ZGC — add '{}' to your launcher's JVM/Additional Arguments (client) and REMOVE any "
                                + "-XX:+UseG1GC / Aikar-style G1 flags; >= 8G heap for RD 48+. Exact flags written to "
                                + "config/superchunk-jvm-args.txt. Silence: -Dsuperchunk.diag.gcAdvisory=false.",
                        names, maxMb, ZGC_FLAGS);
            }
        } catch (Throwable ignored) {
            // an advisory must never affect startup
        }
    }

    /** @return "written" | "conflict" | null. Dedicated server only (the client launcher owns args). */
    private static String tryConfigureServerArgs() {
        try {
            if (!FMLEnvironment.dist.isDedicatedServer()) {
                return null;
            }
            Path f = Path.of("user_jvm_args.txt");
            if (!Files.isRegularFile(f) || !Files.isWritable(f)) {
                return null;
            }
            String content = Files.readString(f);
            String lower = content.toLowerCase();
            if (lower.contains("usezgc") || lower.contains("useshenandoah")) {
                return null; // already low-pause (possibly ours) — idempotent
            }
            if (lower.contains("useg1gc") || lower.contains("useparallelgc")
                    || lower.contains("useserialgc") || lower.contains("useconcmarksweepgc")) {
                return "conflict"; // an explicit collector we won't silently override
            }
            String nl = System.lineSeparator();
            String block = nl
                    + "# --- SuperChunk: Generational ZGC for smooth high-render-distance chunk streaming ---" + nl
                    + "# Remove this block (and restart) to revert; -Dsuperchunk.gc.autoConfig=false to stop re-adding." + nl
                    + "-XX:+UseZGC" + nl
                    + "-XX:+ZGenerational" + nl;
            Files.writeString(f, block, StandardOpenOption.APPEND);
            return "written";
        } catch (Throwable t) {
            return null;
        }
    }

    /** Turnkey reference for launcher-managed (client) setups where we cannot edit the JVM args. */
    private static void writeClientHint() {
        try {
            Path dir = Path.of("config");
            if (!Files.isDirectory(dir)) {
                return;
            }
            String body = "# SuperChunk: use Generational ZGC for smooth chunk streaming at high render distance.\n"
                    + "# A mod cannot change the running JVM's garbage collector, so paste the line below into your\n"
                    + "# launcher's JVM / \"Additional Arguments\" (CurseForge: Profile Options -> Java; Prism: Settings\n"
                    + "# -> Java). REMOVE any -XX:+UseG1GC / Aikar-style G1 flags first; give the JVM >= 8G for RD 48+.\n"
                    + "# (Dedicated servers are auto-configured in user_jvm_args.txt instead.)\n\n"
                    + ZGC_FLAGS + "\n";
            Files.writeString(dir.resolve("superchunk-jvm-args.txt"), body);
        } catch (Throwable ignored) {
        }
    }
}
