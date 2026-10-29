package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/** Melee picking and arrow hits use {@link MahApi#raycastCombat}. */
// m_37287_ = getEntityHitResult (melee overload)
// m_150175_ = getEntityHitResult (arrow overload; not the 6-arg delegate m_37304_)
@Mixin(value = ProjectileUtil.class, remap = false)
public abstract class ProjectileUtilMixin {

    @Inject(
            method = "m_37287_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectMeleePick(Entity pShooter, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox,
                                                Predicate<Entity> pFilter, double pDistance,
                                                CallbackInfoReturnable<EntityHitResult> cir) {
        if (!MahConfig.enabled) return;

        Vec3 rayVec = pEndVec.subtract(pStartVec);
        double rayLength = rayVec.length();
        if (rayLength < 1.0e-6) return; // "already touching"; let vanilla handle it

        // pDistance is squared.
        double maxDistance = Math.min(rayLength, Math.sqrt(Math.max(pDistance, 0.0)));
        if (maxDistance < 1.0e-6) return;

        Predicate<Entity> combatFilter = e -> e != pShooter && pFilter.test(e);
        List<PartHitResult> hits = MahApi.raycastCombat(pShooter.level(), pStartVec, rayVec, maxDistance, combatFilter);
        if (hits != null && !hits.isEmpty()) {
            PartHitResult nearest = hits.get(0);
            cir.setReturnValue(new EntityHitResult(nearest.entity(), nearest.worldEntryPoint()));
            cir.cancel();
            return;
        }

        if (MahApi.hasCombatCandidates(pShooter.level(), pStartVec, rayVec, maxDistance, combatFilter)) {
            // A trusted entity was in range but missed: cancel, or vanilla's bigger box would still hit.
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }
    }

    @Inject(
            method = "m_150175_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectArrowHit(Level pLevel, Entity pProjectile, Vec3 pStartVec, Vec3 pEndVec,
                                               AABB pBoundingBox, Predicate<Entity> pFilter, float pInflationAmount,
                                               CallbackInfoReturnable<EntityHitResult> cir) {
        if (!MahConfig.enabled) return;

        Vec3 rayVec = pEndVec.subtract(pStartVec);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) return;

        Predicate<Entity> combatFilter = e -> e != pProjectile && pFilter.test(e);
        List<PartHitResult> hits = MahApi.raycastCombat(pLevel, pStartVec, rayVec, maxDistance, combatFilter);
        if (hits != null && !hits.isEmpty()) {
            PartHitResult nearest = hits.get(0);
            cir.setReturnValue(new EntityHitResult(nearest.entity(), nearest.worldEntryPoint()));
            cir.cancel();
            return;
        }

        if (MahApi.hasCombatCandidates(pLevel, pStartVec, rayVec, maxDistance, combatFilter)) {
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }
    }
}
