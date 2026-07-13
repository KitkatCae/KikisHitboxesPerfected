package exp.CCnewmods.khp.blueprint;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads every {@code data/khp/animstatecache/*.json} blueprint file each resource/
 * datapack reload and publishes the result into {@link BlueprintStore}.
 *
 * <p><b>Static-init ordering note</b> (project lesson, Phase 1 handoff §5, re-stated
 * here since this is the first {@code SimpleJsonResourceReloadListener} subclass in
 * KHP): {@code GSON} is declared before this class does anything else with it,
 * matching the known "GSON must precede INSTANCE" ordering trap in this exact base
 * class elsewhere in the project — not applicable verbatim here since KHP has no
 * {@code INSTANCE}-style static field, but kept as the first field for the same
 * reason: nothing below should be able to observe {@code GSON} as null.
 */
public final class BlueprintReloadListener extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "animstatecache";

    public BlueprintReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> prepared, ResourceManager resourceManager,
                          ProfilerFiller profiler) {
        Map<ResourceLocation, EntityBlueprint> adults = new HashMap<>();
        Map<ResourceLocation, EntityBlueprint> babies = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : prepared.entrySet()) {
            ResourceLocation fileKey = entry.getKey();
            try {
                EntityBlueprint blueprint = BlueprintSerializer.fromJson(entry.getValue().getAsJsonObject());

                if (blueprint.poses().isEmpty()) {
                    LOGGER.warn("[KHP] Blueprint file {} for entity type {} has zero poses — skipping.",
                            fileKey, blueprint.entityType());
                    continue;
                }
                if (blueprint.pose("rest") == null) {
                    LOGGER.warn("[KHP] Blueprint file {} for entity type {} has no \"rest\" pose — skipping "
                            + "(every blueprint must have one, see EntityBlueprint#restPose).", fileKey, blueprint.entityType());
                    continue;
                }

                Map<ResourceLocation, EntityBlueprint> target = blueprint.baby() ? babies : adults;
                EntityBlueprint existing = target.get(blueprint.entityType());
                if (existing != null) {
                    LOGGER.warn("[KHP] Duplicate blueprint for entity type {} (baby={}) — file {} overwrites "
                            + "an earlier one for the same entity type/variant in this reload.",
                            blueprint.entityType(), blueprint.baby(), fileKey);
                }
                target.put(blueprint.entityType(), blueprint);
            } catch (Exception e) {
                LOGGER.error("[KHP] Failed to parse blueprint file {} — skipping this file, others still load.",
                        fileKey, e);
            }
        }

        BlueprintStore.reload(adults, babies);
        LOGGER.info("[KHP] Loaded {} adult and {} baby entity blueprints from data/khp/{}/.",
                adults.size(), babies.size(), DIRECTORY);
    }
}
