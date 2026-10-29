package exp.CCnewmods.khp.blueprint.devcapture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.blueprint.BlueprintPose;
import exp.CCnewmods.khp.blueprint.BlueprintSerializer;
import exp.CCnewmods.khp.blueprint.BlueprintStore;
import exp.CCnewmods.khp.blueprint.EntityBlueprint;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev-mode captures in {@code config/khp/animstatecache_capture/}, same format as shipped blueprints (promote by
 * copying into {@code data/khp/animstatecache/}). Never read by {@code BlueprintResolver}.
 */
public final class DevCapturePendingStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record Key(ResourceLocation entityType, boolean baby) {
    }

    private static final Map<Key, EntityBlueprint> CACHE = new ConcurrentHashMap<>();

    private static File captureDir() {
        return new File(Minecraft.getInstance().gameDirectory, "config/khp/animstatecache_capture");
    }

    private static File fileFor(ResourceLocation entityType, boolean baby) {
        String safeName = entityType.toString().replace(':', '_') + (baby ? "_baby" : "") + ".json";
        return new File(captureDir(), safeName);
    }

    /** Shipped poses plus pending ones (pending wins on the same id). */
    public static List<List<PartOBB>> allKnownPoseParts(ResourceLocation entityType, boolean baby) {
        List<List<PartOBB>> result = new ArrayList<>();
        EntityBlueprint shipped = BlueprintStore.get(entityType, baby);
        EntityBlueprint pending = getPending(entityType, baby);
        Map<String, BlueprintPose> byPoseId = new LinkedHashMap<>();
        if (shipped != null) {
            for (BlueprintPose p : shipped.poses()) byPoseId.put(p.poseId(), p);
        }
        if (pending != null) {
            for (BlueprintPose p : pending.poses()) byPoseId.put(p.poseId(), p);
        }
        for (BlueprintPose p : byPoseId.values()) result.add(p.parts());
        return result;
    }

    public static BlueprintPose getPendingPose(ResourceLocation entityType, boolean baby, String poseId) {
        EntityBlueprint pending = getPending(entityType, baby);
        return pending != null ? pending.pose(poseId) : null;
    }

    private static EntityBlueprint getPending(ResourceLocation entityType, boolean baby) {
        Key key = new Key(entityType, baby);
        EntityBlueprint cached = CACHE.get(key);
        if (cached != null) return cached;

        File file = fileFor(entityType, baby);
        if (!file.exists()) return null;
        try (FileReader reader = new FileReader(file)) {
            EntityBlueprint loaded = BlueprintSerializer.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
            CACHE.put(key, loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[KHP] Dev-capture: failed to read existing pending file {} — treating as absent.", file, e);
            return null;
        }
    }

    public static synchronized void appendPose(ResourceLocation entityType, boolean baby, String rendererKind,
                                                ResourceLocation texture, String poseId, List<PartOBB> parts) {
        Key key = new Key(entityType, baby);
        EntityBlueprint existing = getPending(entityType, baby);

        List<BlueprintPose> poses = new ArrayList<>();
        if (existing != null) {
            for (BlueprintPose p : existing.poses()) {
                if (!p.poseId().equals(poseId)) poses.add(p);
            }
        }
        poses.add(new BlueprintPose(poseId, parts));

        EntityBlueprint updated = new EntityBlueprint(entityType, baby, rendererKind, texture, poses);
        CACHE.put(key, updated);

        File dir = captureDir();
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.error("[KHP] Dev-capture: failed to create export directory {}", dir);
            return;
        }
        File file = fileFor(entityType, baby);
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(BlueprintSerializer.toJson(updated), writer);
        } catch (IOException e) {
            LOGGER.error("[KHP] Dev-capture: failed to write {}", file, e);
        }
    }

    private DevCapturePendingStore() {
    }
}
