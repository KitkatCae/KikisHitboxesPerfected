package exp.CCnewmods.khp.mixin.epicfight;

import exp.CCnewmods.khp.duck.MahColliderAccess;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import yesman.epicfight.api.collider.Collider;

/**
 * Shadows {@code worldCenter} on its actual declaring class, {@code Collider} —
 * see {@link MahColliderAccess}'s javadoc for why this exists as its own mixin
 * rather than each subclass mixin shadowing the field directly.
 */
@Mixin(value = Collider.class, remap = false)
public abstract class EpicFightColliderMixin implements MahColliderAccess {

    @Shadow(remap = false) protected Vec3 worldCenter;

    @Override
    public Vec3 mah$getWorldCenter() {
        return worldCenter;
    }
}
