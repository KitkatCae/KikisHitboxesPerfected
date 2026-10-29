package exp.CCnewmods.khp.mixin.citadel;

import com.github.alexthe666.citadel.client.model.AdvancedModelBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.compat.citadel.CitadelBoxCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Citadel's Tabula-style boxes, at {@code doRender} HEAD where the pose is already applied. */
@Mixin(value = AdvancedModelBox.class, remap = false)
public abstract class CitadelAdvancedModelBoxMixin {

    @Inject(method = "doRender", at = @At("HEAD"), remap = false)
    private void mah$captureBox(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                int packedOverlay, float red, float green, float blue, float alpha,
                                CallbackInfo ci) {
        CitadelBoxCapture.capture((AdvancedModelBox) (Object) this, pose);
    }
}
