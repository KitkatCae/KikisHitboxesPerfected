package exp.CCnewmods.khp.mixin.epicfight;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.CastShape;
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
import yesman.epicfight.api.collider.OBBCollider;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.List;

/**
 * Tests Epic Fight OBB weapon colliders against captured geometry instead of the target's vanilla box. Epic
 * Fight's collider space mirrors world X and Z ({@link #khp$mirror}). Only the 4-vertex box form of
 * {@code OBBCollider} is handled. TODO: verify in game.
 */
@Mixin(value = OBBCollider.class, remap = false)
public abstract class EpicFightOBBColliderMixin {

    // worldCenter lives on the Collider superclass; read via MahColliderAccess.
    @Shadow(remap = false) protected Vec3[] modelVertices;
    @Shadow(remap = false) protected Vec3[] rotatedVertices;
    @Shadow(remap = false) protected Vec3[] rotatedNormals;
    @Shadow(remap = false) protected Vec3f scale;

    @Inject(method = "isCollide(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void khp$redirectToMahCombat(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!MahConfig.enabled) return;

        if (rotatedVertices == null || rotatedVertices.length != 4
                || rotatedNormals == null || rotatedNormals.length != 3
                || modelVertices == null || modelVertices.length != 4
                || scale == null) {
            return;
        }

        if (MahApi.getCombatSnapshot(entity) == null) {
            return;
        }

        Vec3 center = khp$mirror(((MahColliderAccess) (Object) this).mah$getWorldCenter());
        Vec3 axisX = khp$mirror(rotatedNormals[0]);
        Vec3 axisY = khp$mirror(rotatedNormals[1]);
        Vec3 axisZ = khp$mirror(rotatedNormals[2]);

        // (vertexX, vertexY, vertexZ) for this constructor; no mirroring needed for a magnitude.
        Vec3 v = modelVertices[1];
        Vec3 halfExtents = new Vec3(
                Math.abs(v.x) * scale.x, Math.abs(v.y) * scale.y, Math.abs(v.z) * scale.z);

        CastShape.BoxShape box = new CastShape.BoxShape(center, halfExtents, axisX, axisY, axisZ);

        List<PartHitResult> hits = MahApi.shapecastEntityCombat(entity, box);
        cir.setReturnValue(hits != null && !hits.isEmpty());
        cir.cancel();
    }

    private static Vec3 khp$mirror(Vec3 v) {
        return new Vec3(-v.x, v.y, -v.z);
    }
}
