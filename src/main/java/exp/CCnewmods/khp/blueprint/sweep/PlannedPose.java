package exp.CCnewmods.khp.blueprint.sweep;

import net.minecraft.world.entity.Entity;

/**
 * One discrete step in a sweep entity's capture plan: mutate whatever entity state
 * is needed so the <b>next</b> client render frame reflects this pose, tagged with
 * the {@code poseId} the resulting captured snapshot will be exported under.
 *
 * <p>Deliberately flat — a GeckoLib clip sampled 10 times (Q2 default) becomes 10
 * separate {@code PlannedPose} entries, not one entry with an internal sample loop.
 * {@link BlueprintCaptureSweep} just walks a single ordered list one entry at a time,
 * waiting a render frame and reading back {@code HitboxStore} after each — no special
 * casing needed for "this step actually contains N sub-samples."
 *
 * @param poseId see {@code BlueprintPose}'s javadoc for the naming convention.
 * @param apply  mutates the sweep's current test entity. Called once, immediately
 *               before {@link BlueprintCaptureSweep} waits for the next render frame
 *               and captures. Idempotent-safe to call more than once if a future
 *               revision needs a retry — should not depend on being called exactly
 *               once from a fresh state, since the previous {@code PlannedPose} in
 *               the list already left the entity in some other posed state.
 */
public record PlannedPose(String poseId, PoseAction apply) {

    @FunctionalInterface
    public interface PoseAction {
        void apply(Entity entity);
    }
}
