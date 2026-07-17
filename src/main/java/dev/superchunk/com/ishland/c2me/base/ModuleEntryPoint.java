package dev.superchunk.com.ishland.c2me.base;

import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;
import io.netty.util.internal.PlatformDependent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

public class ModuleEntryPoint {

    private static final boolean enabled = true;

    private static final String DEFAULT_EXPRESSION =
            """
                                    
                    max(
                        1,
                        min(
                            if( is_windows,
                                (cpus / 1.6),
                                (cpus / 1.3)
                            )  - if(is_client, 1, 0),
                            ( ( mem_gb - (if(is_client, 1.2, 0.6)) ) / 0.6 )
                        )
                    )
                \040""";

    public static final String defaultGlobalExecutorParallelismExpression = new ConfigSystem.ConfigAccessor()
            .key("defaultGlobalExecutorParallelismExpression")
            .comment("""

                    The expression for the default value of global executor parallelism.\s
                    This is used when the parallelism isn't overridden.
                    Available variables: is_windows, is_j9vm, is_client, cpus, mem_gb
                    """.indent(1))
            .getString(DEFAULT_EXPRESSION, DEFAULT_EXPRESSION);

    public static final long threadPoolPriority = new ConfigSystem.ConfigAccessor()
            .key("threadPoolPriority")
            .comment("""
                    Sets the thread priority for worker threads
                    
                    References:
                    - https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html#setPriority(int)
                    """)
            .getLong(Thread.NORM_PRIORITY - 1, Thread.NORM_PRIORITY - 1, ConfigSystem.LongChecks.POSITIVE_VALUES_ONLY);

    public static final int defaultParallelism;

    private static int tryEvaluateExpression(String expression) {
        return (int) Math.max(1,
                new ExpressionBuilder(expression)
                        .variables("is_windows", "is_j9vm", "is_client", "cpus", "mem_gb")
                        .function(new Function("max", 2) {
                            @Override
                            public double apply(double... args) {
                                return Math.max(args[0], args[1]);
                            }
                        })
                        .function(new Function("min", 2) {
                            @Override
                            public double apply(double... args) {
                                return Math.min(args[0], args[1]);
                            }
                        })
                        .function(new Function("if", 3) {
                            @Override
                            public double apply(double... args) {
                                return args[0] != 0 ? args[1] : args[2];
                            }
                        })
                        .build()
                        .setVariable("is_windows", PlatformDependent.isWindows() ? 1 : 0)
                        .setVariable("is_j9vm", PlatformDependent.isJ9Jvm() ? 1 : 0)
                        .setVariable("is_client", FMLEnvironment.dist == Dist.CLIENT ? 1 : 0)
                        .setVariable("cpus", Runtime.getRuntime().availableProcessors())
                        .setVariable("mem_gb", Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0 / 1024.0)
                        .evaluate()
        );
    }

    public static final long globalExecutorParallelism;

    static {
        final int defaultEval = tryEvaluateExpression(DEFAULT_EXPRESSION);
        int value;
        try {
            value = tryEvaluateExpression(defaultGlobalExecutorParallelismExpression);
        } catch (Throwable t) {
            ConfigSystem.LOGGER.error("Failed to evaluate defaultGlobalExecutorParallelismExpression, falling back to default value", t);
            value = defaultEval;
        }

        defaultParallelism = value;
        globalExecutorParallelism = new ConfigSystem.ConfigAccessor()
                .key("globalExecutorParallelism")
                .comment("Configures the parallelism of global executor")
                .getLong(value, value, ConfigSystem.LongChecks.THREAD_COUNT);

        ConfigSystem.LOGGER.info("Global Executor Parallelism: {} configured, {} evaluated, {} default evaluated", globalExecutorParallelism, defaultParallelism, defaultEval);

        // Startup hint (diagnostic only, no worldgen impact): warn when worker parallelism is heap-capped
        // below the CPU ceiling, which is the proven +25-29% chunks/sec pre-gen lever. The CPU and heap terms
        // below are the built-in DEFAULT_EXPRESSION sub-terms, so this hint only reasons correctly about the
        // built-in default. Gate on defaultParallelism == defaultEval so a customized
        // defaultGlobalExecutorParallelismExpression (whose effective value would not match these terms) does
        // not false-fire the hint. Also suppressed when the operator has already overridden parallelism
        // (globalExecutorParallelism != defaultParallelism).
        if (globalExecutorParallelism == defaultParallelism && defaultParallelism == defaultEval) {
            final int cpuTerm = tryEvaluateExpression("max(1, if(is_windows,(cpus/1.6),(cpus/1.3)) - if(is_client,1,0))");
            final int heapTerm = tryEvaluateExpression("max(1, ( mem_gb - (if(is_client,1.2,0.6)) ) / 0.6 )");
            if (heapTerm < cpuTerm - 1) {
                ConfigSystem.LOGGER.warn(
                        "C2ME worldgen is heap-limited to {} worker threads; this CPU supports up to {}. " +
                        "To unlock ~+25% chunks/sec during pre-generation, raise -Xmx (each +0.6 GiB heap = +1 worker up to {}) " +
                        "or set -Dc2me.base.config.override.globalExecutorParallelism={} (survives config-version wipes).",
                        heapTerm, cpuTerm, cpuTerm, cpuTerm);
            }
        }
    }
}
