package exp.CCnewmods.khp.mixin.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Locale;

/**
 * TACZ bullet hits use captured geometry. No trusted geometry: TACZ runs as normal. Trusted but missed: cancel
 * with {@code null}, so TACZ's bigger box can't turn a miss into a hit.
 */
@Mixin(value = EntityUtil.class, remap = false)
public abstract class TaczEntityUtilMixin {

    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectToMahCombat(Projectile bulletEntity, Entity entity, Vec3 startVec, Vec3 endVec,
                                                  CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (!MahConfig.enabled) return;

        if (MahApi.getCombatSnapshot(entity) == null) {
            return;
        }

        Vec3 rayVec = endVec.subtract(startVec);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) {
            return;
        }

        List<PartHitResult> hits = MahApi.raycastEntityCombat(entity, startVec, rayVec, maxDistance);
        if (hits == null || hits.isEmpty()) {
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }

        PartHitResult nearest = hits.get(0);
        boolean headshot = nearest.boneName() != null
                && nearest.boneName().toLowerCase(Locale.ROOT).contains("head");
        cir.setReturnValue(new EntityKineticBullet.EntityResult(entity, nearest.worldEntryPoint(), headshot));
        cir.cancel();
    }
}
