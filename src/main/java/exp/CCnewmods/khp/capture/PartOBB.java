package exp.CCnewmods.khp.capture;

import exp.CCnewmods.khp.MahConfig;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * One captured cube as an oriented box in entity-local render space. World transform:
 * {@code translate(anchorPos + (entity.position() - capturedEntityPos)) * transform}.
 *
 * <p>Never mutate {@code transform} or {@code localBox}: instances cross threads and a transform is shared by
 * every cube of a part. The constructor pads boxes to {@code minPartThickness}, since zero-depth wings/fins
 * can't be hit by rays parallel to them.
 *
 * @param localBox   bounds in block units. TODO: excludes vanilla CubeDeformation "grow".
 * @param faceMask   bit per Direction ordinal: 1 = fully transparent.
 * @param flatFaceUV set only when a face is part opaque/part transparent; enables per-pixel raycasts.
 * @param allFaceUVs UVs of every fully sampled face; never null, a missing key means no data.
 */
public record PartOBB(AABB localBox, Matrix4f transform, short boneId, byte faceMask, FlatFaceUV flatFaceUV,
                       java.util.Map<Direction, FlatFaceUV> allFaceUVs) {

    public PartOBB(AABB localBox, Matrix4f transform, short boneId, byte faceMask, FlatFaceUV flatFaceUV) {
        this(localBox, transform, boneId, faceMask, flatFaceUV, java.util.Map.of());
    }

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
        if (halfX == origHalfX && halfY == origHalfY && halfZ == origHalfZ) {
            return box;
        }
        double cx = (box.minX + box.maxX) / 2.0, cy = (box.minY + box.maxY) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
        return new AABB(cx - halfX, cy - halfY, cz - halfZ, cx + halfX, cy + halfY, cz + halfZ);
    }

    /** UVs at {@link FaceGeometry#faceCorners}'s four corners, in that order. */
    public record FlatFaceUV(Direction face,
                              float u0, float v0, float u1, float v1,
                              float u2, float v2, float u3, float v3) {
    }
}
