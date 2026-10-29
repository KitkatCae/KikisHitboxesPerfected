package exp.CCnewmods.khp.mixin.mowziesmobs;

import com.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.mowziesmobs.MowzieAdvancedBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LLibrary boxes used by Mowzie's Mobs' older creatures. */
@Mixin(value = AdvancedModelRenderer.class, remap = false)
public abstract class MowzieAdvancedModelRendererMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        MowzieAdvancedBoxCapture.capture((AdvancedModelRenderer) (Object) this, pose);
    }
}
