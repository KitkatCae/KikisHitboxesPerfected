package exp.CCnewmods.khp.blueprint.devcapture;

import exp.CCnewmods.khp.capture.PartOBB;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a freshly-captured live pose is "close enough" to an existing
 * stored pose to be considered the same pose, for dev-mode gap-filling (design doc
 * §5.2). Matched per-bone by {@code boneId} (stable within one entity key's
 * {@code BoneTable}); flags "different" on the worst single bone, not an aggregate —
 * a single limb held at a meaningfully different angle is a real new pose even if
 * every other bone matches exactly.
 *
 * <p><b>Threshold is a starting point, not a measured value</b> —
 * there's no existing corpus of captured poses to measure a real "how different is
 * too different" against yet. {@link #CORNER_DELTA_THRESHOLD} will likely need
 * adjusting after the first real session using this: too loose silently folds a
 * genuinely different pose into "close enough"; too tight floods
 * {@code config/khp/animstatecache_capture/} with near-duplicate samples of the same
 * clip's natural per-frame wobble.
 */
public final class PoseSimilarity {

    /**
     * Max allowed corner displacement (blocks) on any single bone before two poses
     * count as different. 0.03125 = half a texture-pixel at 16px/block — small
     * enough to catch a real distinct pose, loose enough to absorb normal
     * interpolation jitter between two captures of what's actually the same held
     * pose. Starting default, see class javadoc.
     */
    public static final double CORNER_DELTA_THRESHOLD = 0.03125;

    /**
     * @return true if {@code candidate} is close enough to {@code existing} to be
     * considered the same pose (i.e. NOT worth flagging as new).
     */
    public static boolean matches(List<PartOBB> existing, List<PartOBB> candidate) {
        Map<Short, PartOBB> byBoneId = new HashMap<>();
        for (PartOBB part : existing) {
            byBoneId.put(part.boneId(), part);
        }
        for (PartOBB candidatePart : candidate) {
            PartOBB existingPart = byBoneId.get(candidatePart.boneId());
            if (existingPart == null) return false; // a bone with no counterpart at all — not a match
            if (!boneMatches(existingPart, candidatePart)) return false;
        }
        return true;
    }

    /** True if every stored pose in {@code existingPoses} fails to match {@code candidate} — i.e. genuinely new. */
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

    /** The 8 corners of {@code part.localBox()}, transformed by {@code part.transform()} — same space both sides. */
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
