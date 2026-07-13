package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.blueprint.BlueprintResolver;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the precise, per-part collision shapes used by Phase 2D Part A2 in
 * place of vanilla's plain whole-body {@code AABB} for entities that are solid
 * ({@code canBeCollidedWith() == true} via {@code EntityCanBeCollidedWithMixin})
 * and have usable MAH geometry.
 *
 * <h3>Why shapes, not a custom depenetration solver</h3>
 * Vanilla's real hard-collision resolver ({@code Shapes.collide}, reached through
 * {@code EntityGetter.getEntityCollisions}) already does correct per-axis swept
 * resolution against a {@code List<VoxelShape>} — the same math used for terrain.
 * The only thing missing for a solid mob is shapes more precise than one crude
 * bounding box; this class supplies them (one {@link VoxelShape} per captured
 * part — see {@link #preciseShapesOrNull}'s own javadoc for why that's a flat
 * list rather than one merged shape), then gets out of the way and lets vanilla's
 * existing, already-correct collision math do the actual physics. {@code
 * VoxelShape} itself is fundamentally an axis-aligned box — there is no
 * rotated-box primitive to hand it — so every {@link PartOBB} is conservatively
 * bounded to a world-axis-aligned box. This is not an approximation forced by
 * laziness; it is the only shape vocabulary this system has, the same one block
 * collision itself uses.
 *
 * <h3>Snapshot policy</h3>
 * Deliberately more permissive than combat's {@code getCombatSnapshot} — a
 * slightly-stale-pose collision shape is a minor physical/visual inaccuracy, not
 * a fairness problem the way a wrong-pose combat hit would be, so this always
 * accepts a fresh <em>live</em> snapshot first, then falls back to a
 * blueprint-resolved one (essential on a real dedicated server, which never has
 * a live snapshot for anything) rather than refusing outright the way combat's
 * allowlist-gated fallback does.
 */
public final class SolidCollisionShapes {

    /**
     * Per-tick cache so multiple {@code getEntityCollisions} calls against the same
     * entity within one server tick (axis-by-axis movement resolution can call this
     * more than once per moving entity) don't rebuild the same shape list repeatedly.
     * Keyed by entity id + the snapshot's own {@code gameTime}, so a new snapshot
     * naturally invalidates the old cache entry without needing an explicit clear
     * hook. Unbounded growth isn't a real concern: entries are only ever created for
     * entities actually queried this tick, and the key changes every tick a live
     * snapshot updates, so stale entries simply stop being looked up and can be
     * garbage-collected under memory pressure like any other weakly-referenced-in-
     * spirit cache — an explicit eviction policy can be added later if profiling
     * ever shows this mattering.
     */
    private static final Map<Long, List<VoxelShape>> CACHE = new ConcurrentHashMap<>();

    /**
     * Single source of truth for "is this entity supposed to be solid," shared by
     * {@code EntityCanBeCollidedWithMixin} (which handles anything in the codebase
     * that checks {@code canBeCollidedWith()} directly) and
     * {@code EntityCollideEntityShapesMixin}'s candidate-gathering predicate (the
     * one that actually matters for movement collision — any entity subclass that
     * overrides {@code canBeCollidedWith()} itself, rather than inheriting
     * {@code Entity}'s base implementation, bypasses an injection placed only on
     * the base method, which is why that mixin doesn't rely on it alone).
     */
    public static boolean isSolidMob(Entity entity) {
        if (!MahConfig.solidCollisionEnabled) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(living.getType());
        return typeId == null || !MahConfig.solidCollisionBlacklist.contains(typeId);
    }

    /**
     * One un-merged {@link VoxelShape} per captured part of {@code entity}'s
     * geometry, in world space, or {@code null} if this entity should fall back to
     * vanilla's plain whole-body box — either because it's on
     * {@code solid_collision_blacklist}, has no usable MAH geometry right now, or
     * its snapshot has more parts than {@code solid_collision_max_parts} (a
     * cost-control cutoff, not a correctness one — see that config field's comment).
     *
     * <h3>Why a list of per-part shapes, not one merged shape</h3>
     * An earlier version of this method returned a single {@code VoxelShape},
     * building it by folding every part through {@code Shapes.or} — which, per
     * vanilla's own (bytecode-verified) implementation, is {@code Arrays.stream
     * (...).reduce(first, Shapes::or)}, and every individual {@code Shapes.or} call
     * is {@code join(...).optimize()} — a full boolean-merge-and-recompact pass, on
     * an accumulated shape whose complexity grows with every part folded in. For an
     * entity with dozens of parts, called from every nearby moving entity's own
     * {@code move()} every tick (not just the player's), this scales badly — exactly
     * the mechanism behind a reported near-total freeze from standing near/on a
     * cluster of solid mobs (schooling fish, dense reefs — precisely what
     * Aquamirae/Aquatica spawn in quantity, and neither is on any blacklist by
     * default).
     *
     * <p>The merge was never actually necessary. {@code Entity.collideBoundingBox}
     * (bytecode-verified) just concatenates whatever {@code List<VoxelShape>} it's
     * given with the world border shape and <i>every block's own individual
     * collision shape</i> — {@code Level.getBlockCollisions} already hands it many
     * small, entirely unmerged per-block shapes as a matter of course — before
     * sweeping the combined list once via {@code Shapes.collide}. A flat list of
     * this entity's own per-part shapes fits that exact same contract with zero
     * merging: cheap {@code Shapes.create(AABB)} per part, no {@code Shapes.or}, no
     * {@code optimize()}, no accumulated-complexity blowup.
     */
    @Nullable
    public static List<VoxelShape> preciseShapesOrNull(Entity entity) {
        if (!isSolidMob(entity)) {
            return null;
        }

        EntityHitboxSnapshot snapshot = resolveCollisionSnapshot(entity);
        if (snapshot == null) {
            return null;
        }

        return shapesFromSnapshot(entity, snapshot);
    }

    /**
     * Same per-part shape-building {@link #preciseShapesOrNull} always did, split out
     * so a caller that has already resolved a snapshot by some other means — e.g.
     * {@code EntityCollideEntityShapesMixin}'s extent-based candidate pass via
     * {@code MahApi.gatherExtentCandidates}, which resolves snapshots itself while
     * finding candidates in the first place — doesn't pay to re-resolve (and
     * re-check {@code isSolidMob}/part-count) a snapshot it already has in hand.
     * Returns {@code null} under the same conditions {@link #preciseShapesOrNull}
     * always did (empty parts, over the part cap) so both callers see one
     * consistent cutoff policy.
     */
    @Nullable
    public static List<VoxelShape> shapesFromSnapshot(Entity entity, EntityHitboxSnapshot snapshot) {
        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty() || parts.size() > MahConfig.solidCollisionMaxParts) {
            return null;
        }

        long cacheKey = (((long) entity.getId()) << 32) ^ (snapshot.gameTime() & 0xFFFFFFFFL);
        List<VoxelShape> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Vec3 currentAnchor = currentAnchor(entity, snapshot);
        List<VoxelShape> shapes = new ArrayList<>(parts.size());
        for (PartOBB part : parts) {
            AABB worldBounds = worldBoundsOf(part, currentAnchor);
            shapes.add(Shapes.create(worldBounds));
        }
        List<VoxelShape> result = List.copyOf(shapes);

        CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * Same fallback chain as {@code MahApi.getCombatSnapshot}, parameterized on
     * {@code MahConfig.solidCollisionStaleSnapshotTicks} instead of the combat
     * threshold, but without combat's allowlist gate on the blueprint fallback —
     * see this class's javadoc for why collision is deliberately more permissive.
     * Duplicated locally (rather than reusing {@code getCombatSnapshot} or adding a
     * new overload to {@code MahApi}) to avoid changing that combat-specific method's
     * contract for an unrelated caller; both this and {@code getCombatSnapshot} are
     * small enough that the duplication is cheap to keep in sync if the underlying
     * {@code HitboxStore}/{@code BlueprintResolver} contracts ever change.
     */
    @Nullable
    public static EntityHitboxSnapshot resolveCollisionSnapshot(Entity entity) {
        if (MahApi.hasFreshSnapshot(entity, MahConfig.solidCollisionStaleSnapshotTicks)) {
            return HitboxStore.get(entity.getUUID());
        }
        return BlueprintResolver.resolve(entity);
    }

    /**
     * Same anchor-tracking convention {@code HitboxRaycaster} uses internally.
     * Delegates to {@code MahApi.currentAnchorFor} — factored out there when
     * {@code synthesizePartSnapshot} and {@code gatherExtentCandidates} turned out
     * to need the identical formula (see
     * {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §5.3); this class's own
     * previously-separate copy is retired in favor of that one shared definition.
     */
    private static Vec3 currentAnchor(Entity entity, EntityHitboxSnapshot snap) {
        return MahApi.currentAnchorFor(entity, snap);
    }

    /** World-axis-aligned conservative bound of one part's OBB — see class javadoc for why AABB, not a true OBB. */
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

    // TEMP DIAGNOSTIC — mirrors the checks in isSolidMob/preciseShapesOrNull so the
    // reported reason is guaranteed to match what those methods actually decided,
    // rather than a second, possibly-drifted copy of the logic. Remove once resolved.
    public static String khp$diagnoseFallbackReason(Entity entity) {
        if (!MahConfig.solidCollisionEnabled) return "solid_collision_enabled=false";
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return "not a living/alive entity";
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(living.getType());
        if (typeId != null && MahConfig.solidCollisionBlacklist.contains(typeId)) return "blacklisted (" + typeId + ")";

        EntityHitboxSnapshot snapshot = resolveCollisionSnapshot(entity);
        if (snapshot == null) {
            // TEMP DIAGNOSTIC — pull whatever's in HitboxStore directly, bypassing
            // hasFreshSnapshot's staleness gate entirely, so we can see the RAW
            // gameTime values on both sides even when the snapshot was rejected as
            // stale. If storedGameTime is a real, recent-looking number but
            // (currentGameTime - storedGameTime) is huge/negative/nonsensical, the
            // client and server game-time counters are offset from each other —
            // that's the bug, not staleness itself. Compare the uuid= here against
            // a "publishing live snapshot" line's uuid= for the same real mob, and
            // compare currentGameTime here against gameTime there.
            EntityHitboxSnapshot rawStored = HitboxStore.get(entity.getUUID());
            long currentGameTime = entity.level().getGameTime();
            String rawDesc = rawStored == null ? "HitboxStore has no entry at all for this uuid"
                    : "HitboxStore HAS an entry: storedGameTime=" + rawStored.gameTime()
                    + ", currentGameTime(this thread)=" + currentGameTime
                    + ", delta=" + (currentGameTime - rawStored.gameTime())
                    + ", solidCollisionStaleSnapshotTicks=" + MahConfig.solidCollisionStaleSnapshotTicks
                    + ", storedParts=" + rawStored.parts().size();
            return "no snapshot resolvable (no live capture AND no blueprint) — "
                    + "queried uuid=" + entity.getUUID() + ", HitboxStore.view().size()="
                    + HitboxStore.view().size() + " from this thread — " + rawDesc;
        }

        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty()) return "snapshot has zero parts (source=" + snapshot.source() + ")";
        if (parts.size() > MahConfig.solidCollisionMaxParts) {
            return "part count " + parts.size() + " exceeds solid_collision_max_parts="
                    + MahConfig.solidCollisionMaxParts + " (source=" + snapshot.source() + ")";
        }
        return "unknown — preciseShapesOrNull returned null despite passing all known checks; "
                + "snapshot source=" + snapshot.source() + ", parts=" + parts.size();
    }

    private SolidCollisionShapes() {
    }
}
