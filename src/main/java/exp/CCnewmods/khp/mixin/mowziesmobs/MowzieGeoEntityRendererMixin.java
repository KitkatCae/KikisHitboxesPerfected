package exp.CCnewmods.khp.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.client.render.entity.MowzieGeoEntityRenderer;
import com.bobmowzie.mowziesmobs.server.entity.MowzieGeckoEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.GeoBone;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mowzie's GeckoLib renderer overrides {@code renderRecursively}, so it needs its own hook. */
@Mixin(value = MowzieGeoEntityRenderer.class, remap = false)
public abstract class MowzieGeoEntityRendererMixin<T extends MowzieGeckoEntity> {

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
