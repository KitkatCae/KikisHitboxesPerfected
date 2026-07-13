package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import exp.CCnewmods.khp.capture.SnapshotSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a {@link BlueprintPose} for an entity with no live snapshot into a real
 * {@link EntityHitboxSnapshot} — design doc §6. This is what makes the blueprint
 * system actually reachable: without this, loaded blueprints just sit in
 * {@link BlueprintStore} unread. See {@code exp.CCnewmods.khp.api.MahApi#getSnapshot}
 * for where this is actually wired in as the fallback.
 *
 * <h3>Re-rotating a blueprint to the entity's current yaw</h3>
 * The sweep tool captures every blueprint pose with the test entity forced to
 * {@code yaw = 0} (see {@code BlueprintCaptureSweep}), so every stored
 * {@code PartOBB.transform()} has vanilla's own yaw-rotation component
 * ({@code LivingEntityRenderer.setupRotations}, confirmed via decompile:
 * {@code poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - yaw))}) baked in at
 * {@code 180 - 0 = 180} degrees. Reconstructing the pose at the entity's real
 * current yaw Y means composing the desired {@code 180 - Y} rotation, which — since
 * single-axis rotations about the same axis commute/add — decomposes as
 * {@code rotateY(-Y) * rotateY(180)}. The stored transform already contains the
 * {@code rotateY(180)} half, so left-multiplying the whole stored transform by
 * {@code rotateY(radians(-Y))} produces exactly the {@code 180 - Y} orientation,
 * with no need to decompose the stored matrix at all. This is a pure rotation
 * (no translation baked into {@code part.transform()} for entity world-position —
 * that's applied separately, on top, at raycast query time via
 * {@code HitboxRaycaster}'s {@code translate(currentAnchor)}), so the left-multiply
 * correctly rotates every part around the entity's own origin, preserving each
 * bone's local pivot offset — not some other, wrong pivot point.
 *
 * <h3>What pose selection can and can't reliably do across capture frameworks</h3>
 * Sit/sleep/baby are universal and always correctly labeled regardless of which of
 * the six capture frameworks produced the blueprint — those poses are forced the
 * same way (entity-level flags) for every framework. "Which clip means walking" or
 * "which clip means attacking," however, is only a generically answerable question
 * for the {@code VANILLA_LIVING} tier, where {@code PosePlanBuilder} itself names
 * poses {@code walk_phase_N}/{@code attack_swing_N} by construction. For
 * GeckoLib/LLibrary/LionfishAPI/Citadel/Epic Fight blueprints, pose ids are the
 * framework's own clip/animation names (or numeric ids) — there is no generic,
 * reliable mapping from "entity is currently attacking" to "which of this mob's
 * named clips is the attack clip" without per-mob authoring knowledge this system
 * doesn't have. Walk/attack matching is therefore only attempted when
 * {@code rendererKind} is {@code VANILLA_LIVING}; every other tier falls straight
 * through sit/sleep to {@code rest} for anything beyond those two universal poses.
 * This is a real, current scope boundary, not an oversight — flagged here rather
 * than silently degrading without explanation.
 */
public final class BlueprintResolver {

    private static final double MOVING_EPSILON_SQ = 0.0004; // ~0.02 blocks/tick

    @Nullable
    public static EntityHitboxSnapshot resolve(Entity entity) {
        EntityBlueprint blueprint = lookupBlueprint(entity);
        if (blueprint == null) return null;

        ResourceLocation entityKey = MahApi.entityKey(entity);
        BlueprintPose pose = selectPose(entity, blueprint);

        float bodyYaw = entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        Matrix4f yawCorrection = new Matrix4f().rotateY((float) Math.toRadians(-bodyYaw));

        List<PartOBB> parts = new ArrayList<>(pose.parts().size());
        for (PartOBB part : pose.parts()) {
            Matrix4f rotated = new Matrix4f(yawCorrection).mul(part.transform());
            parts.add(new PartOBB(part.localBox(), rotated, part.boneId(), part.faceMask(), part.flatFaceUV()));
        }

        Vec3 pos = entity.position();
        // anchorPos == capturedEntityPos (both "now") — resolved fresh on every call,
        // never aged, so HitboxRaycaster's position-delta tracking always computes a
        // zero delta for this snapshot specifically. gameTime is "now" for the same
        // reason: a resolved snapshot is never stale by construction.
        //
        // localBounds uses the same EntityHitboxSnapshot.computeLocalBounds the live
        // capture path uses — both producers share one definition of "how far this
        // snapshot's geometry reaches," so a blueprint-resolved snapshot benefits from
        // extent-aware candidate search identically to a live one (see
        // KHP_Snapshot_Extent_Candidate_Search_Design.md §9.1 — this is what lets
        // Phase 3's HitboxStore population reuse §5's search unchanged).
        return new EntityHitboxSnapshot(entityKey, entity.getId(), pos, pos, bodyYaw,
                entity.level().getGameTime(), parts, EntityHitboxSnapshot.computeLocalBounds(parts),
                SnapshotSource.BLUEPRINT, blueprint.texture());
    }

    /**
     * Baby-first, adult-fallback blueprint lookup — the same logic {@link #resolve}
     * has always used, factored out so other callers (Phase 2C's combat allowlist
     * {@code rendererKind} check, {@code MahApi.getCombatSnapshot}) resolve to
     * <b>exactly</b> the same blueprint {@code resolve} itself would use, rather than
     * re-implementing baby/adult fallback and risking the two disagreeing.
     */
    @Nullable
    public static EntityBlueprint lookupBlueprint(Entity entity) {
        ResourceLocation entityKey = MahApi.entityKey(entity);
        boolean baby = entity instanceof AgeableMob ageable && ageable.isBaby();

        EntityBlueprint blueprint = BlueprintStore.get(entityKey, baby);
        if (blueprint == null && baby) {
            // No baby-variant file — most entity types have none at all. Falling
            // back to the adult blueprint's poses beats having no geometry.
            blueprint = BlueprintStore.get(entityKey, false);
        }
        return blueprint;
    }

    private static BlueprintPose selectPose(Entity entity, EntityBlueprint blueprint) {
        if (entity instanceof TamableAnimal tamable && tamable.isInSittingPose()) {
            BlueprintPose p = blueprint.pose("sitting");
            if (p != null) return p;
        }
        if (entity.getPose() == Pose.SLEEPING) {
            BlueprintPose p = blueprint.pose("sleeping");
            if (p != null) return p;
        }

        // Walk/attack tier-matching only attempted for VANILLA_LIVING blueprints —
        // see class javadoc for why this can't be done generically for the other
        // five capture frameworks.
        if ("VANILLA_LIVING".equals(blueprint.rendererKind())) {
            if (entity instanceof LivingEntity living && living.attackAnim > 0.0f) {
                BlueprintPose p = nearestByPrefix(blueprint, "attack_swing_", living.attackAnim);
                if (p != null) return p;
            }
            if (entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_EPSILON_SQ) {
                double phase = (entity.tickCount % 20) / 20.0; // cosmetic phase estimate, nothing is watching to notice mismatch
                BlueprintPose p = nearestByPrefix(blueprint, "walk_phase_", (float) phase);
                if (p != null) return p;
            }
        }

        return blueprint.restPose();
    }

    /**
     * Among poses named {@code prefix + N} (N an integer index, not itself a
     * progress fraction), pick the one whose index is nearest {@code fraction * (count-1)}.
     * Returns {@code null} if no pose with this prefix exists at all — the caller
     * falls through to the next tier down.
     */
    @Nullable
    private static BlueprintPose nearestByPrefix(EntityBlueprint blueprint, String prefix, float fraction) {
        List<BlueprintPose> matches = new ArrayList<>();
        for (BlueprintPose pose : blueprint.poses()) {
            if (pose.poseId().startsWith(prefix)) matches.add(pose);
        }
        if (matches.isEmpty()) return null;
        int index = Math.round(Math.max(0.0f, Math.min(1.0f, fraction)) * (matches.size() - 1));
        return matches.get(index);
    }

    private BlueprintResolver() {
    }
}
