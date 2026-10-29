package exp.CCnewmods.khp.capture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Immutable snapshot of one entity's captured geometry; safe across threads.
 *
 * @param anchorPos         render origin at capture time.
 * @param capturedEntityPos {@code entity.position()} at capture; the current anchor is
 *                          {@code anchorPos + (entity.position() - capturedEntityPos)}.
 * @param localBounds       envelope of all parts in entity-local space; used to find entities by real reach.
 * @param texture           used by flat parts' per-pixel raycast test; may be {@code null}.
 */
public record EntityHitboxSnapshot(ResourceLocation entityKey,
                                   int entityId,
                                   Vec3 anchorPos,
                                   Vec3 capturedEntityPos,
                                   float bodyYaw,
                                   long gameTime,
                                   List<PartOBB> parts,
                                   AABB localBounds,
                                   SnapshotSource source,
                                   ResourceLocation texture) {

    public EntityHitboxSnapshot {
        parts = List.copyOf(parts);
        if (localBounds == null) localBounds = new AABB(Vec3.ZERO, Vec3.ZERO);
    }

    public boolean isStale(long now, int staleTicks) {
        return now - gameTime > staleTicks;
    }

    /** {@link #localBounds} in world space at {@code currentAnchor}. */
    public AABB worldBounds(Vec3 currentAnchor) {
        return localBounds.move(currentAnchor);
    }

    public static AABB computeLocalBounds(List<PartOBB> parts) {
        if (parts.isEmpty()) return new AABB(Vec3.ZERO, Vec3.ZERO);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (PartOBB part : parts) {
            AABB box = part.localBox();
            Matrix4f transform = part.transform();
            for (int cx = 0; cx < 2; cx++) {
                for (int cy = 0; cy < 2; cy++) {
                    for (int cz = 0; cz < 2; cz++) {
                        float lx = (float) (cx == 0 ? box.minX : box.maxX);
                        float ly = (float) (cy == 0 ? box.minY : box.maxY);
                        float lz = (float) (cz == 0 ? box.minZ : box.maxZ);
                        Vector3f world = transform.transformPosition(new Vector3f(lx, ly, lz));
                        if (world.x < minX) minX = world.x;
                        if (world.y < minY) minY = world.y;
                        if (world.z < minZ) minZ = world.z;
                        if (world.x > maxX) maxX = world.x;
                        if (world.y > maxY) maxY = world.y;
                        if (world.z > maxZ) maxZ = world.z;
                    }
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
