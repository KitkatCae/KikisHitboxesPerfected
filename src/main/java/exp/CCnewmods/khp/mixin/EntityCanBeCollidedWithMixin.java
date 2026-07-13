package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.collision.SolidCollisionShapes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 2D, Part A1 — solid mob collision, binary axis.
 *
 * <p>Confirmed via decompile against the mapped Forge jar: there is no
 * {@code isCollidable()} in this codebase's actual mappings (that name belongs
 * to {@code solid_mobs}' own Fabric-side intermediary naming). The real hook
 * under Parchment/Forge mappings is {@link Entity#canBeCollidedWith()} —
 * defaults {@code false} on the base {@link Entity} class and is <b>not</b>
 * overridden by {@link LivingEntity}, so every ordinary living mob (vanilla or
 * custom Misanthrope) falls through to that base {@code false} today.
 *
 * <h3>Not the actual authority on movement collision</h3>
 * Some entity subclasses (vanilla or from this pack's addon mods) override
 * {@code canBeCollidedWith()} themselves, bypassing this injection entirely and
 * keeping whatever their own override returns, regardless of KHP's config.
 * {@code EntityCollideEntityShapesMixin} folds
 * {@link SolidCollisionShapes#isSolidMob} directly into its own candidate
 * predicate, independent of this method's return value, and is the actual
 * authority on whether movement is physically blocked. This mixin is kept only
 * because other, unrelated code might reasonably call {@code canBeCollidedWith()}
 * directly and expect a truthful answer for a KHP-solid mob that doesn't already
 * override it itself.
 */
@Mixin(Entity.class)
public abstract class EntityCanBeCollidedWithMixin {

    @Inject(method = "canBeCollidedWith", at = @At("RETURN"), cancellable = true)
    private void khp$solidMobCollision(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (SolidCollisionShapes.isSolidMob(self)) {
            cir.setReturnValue(true);
        }
    }
}
