package exp.CCnewmods.khp.mixin.uranus;

import com.iafenvoy.uranus.client.model.AdvancedModelBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.uranus.UranusBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uranus (Ice and Fire: Community Edition) counterpart of {@code CitadelAdvancedModelBoxMixin}. */
@Mixin(value = AdvancedModelBox.class, remap = false)
public abstract class UranusAdvancedModelBoxMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void khp$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        UranusBoxCapture.capture((AdvancedModelBox) (Object) this, pose);
    }
}
