package exp.CCnewmods.khp.mixin.vs2;

import exp.CCnewmods.khp.livingplatform.LivingPlatformShipManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import java.util.UUID;

/** Excludes a living platform's ship from its own anchoring entity's VS2 collision. */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class EntityShipCollisionUtilsMixin {

    @Redirect(method = "getShipPolygonsCollidingWithEntity", at = @At(value = "INVOKE",
            target = "Lorg/valkyrienskies/mod/common/util/EntityShipCollisionUtils;mayShipIntersectLocalAabb(Lorg/valkyrienskies/core/api/ships/Ship;Lorg/joml/primitives/AABBdc;)Z"))
    private boolean khp$excludeOwningEntity(Ship ship, AABBdc aabb,
                                             Entity entity, Vec3 movement, AABB entityBoundingBox, Level world) {
        boolean vanillaResult = EntityShipCollisionUtils.mayShipIntersectLocalAabb(ship, aabb);
        if (!vanillaResult) return false;
        if (entity == null) return true;

        UUID owningEntityUuid = LivingPlatformShipManager.resolveOwningEntityUuid(ship);
        return owningEntityUuid == null || !owningEntityUuid.equals(entity.getUUID());
    }
}
