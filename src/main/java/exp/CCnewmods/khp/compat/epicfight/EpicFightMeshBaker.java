package exp.CCnewmods.khp.compat.epicfight;

import net.minecraft.world.phys.AABB;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bakes a rest-pose box per joint from an Epic Fight mesh (built from the original model, so boxes match its
 * cubes). Vertices go to their highest-weight joint and into joint-local space via {@code getToOrigin()}.
 * Cached per mesh.
 */
public final class EpicFightMeshBaker {

    private static final Map<SkinnedMesh, Map<Integer, AABB>> CACHE = new WeakHashMap<>();

    public static Map<Integer, AABB> bakeRestBounds(SkinnedMesh mesh, Armature armature) {
        return CACHE.computeIfAbsent(mesh, m -> bake(m, armature));
    }

    private static Map<Integer, AABB> bake(SkinnedMesh mesh, Armature armature) {
        float[] positions = mesh.positions();
        float[] weights = mesh.weights();
        int[] affectingJointCounts = mesh.affectingJointCounts();
        int[][] affectingJointIndices = mesh.affectingJointIndices();
        int[][] affectingWeightIndices = mesh.affectingWeightIndices();

        Map<Integer, float[]> bounds = new HashMap<>(); // jointId -> [minX,minY,minZ,maxX,maxY,maxZ]
        if (positions == null || affectingJointCounts == null) return Map.of();

        Vec4f scratchSrc = new Vec4f();
        Vec4f scratchDest = new Vec4f();

        int vertexCount = positions.length / 3;
        for (int v = 0; v < vertexCount && v < affectingJointCounts.length; v++) {
            int count = affectingJointCounts[v];
            if (count <= 0) continue;
            int[] joints = affectingJointIndices[v];
            int[] weightIdx = affectingWeightIndices[v];

            int dominantJoint = -1;
            float maxWeight = -1.0f;
            for (int i = 0; i < count; i++) {
                float w = weights[weightIdx[i]];
                if (w > maxWeight) {
                    maxWeight = w;
                    dominantJoint = joints[i];
                }
            }
            if (dominantJoint < 0) continue;

            Joint joint = armature.searchJointById(dominantJoint);
            if (joint == null) continue;

            scratchSrc.x = positions[v * 3];
            scratchSrc.y = positions[v * 3 + 1];
            scratchSrc.z = positions[v * 3 + 2];
            scratchSrc.w = 1.0f;
            OpenMatrix4f.transform(joint.getToOrigin(), scratchSrc, scratchDest);

            float x = scratchDest.x;
            float y = scratchDest.y;
            float z = scratchDest.z;

            float[] b = bounds.computeIfAbsent(dominantJoint, k -> new float[]{
                    Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
            });
            if (x < b[0]) b[0] = x;
            if (y < b[1]) b[1] = y;
            if (z < b[2]) b[2] = z;
            if (x > b[3]) b[3] = x;
            if (y > b[4]) b[4] = y;
            if (z > b[5]) b[5] = z;
        }

        Map<Integer, AABB> result = new HashMap<>();
        for (Map.Entry<Integer, float[]> e : bounds.entrySet()) {
            float[] b = e.getValue();
            result.put(e.getKey(), new AABB(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return Map.copyOf(result);
    }

    private EpicFightMeshBaker() {
    }
}
