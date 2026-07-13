package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.SnapshotSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Populates {@link HitboxStore} from blueprint data on the server thread,
 * wherever no fresher live client capture already covers an entity — the "phase
 * 3" work {@link HitboxStore}'s own class doc calls out as pending, and the
 * concrete design from {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §9.
 *
 * <h3>Why this needs to exist at all</h3>
 * Blueprint data ({@link BlueprintStore}) is already loaded on both integrated
 * and dedicated servers — {@link BlueprintReloadRegistration} deliberately hooks
 * the Forge-bus {@code AddReloadListenerEvent}, tied to
 * {@code ReloadableServerResources}, specifically because that exists on a real
 * dedicated server too. But until now, nothing ever actually read it unless a
 * combat/raycast query happened to ask for it at that exact instant — nothing
 * proactively made it available for {@link exp.CCnewmods.khp.api.MahApi
 * #gatherExtentCandidates} to find via {@link HitboxStore#view()} the way live
 * captures already are. On a real dedicated server, {@link HitboxStore} is
 * <b>never</b> written by anything else at all (no render thread exists there),
 * so before this class, a dedicated server had zero geometry for any entity no
 * connected client happened to be actively rendering that exact moment —
 * arrows, mainly, since they're server-authoritative.
 *
 * <h3>Baseline vs. refinement</h3>
 * Blueprint data is the always-available baseline; a client's live render
 * stream is a refinement that's simply better whenever it exists. This job
 * never overwrites a live snapshot that's still fresh — see
 * {@link #shouldSync(LivingEntity)} — so on the integrated server (where live
 * capture already works fine most of the time) this mostly no-ops, and only
 * actually does anything when nobody's currently looking at the entity.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintLiveSync {

    @SubscribeEvent
    static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return; // server-authoritative population — see class javadoc; client already has its own, better source (live capture)
        if (!MahConfig.enabled) return;

        // Stagger by entity id so entities don't all resolve on the same tick —
        // resolve() composes a transform per part, not free at scale.
        if ((entity.tickCount + entity.getId()) % Math.max(1, MahConfig.blueprintSyncIntervalTicks) != 0) return;

        // Mirrors HitboxCaptureManager.shouldTrack's own player-distance gate —
        // only entities anyone could plausibly be fighting are worth the resolve.
        double radius = Math.sqrt(MahConfig.blueprintSyncRadiusSqr);
        if (!entity.level().hasNearbyAlivePlayer(entity.getX(), entity.getY(), entity.getZ(), radius)) return;

        ResourceLocation entityKey = MahApi.entityKey(entity);
        boolean baby = entity instanceof AgeableMob ageable && ageable.isBaby();
        if (!BlueprintStore.has(entityKey, baby) && !BlueprintStore.has(entityKey, false)) {
            return; // nothing to sync — cheap early-out, the overwhelming common case (most entities have no blueprint at all)
        }

        if (!shouldSync(entity)) return;

        EntityHitboxSnapshot resolved = BlueprintResolver.resolve(entity);
        if (resolved != null) {
            HitboxStore.put(entity.getUUID(), resolved);
        }
    }

    /**
     * False if a live, client-rendered snapshot already covers this entity and is
     * still fresh — a client is actively watching it, its data is strictly better
     * than a blueprint resolve, don't downgrade it. True otherwise (no snapshot at
     * all, or the existing one is blueprint-sourced/stale).
     */
    private static boolean shouldSync(LivingEntity entity) {
        EntityHitboxSnapshot existing = HitboxStore.get(entity.getUUID());
        if (existing == null) return true;
        if (existing.source() != SnapshotSource.LIVE_CLIENT) return true;
        return existing.isStale(entity.level().getGameTime(), MahConfig.staleSnapshotTicks);
    }

    private BlueprintLiveSync() {
    }
}
