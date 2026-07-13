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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Raycast/shapecast against captured {@link PartOBB} geometry — the actual consumer
 * of {@code faceMask} and {@link PartOBB.FlatFaceUV} everything since Phase 1 has
 * been building toward.
 *
 * <h3>Candidate entities are supplied by the caller, not looked up here</h3>
 * This class deliberately never reads {@link HitboxStore} for the world-scan entry
 * points ({@link #raycastWorld}/{@link #shapecast}) or {@link #raycastEntity} —
 * candidates and their resolved {@link EntityHitboxSnapshot} come in as a
 * caller-supplied map. This is what lets a snapshot be either a live capture or a
 * resolved blueprint transparently: merging those two producers requires calling
 * into {@code exp.CCnewmods.khp.blueprint.BlueprintResolver}, which this
 * ({@code capture}) package must not depend on (blueprint depends on capture and
 * api, not the reverse — see {@code KHP_Headless_Resolver_Design_v2.md}). The merge
 * lives in {@code exp.CCnewmods.khp.api.MahApi#getSnapshot}, one layer up; this
 * class only ever tests whatever snapshot it's handed.
 *
 * <h3>World transform at query time</h3>
 * Per {@link PartOBB}'s own javadoc: {@code translate(anchorPos + (entity.position() -
 * capturedEntityPos)) * transform}. A ray/shape in world space is tested by inverting
 * that per-part matrix and working in the part's own local space instead — cheaper
 * than transforming a box into world space per query, and reuses the same
 * "entity tracked between captures via position delta" convention the debug renderer
 * already relies on.
 *
 * <h3>Per-part test order</h3>
 * <ol>
 *   <li>Ray/AABB slab test in local space (fast rejection).</li>
 *   <li>If the entered face's {@code faceMask} bit is set (fully transparent) — miss,
 *       this part doesn't count, keep checking other parts.</li>
 *   <li>If the part has a {@link PartOBB.FlatFaceUV} for the entered face (or its
 *       opposite — front/back of a thin plane are the same physical surface) — Option
 *       B: sample the real texture at the exact local intersection point via
 *       {@link FaceGeometry#bilinearUV}; at/below threshold — miss.</li>
 *   <li>Otherwise — real hit.</li>
 * </ol>
 */
public final class HitboxRaycaster {

    // TEMPORARY diagnostic — see debug_opacity_watchlist's config comment and
    // collectRayHits' own use of this logger. Not meant to ship long-term.
    private static final Logger RAYCAST_DEBUG_LOGGER = LoggerFactory.getLogger("MAH/raycast-debug");

    /**
     * @param candidates every entity to test, mapped to its already-resolved
     *                   snapshot (live or blueprint — see class javadoc). Building
     *                   this map is the caller's job: gather real level entities in
     *                   range, resolve each via {@code MahApi.getSnapshot}, skip any
     *                   that resolve to {@code null}. Entity filtering (blacklist,
     *                   distance, a consumer-supplied predicate) happens while
     *                   building this map, not in here.
     */
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

    /** @param snap already-resolved (live or blueprint) — see class javadoc. {@code null} short-circuits to no hits. */
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
     * Every part any entity's captured geometry overlaps {@code shape} — for
     * shockwaves, breath attacks, expanding rings, anything that needs to hit
     * everything a volume touches at once instead of sweeping hundreds of raycasts.
     * An <i>expanding</i> shape is expressed by the caller re-calling this each tick
     * with updated shape parameters, same as {@link #raycastWorld} takes one
     * origin/direction snapshot rather than a moving ray — this interface doesn't
     * animate anything itself.
     *
     * <h3>Precision note</h3>
     * Overlap is decided by broad-phase AABB rejection ({@code shape.boundingBox()}
     * vs. the part's world bounds) followed by testing 26 sample points on the
     * part's actual oriented box — 8 corners, 12 edge midpoints, 6 face centers —
     * against {@code shape.contains(...)}. Much more precise than a single
     * center-point or bounding-box-only test (correctly excludes a part sitting in a
     * torus/ring's empty hole, for instance), still far cheaper than sweeping
     * raycasts across the part's surface, but not a true analytic OBB/shape
     * intersection — a part small enough to fit entirely between two of the 26
     * sample points relative to a thin shape (a narrow ring, a slim cone) could
     * theoretically be missed. Flagged here rather than assumed away; worth revisiting
     * with denser sampling for a specific consumer if that turns out to matter in
     * practice.
     *
     * <p>{@code worldExitPoint}/{@code faceHit} on results are best-effort (no single
     * ray direction to be exit-aware about) — derived from the line between the
     * shape's bounding-box center and the part's closest sample point, not a true
     * directional exit.
     *
     * @param candidates same contract as {@link #raycastWorld}'s parameter.
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

    /** 8 corners, 12 edge midpoints, 6 face centers of a local AABB — the sample set {@link #shapecast} tests. */
    private static Vec3[] sampleBoxPoints(AABB box) {
        double x0 = box.minX, y0 = box.minY, z0 = box.minZ;
        double x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
        double xm = (x0 + x1) * 0.5, ym = (y0 + y1) * 0.5, zm = (z0 + z1) * 0.5;
        return new Vec3[]{
                // corners
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1),
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1),
                // edge midpoints
                new Vec3(xm, y0, z0), new Vec3(xm, y0, z1), new Vec3(xm, y1, z0), new Vec3(xm, y1, z1),
                new Vec3(x0, ym, z0), new Vec3(x1, ym, z0), new Vec3(x0, ym, z1), new Vec3(x1, ym, z1),
                new Vec3(x0, y0, zm), new Vec3(x1, y0, zm), new Vec3(x0, y1, zm), new Vec3(x1, y1, zm),
                // face centers
                new Vec3(xm, ym, z0), new Vec3(xm, ym, z1), new Vec3(xm, y0, zm), new Vec3(xm, y1, zm),
                new Vec3(x0, ym, zm), new Vec3(x1, ym, zm),
        };
    }

    // ── Shared ray-vs-part walk ──────────────────────────────────────────────

    /**
     * Freshness is decided once, upstream, by {@code MahApi.getSnapshot} — not here.
     * This used to reject stale snapshots internally, but that's now wrong: a stale
     * live snapshot reaching this method is deliberately here because
     * {@code getSnapshot} had nothing better (no fresh live, no blueprint available)
     * and chose "stale is better than nothing" — silently discarding it at this
     * point would defeat that fallback entirely. A blueprint-resolved snapshot is
     * never stale by construction (synthesized with the current game time on every
     * call), so it was never at risk from this check either way.
     */
    private static void collectRayHits(Entity entity, EntityHitboxSnapshot snap, Vec3 origin, Vec3 dir,
                                       double maxDistance, List<PartHitResult> out) {
        Vec3 currentAnchor = currentAnchor(entity, snap);
        BoneTable boneTable = HitboxStore.getBoneTable(snap.entityKey());

        // TEMPORARY diagnostic (MahConfig.debugOpacityWatchlist): one Set.isEmpty()
        // check per call, effectively free when the watchlist is empty (the
        // default) — see debug_opacity_watchlist's own config comment for what this
        // traces and why it's separate from debug_render. Not meant to ship long-term;
        // this is the raycast-time counterpart to the capture-time-only tracing
        // debug_render/flatDebug/opacity-debug already provided (see
        // KHP_Phase2A_Handoff.md §6 — none of the existing debug output actually
        // covers what a real attack's raycast decided, only what capture built).
        boolean debug = !MahConfig.debugOpacityWatchlist.isEmpty()
                && MahConfig.debugOpacityWatchlist.contains(snap.entityKey());

        for (PartOBB part : snap.parts()) {
            // Resolved once per part rather than baked into resolveBoneName's own
            // call below — that one only runs on an actual out.add() (a real hit),
            // but the debug trace needs a name on every branch, hit or miss.
            String debugBoneName = debug ? resolveBoneName(boneTable, part.boneId()) : null;

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
            if ((part.faceMask() & faceBit) != 0) {
                if (debug) {
                    RAYCAST_DEBUG_LOGGER.info(
                            "MAH raycast-debug: boneId={} boneName={} enteredFace={} faceMask={} -> MISS (faceMask bit set, fully transparent face)",
                            part.boneId(), debugBoneName, slab.enteredFace, Integer.toBinaryString(part.faceMask() & 0xFF));
                }
                continue; // fully transparent face — miss
            }

            if (part.flatFaceUV() != null
                    && (slab.enteredFace == part.flatFaceUV().face() || slab.enteredFace == part.flatFaceUV().face().getOpposite())) {
                ResourceLocation texture = snap.texture();
                if (texture != null) {
                    float[] uv = FaceGeometry.bilinearUV(part.localBox(), part.flatFaceUV().face(),
                            localEntry.x, localEntry.y, localEntry.z, part.flatFaceUV());
                    float opacity = TextureAlphaSampler.sampleOpacity(texture, uv[0], uv[1]);
                    boolean isMiss = opacity <= MahConfig.opacityThreshold;
                    if (debug) {
                        RAYCAST_DEBUG_LOGGER.info(
                                "MAH raycast-debug: boneId={} boneName={} enteredFace={} texture={} uv=({}, {}) opacity={} threshold={} -> {}",
                                part.boneId(), debugBoneName, slab.enteredFace, texture, uv[0], uv[1], opacity,
                                MahConfig.opacityThreshold, isMiss ? "MISS (Option B, at/below threshold)" : "HIT");
                    }
                    if (isMiss) continue; // Option B: real pixel is transparent — miss
                } else if (debug) {
                    RAYCAST_DEBUG_LOGGER.info(
                            "MAH raycast-debug: boneId={} boneName={} enteredFace={} flatFaceUV present but snapshot texture is NULL -> Option B skipped, treated as opaque (HIT)",
                            part.boneId(), debugBoneName, slab.enteredFace);
                }
            }

            if (debug) {
                RAYCAST_DEBUG_LOGGER.info("MAH raycast-debug: boneId={} boneName={} enteredFace={} -> HIT (no faceMask/flatFaceUV denial)",
                        part.boneId(), debugBoneName, slab.enteredFace);
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
        if (enteredFace == null) enteredFace = Direction.UP; // origin starts inside the box — no real entry face
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
