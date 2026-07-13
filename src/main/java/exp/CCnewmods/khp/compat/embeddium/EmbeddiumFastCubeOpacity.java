package exp.CCnewmods.khp.compat.embeddium;

import exp.CCnewmods.khp.capture.FaceGeometry;
import exp.CCnewmods.khp.capture.PartOBB;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import me.jellysquid.mods.sodium.client.model.ModelCuboidAccessor;
import me.jellysquid.mods.sodium.client.render.immediate.model.ModelCuboid;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Computes real {@code faceMask}/{@code flatFaceUV} for a vanilla cube directly from
 * Embeddium's own pre-baked {@link ModelCuboid} data, for the specific case {@code
 * HitboxCaptureManager#captureVanillaCubes} needs to handle: a cube Embeddium is going
 * to render via its "fast" path ({@code EntityRenderer.renderCuboidFast}), which
 * never calls {@code ModelPart.Cube.compile()} at all — meaning {@code
 * CubeOpacityRecorder}'s per-vertex {@code VertexConsumer} interception (Phase 2c)
 * never gets a chance to run, silently leaving {@code faceMask}/{@code flatFaceUV} at
 * their zero/null capture-time placeholders forever. This class exists so those
 * cubes still get real opacity data instead, by reading the same box/UV fields
 * Embeddium's own {@code EntityRenderer} already computed and cached — no
 * {@code VertexConsumer} interception needed at all for this path.
 *
 * <h3>Isolation</h3>
 * This is the <b>only</b> class in KHP that references {@code ModelCuboidAccessor}/
 * {@code ModelCuboid} directly — same isolation convention as every other optional
 * compat class (Citadel, LionfishAPI, Mowzie's, Ice and Fire). Callers must confirm
 * Embeddium is actually loaded (see {@code HitboxCaptureManager}'s own cached
 * presence check) before ever calling into this class, so this class's
 * Embeddium-referencing bytecode is never verified/loaded at all when Embeddium is
 * absent.
 *
 * <h3>Six-face mapping — corrected, cross-verified against two independent sources</h3>
 * {@code EntityRenderer}'s own {@code FACE_NEG_X}/{@code FACE_POS_X} constant names
 * (quad indices 4/5) do <b>not</b> match their actual geometric assignment —
 * {@code CUBE_VERTICES[4]} uses every corner's {@code x2} (high-X) field and {@code
 * CUBE_VERTICES[5]} uses every corner's {@code x1} (low-X), which is the reverse of
 * what those constant names imply. Confirmed two ways this session: (1)
 * {@code prepareNormals} itself populates {@code CUBE_NORMALS[4]} with {@code
 * Direction.EAST} and {@code CUBE_NORMALS[5]} with {@code Direction.WEST} — opposite
 * of the constant names — and (2) cross-checked independently against vanilla's own
 * {@code ModelPart.Cube} constructor, which builds its {@code Direction.WEST}
 * polygon entirely from the low-x vertices. Both agree: quad index 4 is EAST, index 5
 * is WEST. An earlier version of this class trusted the constant names and had these
 * swapped — real per-face UV rects were still computed correctly, just attached to
 * the wrong {@link Direction}, which matters wherever {@code shouldDrawFace}'s
 * per-face gating or {@code faceMask}'s bit position differs between the two faces.
 *
 * <h3>Scope: non-mirrored cubes only for {@code flatFaceUV}</h3>
 * {@code faceMask} (fully-transparent-or-not) is direction-of-winding-independent —
 * correct regardless of {@link ModelCuboid#mirror}. {@code flatFaceUV} (Option A's
 * capture-time box tightening, Option B's raycast-time per-pixel precision on
 * <i>partially</i> transparent faces) needs an exact corner-to-UV correspondence,
 * which was derived and verified against {@code EntityRenderer.prepareVertices}/
 * {@code emitQuads}'s bytecode for the non-mirrored case only — a mirrored cube swaps
 * {@code ModelCuboid#x1}/{@code x2} at construction time (confirmed via decompile),
 * which flips which corner maps to which UV corner on every face whose position
 * varies in X (i.e. every face except WEST/EAST themselves), and that flip wasn't
 * re-derived with the same rigor this session. Mirrored cubes therefore still get a
 * correct {@code faceMask} but no {@code flatFaceUV} (falls back to "treated as fully
 * solid on a partially-transparent face" — today's existing behavior for those
 * specific cubes, not a regression) until that derivation gets done properly.
 */
public final class EmbeddiumFastCubeOpacity {

    /**
     * Everything {@code HitboxCaptureManager#captureVanillaCubes} needs for a cube
     * Embeddium will fast-path — expressed entirely in vanilla/KHP-native types so
     * the caller never needs to reference {@code ModelCuboidAccessor}/{@code
     * ModelCuboid} itself. That isolation is the actual point of this record's
     * existence, not just convenience: it's what lets {@code HitboxCaptureManager}'s
     * own class file stay completely silent on Embeddium's types, so it never fails
     * to verify/load when Embeddium is absent.
     */
    public record FastCubeResult(AABB localBox, byte faceMask, @Nullable PartOBB.FlatFaceUV flatFaceUV) {
    }

    /**
     * {@code null} if Embeddium will <b>not</b> fast-path {@code cube} (a mirrored or
     * otherwise non-"simple" cube, or Embeddium simply isn't the class backing it) —
     * the caller should fall back to the existing {@code VANILLA_CUBE_PARTS_INDEX}/
     * {@code beginVanillaCubeOpacity} two-phase patch mechanism unchanged in that
     * case, exactly as if this class didn't exist.
     *
     * @param texture          {@code null} leaves every face unmarked and skips
     *                         {@code flatFaceUV} entirely (opaque-safe default).
     */
    @Nullable
    public static FastCubeResult tryCompute(ModelPart.Cube cube, @Nullable ResourceLocation texture,
                                             double opacityThreshold) {
        if (!(cube instanceof ModelCuboidAccessor accessor)) return null;
        ModelCuboid simple = accessor.embeddium$getSimpleCuboid();
        if (simple == null) return null;

        AABB box = localBox(simple);
        byte faceMask = computeFaceMask(simple, texture, opacityThreshold);

        if (texture == null || simple.mirror) {
            // Mirrored: faceMask is still correct (see class javadoc), flatFaceUV is
            // the deliberately-scoped-out case — fall back to "no tightening" exactly
            // like any other part would, rather than risk a wrong corner mapping.
            return new FastCubeResult(box, faceMask, null);
        }

        double dx = box.maxX - box.minX, dy = box.maxY - box.minY, dz = box.maxZ - box.minZ;
        // No size gate — Option A/B tightening is attempted on every cube regardless
        // of its thinnest-axis extent, not just ones under some "flat" threshold.
        // Same candidate-face selection finalizeStructuredPart uses for the slow path.
        Direction[] candidates = dx <= dy && dx <= dz ? new Direction[]{Direction.EAST, Direction.WEST}
                : dy <= dz ? new Direction[]{Direction.UP, Direction.DOWN}
                : new Direction[]{Direction.SOUTH, Direction.NORTH};

        for (Direction dir : candidates) {
            float[] rect = faceUvRect(simple, dir);
            TextureAlphaSampler.RectAnalysis analysis = TextureAlphaSampler.analyzeRect(
                    texture, rect[0], rect[1], rect[2], rect[3], (float) opacityThreshold);
            if (!analysis.anyOpaque()) continue; // fully transparent — faceMask already covers it
            if (!analysis.anyTransparent()) return new FastCubeResult(box, faceMask, null); // fully opaque, no tightening needed

            PartOBB.FlatFaceUV initial = buildFlatFaceUV(simple, dir);
            FaceGeometry.Tightened tightened = FaceGeometry.tightenToOpaqueBounds(box, dir, initial, analysis);
            return new FastCubeResult(tightened.box(), faceMask, tightened.flatUV());
        }
        return new FastCubeResult(box, faceMask, null);
    }

    /**
     * The local-space box {@code cuboid} occupies, already in the same units
     * ({@code /16}'d block units) {@code PartOBB#localBox()} expects — {@code
     * ModelCuboid}'s own constructor pre-swaps {@code x1}/{@code x2} for a mirrored
     * cube (confirmed via decompile), so {@code x1} isn't guaranteed {@code <= x2}
     * the way {@link AABB}'s own contract assumes; normalized here via min/max rather
     * than trusted as-is.
     */
    private static AABB localBox(ModelCuboid cuboid) {
        double minX = Math.min(cuboid.x1, cuboid.x2), maxX = Math.max(cuboid.x1, cuboid.x2);
        double minY = Math.min(cuboid.y1, cuboid.y2), maxY = Math.max(cuboid.y1, cuboid.y2);
        double minZ = Math.min(cuboid.z1, cuboid.z2), maxZ = Math.max(cuboid.z1, cuboid.z2);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Real per-face {@code faceMask} for {@code cuboid} — a bit is set only if every
     * sampled point across that face's whole UV footprint is at/below {@code
     * opacityThreshold} (same "fully transparent" definition {@code
     * FaceOpacityAccumulator#buildFaceMask} uses for the slow path), computed via
     * {@link TextureAlphaSampler#analyzeRect} against the UV rect bounds {@link
     * #faceUvRect} returns.
     *
     * <p>A face vanilla itself never renders ({@code cuboid.shouldDrawFace(dir)} is
     * {@code false}) is left unmarked, matching the slow path's own {@code
     * hasData[i] == false} behavior for the same case: no data means "unknown,
     * treated as solid," not "confirmed transparent."
     */
    private static byte computeFaceMask(ModelCuboid cuboid, @Nullable ResourceLocation texture, double opacityThreshold) {
        if (texture == null) return 0;
        byte mask = 0;
        for (Direction dir : Direction.values()) {
            int ordinal = dir.ordinal();
            if (!cuboid.shouldDrawFace(ordinal)) continue;
            float[] rect = faceUvRect(cuboid, dir);
            TextureAlphaSampler.RectAnalysis analysis = TextureAlphaSampler.analyzeRect(
                    texture, rect[0], rect[1], rect[2], rect[3], (float) opacityThreshold);
            if (!analysis.anyOpaque()) {
                mask |= (byte) (1 << ordinal);
            }
        }
        return mask;
    }

    /**
     * {@code {minU, minV, maxU, maxV}} for {@code dir}, from {@code cuboid}'s own
     * {@code u0..u5}/{@code v0..v2} fields — bytecode-sourced directly from {@code
     * EntityRenderer.prepareVertices}'s {@code buildVertexTexCoord} calls (see class
     * javadoc for the WEST/EAST correction).
     */
    private static float[] faceUvRect(ModelCuboid c, Direction dir) {
        return switch (dir) {
            case DOWN -> rect(c.u1, c.v0, c.u2, c.v1);
            case UP -> rect(c.u2, c.v0, c.u3, c.v1);
            case NORTH -> rect(c.u1, c.v1, c.u2, c.v2);
            case SOUTH -> rect(c.u4, c.v1, c.u5, c.v2);
            case EAST -> rect(c.u2, c.v1, c.u4, c.v2);
            case WEST -> rect(c.u0, c.v1, c.u1, c.v2);
        };
    }

    private static float[] rect(float uA, float vA, float uB, float vB) {
        return new float[]{Math.min(uA, uB), Math.min(vA, vB), Math.max(uA, uB), Math.max(vA, vB)};
    }

    /**
     * The initial (untightened) {@link PartOBB.FlatFaceUV} for {@code dir}, in
     * {@link FaceGeometry#faceCorners}'s own corner order — i.e. what {@code
     * FaceOpacityAccumulator} would have built from real per-vertex samples on the
     * slow path, reconstructed instead from {@code cuboid}'s pre-baked fields.
     * Non-mirrored cubes only (see class javadoc) — derived by hand-matching {@code
     * EntityRenderer}'s {@code CUBE_VERTICES}/{@code buildVertexTexCoord} vertex
     * emission order against {@code FaceGeometry.faceCorners}'s corner order, face by
     * face, this session.
     */
    private static PartOBB.FlatFaceUV buildFlatFaceUV(ModelCuboid c, Direction dir) {
        return switch (dir) {
            case DOWN -> new PartOBB.FlatFaceUV(dir, c.u1, c.v1, c.u2, c.v1, c.u2, c.v0, c.u1, c.v0);
            case UP -> new PartOBB.FlatFaceUV(dir, c.u2, c.v1, c.u3, c.v1, c.u3, c.v0, c.u2, c.v0);
            case NORTH -> new PartOBB.FlatFaceUV(dir, c.u1, c.v1, c.u2, c.v1, c.u2, c.v2, c.u1, c.v2);
            case SOUTH -> new PartOBB.FlatFaceUV(dir, c.u5, c.v1, c.u4, c.v1, c.u4, c.v2, c.u5, c.v2);
            case EAST -> new PartOBB.FlatFaceUV(dir, c.u2, c.v1, c.u4, c.v1, c.u4, c.v2, c.u2, c.v2);
            case WEST -> new PartOBB.FlatFaceUV(dir, c.u1, c.v1, c.u0, c.v1, c.u0, c.v2, c.u1, c.v2);
        };
    }

    private EmbeddiumFastCubeOpacity() {
    }
}
