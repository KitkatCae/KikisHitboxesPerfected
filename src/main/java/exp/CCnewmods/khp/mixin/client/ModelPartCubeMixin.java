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

/**
 * Phase 2c: computes a real per-face {@code faceMask} for vanilla cubes by wrapping
 * the {@link VertexConsumer} for exactly one {@code Cube.compile} call — see
 * {@link CubeOpacityRecorder}'s javadoc for why this reads live {@code uv()}/
 * {@code color()} calls instead of reflecting into {@code Cube}'s package-private
 * {@code Polygon}/{@code Vertex} fields (the {@code u}/{@code v} float-field
 * disambiguation problem specifically).
 *
 * <p>{@code ModelPart.compile} (see {@code ModelPartMixin}) already adds one
 * {@link exp.CCnewmods.khp.capture.PartOBB} per cube to the active bracket's part
 * list, eagerly, at its own HEAD — before any individual {@code Cube.compile} call
 * has run. This mixin's job is narrower: patch that already-added {@code PartOBB}'s
 * {@code faceMask} in place once this cube's own compile call finishes, via
 * {@link HitboxCaptureManager}'s identity-keyed cube→index correlation (built
 * alongside the eager add, not inferred from call order).
 *
 * <p>Same {@code @ModifyVariable}-at-HEAD / finalize-at-RETURN shape as
 * {@code FerrouslimeRendererMixin}'s gel-body capture — proven pattern, different
 * target.
 */
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
