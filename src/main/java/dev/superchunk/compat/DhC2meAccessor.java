package dev.superchunk.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers SuperChunk as Distant Horizons' <b>C2ME accessor</b>, so DH stops telling the player
 * that C2ME is missing.
 *
 * <h2>The problem</h2>
 * In its default {@code INTERNAL_SERVER} generator mode DH hands every LOD chunk request to the
 * server's chunk pipeline, and its {@code InternalServerGenerator.runValidation()} checks whether a
 * mod is present that makes that pipeline parallel. That check is a single lookup of DH's own
 * {@code IC2meAccessor} binding, which DH's loader creates only when a mod with the literal id
 * {@code c2me} is installed. SuperChunk <i>is</i> a C2ME merge (plus ScalableLux, Lithium, Noisium,
 * VMP and the GPU offload) under one mod id, so that lookup misses and DH prints — to the log AND
 * to the player's chat:
 *
 * <blockquote>C2ME missing, low CPU usage and slow world gen speeds expected. DH is set to use MC's
 * internal server for world gen; this mode is less efficient unless a mod like C2ME is
 * present.</blockquote>
 *
 * <p>Which is exactly backwards: with SuperChunk that path is served by the C2ME chunk system
 * rewrite ({@code NewChunkHolderVanillaInterface} routes DH's chunk-generation request straight
 * into the FlowSched status machine) plus the whole SuperChunk optimization stack.
 *
 * <h2>The fix</h2>
 * Bind a stand-in {@code IC2meAccessor} into DH's {@code ModAccessorInjector} before DH's generator
 * classes initialize. Everything is reflective — SuperChunk does not compile against DH — and the
 * accessor is a {@link Proxy} over DH's own interface, so it satisfies DH's binding validation
 * ({@code checkIfClassImplements}) without SuperChunk owning a DH type. {@code IModAccessor}'s one
 * real method is {@code getModName()}; it feeds DH's "Registered mod compatibility accessor for:
 * [x]" log line only, so it truthfully reports SuperChunk rather than impersonating C2ME.
 *
 * <p>Nothing about generation changes — DH's C2ME binding is a reporting flag, not a code path.
 * This purely removes a false warning. Disable with {@code -Dsuperchunk.dh.c2meAccessor=false}.
 *
 * <p>Every failure mode is a no-op: DH absent, class names changed, already bound by a real C2ME,
 * or the injector refusing the bind — all end with the binding untouched and the warning simply
 * still shown. Never throws.
 *
 * <h2>Forge 1.20.1 port note — INERT on the DH 2.3.x line (which is what 1.20.1 gets)</h2>
 * Verified against {@code DistantHorizons-2.3.4-b-1.20.1-fabric-forge.jar} (the newest 1.20.1 DH
 * build available locally): that jar has <b>no {@code IC2meAccessor}</b> at all. Its
 * {@code modAccessor} package carries only Iris/Sodium/Optifine/Starlight/BCLib accessors, and its
 * C2ME question is asked a completely different way — {@code BatchGenerationEnvironment}'s
 * constructor calls {@code IModChecker.isModLoaded("c2me")} directly. So the reflective bind below
 * finds nothing, takes its {@code ClassNotFoundException} branch and logs one debug line: the whole
 * class is a no-op on that DH line. It is shipped UNCHANGED anyway because it is exactly right for
 * any DH 3.x-line build (where {@code IC2meAccessor} + {@code ModAccessorInjector} are the
 * mechanism), and because it costs nothing when DH is absent or older.
 *
 * <p><b>The 1.20.1-only alternative was deliberately NOT taken.</b> Making DH 2.3.x see "c2me"
 * would mean mixing into DH's {@code ModChecker.isModLoaded} and lying about a mod id we do not
 * own. And on 2.3.x that answer is not cosmetic: the hit sets
 * {@code BatchGenerationEnvironment.pullExistingChunkUsingMcAsyncMethod = true}, i.e. it CHANGES
 * DH's chunk-pull code path. The NeoForge change this ports is explicitly "reporting only; no code
 * path changes" (commit 8f09747), so shipping a behavioural flag flip under the same name and the
 * same kill switch would break that parity argument. The 2.3.x-only upside is also small: without
 * the flag DH 2.3.4 does not print the "C2ME missing, slow world gen expected" warning at all — it
 * simply stays quiet — so there is no false warning to remove there in the first place.
 */
public final class DhC2meAccessor {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    private static final String IC2ME_ACCESSOR =
            "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IC2meAccessor";
    private static final String IMOD_ACCESSOR =
            "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor";
    private static final String IBINDABLE =
            "com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable";
    private static final String MOD_ACCESSOR_INJECTOR =
            "com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector";

    /** What DH logs as the accessor's owner. Honest on purpose — nothing keys off this string. */
    private static final String MOD_NAME = "superchunk (bundled C2ME)";

    /** One-shot: the bind is attempted exactly once per run, whatever the outcome. */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private DhC2meAccessor() {
    }

    /**
     * Binds SuperChunk as DH's C2ME accessor if DH is present, the compat is enabled, and nothing
     * is bound yet. Idempotent (only the first call does anything) and safe to call before DH has
     * loaded a level — it must run before DH's {@code InternalServerGenerator} class initializes,
     * which is why {@code SuperChunk} calls it at server start.
     */
    public static void bindIfPresent() {
        if (!DistantHorizonsCompat.active() || !DistantHorizonsCompat.BIND_C2ME_ACCESSOR) {
            return;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        try {
            ClassLoader loader = DhC2meAccessor.class.getClassLoader();
            Class<?> ic2me = Class.forName(IC2ME_ACCESSOR, false, loader);
            Class<?> imod = Class.forName(IMOD_ACCESSOR, false, loader);
            Class<?> ibindable = Class.forName(IBINDABLE, false, loader);
            Class<?> injectorClass = Class.forName(MOD_ACCESSOR_INJECTOR, true, loader);
            Object injector = injectorClass.getField("INSTANCE").get(null);
            if (injector == null) {
                return;
            }

            // Already bound (a real C2ME is installed alongside us, or DH bound it itself):
            // leave it alone — a duplicate bind would throw inside DH.
            Method get = injectorClass.getMethod("get", Class.class);
            Object existing;
            try {
                existing = get.invoke(injector, ic2me);
            } catch (Throwable t) {
                existing = null;
            }
            if (existing != null) {
                return;
            }

            // All three interfaces are declared on the proxy so DH's binding validation passes
            // whether it inspects the requested interface, the injector's bindable interface, or
            // the IBindable root. A Proxy dispatches EVERY call — including IBindable's default
            // methods — to the handler, so the handler answers them explicitly.
            Object accessor = Proxy.newProxyInstance(loader, new Class<?>[]{ic2me, imod, ibindable},
                    new AccessorHandler());

            Method bind = injectorClass.getMethod("bind", Class.class, imod);
            bind.invoke(injector, ic2me, accessor);
            LOGGER.info("[SuperChunk-Compat] [dh] registered SuperChunk as Distant Horizons' C2ME accessor — DH's "
                    + "\"C2ME missing, slow world gen expected\" warning no longer applies: SuperChunk's bundled C2ME "
                    + "chunk system is what serves DH's INTERNAL_SERVER chunk requests. "
                    + "(-Dsuperchunk.dh.c2meAccessor=false to skip this.)");
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            // A DH version whose accessor/injector types moved — which is the NORMAL outcome on
            // 1.20.1: the DH 2.3.x line has no IC2meAccessor at all (see the class javadoc).
            // Nothing to do — DH keeps its (cosmetic) warning and everything else works.
            LOGGER.debug("[SuperChunk-Compat] [dh] C2ME accessor bind skipped — DH internals not as expected.", e);
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] C2ME accessor bind failed — DH keeps its \"C2ME missing\" warning.", t);
        }
    }

    /** Answers DH's {@code IModAccessor} / {@code IBindable} calls without any DH type on our side. */
    private static final class AccessorHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            int argc = args == null ? 0 : args.length;
            if (argc == 0) {
                switch (name) {
                    case "getModName":
                        return MOD_NAME;
                    // IBindable: SuperChunk's C2ME is fully initialized long before DH asks.
                    case "getDelayedSetupComplete":
                        return Boolean.TRUE;
                    case "finishDelayedSetup":
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "SuperChunkC2meAccessor[" + MOD_NAME + "]";
                    default:
                        break;
                }
            } else if (argc == 1 && "equals".equals(name)) {
                return proxy == args[0];
            }
            // Any method a future DH version adds: answer with the type's zero value rather than
            // throwing into DH.
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) {
                return Boolean.FALSE;
            }
            if (ret == int.class) {
                return 0;
            }
            if (ret == long.class) {
                return 0L;
            }
            if (ret == double.class) {
                return 0.0d;
            }
            if (ret == float.class) {
                return 0.0f;
            }
            if (ret == short.class) {
                return (short) 0;
            }
            if (ret == byte.class) {
                return (byte) 0;
            }
            if (ret == char.class) {
                return (char) 0;
            }
            return null;
        }
    }
}
