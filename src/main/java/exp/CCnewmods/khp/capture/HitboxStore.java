package exp.CCnewmods.khp.capture;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All captured snapshots and bone tables. Written by the client render thread; on an integrated server the
 * server reads the same maps. On dedicated servers it's filled from blueprints.
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
