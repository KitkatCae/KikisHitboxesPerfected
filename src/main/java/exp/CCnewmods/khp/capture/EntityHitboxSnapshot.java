package exp.CCnewmods.khp.capture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Immutable snapshot of one entity's captured hitbox geometry.
 *
 * <p>Safe to hand across threads: all fields are final and {@link #parts} is an
 * unmodifiable copy whose elements honour the {@link PartOBB} immutability contract.
 *
 * @param entityKey          the entity key (usually the EntityType id; corpses key by
 *                           mob type via a registered EntityKeyProvider) — identifies
 *                           which {@link BoneTable} resolves {@link PartOBB#boneId()}.
 * @param entityId           network entity id, for cheap {@code Level.getEntity(int)}
 *                           lookups by consumers that only hold the snapshot.
 * @param anchorPos          world position of the render origin at capture time
 *                           (lerped entity position + renderer offset).
 * @param capturedEntityPos  {@code entity.position()} at capture time. Consumers track
 *                           the entity between captures via
 *                           {@code entity.position() - capturedEntityPos} added to
 *                           {@code anchorPos}.
 * @param bodyYaw            body yaw at capture (degrees) — used by later phases to
 *                           re-rotate blueprint geometry; recorded for live snapshots
 *                           for diagnostics/consistency.
 * @param gameTime           level game time at capture; staleness is judged against
 *                           {@code MahConfig.staleSnapshotTicks}.
 * @param parts              captured OBBs, unmodifiable.
 * @param localBounds        union {@link AABB} of every part's world-space corners
 *                           (i.e. {@code part.localBox()} corners transformed by
 *                           {@code part.transform()}), in the same entity-local
 *                           render space {@link #anchorPos} is the origin of —
 *                           <b>not</b> a per-part box, the envelope of the whole
 *                           snapshot. Computed once, at publish time
 *                           ({@code HitboxCaptureManager#exitBracket} for a live
 *                           snapshot, {@code BlueprintResolver#resolve} for a
 *                           resolved one), from the finished {@link #parts} list —
 *                           see {@code KHP_Snapshot_Extent_Candidate_Search_Design.md}
 *                           §4.1/§7.1. This is what lets candidate search key off an
 *                           entity's <i>real captured reach</i> instead of a fixed
 *                           world-space margin: a snake/worm/tentacled body whose
 *                           geometry legitimately extends far past its registered
 *                           vanilla bounding box is found because this box says so,
 *                           not because some constant happened to be big enough.
 *                           Zero-size at the origin for a snapshot with no parts
 *                           (shouldn't occur in practice — degrades to "no extra
 *                           reach beyond the entity's own position," never worse
 *                           than pre-this-field behavior).
 * @param source             fidelity class of this snapshot.
 * @param texture            the texture this entity rendered with at capture time —
 *                           resolved once per bracket in {@code HitboxCaptureManager}.
 *                           Only actually consumed by flat parts' Option B per-pixel
 *                           raycast test; {@code null} for entities/renderer kinds
 *                           where resolution failed (raycast falls back to trusting
 *                           the box, same as any other unreadable-texture case).
 */
public record EntityHitboxSnapshot(ResourceLocation entityKey,
                                   int entityId,
                                   Vec3 anchorPos,
                                   Vec3 capturedEntityPos,
                                   float bodyYaw,
                                   long gameTime,
                                   List<PartOBB> parts,
                                   AABB localBounds,
                                   SnapshotSource source,
                                   ResourceLocation texture) {

    public EntityHitboxSnapshot {
        parts = List.copyOf(parts);
        if (localBounds == null) localBounds = new AABB(Vec3.ZERO, Vec3.ZERO);
    }

    /** True if this snapshot is older than the given staleness window. */
    public boolean isStale(long now, int staleTicks) {
        return now - gameTime > staleTicks;
    }

    /**
     * {@link #localBounds} translated to world space by {@code currentAnchor} — the
     * entity's render anchor <i>right now</i>, drift-corrected from
     * {@link #anchorPos}/{@link #capturedEntityPos} the same way every other
     * consumer of this snapshot already does (see {@code MahApi#currentAnchorFor}).
     * This, not {@link #localBounds} directly, is what candidate search tests
     * against a query shape's world-space bounds.
     */
    public AABB worldBounds(Vec3 currentAnchor) {
        return localBounds.move(currentAnchor);
    }

    /**
     * Folds the union {@link AABB} (in entity-local render space) of every part in
     * {@code parts} — all 8 corners of each part's {@code localBox}, transformed by
     * that part's own {@code transform}. Shared by both publish points (live capture
     * and blueprint resolution) so the two producers can never compute this
     * differently. Returns a zero-size box at the origin for an empty list.
     */
    public static AABB computeLocalBounds(List<PartOBB> parts) {
        if (parts.isEmpty()) return new AABB(Vec3.ZERO, Vec3.ZERO);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (PartOBB part : parts) {
            AABB box = part.localBox();
            Matrix4f transform = part.transform();
            for (int cx = 0; cx < 2; cx++) {
                for (int cy = 0; cy < 2; cy++) {
                    for (int cz = 0; cz < 2; cz++) {
                        float lx = (float) (cx == 0 ? box.minX : box.maxX);
                        float ly = (float) (cy == 0 ? box.minY : box.maxY);
                        float lz = (float) (cz == 0 ? box.minZ : box.maxZ);
                        Vector3f world = transform.transformPosition(new Vector3f(lx, ly, lz));
                        if (world.x < minX) minX = world.x;
                        if (world.y < minY) minY = world.y;
                        if (world.z < minZ) minZ = world.z;
                        if (world.x > maxX) maxX = world.x;
                        if (world.y > maxY) maxY = world.y;
                        if (world.z > maxZ) maxZ = world.z;
                    }
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
