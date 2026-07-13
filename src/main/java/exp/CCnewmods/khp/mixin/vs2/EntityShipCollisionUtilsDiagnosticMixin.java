package exp.CCnewmods.khp.mixin.vs2;

import exp.CCnewmods.khp.Mah;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KHP Phase 2E, §16 addendum — pure diagnostic, no behavior change. Isolates
 * whether VS2's own ship-collision entry point
 * ({@code adjustEntityMovementForShipCollisions}, called from VS2's
 * {@code MixinEntity.collideWithShips}, a {@code @WrapOperation} on
 * {@code Entity.collide(Vec3)} inside {@code move()} — the exact same call
 * site KHP's own {@code EntityCollideEntityShapesMixin} already
 * {@code @Redirect}s) is even being reached at all, separate from whether
 * {@link EntityShipCollisionUtilsMixin}'s own redirect further downstream
 * applies. If this never logs but the other mixin's diagnostic does, VS2's
 * whole ship-collision pathway is being skipped upstream — likely by KHP's
 * own redirect on the same call site — and the real fix is making
 * {@code EntityCollideEntityShapesMixin} itself VS2-aware, not anything in
 * this package. If both log, the exclusion logic itself needs another look.
 * Remove once resolved either way.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class EntityShipCollisionUtilsDiagnosticMixin {

    private static final AtomicBoolean khp$confirmed = new AtomicBoolean(false);

    @Inject(method = "adjustEntityMovementForShipCollisions", at = @At("HEAD"))
    private void khp$diag(Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
                           CallbackInfoReturnable<Vec3> cir) {
        if (khp$confirmed.compareAndSet(false, true)) {
            Mah.LOGGER.info("[KHP DIAG] VS2's adjustEntityMovementForShipCollisions IS being reached "
                    + "(first call, entity={}, level={})", entity != null ? entity.getType() : "null",
                    world.isClientSide() ? "client" : "server");
        }
    }
}
