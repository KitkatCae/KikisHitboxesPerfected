package exp.CCnewmods.khp.compat.embeddium;

import exp.CCnewmods.khp.Mah;
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
 * Opacity for vanilla cubes drawn by Embeddium's fast path, which never calls {@code Cube.compile()}. The only
 * class touching Embeddium's {@code ModelCuboid}; only call it when Embeddium is loaded.
 *
 * <p>{@code EntityRenderer}'s {@code FACE_NEG_X}/{@code FACE_POS_X} are named backwards: quad 4 is EAST, 5 is
 * WEST. Mirrored cubes get a faceMask but no flatFaceUV.
 */
public final class EmbeddiumFastCubeOpacity {

    /** Set once if {@code embeddium$getSimpleCuboid()} turns out unimplemented (Oculus). */
    private static boolean fastPathUnavailable = false;

    /** Vanilla/KHP types only, so callers never load Embeddium classes. */
    public record FastCubeResult(AABB localBox, byte faceMask, @Nullable PartOBB.FlatFaceUV flatFaceUV) {
    }

    /** {@code null} if Embeddium won't fast-path {@code cube}. */
    @Nullable
    public static FastCubeResult tryCompute(ModelPart.Cube cube, @Nullable ResourceLocation texture,
                                             double opacityThreshold) {
        if (fastPathUnavailable) return null;
        if (!(cube instanceof ModelCuboidAccessor accessor)) return null;
        ModelCuboid simple;
        try {
            simple = accessor.embeddium$getSimpleCuboid();
        } catch (IncompatibleClassChangeError e) {
            // Under Oculus this method has no implementation; SodiumTreeRenderCapture covers those parts.
            fastPathUnavailable = true;
            Mah.LOGGER.warn("[KHP] Embeddium simple-cuboid accessor isn't implemented on ModelPart.Cube "
                    + "in this setup (Oculus replaces Embeddium's entity renderer). Falling back to the "
                    + "regular cube opacity path; this message only appears once.", e);
            return null;
        }
        if (simple == null) return null;
        return fromCuboid(simple, texture, opacityThreshold);
    }

    public static FastCubeResult fromCuboid(ModelCuboid simple, @Nullable ResourceLocation texture,
                                            double opacityThreshold) {
        AABB box = localBox(simple);
        byte faceMask = computeFaceMask(simple, texture, opacityThreshold);

        if (texture == null || simple.mirror) {
            return new FastCubeResult(box, faceMask, null);
        }

        double dx = box.maxX - box.minX, dy = box.maxY - box.minY, dz = box.maxZ - box.minZ;
        Direction[] candidates = dx <= dy && dx <= dz ? new Direction[]{Direction.EAST, Direction.WEST}
                : dy <= dz ? new Direction[]{Direction.UP, Direction.DOWN}
                : new Direction[]{Direction.SOUTH, Direction.NORTH};

        for (Direction dir : candidates) {
            float[] rect = faceUvRect(simple, dir);
            TextureAlphaSampler.RectAnalysis analysis = TextureAlphaSampler.analyzeRect(
                    texture, rect[0], rect[1], rect[2], rect[3], (float) opacityThreshold);
            if (!analysis.anyOpaque()) continue;
            if (!analysis.anyTransparent()) return new FastCubeResult(box, faceMask, null);

            PartOBB.FlatFaceUV initial = buildFlatFaceUV(simple, dir);
            FaceGeometry.Tightened tightened = FaceGeometry.tightenToOpaqueBounds(box, dir, initial, analysis);
            return new FastCubeResult(tightened.box(), faceMask, tightened.flatUV());
        }
        return new FastCubeResult(box, faceMask, null);
    }

    /** In /16 block units; mirrored cubes have x1/x2 swapped, hence min/max. */
    private static AABB localBox(ModelCuboid cuboid) {
        double minX = Math.min(cuboid.x1, cuboid.x2), maxX = Math.max(cuboid.x1, cuboid.x2);
        double minY = Math.min(cuboid.y1, cuboid.y2), maxY = Math.max(cuboid.y1, cuboid.y2);
        double minZ = Math.min(cuboid.z1, cuboid.z2), maxZ = Math.max(cuboid.z1, cuboid.z2);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Faces the cuboid doesn't draw stay unmarked (solid). */
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

    /** Non-mirrored cubes only. */
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
