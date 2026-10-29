package exp.CCnewmods.khp.capture;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.CastShape;
import exp.CCnewmods.khp.api.PartHitResult;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Raycast/shapecast against captured {@link PartOBB}s. Callers supply the candidates with already-resolved
 * snapshots. Per part: slab test, then skip fully transparent faces ({@code faceMask}), then for flat parts a
 * per-pixel texture test at the hit point.
 */
public final class HitboxRaycaster {

    @Nullable
    public static List<PartHitResult> raycastWorld(Map<Entity, EntityHitboxSnapshot> candidates,
                                                    Vec3 origin, Vec3 direction, double maxDistance) {
        Vec3 dir = direction.normalize();
        List<PartHitResult> hits = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            collectRayHits(entry.getKey(), entry.getValue(), origin, dir, maxDistance, hits);
        }
        if (hits.isEmpty()) return null;
        hits.sort(Comparator.comparingDouble(h -> h.worldEntryPoint().distanceToSqr(origin)));
        return hits;
    }

    @Nullable
    public static List<PartHitResult> raycastEntity(Entity target, @Nullable EntityHitboxSnapshot snap,
                                                     Vec3 origin, Vec3 direction, double maxDistance) {
        if (snap == null) return null;
        Vec3 dir = direction.normalize();
        List<PartHitResult> hits = new ArrayList<>();
        collectRayHits(target, snap, origin, dir, maxDistance, hits);
        if (hits.isEmpty()) return null;
        hits.sort(Comparator.comparingDouble(h -> h.worldEntryPoint().distanceToSqr(origin)));
        return hits;
    }

    /**
     * Tests 26 sample points per part (corners, edge midpoints, face centers) against {@code shape}; a small part
     * between samples of a thin shape can be missed. {@code worldExitPoint}/{@code faceHit} are best-effort.
     */
    @Nullable
    public static List<PartHitResult> shapecast(Map<Entity, EntityHitboxSnapshot> candidates, CastShape shape) {
        List<PartHitResult> hits = new ArrayList<>();
        AABB shapeBounds = shape.boundingBox();
        Vec3 shapeCenter = shapeBounds.getCenter();
        for (var entry : candidates.entrySet()) {
            Entity entity = entry.getKey();
            EntityHitboxSnapshot snap = entry.getValue();
            Vec3 currentAnchor = currentAnchor(entity, snap);
            BoneTable boneTable = HitboxStore.getBoneTable(snap.entityKey());

            for (PartOBB part : snap.parts()) {
                Matrix4f worldTransform = new Matrix4f().translate(
                        (float) currentAnchor.x, (float) currentAnchor.y, (float) currentAnchor.z).mul(part.transform());
                AABB worldBounds = worldBoundsOf(part.localBox(), worldTransform);
                if (!worldBounds.intersects(shapeBounds)) continue;

                Vec3 hitPoint = null;
                for (Vec3 local : sampleBoxPoints(part.localBox())) {
                    Vector3f w = worldTransform.transformPosition(
                            new Vector3f((float) local.x, (float) local.y, (float) local.z));
                    Vec3 worldSample = new Vec3(w.x, w.y, w.z);
                    if (shape.contains(worldSample)) {
                        hitPoint = worldSample;
                        break;
                    }
                }
                if (hitPoint == null) continue;

                Vec3 farSide = new Vec3(
                        hitPoint.x * 2 - shapeCenter.x, hitPoint.y * 2 - shapeCenter.y, hitPoint.z * 2 - shapeCenter.z);
                Direction approxFace = Direction.getNearest(
                        (float) (hitPoint.x - shapeCenter.x), (float) (hitPoint.y - shapeCenter.y), (float) (hitPoint.z - shapeCenter.z));

                hits.add(new PartHitResult(entity, snap.entityKey(), part.boneId(),
                        resolveBoneName(boneTable, part.boneId()),
                        hitPoint, farSide, localUVW(part.localBox(), hitPoint, worldTransform),
                        hitPoint.distanceTo(farSide), approxFace));
            }
        }
        return hits.isEmpty() ? null : hits;
    }

    private static Vec3[] sampleBoxPoints(AABB box) {
        double x0 = box.minX, y0 = box.minY, z0 = box.minZ;
        double x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
        double xm = (x0 + x1) * 0.5, ym = (y0 + y1) * 0.5, zm = (z0 + z1) * 0.5;
        return new Vec3[]{
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1),
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1),
                new Vec3(xm, y0, z0), new Vec3(xm, y0, z1), new Vec3(xm, y1, z0), new Vec3(xm, y1, z1),
                new Vec3(x0, ym, z0), new Vec3(x1, ym, z0), new Vec3(x0, ym, z1), new Vec3(x1, ym, z1),
                new Vec3(x0, y0, zm), new Vec3(x1, y0, zm), new Vec3(x0, y1, zm), new Vec3(x1, y1, zm),
                new Vec3(xm, ym, z0), new Vec3(xm, ym, z1), new Vec3(xm, y0, zm), new Vec3(xm, y1, zm),
                new Vec3(x0, ym, zm), new Vec3(x1, ym, zm),
        };
    }

    // ── Shared ray-vs-part walk ──────────────────────────────────────────────

    private static void collectRayHits(Entity entity, EntityHitboxSnapshot snap, Vec3 origin, Vec3 dir,
                                       double maxDistance, List<PartHitResult> out) {
        Vec3 currentAnchor = currentAnchor(entity, snap);
        BoneTable boneTable = HitboxStore.getBoneTable(snap.entityKey());

        for (PartOBB part : snap.parts()) {
            Matrix4f worldTransform = new Matrix4f().translate(
                    (float) currentAnchor.x, (float) currentAnchor.y, (float) currentAnchor.z).mul(part.transform());
            Matrix4f worldToLocal = new Matrix4f(worldTransform).invert();

            Vector3f localOrigin = worldToLocal.transformPosition(
                    new Vector3f((float) origin.x, (float) origin.y, (float) origin.z));
            Vector3f localDir = worldToLocal.transformDirection(
                    new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));

            SlabResult slab = slabTest(part.localBox(), localOrigin, localDir);
            if (slab == null) continue;

            Vector3f localEntry = new Vector3f(localDir).mul(slab.tNear).add(localOrigin);
            Vector3f localExit = new Vector3f(localDir).mul(slab.tFar).add(localOrigin);
            Vector3f worldEntryV = worldTransform.transformPosition(new Vector3f(localEntry));
            Vector3f worldExitV = worldTransform.transformPosition(new Vector3f(localExit));
            Vec3 worldEntry = new Vec3(worldEntryV.x, worldEntryV.y, worldEntryV.z);
            Vec3 worldExit = new Vec3(worldExitV.x, worldExitV.y, worldExitV.z);

            if (worldEntry.distanceTo(origin) > maxDistance) continue;

            int faceBit = 1 << slab.enteredFace.ordinal();
            if ((part.faceMask() & faceBit) != 0) continue;

            if (part.flatFaceUV() != null
                    && (slab.enteredFace == part.flatFaceUV().face() || slab.enteredFace == part.flatFaceUV().face().getOpposite())) {
                ResourceLocation texture = snap.texture();
                if (texture != null) {
                    float[] uv = FaceGeometry.bilinearUV(part.localBox(), part.flatFaceUV().face(),
                            localEntry.x, localEntry.y, localEntry.z, part.flatFaceUV());
                    float opacity = TextureAlphaSampler.sampleOpacity(texture, uv[0], uv[1]);
                    if (opacity <= MahConfig.opacityThreshold) continue;
                }
            }

            out.add(new PartHitResult(entity, snap.entityKey(), part.boneId(),
                    resolveBoneName(boneTable, part.boneId()),
                    worldEntry, worldExit, localUVW(part.localBox(), worldEntry, worldTransform),
                    worldEntry.distanceTo(worldExit), slab.enteredFace));
        }
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private static String resolveBoneName(@Nullable BoneTable boneTable, short boneId) {
        if (boneTable == null) return "unknown";
        String name = boneTable.nameOf(boneId);
        return name != null ? name : "unknown";
    }

    private static Vec3 currentAnchor(Entity entity, EntityHitboxSnapshot snap) {
        Vec3 delta = entity.position().subtract(snap.capturedEntityPos());
        return snap.anchorPos().add(delta);
    }

    private record SlabResult(float tNear, float tFar, Direction enteredFace) {
    }

    @Nullable
    private static SlabResult slabTest(AABB box, Vector3f origin, Vector3f dir) {
        float tMin = Float.NEGATIVE_INFINITY, tMax = Float.POSITIVE_INFINITY;
        Direction enteredFace = null;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        double[] boxMin = {box.minX, box.minY, box.minZ};
        double[] boxMax = {box.maxX, box.maxY, box.maxZ};
        Direction[][] facePerAxis = {
                {Direction.WEST, Direction.EAST}, {Direction.DOWN, Direction.UP}, {Direction.NORTH, Direction.SOUTH}};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-8f) {
                if (o[axis] < boxMin[axis] || o[axis] > boxMax[axis]) return null;
                continue;
            }
            float t1 = (float) ((boxMin[axis] - o[axis]) / d[axis]);
            float t2 = (float) ((boxMax[axis] - o[axis]) / d[axis]);
            Direction faceAtT1 = facePerAxis[axis][0];
            if (t1 > t2) {
                float tmp = t1;
                t1 = t2;
                t2 = tmp;
                faceAtT1 = facePerAxis[axis][1];
            }
            if (t1 > tMin) {
                tMin = t1;
                enteredFace = faceAtT1;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }
        if (tMax < 0) return null;
        if (enteredFace == null) enteredFace = Direction.UP; // origin starts inside the box
        return new SlabResult(Math.max(tMin, 0), tMax, enteredFace);
    }

    private static Vec3 localUVW(AABB box, Vec3 worldPoint, Matrix4f worldTransform) {
        Matrix4f localToWorldInverse = new Matrix4f(worldTransform).invert();
        Vector3f local = localToWorldInverse.transformPosition(
                new Vector3f((float) worldPoint.x, (float) worldPoint.y, (float) worldPoint.z));
        double u = box.maxX > box.minX ? (local.x - box.minX) / (box.maxX - box.minX) : 0;
        double v = box.maxY > box.minY ? (local.y - box.minY) / (box.maxY - box.minY) : 0;
        double w = box.maxZ > box.minZ ? (local.z - box.minZ) / (box.maxZ - box.minZ) : 0;
        return new Vec3(u, v, w);
    }

    private static AABB worldBoundsOf(AABB localBox, Matrix4f worldTransform) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[] xs = {localBox.minX, localBox.maxX};
        double[] ys = {localBox.minY, localBox.maxY};
        double[] zs = {localBox.minZ, localBox.maxZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector3f w = worldTransform.transformPosition(new Vector3f((float) x, (float) y, (float) z));
                    minX = Math.min(minX, w.x);
                    minY = Math.min(minY, w.y);
                    minZ = Math.min(minZ, w.z);
                    maxX = Math.max(maxX, w.x);
                    maxY = Math.max(maxY, w.y);
                    maxZ = Math.max(maxZ, w.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private HitboxRaycaster() {
    }
}
