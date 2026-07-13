package exp.CCnewmods.khp.capture;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * One cube's worth of per-face bookkeeping: {@code faceMask} (unchanged logic from
 * Phase 2c — a face's bit is set only if every sampled point on it is at/below the
 * opacity threshold) plus, per face, the four corners' UV values needed to build a
 * {@link PartOBB.FlatFaceUV} for Option A/B's flat-geometry handling.
 *
 * <p>Introduced to replace three near-identical hand-rolled versions of this same
 * bookkeeping that were about to get written separately into vanilla's
 * {@code CubeOpacityRecorder}, GeckoLib's {@code captureGeoBone} loop, and Citadel's
 * {@code computeFaceMask} — one implementation, three call sites.
 */
public final class FaceOpacityAccumulator {

    private final AABB box;
    private final boolean[] hasData = new boolean[6];
    private final boolean[] fullyTransparent = {true, true, true, true, true, true};
    private final float[][][] cornerUV = new float[6][4][2];
    private final boolean[][] cornerSet = new boolean[6][4];
    private final int[] sampleCount = new int[6];

    public FaceOpacityAccumulator(AABB box) {
        this.box = box;
    }

    /**
     * Record one sampled vertex: its face, local position (used only to find the
     * nearest of that face's four {@link FaceGeometry#faceCorners} slots — see that
     * class's javadoc for why nearest-match rather than assumed order), UV, and
     * already-computed effective opacity (texture alpha × vertex color alpha, or
     * texture alpha alone where per-vertex color isn't available — see each call
     * site for which).
     */
    public void addSample(Direction face, float lx, float ly, float lz, float u, float v,
                          float opacity, double threshold) {
        if (face == null) return;
        int fi = face.ordinal();
        hasData[fi] = true;
        sampleCount[fi]++;
        if (opacity > threshold) fullyTransparent[fi] = false;
        float[][] corners = FaceGeometry.faceCorners(box, face);
        int ci = FaceGeometry.nearestCornerIndex(corners, lx, ly, lz);
        cornerUV[fi][ci][0] = u;
        cornerUV[fi][ci][1] = v;
        cornerSet[fi][ci] = true;
    }

    public byte buildFaceMask() {
        byte mask = 0;
        for (int i = 0; i < 6; i++) {
            if (hasData[i] && fullyTransparent[i]) mask |= (byte) (1 << i);
        }
        return mask;
    }

    /**
     * Builds a {@link PartOBB.FlatFaceUV} for {@code face} if all four of its corners
     * got a UV sample (the normal case for a real cuboid quad) — {@code null}
     * otherwise, meaning the caller should skip Option A/B for this face rather than
     * guess at missing corners.
     */
    public PartOBB.FlatFaceUV buildFlatFaceUV(Direction face) {
        int fi = face.ordinal();
        if (!cornerSet[fi][0] || !cornerSet[fi][1] || !cornerSet[fi][2] || !cornerSet[fi][3]) return null;
        return new PartOBB.FlatFaceUV(face,
                cornerUV[fi][0][0], cornerUV[fi][0][1],
                cornerUV[fi][1][0], cornerUV[fi][1][1],
                cornerUV[fi][2][0], cornerUV[fi][2][1],
                cornerUV[fi][3][0], cornerUV[fi][3][1]);
    }

    /**
     * TEMPORARY diagnostic for the "missing corner samples" investigation — distinguishes
     * "too few samples arrived at all" (source geometry doesn't emit 4 distinct
     * vertices for this face) from "samples arrived but collided onto fewer than 4
     * corners" (a real bug in {@link FaceGeometry#nearestCornerIndex} matching or in
     * this face's corner geometry itself). Remove once the cause is confirmed.
     */
    public String describeFace(Direction face) {
        int fi = face.ordinal();
        return "samples=" + sampleCount[fi] + " cornersHit=[" + cornerSet[fi][0] + "," + cornerSet[fi][1]
                + "," + cornerSet[fi][2] + "," + cornerSet[fi][3] + "]";
    }
}
