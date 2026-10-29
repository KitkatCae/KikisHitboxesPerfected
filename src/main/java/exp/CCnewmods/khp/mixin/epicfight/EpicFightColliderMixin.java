package exp.CCnewmods.khp.mixin.epicfight;

import exp.CCnewmods.khp.duck.MahColliderAccess;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import yesman.epicfight.api.collider.Collider;

/** See {@link MahColliderAccess}. */
@Mixin(value = Collider.class, remap = false)
public abstract class EpicFightColliderMixin implements MahColliderAccess {

    @Shadow(remap = false) protected Vec3 worldCenter;

    @Override
    public Vec3 mah$getWorldCenter() {
        return worldCenter;
    }
}
