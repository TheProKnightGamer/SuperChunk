package dev.superchunk.com.ishland.c2me.opts.natives_math;

/**
 * Stub entry point for the UNPORTED c2me-opts-natives-math module (native-FFI worldgen
 * math: Java 22 {@code java.lang.foreign} + prebuilt .so/.dll natives — see the
 * neoforge.mods.toml note). The registered mixin config has an empty mixin list, but
 * {@code ModuleMixinPlugin.onLoad} still {@code Class.forName}s
 * {@code <package>.ModuleEntryPoint}; without this class that threw
 * {@code ClassNotFoundException}, logging a WARN + full stack trace on EVERY launch
 * while leaving the empty module "enabled". {@code enabled=false} makes the plugin log
 * a clean "Disabling ..." INFO instead.
 *
 * <p>If the module is ever actually ported (official C2ME-NeoForge recipe: a separate
 * Java-22 compilation unit + vendored natives, runtime-gated so Java 21 silently
 * no-ops), replace this stub with the real entry point.
 */
public class ModuleEntryPoint {

    public static final boolean enabled = false;

}
