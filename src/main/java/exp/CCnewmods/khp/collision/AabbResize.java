package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.MahConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional (off by default): grows a mob's vanilla collision box to cover its model. STABLE keeps a per-entity
 * envelope that only grows (may be small right after spawn); LIVE follows the current pose. Envelopes are
 * never pruned.
 */
public final class AabbResize {

    public enum Mode {
        STABLE,
        LIVE
    }

    private static final Map<UUID, AABB> STABLE_ENVELOPES = new ConcurrentHashMap<>();

    /** @return the resized box, or {@code null} to keep vanilla's. */
    @Nullable
    public static AABB resizedBoxOrNull(Entity entity) {
        if (!MahConfig.aabbResizeEnabled) {
            return null;
        }

        List<AABB> parts = PreciseWorldParts.resolveOrNull(entity, MahConfig.aabbResizeMaxParts);
        if (parts == null) {
            return null;
        }

        AABB worldUnion = PreciseWorldParts.union(parts);

        if (MahConfig.aabbResizeMode == Mode.LIVE) {
            return worldUnion;
        }

        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        AABB localBox = worldUnion.move(-ex, -ey, -ez);

        AABB envelope = STABLE_ENVELOPES.merge(entity.getUUID(), localBox, AABB::minmax);
        return envelope.move(ex, ey, ez);
    }

    private AabbResize() {
    }
}
