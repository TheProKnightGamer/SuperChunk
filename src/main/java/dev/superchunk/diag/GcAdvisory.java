package dev.superchunk.diag;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
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
 *   <li><b>Dedicated server</b>, when opted in: if {@code user_jvm_args.txt} (read by the standard
 *       Forge run scripts — {@code run.sh}/{@code run.bat} pass it to {@code java} as
 *       {@code @user_jvm_args.txt} from the install directory) is present and writable, and neither
 *       it nor the running JVM's own arguments select a collector, we append a marked ZGC block.
 *       Applies on the next restart; remove the block to revert.</li>
 *   <li><b>Client</b>: the launcher owns JVM args, so we write {@code config/superchunk-jvm-args.txt}
 *       with the exact flags to paste into the launcher's JVM/"Additional Arguments" field, and log
 *       a WARN pointing at it.</li>
 * </ul>
 * The {@code user_jvm_args.txt} write is opt-in with {@code -Dsuperchunk.gc.autoConfig=true} (off by
 * default since 0.4.0); silence entirely with {@code -Dsuperchunk.diag.gcAdvisory=false}.
 *
 * <p><b>1.20.1 PORT DIVERGENCE — the JVM baseline moved.</b> Generational ZGC requires a
 * Java&nbsp;21+ JVM, but Minecraft 1.20.1 / Forge 47.x baseline on Java&nbsp;17, where
 * {@code -XX:+ZGenerational} does not exist: a Java&nbsp;17 JVM handed that flag REFUSES TO START
 * ("Unrecognized VM option 'ZGenerational'"). Since {@link #tryConfigureServerArgs()} writes flags
 * that the NEXT launch consumes, writing it on a Java&nbsp;17 install would brick the server. So
 * the auto-write is gated on the running JVM's feature version (the run scripts re-launch the same
 * {@code java}):
 * <ul>
 *   <li><b>21–23</b>: {@code -XX:+UseZGC -XX:+ZGenerational} — exactly the benched configuration;
 *       auto-write behaves as on neo (1.20.1 runs fine on Java 21, and does here).</li>
 *   <li><b>24+</b>: {@code -XX:+UseZGC} alone — ZGC is generational by default there and
 *       {@code ZGenerational} was removed (JEP 490).</li>
 *   <li><b>17–20</b>: nothing is written. Single-generation ZGC does exist on 17, but the bench
 *       that justifies this advisory measured GENERATIONAL ZGC; single-gen ZGC under SuperChunk's
 *       multi-GB/s chunk allocation rate is unvalidated, so we do not silently ship it as the fix.
 *       The advisory instead says to move to a Java&nbsp;21+ JVM, and the recommended flag string
 *       stays the generational one (it is inert text there — for the JVM the user installs).</li>
 * </ul>
 */
public final class GcAdvisory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Feature version of the JVM we are running in — and, in practice, of the next launch. */
    private static final int JAVA_FEATURE = Runtime.version().feature();

    /** Can THIS JVM actually run the benched collector? Gates the user_jvm_args.txt write. */
    private static final boolean GENERATIONAL_ZGC_AVAILABLE = JAVA_FEATURE >= 21;

    /** The flags we RECOMMEND (a Java 21+ target); {@code ZGenerational} was removed in 24. */
    private static final String ZGC_FLAGS =
            JAVA_FEATURE >= 24 ? "-XX:+UseZGC" : "-XX:+UseZGC -XX:+ZGenerational";

    /** Appended to the generic advisory when the running JVM is too old for Generational ZGC. */
    private static final String JVM_CAVEAT = GENERATIONAL_ZGC_AVAILABLE ? ""
            : " NOTE: this JVM is Java " + JAVA_FEATURE + " — Generational ZGC needs Java 21+"
            + " (Minecraft 1.20.1 runs fine on 21); on Java 17 the -XX:+ZGenerational flag will"
            + " NOT start the JVM, so point your launcher/run script at a Java 21+ runtime FIRST.";

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

            // Editing the server's user_jvm_args.txt is opt-in: ZGC needs more memory than a
            // compressed-oops G1 heap, so switching a memory-capped or small host silently can turn
            // a working server into out-of-memory kills. The flags file in config/ is harmless.
            boolean autoWrite = Boolean.parseBoolean(System.getProperty("superchunk.gc.autoConfig", "false"));
            String serverResult = autoWrite ? tryConfigureServerArgs() : null;
            writeClientHint();

            if ("written".equals(serverResult)) {
                LOGGER.warn("[SuperChunk] GC advisory: stop-the-world collector active ({}). AUTO-CONFIGURED "
                                + "Generational ZGC in user_jvm_args.txt — RESTART the server to apply (remove that "
                                + "marked block to revert; stop with -Dsuperchunk.gc.autoConfig=false).",
                        names);
            } else if ("conflict".equals(serverResult)) {
                // Advice, not a fault: shown with logging.verbose=true.
                LOGGER.info("[SuperChunk] GC advisory: stop-the-world collector active ({}) and user_jvm_args.txt "
                                + "already selects a collector — not overriding. For smooth chunk streaming replace it "
                                + "with '{}' (Java 21) and give >= 8G heap.{}",
                        names, ZGC_FLAGS, JVM_CAVEAT);
            } else if ("needs-jvm".equals(serverResult)) {
                // 1.20.1-only branch: we WOULD have written user_jvm_args.txt, but this JVM is
                // older than 21 and would refuse to boot with -XX:+ZGenerational.
                LOGGER.warn("[SuperChunk] GC advisory: stop-the-world collector active ({}, heap {}MB) and this server "
                                + "runs Java {}. NOT auto-configuring: Generational ZGC — the collector our benchmarks "
                                + "show removes the high-render-distance stutter (G1 max 254ms pauses vs 0.025ms) — "
                                + "needs Java 21+, and Java {} refuses to start with -XX:+ZGenerational. Minecraft "
                                + "1.20.1 runs fine on Java 21: install a 21+ JRE, point run.sh/run.bat at it, then "
                                + "add '{}' to user_jvm_args.txt (>= 8G heap for RD 48+). Silence: "
                                + "-Dsuperchunk.diag.gcAdvisory=false.",
                        names, maxMb, JAVA_FEATURE, JAVA_FEATURE, ZGC_FLAGS);
            } else {
                // Advice, not a fault: shown with logging.verbose=true.
                LOGGER.info("[SuperChunk] GC advisory: a stop-the-world collector is active ({}, heap {}MB). At high "
                                + "render/no-tick distances SuperChunk's GC pauses (tens–hundreds of ms) surface as "
                                + "periodic stutter, NOT a memory leak. For smooth chunk streaming switch to Generational "
                                + "ZGC — add '{}' to your launcher's JVM/Additional Arguments (client) and REMOVE any "
                                + "-XX:+UseG1GC / Aikar-style G1 flags; >= 8G heap for RD 48+. Exact flags written to "
                                + "config/superchunk-jvm-args.txt. Silence: -Dsuperchunk.diag.gcAdvisory=false.{}",
                        names, maxMb, ZGC_FLAGS, JVM_CAVEAT);
            }
        } catch (Throwable ignored) {
            // an advisory must never affect startup
        }
    }

    /**
     * @return "written" | "conflict" | "needs-jvm" | null. Dedicated server only (the client
     *         launcher owns args). "needs-jvm" is the 1.20.1-port case: everything lined up for
     *         the write except the JVM, which is older than 21 (see the class javadoc).
     */
    private static String tryConfigureServerArgs() {
        try {
            if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
                return null;
            }
            // Deliberately CWD-relative, not FMLPaths.GAMEDIR: run.sh/run.bat resolve
            // "@user_jvm_args.txt" against the process working directory, so that is the file the
            // next launch will actually read even when --gameDir points elsewhere.
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
            // The collector may also be chosen outside this file (the start script's java line,
            // JDK_JAVA_OPTIONS, ...). Appending ZGC then selects two collectors and the JVM refuses
            // to start, so only write when this JVM was launched with no explicit collector at all.
            if (explicitCollectorElsewhere()) {
                return "conflict";
            }
            if (!GENERATIONAL_ZGC_AVAILABLE) {
                return "needs-jvm"; // writing -XX:+ZGenerational here would brick the next boot
            }
            String nl = System.lineSeparator();
            StringBuilder block = new StringBuilder(nl)
                    .append("# --- SuperChunk: Generational ZGC for smooth high-render-distance chunk streaming ---").append(nl)
                    .append("# Remove this block (and restart) to revert; -Dsuperchunk.gc.autoConfig=false to stop re-adding.").append(nl);
            // One flag per line, as in the file Forge's installer generates (the @argfile format
            // this is read through takes one argument per line and honours '#' comments).
            for (String flag : ZGC_FLAGS.split(" ")) {
                block.append(flag).append(nl);
            }
            Files.writeString(f, block.toString(), StandardOpenOption.APPEND);
            return "written";
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether this JVM's own arguments or Java option variables name a garbage collector. */
    private static boolean explicitCollectorElsewhere() {
        java.util.List<String> args = new java.util.ArrayList<>(
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        for (String variable : new String[] {"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"}) {
            String value = System.getenv(variable);
            if (value != null) {
                args.addAll(java.util.Arrays.asList(value.trim().split("\\s+")));
            }
        }
        for (String arg : args) {
            String a = arg.toLowerCase(java.util.Locale.ROOT);
            if (a.startsWith("-xx:+use") && a.endsWith("gc")) {
                return true;
            }
        }
        return false;
    }

    /** Turnkey reference for launcher-managed (client) setups where we cannot edit the JVM args. */
    private static void writeClientHint() {
        try {
            // FMLPaths.CONFIGDIR, not a cwd-relative "./config": the rest of the mod's config I/O
            // is anchored there (SuperChunkConfig, ConfigSystem, LithiumMixinPlugin), and cwd is
            // not always the game directory.
            Path dir = FMLPaths.CONFIGDIR.get();
            if (!Files.isDirectory(dir)) {
                return;
            }
            String body = "# SuperChunk: use Generational ZGC for smooth chunk streaming at high render distance.\n"
                    + "# A mod cannot change the running JVM's garbage collector, so paste the line below into your\n"
                    + "# launcher's JVM / \"Additional Arguments\" (CurseForge: Profile Options -> Java; Prism: Settings\n"
                    + "# -> Java). REMOVE any -XX:+UseG1GC / Aikar-style G1 flags first; give the JVM >= 8G for RD 48+.\n"
                    + "# (Dedicated servers: paste it into user_jvm_args.txt, or start once with -Dsuperchunk.gc.autoConfig=true\n"
                    + "# to have SuperChunk add it there.)\n"
                    + (GENERATIONAL_ZGC_AVAILABLE ? ""
                            : "# NOTE: this instance runs Java " + JAVA_FEATURE + ". Generational ZGC needs Java 21+ —\n"
                            + "# select a Java 21+ runtime in the launcher FIRST; Java 17 will not start with\n"
                            + "# -XX:+ZGenerational. Minecraft 1.20.1 itself runs fine on Java 21.\n")
                    + "\n"
                    + ZGC_FLAGS + "\n";
            Files.writeString(dir.resolve("superchunk-jvm-args.txt"), body);
        } catch (Throwable ignored) {
        }
    }
}
