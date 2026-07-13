package exp.CCnewmods.khp.mixin.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.DynamicGeoEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DynamicGeoEntityRenderer declares its own renderRecursively override (a standalone
 * reimplementation that never calls GeoEntityRenderer's version, verified in the
 * pinned jar), so the GeoEntityRendererMixin injection never fires for it — this
 * mixin covers the dynamic path (per-bone texture/render-type swapping renderers).
 */
@Mixin(value = DynamicGeoEntityRenderer.class, remap = false)
public abstract class DynamicGeoEntityRendererMixin<T extends Entity & GeoAnimatable> {

    @Inject(method = "renderRecursively", at = @At("HEAD"), remap = false)
    private void mah$captureBone(PoseStack poseStack, T animatable, GeoBone bone,
                                 RenderType renderType, MultiBufferSource bufferSource,
                                 VertexConsumer buffer, boolean isReRender, float partialTick,
                                 int packedLight, int packedOverlay,
                                 float red, float green, float blue, float alpha,
                                 CallbackInfo ci) {
        HitboxCaptureManager.captureGeoBone(bone, isReRender, poseStack);
    }
}
