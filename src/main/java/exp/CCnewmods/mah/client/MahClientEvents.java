package exp.CCnewmods.mah.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.mah.Mah;
import exp.CCnewmods.mah.MahConfig;
import exp.CCnewmods.mah.capture.EntityHitboxSnapshot;
import exp.CCnewmods.mah.capture.HitboxCaptureManager;
import exp.CCnewmods.mah.capture.HitboxStore;
import exp.CCnewmods.mah.capture.PartOBB;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
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
 * Forge-bus client events:
 * <ul>
 *   <li>Scopes the capture bracket to actual level rendering via the level-render-pass
 *       flag (AFTER_SKY → on, AFTER_ENTITIES → off), so dispatcher calls from GUI
 *       screens (inventory paper-doll etc.) never publish garbage snapshots.</li>
 *   <li>Debug wireframes at AFTER_ENTITIES — the phase-1 acceptance tool. Green boxes
 *       must stay glued to animated models with zero drift.</li>
 *   <li>Snapshot cleanup on entity removal and logout.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MahClientEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            HitboxCaptureManager.setLevelRenderPass(true);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // Draw first (flag state irrelevant to drawing), then close the pass.
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

            // Track the entity between captures: anchor + (currentPos - capturedPos).
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
                        0.2f, 1.0f, 0.3f, 1.0f); // green = LIVE_CLIENT
                poseStack.popPose();
                drewAny = true;
            }
        }

        if (drewAny) {
            bufferSource.endBatch(RenderType.lines());
        }
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
