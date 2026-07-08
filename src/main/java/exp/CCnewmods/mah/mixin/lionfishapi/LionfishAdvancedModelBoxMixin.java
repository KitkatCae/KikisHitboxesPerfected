package exp.CCnewmods.mah.mixin.lionfishapi;

import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.mah.compat.lionfishapi.LionfishBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures LionfishAPI's Tabula-style model boxes (used throughout Ender's
 * Cataclysm) at {@code doRender} HEAD — structurally identical to Citadel's
 * {@code AdvancedModelBox} (verified against lionfishapi-3.0: same
 * render/translateAndRotate/doRender shape, same pre-grow {@code posX1..posZ2}
 * bounds on its {@code ModelBox}).
 *
 * <p>Third-party target: remap=false, literal names. Only loaded when LionfishAPI is
 * present (mah.lionfishapi.mixins.json is "required": false).
 */
@Mixin(value = AdvancedModelBox.class, remap = false)
public abstract class LionfishAdvancedModelBoxMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        LionfishBoxCapture.capture((AdvancedModelBox) (Object) this, pose);
    }
}
