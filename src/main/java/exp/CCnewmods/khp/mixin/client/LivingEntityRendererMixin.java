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
 * Closes the primary-geometry gate once the entity's own model render
 * ({@code this.model.renderToBuffer(...)}, INVOKE owner EntityModel — verified in the
 * mapped jar) has finished, so RenderLayer geometry rendered afterward (armor,
 * saddles, energy swirls) is skipped unless capture_layers is on. RETURN close is a
 * safety net for the same reason.
 *
 * <p><b>Phase 1C change:</b> the gate no longer needs an explicit "open" injection.
 * {@link HitboxCaptureManager#enterBracket} now defaults {@code primaryGate} to
 * {@code true} for every VANILLA_LIVING bracket, closed only by the injections below.
 * The old design required this class's own {@code render(LivingEntity,...)} method
 * to actually execute before capture could begin — which silently produced zero boxes
 * for any renderer that fully overrides {@code render(...)} without calling
 * {@code super.render(...)} (confirmed via bytecode: Ice and Fire's
 * {@code RenderGhost}, which reimplements the whole render pipeline itself and calls
 * {@code ModelGhost.renderToBuffer} directly, never reaching this class's method body
 * at all). Defaulting to open and only closing here means such renderers are now
 * captured as fully primary — the correct behavior when they have no RenderLayers to
 * distinguish from, and a strict improvement over silently capturing nothing.
 *
 * <p><b>Phase 1D addition:</b> {@code mah$wrapLayerRender} redirects each individual
 * {@code RenderLayer.render(...)} call inside the layers loop. Most layers render
 * with the gate closed as before (cosmetic overlays — armor, saddles). Layers whose
 * concrete class is registered via
 * {@link HitboxCaptureManager#registerAnatomicalRenderLayer} (confirmed real anatomy
 * implemented as a layer, e.g. vanilla's {@code SlimeOuterLayer} or Ice and Fire's
 * {@code LayerHydraHead}) get the gate briefly reopened for just that one layer's
 * render call, then closed again for whatever comes next in the loop.
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
