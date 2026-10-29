package exp.CCnewmods.khp.mixin.alexscaves;

import com.github.alexmodguy.alexscaves.client.render.entity.FerrouslimeRenderer;
import com.github.alexmodguy.alexscaves.server.entity.living.FerrouslimeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures Ferrouslime's gel body and spikes, which are drawn as raw vertices. The body is one axis-aligned box;
 * each spike gets its own oriented box (one box for all spikes was huge, axis-aligned ones balloon on diagonals).
 */
@Mixin(value = FerrouslimeRenderer.class, remap = false)
public abstract class FerrouslimeRendererMixin {

    @Shadow(remap = false)
    private void spikeCubeFace(FerrouslimeEntity entity, Matrix4f matrix4f, Matrix3f matrix3f,
                               VertexConsumer vertexConsumer, float offset, float length, float width,
                               int packedLightIn) {
        throw new AssertionError("MAH: spikeCubeFace mixin shadow stub should never execute directly");
    }

    @Unique
    private int mah$spikeIndex = 0;

    // ── Gel body ────────────────────────────────────────────────────────────

    @ModifyVariable(method = "renderGel", at = @At("HEAD"), argsOnly = true, remap = false)
    private VertexConsumer mah$wrapGelConsumer(VertexConsumer consumer) {
        return HitboxCaptureManager.beginRawVertexCapture("gel", consumer);
    }

    @Inject(method = "renderGel", at = @At("RETURN"), remap = false)
    private void mah$finishGelCapture(FerrouslimeEntity entity, float partialTicks, PoseStack poseStack,
                                      VertexConsumer consumer, float size, int packedLight, CallbackInfo ci) {
        HitboxCaptureManager.endRawVertexCapture();
    }

    // ── Spikes ──────────────────────────────────────────────────────────────

    @Inject(method = "renderGelSpikes", at = @At("HEAD"), remap = false)
    private void mah$resetSpikeIndex(FerrouslimeEntity entity, float partialTicks, PoseStack poseStack,
                                     VertexConsumer consumer, float size, int packedLight, CallbackInfo ci) {
        mah$spikeIndex = 0;
    }

    @Redirect(method = "renderGelSpikes", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/alexmodguy/alexscaves/client/render/entity/FerrouslimeRenderer;"
                            + "spikeCubeFace(Lcom/github/alexmodguy/alexscaves/server/entity/living/FerrouslimeEntity;"
                            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix3f;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFI)V"))
    private void mah$captureSpike(FerrouslimeRenderer self, FerrouslimeEntity entity, Matrix4f matrix4f,
                                  Matrix3f matrix3f, VertexConsumer vertexConsumer, float offset, float length,
                                  float width, int packedLightIn) {
        VertexConsumer wrapped = HitboxCaptureManager.beginRawVertexCapture(
                "gel_spike_" + mah$spikeIndex, vertexConsumer, matrix4f);
        // Call via `this` (the @Shadow stub); a private method can't be called on `self`.
        this.spikeCubeFace(entity, matrix4f, matrix3f, wrapped, offset, length, width, packedLightIn);
        HitboxCaptureManager.endRawVertexCapture();
        mah$spikeIndex++;
    }
}
