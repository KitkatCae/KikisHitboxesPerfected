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
 * Turns a blueprint pose into a snapshot for entities with no live capture. Poses were captured at yaw 0 (which
 * already includes the renderer's {@code rotateY(180)}), so they're rotated by {@code -yaw} about the entity's
 * origin.
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
            parts.add(new PartOBB(part.localBox(), rotated, part.boneId(), part.faceMask(), part.flatFaceUV(),
                part.allFaceUVs()));
        }

        Vec3 pos = entity.position();
        // Resolved fresh every call, so never stale and zero drift.
        return new EntityHitboxSnapshot(entityKey, entity.getId(), pos, pos, bodyYaw,
                entity.level().getGameTime(), parts, EntityHitboxSnapshot.computeLocalBounds(parts),
                SnapshotSource.BLUEPRINT, blueprint.texture());
    }

    /** Baby first, falling back to adult. */
    @Nullable
    public static EntityBlueprint lookupBlueprint(Entity entity) {
        ResourceLocation entityKey = MahApi.entityKey(entity);
        boolean baby = entity instanceof AgeableMob ageable && ageable.isBaby();

        EntityBlueprint blueprint = BlueprintStore.get(entityKey, baby);
        if (blueprint == null && baby) {
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

        if (entity instanceof LivingEntity living && living.attackAnim > 0.0f) {
            BlueprintPose p = nearestByPrefix(blueprint, "attack_swing_", living.attackAnim);
            if (p != null) return p;
        }
        if (entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_EPSILON_SQ) {
            double phase = (entity.tickCount % 20) / 20.0; // cosmetic
            BlueprintPose p = nearestByPrefix(blueprint, "walk_phase_", (float) phase);
            if (p != null) return p;
        }

        return blueprint.restPose();
    }

    /** Pose {@code prefix + N} nearest {@code fraction * (count-1)}, or {@code null}. */
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