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
 * Bakes a rest-pose bounding box per joint from a {@link SkinnedMesh}'s own vertex
 * and bone-weight data, once per unique mesh instance, cached forever after.
 *
 * <h3>Why this gives "regular mob cube"-sized boxes for free</h3>
 * Epic Fight's own {@code VanillaModelTransformer}/{@code GeoModelTransformer}/
 * {@code AzureModelTransformer} classes (confirmed via bytecode) build every
 * {@code SkinnedMesh} <i>from</i> the entity's original vanilla/GeckoLib model at
 * load time — the mesh's rest-pose vertex positions are a (possibly re-triangulated,
 * possibly smoothed near joint boundaries) copy of the original cube geometry, not
 * an independent shape. Baking bounds directly from those rest vertices, grouped by
 * joint, naturally lands close to the size of the original mob's cubes without
 * needing any cross-system name mapping between Epic Fight's joints and the
 * original model's parts — everything needed lives inside the mesh's own data.
 *
 * <h3>Joint-local space, not mesh space (fixed after first playtest)</h3>
 * Confirmed via bytecode ({@code SkinnedMesh.drawPosed}): the actual per-vertex
 * skinning transform Epic Fight applies is
 * {@code poseStack.pose() * poses[jointId] * joint.getToOrigin() * restVertexPos} —
 * {@code poses[jointId]} (the live animated pose) expects to receive a position
 * already in that joint's own local frame, not raw mesh-space coordinates.
 * {@code Joint.getToOrigin()} is the inverse bind-pose matrix (baked once, at load,
 * from the joint hierarchy) that does that conversion. The first version of this
 * class baked bounds directly from raw mesh-space rest positions, skipping
 * {@code getToOrigin()} entirely — this produced boxes with no reliable relationship
 * to the camera or entity position at all (confirmed by screenshot: huge, apparently
 * "attached to the camera"). Every rest vertex is now transformed by its dominant
 * joint's {@code getToOrigin()} <i>before</i> being folded into that joint's running
 * bounding box, so the baked box is already in the same joint-local space
 * {@code poses[jointId]} expects at capture time.
 *
 * <h3>Grouping policy</h3>
 * Confirmed via bytecode: a vertex can be influenced by multiple joints at once
 * (standard skeletal skinning — {@code affectingJointCounts}/{@code affectingJointIndices}/
 * {@code affectingWeightIndices} are all per-vertex, COLLADA vcount/vindices-style).
 * Each vertex is assigned to its single <b>dominant</b> joint (highest weight) rather
 * than contributing to every joint that influences it even slightly — this keeps
 * joint boxes from bloating into their smoothly-blended neighbors, at the cost of a
 * small amount of geometry right at joint boundaries not being covered by either
 * neighbor's box. A reasonable first-pass tradeoff; revisit if boundary regions turn
 * out to matter for the wound system in practice.
 *
 * <h3>Caching</h3>
 * All public {@code SkinnedMesh} accessors used here ({@code positions()},
 * {@code weights()}, {@code affectingJointCounts()}, {@code affectingJointIndices()},
 * {@code affectingWeightIndices()}) — no reflection needed. Baking walks every
 * vertex once; cached by mesh instance identity ({@code WeakHashMap}) since
 * {@code SkinnedMesh} instances are shared per model type (an asset-level singleton,
 * same pattern as vanilla {@code EntityModel} instances), not recreated per entity.
 * A mesh is always paired with exactly one armature, so caching by mesh identity
 * alone (not mesh+armature) is safe.
 */
public final class EpicFightMeshBaker {

    private static final Map<SkinnedMesh, Map<Integer, AABB>> CACHE = new WeakHashMap<>();

    /** Joint id → rest-pose local (joint-local, post-getToOrigin) AABB. */
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

            // Transform the rest-pose (mesh-space) vertex into this joint's own
            // local frame — the space poses[jointId] expects at draw time.
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

