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

/**
 * Mowzie's Mobs wraps real GeckoLib in its own {@code MowzieGeoEntityRenderer}, which
 * extends GeckoLib's {@code GeoEntityRenderer} but — verified against
 * mowziesmobs-1.8.2 — declares its <b>own</b> {@code renderRecursively} override,
 * exactly like GeckoLib's own {@code DynamicGeoEntityRenderer} does. That means
 * neither of MAH's existing GeckoLib mixins (which target the three renderer classes
 * declared directly in the GeckoLib jar) ever fire for a Mowzie's Mobs entity — this
 * is a fourth, mod-specific renderRecursively override needing its own hook, not a
 * new geometry format: the bone type is real GeckoLib {@code GeoBone}, so this reuses
 * {@link HitboxCaptureManager#captureGeoBone} unchanged.
 *
 * <p>Third-party target: remap=false, literal names. Optional dependency — this
 * config is {@code "required": false} and simply won't find its target class if
 * Mowzie's Mobs isn't installed.
 */
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
