package exp.CCnewmods.khp.blueprint.sweep;

import net.minecraft.world.entity.Entity;

/** @param apply sets up the pose for the next frame; must not assume the entity starts from rest. */
public record PlannedPose(String poseId, PoseAction apply) {

    @FunctionalInterface
    public interface PoseAction {
        void apply(Entity entity);
    }
}
