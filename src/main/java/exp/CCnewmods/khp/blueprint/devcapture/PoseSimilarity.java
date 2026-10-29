package exp.CCnewmods.khp.blueprint.devcapture;

import exp.CCnewmods.khp.capture.PartOBB;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a live pose matches a stored one, by the worst single bone. TODO: {@link #CORNER_DELTA_THRESHOLD} is
 * untuned.
 */
public final class PoseSimilarity {

    /** Blocks; half a texture pixel at 16 px/block. */
    public static final double CORNER_DELTA_THRESHOLD = 0.03125;

    public static boolean matches(List<PartOBB> existing, List<PartOBB> candidate) {
        Map<Short, PartOBB> byBoneId = new HashMap<>();
        for (PartOBB part : existing) {
            byBoneId.put(part.boneId(), part);
        }
        for (PartOBB candidatePart : candidate) {
            PartOBB existingPart = byBoneId.get(candidatePart.boneId());
            if (existingPart == null) return false;
            if (!boneMatches(existingPart, candidatePart)) return false;
        }
        return true;
    }

    public static boolean isNewPose(List<List<PartOBB>> existingPoses, List<PartOBB> candidate) {
        for (List<PartOBB> existing : existingPoses) {
            if (matches(existing, candidate)) return false;
        }
        return true;
    }

    private static boolean boneMatches(PartOBB a, PartOBB b) {
        Vector3f[] cornersA = corners(a);
        Vector3f[] cornersB = corners(b);
        for (int i = 0; i < cornersA.length; i++) {
            if (cornersA[i].distance(cornersB[i]) > CORNER_DELTA_THRESHOLD) return false;
        }
        return true;
    }

    private static Vector3f[] corners(PartOBB part) {
        var box = part.localBox();
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        Vector3f[] result = new Vector3f[8];
        int i = 0;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    result[i++] = part.transform().transformPosition(
                            new Vector3f((float) x, (float) y, (float) z));
                }
            }
        }
        return result;
    }

    private PoseSimilarity() {
    }
}
