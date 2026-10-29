package exp.CCnewmods.khp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.FaceGeometry;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.PartOBB;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;

/**
 * Limits capture to world rendering (so GUI paper-dolls aren't captured), draws debug wireframes, and cleans up
 * snapshots.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MahClientEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            HitboxCaptureManager.setLevelRenderPass(true);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (MahConfig.debugRender) {
                drawDebugWireframes(event);
            }
            HitboxCaptureManager.setLevelRenderPass(false);
        }
    }

    private static void drawDebugWireframes(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Vec3 cam = event.getCamera().getPosition();
        long now = level.getGameTime();
        boolean firstPerson = mc.options.getCameraType() == CameraType.FIRST_PERSON;
        UUID selfId = mc.player != null ? mc.player.getUUID() : null;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        boolean drewAny = false;

        for (Map.Entry<UUID, EntityHitboxSnapshot> entry : HitboxStore.view().entrySet()) {
            EntityHitboxSnapshot snap = entry.getValue();
            if (snap.isStale(now, MahConfig.staleSnapshotTicks)) continue;
            if (firstPerson && entry.getKey().equals(selfId)) continue;

            Entity entity = level.getEntity(snap.entityId());
            Vec3 delta = entity != null
                    ? entity.position().subtract(snap.capturedEntityPos())
                    : Vec3.ZERO;
            Vec3 base = snap.anchorPos().add(delta).subtract(cam);

            for (PartOBB part : snap.parts()) {
                poseStack.pushPose();
                poseStack.translate(base.x, base.y, base.z);
                poseStack.mulPoseMatrix(part.transform());
                LevelRenderer.renderLineBox(poseStack, lines, part.localBox(),
                        0.2f, 1.0f, 0.3f, 1.0f); // green = live
                // Mark points the per-pixel raycast test would reject.
                if (part.flatFaceUV() != null) {
                    drawOptionBDenialMarks(poseStack.last(), lines, part.localBox(), part.flatFaceUV(), snap.texture());
                }
                poseStack.popPose();
                drewAny = true;
            }
        }

        if (drewAny) {
            bufferSource.endBatch(RenderType.lines());
        }
    }

    /** Red X wherever a hit would pass through; a mark on a visibly opaque area means an opacity bug. */
    private static void drawOptionBDenialMarks(PoseStack.Pose pose, VertexConsumer lines, AABB box,
                                                PartOBB.FlatFaceUV flatUV, ResourceLocation texture) {
        if (texture == null) return;
        Direction dir = flatUV.face();
        float[][] corners = FaceGeometry.faceCorners(box, dir);
        int[] axes = FaceGeometry.inPlaneAxes(dir);
        final int GRID = 6;
        float markSize = 0.02f;

        for (int gs = 0; gs < GRID; gs++) {
            float s = gs / (float) (GRID - 1);
            for (int gt = 0; gt < GRID; gt++) {
                float t = gt / (float) (GRID - 1);
                float[] p = {
                        lerp(corners[0][0], corners[1][0], s), lerp(corners[0][1], corners[1][1], s), lerp(corners[0][2], corners[1][2], s)
                };
                float[] p1 = {
                        lerp(corners[3][0], corners[2][0], s), lerp(corners[3][1], corners[2][1], s), lerp(corners[3][2], corners[2][2], s)
                };
                p[0] = lerp(p[0], p1[0], t);
                p[1] = lerp(p[1], p1[1], t);
                p[2] = lerp(p[2], p1[2], t);

                float[] uv = FaceGeometry.bilinearUV(box, dir, p[0], p[1], p[2], flatUV);
                float opacity = TextureAlphaSampler.sampleOpacity(texture, uv[0], uv[1]);
                if (opacity > MahConfig.opacityThreshold) continue;

                float[] a1 = p.clone(), a2 = p.clone(), b1 = p.clone(), b2 = p.clone();
                a1[axes[0]] -= markSize;
                a2[axes[0]] += markSize;
                b1[axes[1]] -= markSize;
                b2[axes[1]] += markSize;
                drawLine(pose, lines, a1, a2);
                drawLine(pose, lines, b1, b2);
            }
        }
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static void drawLine(PoseStack.Pose pose, VertexConsumer lines, float[] a, float[] b) {
        float nx = b[0] - a[0], ny = b[1] - a[1], nz = b[2] - a[2];
        lines.vertex(pose.pose(), a[0], a[1], a[2]).color(1.0f, 0.15f, 0.15f, 1.0f)
                .normal(pose.normal(), nx, ny, nz).endVertex();
        lines.vertex(pose.pose(), b[0], b[1], b[2]).color(1.0f, 0.15f, 0.15f, 1.0f)
                .normal(pose.normal(), nx, ny, nz).endVertex();
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            HitboxStore.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        HitboxStore.clearSnapshots();
        HitboxCaptureManager.setLevelRenderPass(false);
    }

    private MahClientEvents() {
    }
}
