package exp.CCnewmods.khp.livingplatform;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every {@code data/khp/khp_living_platforms/*.json} file each resource/
 * datapack reload and publishes the result into {@link LivingPlatformStore}. Own
 * namespace/directory, deliberately not shared with {@code vs_entities} — see
 * {@code KHP_Phase2E_Design.md} §1/§2.2.
 *
 * <p>{@code GSON} declared before anything else touches it — same static-init
 * ordering discipline as {@code BlueprintReloadListener}.
 *
 * <h3>File shape</h3>
 * <pre>{@code
 * {
 *   "entity": "misanthrope_core:some_dragon",
 *   "anchors": [
 *     { "bone": "back_plate",    "id": "back", "seed": { "width": 4, "height": 4, "depth": 4 } },
 *     { "bone": "tail_platform", "id": "tail" }
 *   ]
 * }
 * }</pre>
 * {@code seed} is optional — omitted means a single 1x1x1 cell (see
 * {@link LivingPlatformAnchor#seedOrDefault}), not "no seed"; every anchor needs
 * at least one placed cell to give players anything to build against.
 */
public final class LivingPlatformReloadListener extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "khp_living_platforms";

    public LivingPlatformReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> prepared, ResourceManager resourceManager,
                          ProfilerFiller profiler) {
        Map<ResourceLocation, LivingPlatformDefinition> definitions = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : prepared.entrySet()) {
            ResourceLocation fileKey = entry.getKey();
            try {
                JsonObject root = entry.getValue().getAsJsonObject();

                String entityStr = root.get("entity").getAsString();
                ResourceLocation entityType = ResourceLocation.tryParse(entityStr);
                if (entityType == null) {
                    LOGGER.warn("[KHP] Living platform file {} has an invalid \"entity\" id \"{}\" — skipping.",
                            fileKey, entityStr);
                    continue;
                }

                JsonArray anchorsJson = root.getAsJsonArray("anchors");
                if (anchorsJson == null || anchorsJson.isEmpty()) {
                    LOGGER.warn("[KHP] Living platform file {} for entity {} has no anchors — skipping.",
                            fileKey, entityType);
                    continue;
                }

                List<LivingPlatformAnchor> anchors = new ArrayList<>();
                java.util.Set<String> seenIds = new java.util.HashSet<>();
                for (JsonElement anchorElement : anchorsJson) {
                    JsonObject anchorObj = anchorElement.getAsJsonObject();
                    String bone = anchorObj.get("bone").getAsString();
                    String id = anchorObj.has("id") ? anchorObj.get("id").getAsString() : bone;
                    if (!seenIds.add(id)) {
                        LOGGER.warn("[KHP] Living platform file {} for entity {} has a duplicate anchor id "
                                + "\"{}\" — later entry wins, earlier one is discarded.", fileKey, entityType, id);
                        anchors.removeIf(a -> a.id().equals(id));
                    }

                    LivingPlatformSeedShape seed = null;
                    if (anchorObj.has("seed")) {
                        JsonObject seedObj = anchorObj.getAsJsonObject("seed");
                        int width = seedObj.has("width") ? seedObj.get("width").getAsInt() : 1;
                        int height = seedObj.has("height") ? seedObj.get("height").getAsInt() : 1;
                        int depth = seedObj.has("depth") ? seedObj.get("depth").getAsInt() : 1;
                        try {
                            seed = new LivingPlatformSeedShape(width, height, depth);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("[KHP] Living platform file {} anchor \"{}\": invalid seed shape ({}) — "
                                    + "falling back to the 1x1x1 default.", fileKey, id, e.getMessage());
                        }
                    }

                    anchors.add(new LivingPlatformAnchor(id, bone, seed));
                }

                LivingPlatformDefinition definition = new LivingPlatformDefinition(entityType, anchors);
                LivingPlatformDefinition existing = definitions.get(entityType);
                if (existing != null) {
                    LOGGER.warn("[KHP] Duplicate living platform whitelist entry for entity type {} — "
                            + "file {} overwrites an earlier one for the same entity type in this reload.",
                            entityType, fileKey);
                }
                definitions.put(entityType, definition);
            } catch (Exception e) {
                LOGGER.error("[KHP] Failed to parse living platform file {} — skipping this file, others still load.",
                        fileKey, e);
            }
        }

        LivingPlatformStore.reload(definitions);
        LOGGER.info("[KHP] Loaded {} living platform whitelist entries from data/khp/{}/.",
                definitions.size(), DIRECTORY);
    }
}
