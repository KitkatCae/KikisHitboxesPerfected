package exp.CCnewmods.mah.capture;

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
 * @param localBox  cube bounds in part space, block units (model units / 16).
 *                  NOTE (phase-2 TODO): for vanilla cubes these come from
 *                  {@code ModelPart.Cube}'s public min/max fields, which exclude the
 *                  CubeDeformation "grow" inflation (up to 0.5 model units on overlay
 *                  layers). Switching to polygon-derived bounds needs an
 *                  AccessTransformer with SRG names for Cube.polygons /
 *                  Polygon.vertices / Vertex.pos.
 * @param transform part space → entity-local render space. Treat as immutable.
 * @param boneId    index into the entity key's {@link BoneTable}.
 * @param faceMask  bit per face (Direction ordinal order): 1 = fully transparent,
 *                  rays pass through. Always 0 in phase 1; filled by the phase-2
 *                  TransparencyAnalyzer.
 */
public record PartOBB(AABB localBox, Matrix4f transform, short boneId, byte faceMask) {
}
