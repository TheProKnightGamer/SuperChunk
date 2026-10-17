package dev.superchunk.com.ishland.c2me.base.common.config;

/**
 * NeoForge port shim for upstream Fabric mod-presence flags.
 * Fabric API lifecycle events (fabric-lifecycle-events-v1) are not present on NeoForge,
 * so this is hard-coded to false; the corresponding event invocations are stubbed to no-ops.
 *
 * <p>Networking, by contrast, is provided directly by NeoForge's payload-handler API
 * (see {@link dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance}), so the
 * networking flag is {@code true} and the corresponding registration is ported to
 * NeoForge's {@code RegisterPayloadHandlersEvent} / {@code IPayloadContext} APIs.
 */
public class ModStatuses {

    public static final boolean fabric_lifecycle_events_v1 = false;

    public static final boolean fabric_networking_api_v1 = true;

}
