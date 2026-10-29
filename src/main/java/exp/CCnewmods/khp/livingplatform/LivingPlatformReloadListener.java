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
 * Loads {@code data/khp/khp_living_platforms/*.json}:
 * <pre>{@code
 * {
 *   "entity": "misanthrope_core:some_dragon",
 *   "anchors": [
 *     { "bone": "back_plate",    "id": "back", "seed": { "width": 4, "height": 4, "depth": 4 } },
 *     { "bone": "wing_tip",      "id": "wing", "seed": { "width_q": 7, "height_q": 3, "depth_q": 5 } },
 *     { "bone": "tail_platform", "id": "tail" }
 *   ]
 * }
 * }</pre>
 * {@code width}/{@code height}/{@code depth} are blocks; the {@code _q} variants are half-blocks and win if both
 * are given. {@code seed} defaults to a single block.
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
                        int widthQ = readAxisQuadrants(seedObj, "width", fileKey, id);
                        int heightQ = readAxisQuadrants(seedObj, "height", fileKey, id);
                        int depthQ = readAxisQuadrants(seedObj, "depth", fileKey, id);
                        try {
                            seed = new LivingPlatformSeedShape(widthQ, heightQ, depthQ);
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

    private static int readAxisQuadrants(JsonObject seedObj, String axisName,
                                          ResourceLocation fileKey, String anchorId) {
        String qKey = axisName + "_q";
        boolean hasBlocks = seedObj.has(axisName);
        boolean hasQuadrants = seedObj.has(qKey);

        if (hasBlocks && hasQuadrants) {
            LOGGER.warn("[KHP] Living platform file {} anchor \"{}\": seed specifies both \"{}\" and \"{}\" — "
                    + "using \"{}\" (quadrant units) and ignoring \"{}\".",
                    fileKey, anchorId, axisName, qKey, qKey, axisName);
            return seedObj.get(qKey).getAsInt();
        }
        if (hasQuadrants) {
            return seedObj.get(qKey).getAsInt();
        }
        if (hasBlocks) {
            return seedObj.get(axisName).getAsInt() * 2;
        }
        return 2; // 1 block
    }
}
