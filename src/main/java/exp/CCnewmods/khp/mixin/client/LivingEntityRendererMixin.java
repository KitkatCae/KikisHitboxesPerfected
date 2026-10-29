package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the primary-geometry gate after the main model renders, so layers (armor, saddles) aren't captured
 * unless capture_layers is on, except layers registered as anatomy.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    shift = At.Shift.AFTER))
    private void mah$closePrimaryGate(LivingEntity entity, float entityYaw, float partialTick,
                                      PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                      CallbackInfo ci) {
        HitboxCaptureManager.setPrimaryGate(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void mah$wrapLayerRender(RenderLayer layer, PoseStack poseStack, MultiBufferSource buffer,
                                     int packedLight, Entity entity, float limbSwing, float limbSwingAmount,
                                     float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        boolean anatomical = HitboxCaptureManager.isAnatomicalRenderLayer(layer.getClass());
        if (anatomical) {
            HitboxCaptureManager.setPrimaryGate(true);
        }
        layer.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount,
                partialTick, ageInTicks, netHeadYaw, headPitch);
        if (anatomical) {
            HitboxCaptureManager.setPrimaryGate(false);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void mah$gateSafetyClose(LivingEntity entity, float entityYaw, float partialTick,
                                     PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                     CallbackInfo ci) {
        HitboxCaptureManager.setPrimaryGate(false);
    }
}
