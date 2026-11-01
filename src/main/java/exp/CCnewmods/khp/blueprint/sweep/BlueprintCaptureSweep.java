package exp.CCnewmods.khp.blueprint.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.blueprint.BlueprintPose;
import exp.CCnewmods.khp.blueprint.BlueprintSerializer;
import exp.CCnewmods.khp.blueprint.BlueprintStore;
import exp.CCnewmods.khp.blueprint.EntityBlueprint;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Dev-only: spawns each entity type 4 blocks north of the player, turns the camera to face it, and captures its
 * planned poses over the following ticks.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BlueprintCaptureSweep {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson EXPORT_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int SETTLE_TICKS = 5;
    /** Times a pose is re-applied when no live snapshot newer than the apply arrives (e.g. not rendered yet). */
    private static final int MAX_CAPTURE_ATTEMPTS = 10;

    private static final Map<PlannedPose, Integer> EXTRA_WAIT = new IdentityHashMap<>();

    public static void registerExtraWait(PlannedPose pose, int ticks) {
        EXTRA_WAIT.put(pose, ticks);
    }

    private static int extraWaitFor(PlannedPose pose) {
        return EXTRA_WAIT.getOrDefault(pose, 0);
    }

    private static boolean running = false;
    private static Queue<ResourceLocation> queue = new ArrayDeque<>();
    private static int totalTypes = 0;
    private static int processedTypes = 0;

    private static Entity testEntity;
    private static boolean babyPhase;
    private static List<PlannedPose> currentPlan;
    private static int planIndex;
    private static int waitTicksRemaining;
    private static boolean awaitingCapture;
    private static long poseAppliedAt;
    private static int captureAttempts;

    private static List<BlueprintPose> adultCaptured = new ArrayList<>();
    private static List<BlueprintPose> babyCaptured = new ArrayList<>();
    private static String currentRendererKind = "OTHER";
    private static ResourceLocation currentTexture;

    public static void start() {
        if (running) {
            LOGGER.warn("[KHP] Sweep already running.");
            return;
        }
        queue = new ArrayDeque<>();
        int alreadyCovered = 0;
        for (ResourceLocation id : ForgeRegistries.ENTITY_TYPES.getKeys()) {
            if (MahConfig.blacklist.contains(id)) continue;
            // Already has a loaded data/khp/animstatecache blueprint (adult file; babies fall back to it).
            if (BlueprintStore.has(id, false)) {
                alreadyCovered++;
                continue;
            }
            queue.add(id);
        }
        totalTypes = queue.size();
        processedTypes = 0;
        testEntity = null;
        running = true;
        LOGGER.info("[KHP] Sweep started — {} entity types queued, {} skipped (already have a blueprint).",
                totalTypes, alreadyCovered);
        warnIfModelsCanBeReplaced();
    }

    // Blueprints ship to everyone, so they must be captured from vanilla-shaped models.
    private static void warnIfModelsCanBeReplaced() {
        if (!ModList.get().isLoaded("entity_model_features")) return;
        String msg = "Entity Model Features is installed: disable any optional resource packs that change mob "
                + "models before sweeping, or the exported blueprints will use those models.";
        LOGGER.warn("[KHP] {}", msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[KHP] " + msg).withStyle(ChatFormatting.YELLOW), false);
        }
    }

    public static void stop() {
        if (!running) return;
        cleanupCurrentEntity();
        running = false;
        LOGGER.info("[KHP] Sweep stopped ({}/{} entity types processed).", processedTypes, totalTypes);
    }

    public static boolean isRunning() {
        return running;
    }

    public static String status() {
        return running
                ? "Sweep running: " + processedTypes + "/" + totalTypes + " types processed"
                        + (testEntity != null ? ", current: " + MahApi.entityTypeKey(testEntity) : "")
                : "Sweep not running.";
    }

    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) return;

        if (testEntity == null) {
            advanceToNextEntityType();
            return;
        }
        if (waitTicksRemaining > 0) {
            waitTicksRemaining--;
            return;
        }
        if (awaitingCapture) {
            if (!captureCurrentPose() && captureAttempts < MAX_CAPTURE_ATTEMPTS) {
                applyCurrentPose(); // retry from scratch so time-sampled poses keep their offset
                return;
            }
            awaitingCapture = false;
            advanceToNextPose();
            return;
        }
        if (currentPlan == null) {
            PosePlanBuilder.PosePlan built = PosePlanBuilder.build(testEntity);
            currentPlan = built.poses();
            currentRendererKind = built.rendererKind();
            planIndex = -1;
            advanceToNextPose();
        }
    }

    private static void advanceToNextEntityType() {
        while (true) {
            ResourceLocation id = queue.poll();
            if (id == null) {
                LOGGER.info("[KHP] Sweep complete — {} entity types processed.", processedTypes);
                running = false;
                return;
            }
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type == null) continue;

            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) {
                LOGGER.warn("[KHP] Sweep: no client level/player — stopping.");
                running = false;
                return;
            }

            Entity spawned;
            try {
                spawned = type.create(level);
            } catch (RuntimeException e) {
                LOGGER.warn("[KHP] Sweep: failed to construct {} — skipping.", id, e);
                continue;
            }
            if (!(spawned instanceof LivingEntity)) {
                continue;
            }

            var playerPos = mc.player.position();
            // Face north (-Z) without moving the player.
            mc.player.absMoveTo(playerPos.x, playerPos.y, playerPos.z, 180.0f, 0.0f);
            spawned.setPos(playerPos.x, playerPos.y, playerPos.z - 4);
            spawned.setYRot(0);
            spawned.setYBodyRot(0);
            if (spawned instanceof Mob mob) {
                mob.setNoAi(true);
            }
            level.putNonPlayerEntity(spawned.getId(), spawned);

            testEntity = spawned;
            babyPhase = false;
            currentPlan = null;
            adultCaptured = new ArrayList<>();
            babyCaptured = new ArrayList<>();
            currentTexture = null;
            currentRendererKind = "OTHER";
            waitTicksRemaining = SETTLE_TICKS;
            processedTypes++;
            return;
        }
    }

    private static void advanceToNextPose() {
        planIndex++;
        if (planIndex >= currentPlan.size()) {
            finishPhase();
            return;
        }
        captureAttempts = 0;
        applyCurrentPose();
    }

    private static void applyCurrentPose() {
        PlannedPose pose = currentPlan.get(planIndex);
        pose.apply().apply(testEntity);
        poseAppliedAt = testEntity.level().getGameTime();
        captureAttempts++;
        waitTicksRemaining = 1 + extraWaitFor(pose);
        awaitingCapture = true;
    }

    private static void finishPhase() {
        if (!babyPhase && testEntity instanceof AgeableMob ageable) {
            ageable.setBaby(true);
            babyPhase = true;
            currentPlan = null;
            waitTicksRemaining = SETTLE_TICKS;
            return;
        }
        exportAndDespawn();
        testEntity = null;
    }

    /** @return false if no live snapshot rendered after the pose was applied (caller may retry). */
    private static boolean captureCurrentPose() {
        PlannedPose pose = currentPlan.get(planIndex);
        // Live capture only; never read back an existing blueprint. A snapshot from before the apply is the
        // previous pose (or nothing, right after spawning), so it doesn't count.
        EntityHitboxSnapshot snap = exp.CCnewmods.khp.capture.HitboxStore.get(testEntity.getUUID());
        if (snap == null || snap.parts().isEmpty() || snap.gameTime() <= poseAppliedAt) {
            if (captureAttempts >= MAX_CAPTURE_ATTEMPTS) {
                LOGGER.warn("[KHP] Sweep: no fresh snapshot for {} pose \"{}\" after {} attempts — skipped "
                        + "(entity may not be rendering, or this capture path isn't supported for this entity "
                        + "type).", MahApi.entityTypeKey(testEntity), pose.poseId(), captureAttempts);
            }
            return false;
        }
        if (currentTexture == null && snap.texture() != null) {
            currentTexture = snap.texture();
        }
        BlueprintPose bp = new BlueprintPose(pose.poseId(), snap.parts());
        (babyPhase ? babyCaptured : adultCaptured).add(bp);
        return true;
    }

    private static void exportAndDespawn() {
        ResourceLocation entityKey = MahApi.entityTypeKey(testEntity);

        if (!adultCaptured.isEmpty()) {
            writeBlueprint(new EntityBlueprint(entityKey, false, currentRendererKind, currentTexture, adultCaptured));
        }
        if (!babyCaptured.isEmpty()) {
            writeBlueprint(new EntityBlueprint(entityKey, true, currentRendererKind, currentTexture, babyCaptured));
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.removeEntity(testEntity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private static void cleanupCurrentEntity() {
        if (testEntity == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.removeEntity(testEntity.getId(), Entity.RemovalReason.DISCARDED);
        }
        testEntity = null;
    }

    private static void writeBlueprint(EntityBlueprint blueprint) {
        if (blueprint.pose("rest") == null) {
            // BlueprintReloadListener rejects these, so don't export a file that would silently never load.
            LOGGER.error("[KHP] Sweep: {} (baby={}) has no \"rest\" pose — not exported. Re-run the sweep for it.",
                    blueprint.entityType(), blueprint.baby());
            return;
        }
        File dir = new File(Minecraft.getInstance().gameDirectory, "config/khp/animstatecache_capture");
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.error("[KHP] Sweep: failed to create export directory {}", dir);
            return;
        }
        String safeName = blueprint.entityType().toString().replace(':', '_')
                + (blueprint.baby() ? "_baby" : "") + ".json";
        File file = new File(dir, safeName);
        try (FileWriter writer = new FileWriter(file)) {
            EXPORT_GSON.toJson(BlueprintSerializer.toJson(blueprint), writer);
            LOGGER.info("[KHP] Sweep: exported {} poses for {} (baby={}) to {}",
                    blueprint.poses().size(), blueprint.entityType(), blueprint.baby(), file);
        } catch (IOException e) {
            LOGGER.error("[KHP] Sweep: failed to write {}", file, e);
        }
    }

    private BlueprintCaptureSweep() {
    }
}
