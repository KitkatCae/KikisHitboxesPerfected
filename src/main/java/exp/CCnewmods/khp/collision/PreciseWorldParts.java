package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared geometry source for Phase 2D Part A3 (precise block/terrain collision) and
 * Phase 2D Part B (precise culling). Both features need the same thing — an entity's
 * captured parts as world-space {@link AABB}s — so it lives in one place rather than
 * being reimplemented per feature, the same way {@code SolidCollisionShapes} already
 * factors this out for Part A2's entity-vs-entity shapes.
 *
 * <h3>Why not reuse SolidCollisionShapes directly</h3>
 * {@code SolidCollisionShapes.isSolidMob} gates on {@code solid_collision_enabled} +
 * a blacklist — a deliberately different, narrower population than this class, which
 * (per design sign-off) applies to <b>any</b> living entity with a resolvable snapshot,
 * no blacklist. Folding both populations through one gate would make the broader
 * features (collision, culling) implicitly depend on the narrower one's config, which
 * is not the intended relationship. The two classes deliberately duplicate the small
 * amount of per-tick-cache/world-bounds math involved rather than share a gate that
 * would couple unrelated config surfaces.
 *
 * <h3>Resolution chain</h3>
 * Uses {@link MahApi#getSnapshot(Entity)} directly — the same live-then-blueprint
 * chain every other consumer goes through, gated by the one shared
 * {@code MahConfig.staleSnapshotTicks} window ("the same distance/staleness cutoff we
 * already have," per design sign-off) rather than a new feature-specific threshold.
 * On a dedicated server this naturally resolves via blueprint only, same as combat and
 * Part A2 collision already do.
 */
public final class PreciseWorldParts {

    /**
     * Per-tick cache, same rationale and key shape as {@code SolidCollisionShapes.CACHE}:
     * keyed by entity id + snapshot gameTime + the caller's maxParts cutoff (collision
     * and culling use different caps, so a shared key would let one feature's cap
     * silently serve stale/wrong-sized results to the other).
     */
    private static final Map<Long, List<AABB>> CACHE = new ConcurrentHashMap<>();

    /**
     * World-space AABB per captured part, or {@code null} if this entity should fall
     * back to vanilla's whole-body box — no snapshot resolvable, empty part list, or
     * more parts than {@code maxParts} (cost-control cutoff, not correctness).
     */
    @Nullable
    public static List<AABB> resolveOrNull(Entity entity, int maxParts) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return null;
        }

        EntityHitboxSnapshot snapshot = MahApi.getSnapshot(living);
        if (snapshot == null) {
            return null;
        }

        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty() || parts.size() > maxParts) {
            return null;
        }

        long cacheKey = (((long) entity.getId()) << 40)
                ^ ((snapshot.gameTime() & 0xFFFFFL) << 20)
                ^ (maxParts & 0xFFFFF);
        List<AABB> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Vec3 currentAnchor = MahApi.currentAnchorFor(entity, snapshot);
        List<AABB> boxes = new ArrayList<>(parts.size());
        for (PartOBB part : parts) {
            boxes.add(worldBoundsOf(part, currentAnchor));
        }
        List<AABB> result = List.copyOf(boxes);

        CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * Union of all boxes — used as the query region for gathering obstacle shapes,
     * and by {@code AabbResize} as the LIVE-mode resized box.
     */
    public static AABB union(List<AABB> boxes) {
        AABB result = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            result = result.minmax(boxes.get(i));
        }
        return result;
    }

    /** Same conservative-AABB-of-a-transformed-box approach as SolidCollisionShapes#worldBoundsOf. */
    private static AABB worldBoundsOf(PartOBB part, Vec3 currentAnchor) {
        Matrix4f worldTransform = new Matrix4f().translate(
                (float) currentAnchor.x, (float) currentAnchor.y, (float) currentAnchor.z).mul(part.transform());

        AABB localBox = part.localBox();
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

    private PreciseWorldParts() {
    }
}
