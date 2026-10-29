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

/** One box per Epic Fight joint, using the live animated pose matrices. */
public final class EpicFightBoxCapture {

    // Must be a direct buffer: a heap buffer crashes the JVM in JOML's Unsafe fast path.
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

    /** Same FloatBuffer route Epic Fight uses in reverse, so no transposition mistakes. */
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
