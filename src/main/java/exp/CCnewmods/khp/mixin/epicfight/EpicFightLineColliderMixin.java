package exp.CCnewmods.khp.mixin.epicfight;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import exp.CCnewmods.khp.duck.MahColliderAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.collider.LineCollider;

import java.util.List;

/** Epic Fight line colliders (thin weapons) use captured geometry; same mirrored space as the OBB mixin. */
@Mixin(value = LineCollider.class, remap = false)
public abstract class EpicFightLineColliderMixin {

    @Shadow(remap = false) protected Vec3 worldVec;

    @Inject(method = "isCollide(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void khp$redirectToMahCombat(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!MahConfig.enabled) return;

        if (MahApi.getCombatSnapshot(entity) == null) {
            return;
        }

        Vec3 worldCenter = ((MahColliderAccess) (Object) this).mah$getWorldCenter();
        Vec3 start = khp$mirror(worldCenter);
        Vec3 end = khp$mirror(worldCenter.add(worldVec));
        Vec3 rayVec = end.subtract(start);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) {
            return;
        }

        List<PartHitResult> hits = MahApi.raycastEntityCombat(entity, start, rayVec, maxDistance);
        cir.setReturnValue(hits != null && !hits.isEmpty());
        cir.cancel();
    }

    private static Vec3 khp$mirror(Vec3 v) {
        return new Vec3(-v.x, v.y, -v.z);
    }
}
