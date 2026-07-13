package exp.CCnewmods.khp.mixin.citadel;

import com.github.alexthe666.citadel.client.model.AdvancedModelBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.citadel.CitadelBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures Citadel's Tabula-style model boxes at {@code doRender} HEAD, where the
 * incoming pose already has this box's own translate + rotate applied (verified
 * against citadel-2.6.3-1.20.1: {@code render()} pushes pose, calls
 * {@code translateRotate}, then {@code doRender(matrixStack.last(), ...)}).
 *
 * <p>Third-party target: remap=false, literal names — no refmap involved, this jar's
 * names are real and stable regardless of Minecraft's own obfuscation. Only loaded
 * when Citadel is present (mah.citadel.mixins.json is "required": false).
 */
@Mixin(value = AdvancedModelBox.class, remap = false)
public abstract class CitadelAdvancedModelBoxMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        CitadelBoxCapture.capture((AdvancedModelBox) (Object) this, pose);
    }
}
