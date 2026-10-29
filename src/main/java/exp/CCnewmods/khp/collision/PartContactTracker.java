package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Carries entities resting against a solid mob's animated parts along with the part, instead of letting vanilla
 * collision shove them out (which stutters). Each contact tracks a fixed point in the part's local space so
 * rotation carries correctly. Runs from the mob's tick, so standing-still players get carried too.
 */
public final class PartContactTracker {

    private record OwnerState(Vec3 anchor, Map<Short, PartOBB> partsByBone) {
    }

    private record ContactRecord(Vector3f localPoint, long lastSeenGameTime) {
    }

    /** Per (owner, part, candidate): one entity can touch two parts at once. */
    private record ContactKey(int ownerId, short boneId, int candidateId) {
    }

    private static final Map<Integer, OwnerState> OWNER_STATE = new ConcurrentHashMap<>();
    private static final Map<ContactKey, ContactRecord> CONTACTS = new ConcurrentHashMap<>();

    public static void tick(Entity owner) {
        if (!MahConfig.displacementTransferEnabled) return;
        if (owner.level().isClientSide()) return;

        EntityHitboxSnapshot snapshot = SolidCollisionShapes.resolveCollisionSnapshot(owner);
        if (snapshot == null) {
            OWNER_STATE.remove(owner.getId());
            return;
        }
        List<PartOBB> parts = snapshot.parts();
        if (parts.isEmpty() || parts.size() > MahConfig.solidCollisionMaxParts) {
            OWNER_STATE.remove(owner.getId());
            return;
        }

        Vec3 currentAnchor = MahApi.currentAnchorFor(owner, snapshot);
        Map<Short, PartOBB> currentByBone = new HashMap<>(parts.size());
        for (PartOBB part : parts) {
            currentByBone.put(part.boneId(), part);
        }

        OwnerState previous = OWNER_STATE.put(owner.getId(), new OwnerState(currentAnchor, currentByBone));
        if (previous == null) {
            return;
        }

        long now = owner.level().getGameTime();
        for (Map.Entry<Short, PartOBB> entry : currentByBone.entrySet()) {
            short boneId = entry.getKey();
            PartOBB currPart = entry.getValue();
            PartOBB prevPart = previous.partsByBone().get(boneId);
            if (prevPart == null) continue;

            Matrix4f prevWorld = worldTransform(previous.anchor(), prevPart);
            Matrix4f currWorld = worldTransform(currentAnchor, currPart);

            if (!movedEnough(prevPart.localBox(), prevWorld, currWorld, MahConfig.displacementTransferEpsilon)) {
                continue;
            }

            AABB prevWorldBounds = worldBoundsOf(prevPart.localBox(), prevWorld);
            carryContactsForPart(owner, boneId, prevPart.localBox(), prevWorld, currWorld, prevWorldBounds, now);
        }

        int ownerId = owner.getId();
        CONTACTS.entrySet().removeIf(e -> e.getKey().ownerId() == ownerId && e.getValue().lastSeenGameTime() != now);
    }

    private static void carryContactsForPart(Entity owner, short boneId, AABB localBox,
                                              Matrix4f prevWorld, Matrix4f currWorld,
                                              AABB prevWorldBounds, long now) {
        Predicate<Entity> predicate = candidate -> candidate != owner
                && !candidate.isSpectator()
                && !candidate.isPassengerOfSameVehicle(owner)
                && candidate.getVehicle() != owner; // riders are positioned by the ride

        List<Entity> candidates = owner.level().getEntities(owner, prevWorldBounds.inflate(1.0E-7), predicate);

        for (Entity candidate : candidates) {
            ContactKey key = new ContactKey(owner.getId(), boneId, candidate.getId());
            ContactRecord existing = CONTACTS.get(key);
            Vector3f localPoint = existing != null
                    ? existing.localPoint()
                    : deriveContactPointLocal(candidate, localBox, prevWorld);

            Vec3 prevWorldPoint = transformPoint(prevWorld, localPoint);
            Vec3 currWorldPoint = transformPoint(currWorld, localPoint);
            Vec3 delta = currWorldPoint.subtract(prevWorldPoint);

            if (delta.lengthSqr() > 1.0E-12) {
                // PISTON: vanilla's "pushed by an external solid", still collision-checked.
                candidate.move(MoverType.PISTON, delta);
            }

            CONTACTS.put(key, new ContactRecord(localPoint, now));
        }
    }

    /** Only used when contact begins; later ticks reuse the stored point. */
    private static Vector3f deriveContactPointLocal(Entity candidate, AABB localBox, Matrix4f prevWorld) {
        Vec3 pos = candidate.position();
        Matrix4f inverse = new Matrix4f(prevWorld).invert();
        Vector3f localPos = inverse.transformPosition(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
        float cx = Mth.clamp(localPos.x, (float) localBox.minX, (float) localBox.maxX);
        float cy = Mth.clamp(localPos.y, (float) localBox.minY, (float) localBox.maxY);
        float cz = Mth.clamp(localPos.z, (float) localBox.minZ, (float) localBox.maxZ);
        return new Vector3f(cx, cy, cz);
    }

    private static Matrix4f worldTransform(Vec3 anchor, PartOBB part) {
        return new Matrix4f().translate((float) anchor.x, (float) anchor.y, (float) anchor.z).mul(part.transform());
    }

    private static Vec3 transformPoint(Matrix4f worldTransform, Vector3f local) {
        Vector3f world = worldTransform.transformPosition(new Vector3f(local));
        return new Vec3(world.x, world.y, world.z);
    }

    private static AABB worldBoundsOf(AABB localBox, Matrix4f worldTransform) {
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

    /** Checks all 8 corners, so a part rotating in place still counts. */
    private static boolean movedEnough(AABB localBox, Matrix4f prevWorld, Matrix4f currWorld, double epsilon) {
        double epsilonSqr = epsilon * epsilon;
        double[] xs = {localBox.minX, localBox.maxX};
        double[] ys = {localBox.minY, localBox.maxY};
        double[] zs = {localBox.minZ, localBox.maxZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector3f local = new Vector3f((float) x, (float) y, (float) z);
                    Vector3f prevP = prevWorld.transformPosition(new Vector3f(local));
                    Vector3f currP = currWorld.transformPosition(new Vector3f(local));
                    double dx = currP.x - prevP.x, dy = currP.y - prevP.y, dz = currP.z - prevP.z;
                    if (dx * dx + dy * dy + dz * dz > epsilonSqr) return true;
                }
            }
        }
        return false;
    }

    private PartContactTracker() {
    }
}
