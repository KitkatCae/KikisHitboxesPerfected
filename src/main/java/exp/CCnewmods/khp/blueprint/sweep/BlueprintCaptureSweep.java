package exp.CCnewmods.khp.blueprint.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.blueprint.BlueprintPose;
import exp.CCnewmods.khp.blueprint.BlueprintSerializer;
import exp.CCnewmods.khp.blueprint.EntityBlueprint;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * Dev-only tick-driven sweep controller — see design doc §4. Runs one small step per
 * client tick rather than blocking a command's execution, since capture is entirely
 * dependent on real render frames actually happening (a spawned entity needs several
 * real ticks/frames to render before {@code HitboxStore} has anything for it).
 *
 * <p>Each new entity type spawns 4 blocks north of the player and forces the
 * player's own camera to face north via {@code Entity.absMoveTo(x, y, z, yaw, pitch)}
 * — the same "teleport with rotation" method vanilla's own {@code /tp} command uses,
 * confirmed via decompile to also sync {@code yRotO}/{@code xRotO} so there's no
 * interpolation snap glitch — rather than leaving whether the entity actually ends up
 * on-screen (and therefore whether it renders at all) to wherever the player happened
 * to be looking. Position args passed are the player's own current position
 * unchanged, so this only ever turns the camera, never teleports the player anywhere.
 *
 * <p>Not thread-sensitive beyond the usual "everything here runs on the client
 * thread" assumption every other client-only class in this mod already makes.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BlueprintCaptureSweep {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson EXPORT_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int SETTLE_TICKS = 5;

    // ── Per-PlannedPose extra wait (GeckoLib clip sampling — see PosePlanBuilder) ──

    private static final Map<PlannedPose, Integer> EXTRA_WAIT = new IdentityHashMap<>();

    public static void registerExtraWait(PlannedPose pose, int ticks) {
        EXTRA_WAIT.put(pose, ticks);
    }

    private static int extraWaitFor(PlannedPose pose) {
        return EXTRA_WAIT.getOrDefault(pose, 0);
    }

    // ── Run state ────────────────────────────────────────────────────────────

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

    private static List<BlueprintPose> adultCaptured = new ArrayList<>();
    private static List<BlueprintPose> babyCaptured = new ArrayList<>();
    private static String currentRendererKind = "OTHER";
    private static ResourceLocation currentTexture;

    // ── Public control surface (called by the client command) ──────────────

    public static void start() {
        if (running) {
            LOGGER.warn("[KHP] Sweep already running.");
            return;
        }
        queue = new ArrayDeque<>();
        for (ResourceLocation id : ForgeRegistries.ENTITY_TYPES.getKeys()) {
            if (MahConfig.blacklist.contains(id)) continue;
            queue.add(id);
        }
        totalTypes = queue.size();
        processedTypes = 0;
        testEntity = null;
        running = true;
        LOGGER.info("[KHP] Sweep started — {} entity types queued.", totalTypes);
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

    // ── Tick driver ──────────────────────────────────────────────────────────

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
            captureCurrentPose();
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

    // ── State transitions ───────────────────────────────────────────────────

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
                continue; // non-living types (boats, minecarts, etc.) out of scope for this sweep
            }

            var playerPos = mc.player.position();
            // North = -Z (confirmed via Direction.NORTH's step vector, (0,0,-1)).
            // Force the player's own look direction to face north too, via the same
            // absMoveTo(x,y,z,yaw,pitch) "teleport with rotation" method vanilla's own
            // /tp command uses — confirmed via decompile that it also syncs
            // yRotO/xRotO, so there's no visible camera-snap interpolation glitch the
            // way a bare setYRot() call alone could produce. Position args are the
            // player's own current position (unchanged) — this only ever turns the
            // camera, never moves the player.
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
            currentRendererKind = "OTHER"; // placeholder until PosePlanBuilder.build() runs and sets the real kind
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
        PlannedPose pose = currentPlan.get(planIndex);
        pose.apply().apply(testEntity);
        waitTicksRemaining = 1 + extraWaitFor(pose);
        awaitingCapture = true;
    }

    private static void finishPhase() {
        if (!babyPhase && testEntity instanceof AgeableMob ageable) {
            ageable.setBaby(true);
            babyPhase = true;
            currentPlan = null;
            waitTicksRemaining = SETTLE_TICKS; // let the scale change actually render
            return;
        }
        exportAndDespawn();
        testEntity = null; // next tick's onClientTick call advances to the next entity type
    }

    private static void captureCurrentPose() {
        PlannedPose pose = currentPlan.get(planIndex);
        // Deliberately HitboxStore, not MahApi.getSnapshot — once BlueprintResolver
        // is wired into MahApi.getSnapshot as a fallback, calling that here could
        // read back a previously-shipped blueprint's resolved data instead of a
        // genuinely fresh live capture, if re-running this sweep against an entity
        // type that already has a blueprint loaded. The sweep is authoring new
        // ground truth; it must only ever read the live capture path.
        EntityHitboxSnapshot snap = exp.CCnewmods.khp.capture.HitboxStore.get(testEntity.getUUID());
        if (snap == null || snap.parts().isEmpty()) {
            LOGGER.warn("[KHP] Sweep: no snapshot for {} pose \"{}\" — skipped (entity may not be "
                    + "rendering, or this capture path isn't supported for this entity type).",
                    MahApi.entityTypeKey(testEntity), pose.poseId());
            return;
        }
        if (currentTexture == null && snap.texture() != null) {
            currentTexture = snap.texture();
        }
        BlueprintPose bp = new BlueprintPose(pose.poseId(), snap.parts());
        (babyPhase ? babyCaptured : adultCaptured).add(bp);
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

    // ── Export ───────────────────────────────────────────────────────────────

    private static void writeBlueprint(EntityBlueprint blueprint) {
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
