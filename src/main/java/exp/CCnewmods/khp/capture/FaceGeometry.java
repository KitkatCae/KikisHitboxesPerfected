package exp.CCnewmods.khp.capture;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Per-face corner and bilinear UV helpers. {@link #faceCorners} has a fixed winding (corner0→1 varies one
 * in-plane axis, corner1→2 the other); the bilinear math depends on it, so don't renumber.
 */
public final class FaceGeometry {

    public static float[][] faceCorners(AABB box, Direction dir) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        return switch (dir) {
            case DOWN -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case UP -> new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
            case NORTH -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}};
        };
    }

    /** In-plane axis indices (0=X,1=Y,2=Z) for {@code dir}: corner0→1, then corner1→2. */
    public static int[] inPlaneAxes(Direction dir) {
        return switch (dir) {
            case DOWN, UP -> new int[]{0, 2};
            case NORTH, SOUTH -> new int[]{0, 1};
            case WEST, EAST -> new int[]{2, 1};
        };
    }

    /** Assumes the point is already on the face's plane. */
    public static float[] bilinearUV(AABB box, Direction dir, float lx, float ly, float lz,
                                     PartOBB.FlatFaceUV uv) {
        float[][] c = faceCorners(box, dir);
        int[] axes = inPlaneAxes(dir);
        float[] p = {lx, ly, lz};
        float s = fraction(p[axes[0]], c[0][axes[0]], c[1][axes[0]]);
        float t = fraction(p[axes[1]], c[1][axes[1]], c[2][axes[1]]);
        float u = bilerp(uv.u0(), uv.u1(), uv.u2(), uv.u3(), s, t);
        float v = bilerp(uv.v0(), uv.v1(), uv.v2(), uv.v3(), s, t);
        return new float[]{u, v};
    }

    /** Shrinks {@code box} to the opaque UV region. Follows the original UV direction, so mirrored boxes work. */
    public static Tightened tightenToOpaqueBounds(AABB box, Direction dir, PartOBB.FlatFaceUV flatUV,
                                                   TextureAlphaSampler.RectAnalysis analysis) {
        float[][] c = faceCorners(box, dir);
        int[] axes = inPlaneAxes(dir);

        boolean uIncreasesWithS = flatUV.u1() >= flatUV.u0();
        boolean vIncreasesWithT = flatUV.v3() >= flatUV.v0();
        float newU0 = uIncreasesWithS ? analysis.opaqueMinU() : analysis.opaqueMaxU();
        float newU1 = uIncreasesWithS ? analysis.opaqueMaxU() : analysis.opaqueMinU();
        float newV0 = vIncreasesWithT ? analysis.opaqueMinV() : analysis.opaqueMaxV();
        float newV3 = vIncreasesWithT ? analysis.opaqueMaxV() : analysis.opaqueMinV();

        float sLo = inverseFraction(flatUV.u0(), flatUV.u1(), newU0);
        float sHi = inverseFraction(flatUV.u0(), flatUV.u1(), newU1);
        float tLo = inverseFraction(flatUV.v0(), flatUV.v3(), newV0);
        float tHi = inverseFraction(flatUV.v0(), flatUV.v3(), newV3);

        float axisLo0 = lerp(c[0][axes[0]], c[1][axes[0]], Math.min(sLo, sHi));
        float axisHi0 = lerp(c[0][axes[0]], c[1][axes[0]], Math.max(sLo, sHi));
        float axisLo1 = lerp(c[0][axes[1]], c[3][axes[1]], Math.min(tLo, tHi));
        float axisHi1 = lerp(c[0][axes[1]], c[3][axes[1]], Math.max(tLo, tHi));

        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        min[axes[0]] = Math.min(axisLo0, axisHi0);
        max[axes[0]] = Math.max(axisLo0, axisHi0);
        min[axes[1]] = Math.min(axisLo1, axisHi1);
        max[axes[1]] = Math.max(axisLo1, axisHi1);
        AABB tightened = new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);

        PartOBB.FlatFaceUV newFlatUV = new PartOBB.FlatFaceUV(dir,
                newU0, newV0, newU1, newV0, newU1, newV3, newU0, newV3);
        return new Tightened(tightened, newFlatUV);
    }

    public record Tightened(AABB box, PartOBB.FlatFaceUV flatUV) {
    }

    /** Matches a vertex to a corner by position, since sources emit vertices in their own order. */
    public static int nearestCornerIndex(float[][] corners, float lx, float ly, float lz) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < corners.length; i++) {
            float dx = corners[i][0] - lx, dy = corners[i][1] - ly, dz = corners[i][2] - lz;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static float fraction(float value, float from, float to) {
        float span = to - from;
        if (Math.abs(span) < 1.0e-6f) return 0.0f;
        return (value - from) / span;
    }

    private static float inverseFraction(float from, float to, float value) {
        float span = to - from;
        if (Math.abs(span) < 1.0e-6f) return 0.0f;
        return (value - from) / span;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float bilerp(float v00, float v10, float v11, float v01, float s, float t) {
        float top = lerp(v00, v10, s);
        float bottom = lerp(v01, v11, s);
        return lerp(top, bottom, t);
    }

    private FaceGeometry() {
    }
}
