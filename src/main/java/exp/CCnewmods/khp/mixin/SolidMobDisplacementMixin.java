package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.collision.PartContactTracker;
import exp.CCnewmods.khp.collision.SolidCollisionShapes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs {@link PartContactTracker#tick} for solid mobs at TAIL, after the mob's own movement and animation. */
@Mixin(LivingEntity.class)
public abstract class SolidMobDisplacementMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void khp$carryDisplacedContacts(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (SolidCollisionShapes.isSolidMob(self)) {
            PartContactTracker.tick(self);
        }
    }
}
