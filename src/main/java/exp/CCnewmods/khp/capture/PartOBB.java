package exp.CCnewmods.khp.capture;

import exp.CCnewmods.khp.MahConfig;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * One captured cube of model geometry as an oriented bounding box in
 * <b>entity-local render space</b>.
 *
 * <h3>Coordinate contract</h3>
 * {@code transform} maps part space → entity-local render space, where the origin is
 * the entity's render anchor ({@link EntityHitboxSnapshot#anchorPos()}). It is the
 * exact render transform captured downstream of every scale/pose/rotation the renderer
 * applied — baby scale, {@code scaleModelForRender}, sleeping offsets, animation, all
 * of it. <b>No margins are added and no transforms are reconstructed</b>; this is the
 * matrix the vertices were (or would be) multiplied by, re-based to the entity.
 *
 * <p>World transform at query time:
 * {@code translate(anchorPos + (entity.position() - capturedEntityPos)) * transform}.
 *
 * <h3>Immutability</h3>
 * {@code transform} and {@code localBox} must never be mutated after construction —
 * instances are shared across threads (render thread writes, game/server thread reads
 * on the integrated server) and a single transform instance is shared by all cubes of
 * the same vanilla model part.
 *
 * <h3>Minimum thickness — every construction path, unconditionally</h3>
 * The compact constructor pads {@code localBox} (symmetrically about its own center)
 * so no axis is ever below {@code MahConfig#minPartThickness}. This is deliberately
 * enforced here rather than in any one capture path (e.g.
 * {@code HitboxCaptureManager#finalizeStructuredPart}) — every producer of a
 * {@code PartOBB} (vanilla, GeckoLib, Citadel, LionfishAPI, raw-vertex capture,
 * external pre-computed parts, {@code MahApi#synthesizePartSnapshot}'s single-bone
 * reboning, blueprint resolution) goes through this one constructor, so none of them
 * can reintroduce a genuinely zero-thickness box by skipping a check that only lived
 * in one specific call site. A real, mundane cause: a wing/fin/membrane cube authored
 * with literal zero depth (single flat quad) is common third-party modeling practice,
 * not a modeling mistake — but {@link HitboxRaycaster}'s slab test needs a real,
 * non-degenerate range on every axis to work reliably. A perfectly flat box is only
 * solvable there when the ray's local-space direction on the thin axis is non-trivial;
 * whenever a player aims roughly <i>parallel</i> to that plane (routine for a
 * horizontal fin during ordinary level-aimed melee, not an edge case), the slab test's
 * near-parallel branch demands the ray origin's local coordinate match the box's
 * single degenerate value exactly — never true for a real float in practice, so the
 * part becomes essentially unhittable from that whole family of aim angles. Padding to
 * a small real thickness up front removes the degeneracy at the source instead of
 * teaching the raycast math to special-case it. See
 * {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §13 for the full writeup.
 *
 * <p>Option A/B's opaque-region tightening always runs against the box's true,
 * pre-padding dimensions, unconditionally regardless of size — padding happens here,
 * at final construction, strictly after that tightening has already been attempted.
 *
 * @param localBox  cube bounds in part space, block units (model units / 16), after
 *                  the minimum-thickness padding described above.
 *                  NOTE (phase-2 TODO): for vanilla cubes these come from
 *                  {@code ModelPart.Cube}'s public min/max fields, which exclude the
 *                  CubeDeformation "grow" inflation (up to 0.5 model units on overlay
 *                  layers). Switching to polygon-derived bounds needs an
 *                  AccessTransformer with SRG names for Cube.polygons /
 *                  Polygon.vertices / Vertex.pos.
 * @param transform part space → entity-local render space. Treat as immutable.
 * @param boneId    index into the entity key's {@link BoneTable}.
 * @param faceMask  bit per face (Direction ordinal order): 1 = fully transparent,
 *                  rays pass through. Computed for vanilla, GeckoLib, and Citadel
 *                  cubes (Phase 2c); still always 0 for every other capture path.
 * @param flatFaceUV null for the overwhelming majority of parts; present only for
 *                  parts whose thin-axis face had a genuinely mixed opaque/
 *                  transparent region (wings, membranes, sails) where
 *                  {@code localBox} has already been tightened to the opaque
 *                  region's bounds (Option A) and the raycast API additionally
 *                  per-pixel-tests ambiguous hits against the real texture at query
 *                  time (Option B) rather than trusting the tightened box alone.
 */
public record PartOBB(AABB localBox, Matrix4f transform, short boneId, byte faceMask, FlatFaceUV flatFaceUV) {

    public PartOBB {
        localBox = padToMinimumThickness(localBox, MahConfig.minPartThickness);
    }

    private static AABB padToMinimumThickness(AABB box, double minThickness) {
        double halfMin = minThickness / 2.0;
        double origHalfX = (box.maxX - box.minX) / 2.0;
        double origHalfY = (box.maxY - box.minY) / 2.0;
        double origHalfZ = (box.maxZ - box.minZ) / 2.0;
        double halfX = Math.max(origHalfX, halfMin);
        double halfY = Math.max(origHalfY, halfMin);
        double halfZ = Math.max(origHalfZ, halfMin);
        // No-op for any box already at or above the floor on every axis — the
        // overwhelming majority of parts, most of the time — skip the allocation.
        if (halfX == origHalfX && halfY == origHalfY && halfZ == origHalfZ) {
            return box;
        }
        double cx = (box.minX + box.maxX) / 2.0, cy = (box.minY + box.maxY) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
        return new AABB(cx - halfX, cy - halfY, cz - halfZ, cx + halfX, cy + halfY, cz + halfZ);
    }

    /**
     * @param face relevant face of {@code localBox} the UV mapping below is for —
     *             for a flat part this is the face on the thin axis (front/back are
     *             the same physical plane for this purpose, either normal works).
     * @param u0,v0..u3,v3 UV at each of {@link FaceGeometry#faceCorners}'s four
     *             corners, in that same order — see {@link FaceGeometry}'s class
     *             javadoc for why all four (not just two diagonal corners) are
     *             needed for a correct bilinear mapping at raycast query time.
     */
    public record FlatFaceUV(Direction face,
                              float u0, float v0, float u1, float v1,
                              float u2, float v2, float u3, float v3) {
    }
}
