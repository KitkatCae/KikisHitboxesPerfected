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
 * Captures Ferrouslime's "gel" body and spikes — geometry with no {@code ModelPart}
 * or {@code AdvancedModelBox} behind it at all. Verified via bytecode:
 * {@code renderGel} and {@code renderGelSpikes} build their shapes from scratch every
 * frame with direct {@code VertexConsumer} calls — there is no model object to hook
 * the way every other MAH capture path works. See
 * {@link exp.CCnewmods.khp.capture.RecordingVertexConsumer} for the general
 * mechanism.
 *
 * <p>Ferrouslime's heads use real Citadel {@code AdvancedModelBox} geometry and are
 * already captured via the existing Citadel mixin — this class only covers the two
 * raw-vertex methods.
 *
 * <h3>Gel body — axis-aligned mode</h3>
 * {@code renderGel} draws one blob with no per-instance rotation to preserve, so its
 * {@code consumer} parameter is wrapped directly via {@code @ModifyVariable} at HEAD
 * (substituting the wrapped consumer for the rest of the method's own unmodified
 * body) and finalized at RETURN.
 *
 * <h3>Spikes — oriented mode, one box per spike</h3>
 * {@code renderGelSpikes} loops over all eight spikes itself, calling the private
 * {@code spikeCubeFace} once per spike with a matrix that already encodes that
 * spike's own rotation. Wrapping the whole loop as one span produced one giant box
 * spanning the full radial burst (confirmed by screenshot); wrapping each spike
 * individually with an <i>axis-aligned</i> box also looked wrong for diagonally
 * oriented spikes — an axis-aligned box has to balloon out to contain a tip that
 * sticks out along both X and Z at once, even though the spike itself is thin
 * (confirmed by a second screenshot).
 *
 * <p>Fixed by capturing each spike in <b>oriented mode</b>
 * ({@link HitboxCaptureManager#beginRawVertexCapture(String, VertexConsumer, Matrix4f)}):
 * bounds are computed in the spike's own unrotated local frame (tight, since the
 * shape is axis-aligned there regardless of which way it's rotated into world
 * space), and the resulting box carries the spike's actual rotation as its
 * transform — the same "local box + separate transform" convention every other
 * capture path in MAH already uses.
 *
 * <p>This needs the spike's own transform matrix <i>and</i> its VertexConsumer
 * together at one injection point, so this uses {@code @Redirect} on the call
 * <i>from</i> {@code renderGelSpikes} <i>to</i> {@code spikeCubeFace} — the redirect
 * handler wraps the consumer using the matrix it's also being handed, calls through
 * to the real (shadowed) method, then finalizes. A {@code @Unique} counter, reset at
 * {@code renderGelSpikes}'s HEAD, names each span {@code gel_spike_0} through
 * {@code gel_spike_7} in loop order.
 *
 * <p>Third-party target: remap=false, literal names. Optional dependency — this
 * config is {@code "required": false} and simply won't find its target class if
 * Alex's Caves isn't installed.
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

    // ── Gel body: axis-aligned, no per-instance rotation to preserve ───────

    @ModifyVariable(method = "renderGel", at = @At("HEAD"), argsOnly = true, remap = false)
    private VertexConsumer mah$wrapGelConsumer(VertexConsumer consumer) {
        return HitboxCaptureManager.beginRawVertexCapture("gel", consumer);
    }

    @Inject(method = "renderGel", at = @At("RETURN"), remap = false)
    private void mah$finishGelCapture(FerrouslimeEntity entity, float partialTicks, PoseStack poseStack,
                                      VertexConsumer consumer, float size, int packedLight, CallbackInfo ci) {
        HitboxCaptureManager.endRawVertexCapture();
    }

    // ── Spikes: oriented, one box per spike ─────────────────────────────────

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
        // Call through the @Shadow stub via `this` (not the `self` redirect parameter):
        // our own mixin class declares spikeCubeFace via @Shadow, so `this.spikeCubeFace(...)`
        // resolves at compile time to that stub, which Mixin rewires to the real private
        // implementation post-merge. `self` is only present because @Redirect requires the
        // original call's receiver as the handler's first parameter; it's the same object
        // as `this` here, but calling a private method on it directly wouldn't compile
        // (Java's private-access check doesn't know `self` is `this` at the source level).
        this.spikeCubeFace(entity, matrix4f, matrix3f, wrapped, offset, length, width, packedLightIn);
        HitboxCaptureManager.endRawVertexCapture();
        mah$spikeIndex++;
    }
}
