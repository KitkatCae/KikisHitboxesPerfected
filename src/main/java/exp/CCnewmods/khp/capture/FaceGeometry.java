package exp.CCnewmods.khp.capture;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Shared per-face geometry helpers — corner extraction (previously duplicated inline
 * in {@code MahClientEvents}' debug overlay, now the one copy the debug renderer,
 * Option A's box tightening, and Option B's per-pixel raycast test all share) and the
 * bilinear local↔UV mapping Option B needs.
 *
 * <h3>Corner convention</h3>
 * {@link #faceCorners(AABB, Direction)} returns four corners in a fixed winding order
 * for a given face. In local (axis-aligned box) space, corner0→corner1 varies exactly
 * one in-plane axis and corner1→corner2 varies exactly the other — both facts this
 * class's bilinear math depends on. Never renumber this without checking every caller.
 *
 * <h3>Why bilinear over 4 corners, not a 2-corner diagonal shortcut</h3>
 * An earlier draft of this class tried to get away with storing only two diagonal
 * corners' UV and interpolating a single shared fraction toward a query point — that's
 * only correct for points that happen to lie exactly on the corner0–corner2 diagonal.
 * A general point on the face (most raycast hits) needs two independent fractions,
 * one per in-plane axis, which in turn needs all four corners' UV to solve
 * unambiguously — caught before it shipped, not after.
 */
public final class FaceGeometry {

    /** The four corners of one face of {@code box}, in a consistent winding order, for {@code dir}. */
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

    /** Which two local axis indices (0=X,1=Y,2=Z) vary in-plane on face {@code dir} — corner0→1 is the first, corner1→2 the second. */
    public static int[] inPlaneAxes(Direction dir) {
        return switch (dir) {
            case DOWN, UP -> new int[]{0, 2};    // X then Z
            case NORTH, SOUTH -> new int[]{0, 1}; // X then Y
            case WEST, EAST -> new int[]{2, 1};   // Z then Y
        };
    }

    /**
     * Bilinear UV at local point ({@code lx,ly,lz}), assumed already on face
     * {@code dir}'s plane (callers do the ray/plane intersection first), given that
     * face's four corner UVs in {@link #faceCorners} order.
     */
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

    /**
     * Option A: shrink {@code box} on its two in-plane axes to the opaque region's
     * UV bounds (from {@link TextureAlphaSampler#analyzeRect}), preserving the
     * original extent on the thin axis untouched — and produce the new
     * {@link PartOBB.FlatFaceUV} for the tightened box in the same pass, so the two
     * stay correctly corresponded by construction rather than by separately
     * reasoning about corner roles twice.
     *
     * <h3>Mirrored UV</h3>
     * Both vanilla {@code Cube} and Citadel/Tabula-lineage boxes support a mirror
     * flag, which can make U <i>decrease</i> as the in-plane s-axis increases instead
     * of the more common increasing case. Blindly assigning
     * {@code opaqueMinU → corner0} would silently flip the mapping for a mirrored
     * box. Instead, the direction U/V actually run in is read off the <i>original</i>
     * {@code flatUV} (whether {@code u1 >= u0}, {@code v3 >= v0}) and the new corners
     * are assigned consistently with that, whichever way it goes.
     */
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

    /**
     * Which of {@code corners}' four entries a sampled vertex position is closest to
     * — used to correlate an incoming vertex's UV to a specific {@link #faceCorners}
     * slot without assuming the source's own vertex emission order matches this
     * class's winding convention (it generally won't; vanilla/GeckoLib/Citadel each
     * emit in whatever order their own model authoring produced).
     */
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
