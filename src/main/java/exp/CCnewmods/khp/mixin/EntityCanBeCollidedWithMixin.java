package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.collision.SolidCollisionShapes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Solid mobs report {@code canBeCollidedWith() = true}. Movement collision checks solidity separately. */
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
