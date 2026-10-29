package exp.CCnewmods.khp.capture;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Per-face opacity and corner UVs for one cube, shared by every structured capture path. */
public final class FaceOpacityAccumulator {

    private final AABB box;
    private final boolean[] hasData = new boolean[6];
    private final boolean[] fullyTransparent = {true, true, true, true, true, true};
    private final float[][][] cornerUV = new float[6][4][2];
    private final boolean[][] cornerSet = new boolean[6][4];

    public FaceOpacityAccumulator(AABB box) {
        this.box = box;
    }

    public void addSample(Direction face, float lx, float ly, float lz, float u, float v,
                          float opacity, double threshold) {
        if (face == null) return;
        int fi = face.ordinal();
        hasData[fi] = true;
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

    /** {@code null} unless all four corners were sampled. */
    public PartOBB.FlatFaceUV buildFlatFaceUV(Direction face) {
        int fi = face.ordinal();
        if (!cornerSet[fi][0] || !cornerSet[fi][1] || !cornerSet[fi][2] || !cornerSet[fi][3]) return null;
        return new PartOBB.FlatFaceUV(face,
                cornerUV[fi][0][0], cornerUV[fi][0][1],
                cornerUV[fi][1][0], cornerUV[fi][1][1],
                cornerUV[fi][2][0], cornerUV[fi][2][1],
                cornerUV[fi][3][0], cornerUV[fi][3][1]);
    }
}
