package exp.CCnewmods.khp.mixin.mowziesmobs;

import com.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.mowziesmobs.MowzieAdvancedBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the LLibrary-based model boxes used by Mowzie's Mobs' older creatures
 * (Frostmaw, Naga, Grottol, Wroughtnaut — confirmed via bytecode: these extend plain
 * {@code MobRenderer} directly, not {@code MowzieGeoEntityRenderer}, so they never
 * reach {@code MowzieGeoEntityRendererMixin} at all). {@code doRender} here is
 * {@code protected} rather than {@code private} (Citadel/LionfishAPI's equivalent),
 * but the same HEAD injection works identically.
 *
 * <p>Third-party target: remap=false, literal names. This class is bundled directly
 * inside the Mowzie's Mobs jar itself (a vendored copy of ilexiconn's LLibrary, still
 * under its original package), so no separate dependency coordinate is needed beyond
 * the existing Mowzie's Mobs one.
 */
@Mixin(value = AdvancedModelRenderer.class, remap = false)
public abstract class MowzieAdvancedModelRendererMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        MowzieAdvancedBoxCapture.capture((AdvancedModelRenderer) (Object) this, pose);
    }
}
