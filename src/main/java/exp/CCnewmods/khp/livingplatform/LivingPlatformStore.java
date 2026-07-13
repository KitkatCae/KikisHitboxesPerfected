package exp.CCnewmods.khp.livingplatform;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Whole-reload replacement store for {@link LivingPlatformDefinition}s, keyed by
 * entity type id — same reload-swap convention as {@code BlueprintStore}. Written
 * once per resource/datapack reload by {@link LivingPlatformReloadListener}, read
 * from gameplay code (entity tick, interaction handling) the rest of the time.
 */
public final class LivingPlatformStore {

    private static volatile Map<ResourceLocation, LivingPlatformDefinition> DEFINITIONS = Map.of();

    static void reload(Map<ResourceLocation, LivingPlatformDefinition> definitions) {
        DEFINITIONS = Map.copyOf(definitions);
    }

    @Nullable
    public static LivingPlatformDefinition get(ResourceLocation entityType) {
        return DEFINITIONS.get(entityType);
    }

    public static boolean isWhitelisted(ResourceLocation entityType) {
        return DEFINITIONS.containsKey(entityType);
    }

    private LivingPlatformStore() {
    }
}
