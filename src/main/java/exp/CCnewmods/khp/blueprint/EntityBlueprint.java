package exp.CCnewmods.khp.blueprint;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * All pre-baked poses for one entity type, from {@code data/khp/animstatecache/}. Babies have their own file.
 *
 * @param rendererKind diagnostic label only.
 * @param texture      assumed constant across poses; may be {@code null}.
 */
public record EntityBlueprint(ResourceLocation entityType, boolean baby, String rendererKind,
                               ResourceLocation texture, List<BlueprintPose> poses) {

    public EntityBlueprint {
        poses = List.copyOf(poses);
    }

    public BlueprintPose pose(String poseId) {
        for (BlueprintPose pose : poses) {
            if (pose.poseId().equals(poseId)) return pose;
        }
        return null;
    }

    public BlueprintPose restPose() {
        BlueprintPose rest = pose("rest");
        if (rest == null) {
            throw new IllegalStateException("Blueprint for " + entityType + (baby ? " (baby)" : "")
                    + " has no \"rest\" pose — malformed at load time, should have been rejected by the reload listener.");
        }
        return rest;
    }
}
