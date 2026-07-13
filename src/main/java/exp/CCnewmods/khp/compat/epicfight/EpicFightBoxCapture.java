package exp.CCnewmods.khp.compat.epicfight;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Captures one box per joint for an Epic Fight skinned-mesh render call.
 *
 * <p>Unlike every other raw-vertex-adjacent capture path in MAH, this one deals with
 * a live skeleton: {@link Armature#getPoseMatrices()} gives the <i>current animated</i>
 * pose for every joint, updated every frame Epic Fight draws — this is exactly why
 * it was worth building at all rather than falling back to a rest-pose snapshot: a
 * player mid-swing or a mob mid-combo has joints in genuinely different positions
 * frame to frame, and this reads whatever Epic Fight itself is using to draw in that
 * exact frame, not a cached idle pose.
 *
 * <h3>Composing with poseStack (fixed after first playtest)</h3>
 * Confirmed via bytecode ({@code SkinnedMesh.drawPosed}): the full per-vertex
 * transform Epic Fight actually applies is
 * {@code poseStack.pose() * poses[jointId] * joint.getToOrigin() * restVertexPos}.
 * {@code getToOrigin()} is handled once, at bake time, by
 * {@link EpicFightMeshBaker} (baked directly into each joint's rest bounding box, so
 * the box is already in the right space). What's left for this class to apply every
 * frame is {@code poseStack.pose() * poses[jointId]} — the first build of this
 * integration omitted the {@code poseStack.pose()} half entirely, which produced
 * boxes with no reliable relationship to the camera or entity position at all
 * (confirmed by screenshot: huge, apparently "attached to the camera"). Fixed by
 * accepting {@code poseStack} here too and composing it with the converted joint
 * matrix before handing the result to
 * {@link HitboxCaptureManager#captureExternalPart}, which then composes it with the
 * bracket's own {@code basisInverse} exactly the same way every other capture path
 * does.
 */
public final class EpicFightBoxCapture {

    // Reused per-thread scratch buffer for the OpenMatrix4f -> JOML Matrix4f conversion.
    //
    // MUST be direct, not FloatBuffer.allocate(16) (heap-backed) — confirmed via a
    // production crash (hs_err EXCEPTION_ACCESS_VIOLATION inside
    // org.joml.MemUtil$MemUtilUnsafe, crash register R8 = 0x10 matching
    // MemUtilUnsafe.ADDRESS's field offset exactly). JOML's Matrix4f.set(FloatBuffer)
    // has an Unsafe-based fast path that reads a buffer's native memory address
    // directly; a heap buffer has no such address, so that path dereferences garbage.
    // A direct buffer has a real native address and is what that fast path expects.
    private static final ThreadLocal<FloatBuffer> SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(16 * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer());

    public static void capture(SkinnedMesh mesh, PoseStack poseStack, Armature armature, OpenMatrix4f[] poses) {
        if (armature == null || poses == null) return;
        Map<Integer, AABB> restBounds = EpicFightMeshBaker.bakeRestBounds(mesh, armature);
        if (restBounds.isEmpty()) return;

        Matrix4f currentPose = poseStack.last().pose();

        for (Map.Entry<Integer, AABB> entry : restBounds.entrySet()) {
            int jointId = entry.getKey();
            if (jointId < 0 || jointId >= poses.length) continue;
            Joint joint = armature.searchJointById(jointId);
            if (joint == null) continue;

            Matrix4f jointPose = toJoml(poses[jointId]);
            Matrix4f localTransform = new Matrix4f(currentPose).mul(jointPose);
            HitboxCaptureManager.captureExternalPart(joint.getName(), entry.getValue(), localTransform);
        }
    }

    /**
     * Converts Epic Fight's own matrix type to JOML's, via the identical
     * {@code FloatBuffer} round-trip Epic Fight's own {@code OpenMatrix4f.importFromMojangMatrix}
     * uses in the opposite direction (verified via bytecode) — deliberately not a
     * hand-written 16-argument constructor call, to avoid any risk of a row/column
     * transposition mistake; this mirrors a conversion path Epic Fight's own code
     * already relies on being correct.
     */
    private static Matrix4f toJoml(OpenMatrix4f openMatrix) {
        FloatBuffer buf = SCRATCH.get();
        buf.clear();
        openMatrix.store(buf);
        buf.flip();
        Matrix4f result = new Matrix4f();
        result.set(buf);
        return result;
    }

    private EpicFightBoxCapture() {
    }
}
