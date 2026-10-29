package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.blueprint.BlueprintResolver;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-part collision shapes for solid mobs, fed to vanilla's normal entity collision. Accepts stale or
 * blueprint snapshots (unlike combat), since slightly-off collision isn't a fairness problem.
 */
public final class SolidCollisionShapes {

    /** Keyed by entity id + snapshot gameTime; movement can query the same entity several times a tick. */
    private static final Map<Long, List<VoxelShape>> CACHE = new ConcurrentHashMap<>();

    /** Also checked directly by EntityCollideEntityShapesMixin, since subclasses can override canBeCollidedWith(). */
    public static boolean isSolidMob(Entity entity) {
        if (!MahConfig.solidCollisionEnabled) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(living.getType());
        return typeId == null || !MahConfig.solidCollisionBlacklist.contains(typeId);
    }

    /**
     * One shape per part, or {@code null} for vanilla's box. Deliberately not {@code Shapes.or}-merged: merging
     * every tick froze the game around dense schools of fish.
     */
    @Nullable
    public static List<VoxelShape> preciseShapesOrNull(Entity entity) {
        if (!isSolidMob(entity)) {
            return null;
        }

        EntityHitboxSnapshot snapshot = resolveCollisionSnapshot(entity);
        if (snapshot == null) {
            return null;
        }

        return shapesFromSnapshot(entity, snapshot);
    }

    @Nullable
    public static List<VoxelShape> shapesFromSnapshot(Entity entity, EntityHitboxSnapshot snapshot) {
        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty() || parts.size() > MahConfig.solidCollisionMaxParts) {
            return null;
        }

        long cacheKey = (((long) entity.getId()) << 32) ^ (snapshot.gameTime() & 0xFFFFFFFFL);
        List<VoxelShape> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Vec3 currentAnchor = currentAnchor(entity, snapshot);
        List<VoxelShape> shapes = new ArrayList<>(parts.size());
        for (PartOBB part : parts) {
            AABB worldBounds = worldBoundsOf(part, currentAnchor);
            shapes.add(Shapes.create(worldBounds));
        }
        List<VoxelShape> result = List.copyOf(shapes);

        CACHE.put(cacheKey, result);

        return result;
    }

    /** Like {@code MahApi.getCombatSnapshot} but without the blueprint allowlist. */
    @Nullable
    public static EntityHitboxSnapshot resolveCollisionSnapshot(Entity entity) {
        boolean fresh = MahApi.hasFreshSnapshot(entity, MahConfig.solidCollisionStaleSnapshotTicks);
        return fresh ? HitboxStore.get(entity.getUUID()) : BlueprintResolver.resolve(entity);
    }

    private static Vec3 currentAnchor(Entity entity, EntityHitboxSnapshot snap) {
        return MahApi.currentAnchorFor(entity, snap);
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

    private SolidCollisionShapes() {
    }
}