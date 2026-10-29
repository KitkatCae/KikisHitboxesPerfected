package exp.CCnewmods.khp.duck;

import net.minecraft.world.phys.Vec3;

/** Exposes Epic Fight's {@code Collider.worldCenter}, which subclass mixins can't {@code @Shadow}. */
public interface MahColliderAccess {

    Vec3 mah$getWorldCenter();
}
