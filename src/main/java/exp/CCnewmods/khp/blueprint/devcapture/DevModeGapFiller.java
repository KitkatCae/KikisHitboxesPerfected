package exp.CCnewmods.khp.blueprint.devcapture;

import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.blueprint.BlueprintPose;
import exp.CCnewmods.khp.blueprint.BlueprintStore;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.PartOBB;
import exp.CCnewmods.khp.capture.SnapshotSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dev mode: captures on-screen poses the sweep missed. Sitting/sleeping are compared directly against their
 * stored pose; everything else is compared against all known poses on a background thread.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DevModeGapFiller {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CHECK_INTERVAL_TICKS = 10;
    /** So a held pose isn't flagged over and over. */
    private static final int POST_CAPTURE_COOLDOWN_TICKS = 200;

    private static final Map<UUID, Long> NEXT_CHECK_TICK = new ConcurrentHashMap<>();

    private static final ExecutorService PROCEDURAL_EXECUTOR = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "KHP-DevCapture-Compare");
                t.setDaemon(true);
                return t;
            });

    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!MahConfig.devModePoseCapture) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long gameTime = mc.level.getGameTime();

        // Live captures only; a blueprint has nothing new to discover.
        for (var entry : HitboxStore.view().entrySet()) {
            EntityHitboxSnapshot snap = entry.getValue();
            if (snap.source() != SnapshotSource.LIVE_CLIENT) continue;

            Entity entity = mc.level.getEntity(snap.entityId());
            if (entity == null) continue;

            UUID uuid = entity.getUUID();
            Long nextCheck = NEXT_CHECK_TICK.get(uuid);
            if (nextCheck != null && gameTime < nextCheck) continue;
            NEXT_CHECK_TICK.put(uuid, gameTime + CHECK_INTERVAL_TICKS);

            checkEntity(entity, snap, gameTime);
        }
    }

    private static void checkEntity(Entity entity, EntityHitboxSnapshot snap, long gameTime) {
        if (snap.parts().isEmpty()) return;

        ResourceLocation entityKey = MahApi.entityKey(entity);
        boolean baby = entity instanceof AgeableMob ageable && ageable.isBaby();
        List<PartOBB> liveParts = snap.parts();

        String statePoseId = null;
        if (entity instanceof TamableAnimal tamable && tamable.isInSittingPose()) {
            statePoseId = "sitting";
        } else if (entity.getPose() == Pose.SLEEPING) {
            statePoseId = "sleeping";
        }

        if (statePoseId != null) {
            checkStateTiedPose(entity, entityKey, baby, statePoseId, liveParts, gameTime);
        } else {
            checkProceduralPose(entity, entityKey, baby, liveParts, gameTime);
        }
    }

    private static void checkStateTiedPose(Entity entity, ResourceLocation entityKey, boolean baby,
                                            String poseId, List<PartOBB> liveParts, long gameTime) {
        BlueprintPose existingShipped = shippedPose(entityKey, baby, poseId);
        BlueprintPose existingPending = DevCapturePendingStore.getPendingPose(entityKey, baby, poseId);
        BlueprintPose existing = existingPending != null ? existingPending : existingShipped;

        if (existing != null && PoseSimilarity.matches(existing.parts(), liveParts)) {
            return;
        }

        capture(entity, entityKey, baby, poseId, liveParts, gameTime);
    }

    private static void checkProceduralPose(Entity entity, ResourceLocation entityKey, boolean baby,
                                             List<PartOBB> liveParts, long gameTime) {
        PROCEDURAL_EXECUTOR.submit(() -> {
            try {
                List<List<PartOBB>> known = DevCapturePendingStore.allKnownPoseParts(entityKey, baby);
                if (!PoseSimilarity.isNewPose(known, liveParts)) return;
                String poseId = "dev_capture_t" + gameTime;
                captureOnClientThread(entity, entityKey, baby, poseId, liveParts, gameTime);
            } catch (RuntimeException e) {
                LOGGER.error("[KHP] Dev-capture: comparison failed for {}", entityKey, e);
            }
        });
    }

    private static void captureOnClientThread(Entity entity, ResourceLocation entityKey, boolean baby,
                                               String poseId, List<PartOBB> liveParts, long gameTime) {
        Minecraft.getInstance().execute(() -> capture(entity, entityKey, baby, poseId, liveParts, gameTime));
    }

    private static void capture(Entity entity, ResourceLocation entityKey, boolean baby,
                                 String poseId, List<PartOBB> liveParts, long gameTime) {
        String rendererKind = shippedRendererKindOr(entityKey, baby, "OTHER");
        EntityHitboxSnapshot currentSnap = HitboxStore.get(entity.getUUID());
        ResourceLocation texture = currentSnap != null ? currentSnap.texture() : null;

        DevCapturePendingStore.appendPose(entityKey, baby, rendererKind, texture, poseId, liveParts);
        NEXT_CHECK_TICK.put(entity.getUUID(), gameTime + POST_CAPTURE_COOLDOWN_TICKS);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "[KHP] New pose captured: " + entityKey + (baby ? " (baby)" : "") + " \u2192 " + poseId), false);
        }
        LOGGER.info("[KHP] Dev-capture: new pose \"{}\" for {} (baby={})", poseId, entityKey, baby);
    }

    private static BlueprintPose shippedPose(ResourceLocation entityKey, boolean baby, String poseId) {
        var blueprint = BlueprintStore.get(entityKey, baby);
        return blueprint != null ? blueprint.pose(poseId) : null;
    }

    private static String shippedRendererKindOr(ResourceLocation entityKey, boolean baby, String fallback) {
        var shipped = BlueprintStore.get(entityKey, baby);
        return shipped != null ? shipped.rendererKind() : fallback;
    }

    private DevModeGapFiller() {
    }
}
