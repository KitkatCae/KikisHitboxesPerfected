package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * F3+B: replaces vanilla's white box with a per-part wireframe for entities combat uses KHP geometry for.
 * Green = live snapshot, orange = trusted blueprint, white (vanilla) = combat uses the vanilla box.
 */
// m_114384_ = render
// m_114441_ = renderHitbox
@Mixin(value = EntityRenderDispatcher.class, remap = false)
public abstract class EntityHitboxOverlayMixin {

    @Shadow(remap = false)
    private static void m_114441_(PoseStack pPoseStack, VertexConsumer pBuffer, Entity pEntity, float pPartialTicks) {
        throw new AssertionError("shadowed");
    }

    @Redirect(
            method = "m_114384_(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "m_114441_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lnet/minecraft/world/entity/Entity;F)V",
                    remap = false),
            remap = false)
    private void khp$redirectRenderHitbox(PoseStack poseStack, VertexConsumer buffer, Entity entity, float partialTicks) {
        if (!MahConfig.enabled) {
            m_114441_(poseStack, buffer, entity, partialTicks);
            return;
        }

        EntityHitboxSnapshot combatSnap = MahApi.getCombatSnapshot(entity);
        if (combatSnap == null) {
            m_114441_(poseStack, buffer, entity, partialTicks);
            return;
        }

        boolean live = MahApi.hasFreshSnapshot(entity, MahConfig.combatStaleSnapshotTicks);
        float r = live ? 0.2f : 1.0f;
        float g = live ? 1.0f : 0.65f;
        float b = 0.3f;

        // The pose is already at the entity's interpolated render position, so only the world-space drift
        // since capture needs adding.
        Vec3 delta = entity.position().subtract(combatSnap.capturedEntityPos());
        Vec3 targetWorldPos = combatSnap.anchorPos().add(delta);
        Vec3 interpolatedRenderPos = new Vec3(
                Mth.lerp((double) partialTicks, (double) entity.xOld, entity.getX()),
                Mth.lerp((double) partialTicks, (double) entity.yOld, entity.getY()),
                Mth.lerp((double) partialTicks, (double) entity.zOld, entity.getZ()));
        Vec3 correction = targetWorldPos.subtract(interpolatedRenderPos);

        for (PartOBB part : combatSnap.parts()) {
            poseStack.pushPose();
            poseStack.translate(correction.x, correction.y, correction.z);
            poseStack.mulPoseMatrix(part.transform());
            LevelRenderer.renderLineBox(poseStack, buffer, part.localBox(), r, g, b, 1.0f);
            poseStack.popPose();
        }
    }
}
