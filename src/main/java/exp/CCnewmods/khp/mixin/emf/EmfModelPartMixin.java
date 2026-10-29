package exp.CCnewmods.khp.mixin.emf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.compat.emf.EmfPartNames;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity Model Features' parts override {@code ModelPart.render}/{@code compile} without calling super, so
 * {@code ModelPartMixin} never sees them. {@code renderLikeVanilla} is EMF's copy of vanilla's render loop:
 * names are pushed/popped around it and cubes captured where it calls {@code compile}, after the part's
 * translateAndRotate (EMF's translateAndRotate has side effects, so it isn't called again here).
 */
// m_104290_ = compile
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart", remap = false)
public abstract class EmfModelPartMixin {

    private static final String RENDER =
            "renderLikeVanilla(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void khp$pushName(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                              float red, float green, float blue, float alpha, CallbackInfo ci) {
        ModelPart self = (ModelPart) (Object) this;
        EmfPartNames.stamp(self);
        HitboxCaptureManager.pushPartName(self);
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void khp$popName(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                             float red, float green, float blue, float alpha, CallbackInfo ci) {
        HitboxCaptureManager.popPartName();
    }

    @Inject(method = RENDER, at = @At(value = "INVOKE",
            target = "Ltraben/entity_model_features/models/parts/EMFModelPart;m_104290_(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
    private void khp$captureCubes(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha, CallbackInfo ci) {
        if (HitboxCaptureManager.shouldCaptureModelGeometry()) {
            HitboxCaptureManager.captureVanillaCubes(EmfPartNames.cubes((ModelPart) (Object) this), poseStack.last());
        }
    }
}
