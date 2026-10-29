package exp.CCnewmods.khp.blueprint;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded blueprints, keyed by (entity type, baby), replaced on each reload. */
public final class BlueprintStore {

    private record Key(ResourceLocation entityType, boolean baby) {
    }

    private static final Map<Key, EntityBlueprint> BLUEPRINTS = new ConcurrentHashMap<>();

    public static void reload(Map<ResourceLocation, EntityBlueprint> adults,
                               Map<ResourceLocation, EntityBlueprint> babies) {
        Map<Key, EntityBlueprint> next = new ConcurrentHashMap<>();
        adults.forEach((type, bp) -> next.put(new Key(type, false), bp));
        babies.forEach((type, bp) -> next.put(new Key(type, true), bp));
        BLUEPRINTS.clear();
        BLUEPRINTS.putAll(next);
    }

    /** @param baby most types have no baby blueprint; callers fall back to the adult one. */
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
