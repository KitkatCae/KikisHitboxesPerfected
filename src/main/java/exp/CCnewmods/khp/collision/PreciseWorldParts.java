package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** World-space part boxes for any living entity with a snapshot; used by AABB resizing and culling. */
public final class PreciseWorldParts {

    /** maxParts is in the key because collision and culling use different caps. */
    private static final Map<Long, List<AABB>> CACHE = new ConcurrentHashMap<>();

    /** {@code null} = use vanilla's box. */
    @Nullable
    public static List<AABB> resolveOrNull(Entity entity, int maxParts) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return null;
        }

        EntityHitboxSnapshot snapshot = MahApi.getSnapshot(living);
        if (snapshot == null) {
            return null;
        }

        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty() || parts.size() > maxParts) {
            return null;
        }

        long cacheKey = (((long) entity.getId()) << 40)
                ^ ((snapshot.gameTime() & 0xFFFFFL) << 20)
                ^ (maxParts & 0xFFFFF);
        List<AABB> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Vec3 currentAnchor = MahApi.currentAnchorFor(entity, snapshot);
        List<AABB> boxes = new ArrayList<>(parts.size());
        for (PartOBB part : parts) {
            boxes.add(worldBoundsOf(part, currentAnchor));
        }
        List<AABB> result = List.copyOf(boxes);

        CACHE.put(cacheKey, result);
        return result;
    }

    public static AABB union(List<AABB> boxes) {
        AABB result = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            result = result.minmax(boxes.get(i));
        }
        return result;
    }

    private static AABB worldBoundsOf(PartOBB part, Vec3 currentAnchor) {
        Matrix4f worldTransform = new Matrix4f().translate(
                (float) currentAnchor.x, (float) currentAnchor.y, (float) currentAnchor.z).mul(part.transform());

        AABB localBox = part.localBox();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[] xs = {localBox.minX, localBox.maxX};
        double[] ys = {localBox.minY, localBox.maxY};
        double[] zs = {localBox.minZ, localBox.maxZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector3f w = worldTransform.transformPosition(new Vector3f((float) x, (float) y, (float) z));
                    minX = Math.min(minX, w.x);
                    minY = Math.min(minY, w.y);
                    minZ = Math.min(minZ, w.z);
                    maxX = Math.max(maxX, w.x);
                    maxY = Math.max(maxY, w.y);
                    maxZ = Math.max(maxZ, w.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private PreciseWorldParts() {
    }
}
