package exp.CCnewmods.khp.blueprint;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global storage for loaded {@link EntityBlueprint}s, populated by
 * {@link BlueprintReloadListener} on every resource/datapack reload.
 *
 * <p>Mirrors {@code HitboxStore}'s shape deliberately — same "static store written by
 * one path, read by another" pattern, so a consumer resolving "do I have a blueprint
 * for this entity" looks structurally identical to "do I have a live snapshot for this
 * entity" (see {@code MahApi} usage once combat interception lands in Phase 2C).
 *
 * <p>Keyed by {@code (entityType, baby)} rather than entity instance/UUID — unlike
 * {@code HitboxStore}'s per-instance snapshots, a blueprint is shared across every
 * entity of the same type (and baby/adult variant), so there's exactly one
 * {@link EntityBlueprint} per key regardless of how many live entities of that type
 * exist.
 */
public final class BlueprintStore {

    private record Key(ResourceLocation entityType, boolean baby) {
    }

    private static final Map<Key, EntityBlueprint> BLUEPRINTS = new ConcurrentHashMap<>();

    /** Replaces the entire loaded set — called once per reload by {@link BlueprintReloadListener}. */
    public static void reload(Map<ResourceLocation, EntityBlueprint> adults,
                               Map<ResourceLocation, EntityBlueprint> babies) {
        Map<Key, EntityBlueprint> next = new ConcurrentHashMap<>();
        adults.forEach((type, bp) -> next.put(new Key(type, false), bp));
        babies.forEach((type, bp) -> next.put(new Key(type, true), bp));
        BLUEPRINTS.clear();
        BLUEPRINTS.putAll(next);
    }

    /**
     * @param baby true to look up the baby-variant blueprint. If no baby-variant file
     *             exists for this entity type, callers should fall back to
     *             {@code get(entityType, false)} themselves (a baby blueprint missing
     *             is common — most entity types have no baby form at all — and falling
     *             back to the adult blueprint's poses is a reasonable default rather
     *             than having no geometry at all).
     */
    @Nullable
    public static EntityBlueprint get(ResourceLocation entityType, boolean baby) {
        return BLUEPRINTS.get(new Key(entityType, baby));
    }

    public static boolean has(ResourceLocation entityType, boolean baby) {
        return BLUEPRINTS.containsKey(new Key(entityType, baby));
    }

    public static int size() {
        return BLUEPRINTS.size();
    }

    private BlueprintStore() {
    }
}
