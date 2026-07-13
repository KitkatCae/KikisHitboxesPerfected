package exp.CCnewmods.khp.mixin.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures every Geo bone at renderRecursively HEAD, where the pose is the parent
 * bone space; the manager replicates GeckoLib's own bone prelude and per-cube pivot
 * math (verified verbatim from the pinned GeckoLib jar's RenderUtils). Recursion goes
 * through {@code this.renderRecursively}, so child bones re-enter this injection with
 * the correct stacked pose. Third-party target: remap=false, no refmap in config.
 *
 * <p>Known gap (by design): renderer subclasses overriding renderRecursively without
 * calling super bypass capture — the debug renderer makes those visible; the phase-3
 * blueprint source covers them.
 */
@Mixin(value = GeoEntityRenderer.class, remap = false)
public abstract class GeoEntityRendererMixin<T extends Entity & GeoAnimatable> {

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
