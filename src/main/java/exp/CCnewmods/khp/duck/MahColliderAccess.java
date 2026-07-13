package exp.CCnewmods.khp.duck;

import net.minecraft.world.phys.Vec3;

/**
 * Duck interface applied to Epic Fight's {@code Collider} (the abstract superclass
 * of {@code OBBCollider}/{@code LineCollider}/etc.) by {@code EpicFightColliderMixin}.
 *
 * <p>Exists because {@code @Shadow} only resolves fields declared directly on a
 * mixin's own {@code @Mixin} target class, not inherited ones — confirmed the hard
 * way: {@code EpicFightOBBColliderMixin} originally {@code @Shadow}ed
 * {@code worldCenter} directly (it's actually declared on {@code Collider}, not
 * {@code OBBCollider}), which threw {@code InvalidMixinException: @Shadow field
 * worldCenter was not located in the target class ... OBBCollider} at mixin-apply
 * time in-game. Mixing into {@code Collider} itself — where the field is actually
 * declared — and exposing it through this duck interface is this project's existing
 * convention for exactly this situation (see {@code MahNamedPart}); every
 * {@code Collider} subclass (including {@code OBBCollider}/{@code LineCollider})
 * inherits this interface implementation automatically since it's woven into the
 * shared superclass.
 */
public interface MahColliderAccess {

    Vec3 mah$getWorldCenter();
}
