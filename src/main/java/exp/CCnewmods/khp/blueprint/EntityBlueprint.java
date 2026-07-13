package exp.CCnewmods.khp.blueprint;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * All pre-baked poses for one entity type (or one entity type's baby variant — see
 * {@link #baby()}), loaded from {@code data/khp/animstatecache/}.
 *
 * <p>Baby and adult are <b>separate files</b>, not separate pose entries within one
 * file — a baby entity can independently combine with any other axis (walking,
 * attacking, sitting, sleeping) the same way an adult can, so collapsing "baby" into
 * a single pose alongside "walk"/"attack" would be wrong. See
 * {@code KHP_Headless_Resolver_Design_v2.md} §3/§6 (Q6).
 *
 * @param entityType   the {@code EntityType} registry id this blueprint is for.
 * @param baby         true if this is the baby-variant file for {@code entityType}.
 * @param rendererKind which capture path produced the poses below — {@code GECKO},
 *                     {@code VANILLA_LIVING}, or {@code OTHER} — recorded for
 *                     diagnostics/consistency, mirroring {@code RendererKind} from
 *                     the live capture pipeline. Not itself consumed by resolution
 *                     logic; a blueprint's poses are used regardless of which path
 *                     produced them.
 * @param texture      the texture this entity rendered with at capture time, same
 *                     role as {@code EntityHitboxSnapshot#texture()} — consumed by
 *                     flat parts' Option B per-pixel raycast test. {@code null} if
 *                     resolution failed at capture time (same graceful-degradation
 *                     path as a live snapshot with no texture: raycast falls back to
 *                     trusting the box). Assumed constant across every pose for a
 *                     given entity type — texture-variant systems aren't handled.
 * @param poses        every captured pose for this entity type/variant. Never empty
 *                     for a loaded blueprint — at minimum a {@code "rest"} pose is
 *                     always present.
 */
public record EntityBlueprint(ResourceLocation entityType, boolean baby, String rendererKind,
                               ResourceLocation texture, List<BlueprintPose> poses) {

    public EntityBlueprint {
        poses = List.copyOf(poses);
    }

    /** First pose matching {@code poseId}, or {@code null} if this blueprint has none. */
    public BlueprintPose pose(String poseId) {
        for (BlueprintPose pose : poses) {
            if (pose.poseId().equals(poseId)) return pose;
        }
        return null;
    }

    /** The {@code "rest"} pose — every blueprint is guaranteed to have exactly one. */
    public BlueprintPose restPose() {
        BlueprintPose rest = pose("rest");
        if (rest == null) {
            throw new IllegalStateException("Blueprint for " + entityType + (baby ? " (baby)" : "")
                    + " has no \"rest\" pose — malformed at load time, should have been rejected by the reload listener.");
        }
        return rest;
    }
}
