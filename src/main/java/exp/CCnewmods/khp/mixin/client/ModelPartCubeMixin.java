package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.CubeOpacityRecorder;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps one {@code Cube.compile} to record its opacity, then patches the cube's already-added part. */
@Mixin(ModelPart.Cube.class)
public abstract class ModelPartCubeMixin {

    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true)
    private VertexConsumer mah$wrapForOpacity(VertexConsumer buffer) {
        return HitboxCaptureManager.beginVanillaCubeOpacity((ModelPart.Cube) (Object) this, buffer);
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void mah$finishOpacityCapture(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                          int packedOverlay, float red, float green, float blue, float alpha,
                                          CallbackInfo ci) {
        HitboxCaptureManager.endVanillaCubeOpacity((ModelPart.Cube) (Object) this, buffer);
    }
}
