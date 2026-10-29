package exp.CCnewmods.khp.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

/**
 * Holds every VS2 reference for {@code EntityCollideEntityShapesMixin}, so it only loads when VS2 is present.
 * Calls the VS2 ship-collision step that the mixin's redirect would otherwise bypass.
 */
public final class Vs2ShipCollisionBridge {

    /** TODO: no ≤0.5-block sub-stepping like VS2's own, so very fast entities could tunnel through a platform. */
    public static Vec3 adjustForShipCollisions(Entity entity, Vec3 movement, AABB entityBoundingBox) {
        return EntityShipCollisionUtils.INSTANCE.adjustEntityMovementForShipCollisions(
                entity, movement, entityBoundingBox, entity.level());
    }

    private Vs2ShipCollisionBridge() {
    }
}
