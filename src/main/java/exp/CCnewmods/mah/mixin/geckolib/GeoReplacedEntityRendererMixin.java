package exp.CCnewmods.mah.mixin.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.mah.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same bone capture as GeoEntityRendererMixin, for replaced-entity renderers
 * (vanilla entities re-skinned with Geo models). Generic bounds mirror the target:
 * {@code <E extends Entity, T extends GeoAnimatable>} — the animatable parameter
 * erases to GeoAnimatable in this class, unlike GeoEntityRenderer where it erases
 * to Entity (verified from the class signatures in the pinned GeckoLib jar).
 */
@Mixin(value = GeoReplacedEntityRenderer.class, remap = false)
public abstract class GeoReplacedEntityRendererMixin<E extends Entity, T extends GeoAnimatable> {

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
