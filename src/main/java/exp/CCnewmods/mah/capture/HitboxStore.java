package exp.CCnewmods.mah.capture;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global storage for captured hitbox snapshots and bone tables.
 *
 * <p>On the physical client this is written by the render thread every frame. On an
 * <b>integrated server</b> the server thread reads the very same maps — same JVM, and
 * both snapshots and bone tables are immutable/thread-safe, so live animated hitboxes
 * are available to server logic for free (design §4, source priority 1). The dedicated
 * -server sources (headless resolver, blueprints) land in phase 3 and publish into the
 * same store on the server side.
 */
public final class HitboxStore {

    private static final Map<UUID, EntityHitboxSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, BoneTable> BONE_TABLES = new ConcurrentHashMap<>();

    public static void put(UUID entityUuid, EntityHitboxSnapshot snapshot) {
        SNAPSHOTS.put(entityUuid, snapshot);
    }

    @Nullable
    public static EntityHitboxSnapshot get(UUID entityUuid) {
        return SNAPSHOTS.get(entityUuid);
    }

    public static void remove(UUID entityUuid) {
        SNAPSHOTS.remove(entityUuid);
    }

    /** All current snapshots — live view for iteration (debug renderer). */
    public static Map<UUID, EntityHitboxSnapshot> view() {
        return SNAPSHOTS;
    }

    public static void clearSnapshots() {
        SNAPSHOTS.clear();
    }

    public static BoneTable getOrCreateBoneTable(ResourceLocation entityKey) {
        return BONE_TABLES.computeIfAbsent(entityKey, k -> new BoneTable());
    }

    @Nullable
    public static BoneTable getBoneTable(ResourceLocation entityKey) {
        return BONE_TABLES.get(entityKey);
    }

    private HitboxStore() {
    }
}
