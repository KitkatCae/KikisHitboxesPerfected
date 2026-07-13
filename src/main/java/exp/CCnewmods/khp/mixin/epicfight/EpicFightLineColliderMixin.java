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

/**
 * Redirects Epic Fight's {@code LineCollider.isCollide(Entity)} — a swept-segment-
 * vs-target-AABB test, used by weapons whose hitbox is a thin line rather than a
 * full OBB (polearm tips, thin blades — confirmed via decompile,
 * {@code KHP_Phase2C_Design.md} §2) — through {@link MahApi#raycastEntityCombat}.
 * Much lower-risk than {@link EpicFightOBBColliderMixin}: this is a direct 1:1
 * mapping from a line segment to MAH's existing ray API, no shape reconstruction.
 *
 * <p>Same X/Z-mirrored collider-space convention as {@link EpicFightOBBColliderMixin}
 * — see that class's javadoc for the two independent decompiled confirmations.
 * {@code worldCenter} (segment start) is inherited from {@code Collider} and
 * retrieved via {@link MahColliderAccess} — {@code @Shadow} can't reach it directly
 * here (same {@code InvalidMixinException} caught in real testing on the OBB mixin;
 * see that duck interface's javadoc). {@code worldVec} (segment delta) is declared
 * directly on {@code LineCollider} itself, so its own {@code @Shadow} is fine as-is.
 */
@Mixin(value = LineCollider.class, remap = false)
public abstract class EpicFightLineColliderMixin {

    @Shadow(remap = false) protected Vec3 worldVec;

    @Inject(method = "isCollide(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void khp$redirectToMahCombat(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!MahConfig.enabled) return;

        if (MahApi.getCombatSnapshot(entity) == null) {
            return; // no MAH geometry trusted for this entity right now
        }

        Vec3 worldCenter = ((MahColliderAccess) (Object) this).mah$getWorldCenter();
        Vec3 start = khp$mirror(worldCenter);
        Vec3 end = khp$mirror(worldCenter.add(worldVec));
        Vec3 rayVec = end.subtract(start);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) {
            return; // degenerate zero-length segment; let vanilla EF logic handle it
        }

        List<PartHitResult> hits = MahApi.raycastEntityCombat(entity, start, rayVec, maxDistance);
        cir.setReturnValue(hits != null && !hits.isEmpty());
        cir.cancel();
    }

    /** Epic Fight's own collider-space-to-world-space convention: negate X and Z, leave Y. */
    private static Vec3 khp$mirror(Vec3 v) {
        return new Vec3(-v.x, v.y, -v.z);
    }
}
