package exp.CCnewmods.khp.mixin.lionfishapi;

import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.lionfishapi.LionfishBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LionfishAPI's Tabula-style boxes (Ender's Cataclysm). */
@Mixin(value = AdvancedModelBox.class, remap = false)
public abstract class LionfishAdvancedModelBoxMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        LionfishBoxCapture.capture((AdvancedModelBox) (Object) this, pose);
    }
}
