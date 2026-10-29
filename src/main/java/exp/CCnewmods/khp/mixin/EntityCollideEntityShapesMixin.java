package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.collision.SolidCollisionShapes;
import exp.CCnewmods.khp.collision.Vs2ShipCollisionBridge;
import exp.CCnewmods.khp.compat.Vs2Compat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Feeds precise per-part shapes of solid mobs into vanilla's movement collision. Redirects the
 * {@code collide()} call inside {@code move()} because another mod replaces {@code collide()}'s body.
 * Step-height logic assumes normal -Y gravity.
 */
@Mixin(Entity.class)
public abstract class EntityCollideEntityShapesMixin {

    // Lazy: this merges into Entity, which loads before ModList is ready.
    private static volatile Boolean khp$vs2Present;

    private static boolean khp$isVs2Present() {
        Boolean cached = khp$vs2Present;
        if (cached != null) return cached;
        boolean loaded = Vs2Compat.isLoaded();
        khp$vs2Present = loaded;
        return loaded;
    }

    @Redirect(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 khp$preciseCollide(Entity self, Vec3 pVec) {
        AABB aabb = self.getBoundingBox();

        // This redirect bypasses VS2's own hook on the same call, so call VS2 here (only via the bridge class).
        Vec3 pVecAdjusted = pVec;
        if (pVec.lengthSqr() != 0.0 && khp$isVs2Present()) {
            pVecAdjusted = Vs2ShipCollisionBridge.adjustForShipCollisions(self, pVec, aabb.expandTowards(pVec));
        }

        List<VoxelShape> list = khp$preciseEntityCollisions(self, aabb.expandTowards(pVecAdjusted));

        Vec3 vec3 = pVecAdjusted.lengthSqr() == 0.0
                ? pVecAdjusted
                : Entity.collideBoundingBox(self, pVecAdjusted, aabb, self.level(), list);

        boolean movedX = pVecAdjusted.x != vec3.x;
        boolean movedY = pVecAdjusted.y != vec3.y;
        boolean movedZ = pVecAdjusted.z != vec3.z;
        boolean onGroundish = self.onGround() || (movedY && pVecAdjusted.y < 0.0);
        float stepHeight = self.getStepHeight();

        if (stepHeight > 0.0f && onGroundish && (movedX || movedZ)) {
            Vec3 stepAttempt = Entity.collideBoundingBox(
                    self, new Vec3(pVecAdjusted.x, stepHeight, pVecAdjusted.z), aabb, self.level(), list);
            Vec3 headroomCheck = Entity.collideBoundingBox(
                    self, new Vec3(0.0, stepHeight, 0.0), aabb.expandTowards(pVecAdjusted.x, 0.0, pVecAdjusted.z), self.level(), list);

            if (headroomCheck.y < stepHeight) {
                Vec3 alt = Entity.collideBoundingBox(
                                self, new Vec3(pVecAdjusted.x, 0.0, pVecAdjusted.z), aabb.move(headroomCheck), self.level(), list)
                        .add(headroomCheck);
                if (alt.horizontalDistanceSqr() > stepAttempt.horizontalDistanceSqr()) {
                    stepAttempt = alt;
                }
            }
            if (stepAttempt.horizontalDistanceSqr() > vec3.horizontalDistanceSqr()) {
                Vec3 settle = Entity.collideBoundingBox(
                        self, new Vec3(0.0, -stepAttempt.y + pVecAdjusted.y, 0.0), aabb.move(stepAttempt), self.level(), list);
                return stepAttempt.add(settle);
            }
        }

        return vec3;
    }

    private List<VoxelShape> khp$preciseEntityCollisions(Entity self, AABB pCollisionBox) {
        if (pCollisionBox.getSize() < 1.0E-7) {
            return List.of();
        }

        Predicate<Entity> vanillaPredicate = EntitySelector.NO_SPECTATORS.and(self::canCollideWith);

        // Checked directly since subclasses can override canBeCollidedWith().
        Predicate<Entity> khpSolidPredicate = candidate ->
                SolidCollisionShapes.isSolidMob(candidate)
                        && !candidate.isSpectator()
                        && !candidate.isPassengerOfSameVehicle(self);

        Predicate<Entity> predicate = vanillaPredicate.or(khpSolidPredicate);

        // Entities without a precise shape this tick are skipped (non-solid) rather than given an invisible box.
        List<VoxelShape> shapes = new ArrayList<>();

        // Found by real extent, so geometry reaching past the vanilla box still collides.
        Map<Entity, EntityHitboxSnapshot> extentCandidates = MahApi.gatherExtentCandidates(
                self.level(), pCollisionBox, predicate, SolidCollisionShapes::resolveCollisionSnapshot);
        for (Map.Entry<Entity, EntityHitboxSnapshot> entry : extentCandidates.entrySet()) {
            List<VoxelShape> precise = SolidCollisionShapes.shapesFromSnapshot(entry.getKey(), entry.getValue());
            if (precise != null) shapes.addAll(precise);
        }

        // Entities with no captured geometry yet.
        List<Entity> candidates = self.level().getEntities(self, pCollisionBox.inflate(1.0E-7), predicate);
        for (Entity candidate : candidates) {
            if (extentCandidates.containsKey(candidate)) continue;
            List<VoxelShape> precise = SolidCollisionShapes.preciseShapesOrNull(candidate);
            if (precise != null) shapes.addAll(precise);
        }

        return List.copyOf(shapes);
    }
}
