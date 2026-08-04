package dev.superchunk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.superchunk.SuperChunk;
import dev.superchunk.config.SuperChunkConfig;
import dev.superchunk.mixin.client.LevelRendererViewAreaAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/**
 * SuperChunk (client): draws a flat gray 16×16 quad at sea level for every chunk that has been
 * <b>received from the server but whose render mesh is not yet built</b>, so the world reads as
 * "filled in" while the section renderer catches up (which matters with the extended render distance).
 *
 * <p>Per {@link RenderLevelStageEvent} it walks the client's render sections
 * ({@code LevelRenderer.viewArea.sections}, exposed via {@link LevelRendererViewAreaAccessor}). A
 * column qualifies when: the section that spans sea level is still {@code UNCOMPILED} (mesh not built)
 * AND the client actually has the chunk data ({@code Level.hasChunk}). Off-screen columns are frustum
 * culled. As a section finishes meshing it stops being {@code UNCOMPILED} and its placeholder
 * disappears on the next frame.
 *
 * <p>Client-dist only (registered from {@code SuperChunk} behind {@code FMLEnvironment.dist.isClient()},
 * mirroring {@link ExtendedRenderDistance}). Disable with {@code client.grayPlaceholders=false}.
 * Never throws from the render callback.
 *
 * <p><b>Note:</b> uses {@code RenderType.debugQuads()} (untextured translucent {@code POSITION_COLOR}
 * quads). Depth/occlusion behaviour against nearby real terrain may want a bespoke depth-tested
 * RenderType — verify in-client.
 */
public final class GrayChunkPlaceholders {

    /** ARGB translucent gray. */
    private static final int COLOR = 0xC0888888;

    private static final boolean ENABLED = readEnabled();

    private GrayChunkPlaceholders() {
    }

    private static boolean readEnabled() {
        try {
            return Boolean.parseBoolean(SuperChunkConfig.get().getProperty("client.grayPlaceholders", "true"));
        } catch (Throwable t) {
            return true;
        }
    }

    /** Called from the mod entry point, client dist only. */
    public static void init() {
        if (!ENABLED) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(GrayChunkPlaceholders::onRenderLevelStage);
        SuperChunk.LOGGER.info("[SuperChunk] gray placeholder squares enabled (loaded-but-unmeshed chunks). "
                + "Disable with client.grayPlaceholders=false.");
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Level level = mc.level;
            LevelRenderer levelRenderer = mc.levelRenderer;
            if (level == null || levelRenderer == null) {
                return;
            }
            ViewArea viewArea = ((LevelRendererViewAreaAccessor) levelRenderer).superchunk$viewArea();
            if (viewArea == null || viewArea.sections == null) {
                return;
            }

            final int seaLevel = level.getSeaLevel();
            final Vec3 cam = event.getCamera().getPosition();
            final Frustum frustum = event.getFrustum();
            final PoseStack pose = event.getPoseStack();
            final Matrix4f mat = pose.last().pose();

            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());

            int drawn = 0;
            for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
                if (section == null) {
                    continue;
                }
                BlockPos origin = section.getOrigin();
                int oy = origin.getY();
                if (seaLevel < oy || seaLevel >= oy + 16) {
                    continue; // only the section that spans sea level -> one quad per chunk column
                }
                if (section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                    continue; // mesh already built -> real terrain will draw; no placeholder
                }
                int cx = origin.getX() >> 4;
                int cz = origin.getZ() >> 4;
                if (!level.hasChunk(cx, cz)) {
                    continue; // chunk data not received from the server yet -> nothing to stand in for
                }
                double wx = cx << 4;
                double wz = cz << 4;
                if (frustum != null && !frustum.isVisible(
                        new AABB(wx, seaLevel - 0.5, wz, wx + 16.0, seaLevel + 0.5, wz + 16.0))) {
                    continue;
                }
                float x0 = (float) (wx - cam.x);
                float y0 = (float) (seaLevel - cam.y);
                float z0 = (float) (wz - cam.z);
                float x1 = x0 + 16.0f;
                float z1 = z0 + 16.0f;
                vc.addVertex(mat, x0, y0, z0).setColor(COLOR);
                vc.addVertex(mat, x0, y0, z1).setColor(COLOR);
                vc.addVertex(mat, x1, y0, z1).setColor(COLOR);
                vc.addVertex(mat, x1, y0, z0).setColor(COLOR);
                drawn++;
            }
            if (drawn > 0) {
                buffers.endBatch(RenderType.debugQuads());
            }
        } catch (Throwable t) {
            // A rendering nicety must never crash the client render loop.
            SuperChunk.LOGGER.warn("[SuperChunk] gray placeholder render failed (disabling this frame).", t);
        }
    }
}
