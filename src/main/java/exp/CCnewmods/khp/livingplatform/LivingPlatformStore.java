package exp.CCnewmods.khp.livingplatform;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;

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
