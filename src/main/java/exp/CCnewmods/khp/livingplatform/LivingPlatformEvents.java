package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Ties {@link LivingPlatformShipManager} into the game loop and player
 * interaction — see {@code KHP_Phase2E_Design.md} §5/§6. Forge event bus
 * (confirmed pattern already used elsewhere in KHP for non-mod-lifecycle events).
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LivingPlatformEvents {

    /**
     * Anchor selection — §5: right-clicking a whitelisted entity resolves which
     * anchor's shipyard is addressed by nearest-bone-to-hit-point, using the same
     * kind of nearest-bone match {@code MahApi#synthesizePartSnapshot} already
     * does for multipart proxies, not a menu.
     *
     * <p>{@code EntityInteractSpecific} (not the plain {@code EntityInteract})
     * carries {@code getLocalPos()} — the precise hit point, entity-relative, not
     * yet rotated into the entity's own local frame per vanilla's
     * {@code Entity#interactAt} convention (world hit point minus entity
     * position). <b>Not bytecode-verified this session</b> — flagging explicitly
     * since everything else in this file's data access was; worth confirming
     * against a real interaction before relying on it, the fallback failure mode
     * if the assumption is wrong is just "picks the wrong anchor on a multi-anchor
     * mob," not a crash.
     */
    @SubscribeEvent
    static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        Entity target = event.getTarget();

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (typeId == null) return;
        LivingPlatformDefinition definition = LivingPlatformStore.get(typeId);
        if (definition == null) return; // not a whitelisted entity type

        Vec3 worldHit = target.position().add(event.getLocalPos());

        LivingPlatformAnchor nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (LivingPlatformAnchor anchor : definition.anchors()) {
            Matrix4f boneWorld = MahApi.getLivingPlatformBoneTransform(target, anchor.bone());
            if (boneWorld == null) continue; // no data for this bone right now — can't be selected
            Vector3f pos = new Vector3f();
            boneWorld.getTranslation(pos);
            double distSq = worldHit.distanceToSqr(pos.x, pos.y, pos.z);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = anchor;
            }
        }
        if (nearest == null) return; // no anchor currently has resolvable bone data — nothing to select

        LivingPlatformShipManager.getOrCreateShip(serverLevel, target, nearest);
    }

    /**
     * Per-tick freeze bookkeeping + optional eager ship creation
     * ({@code MahConfig.livingPlatformEagerCreation}, default off — lazy
     * creation via the interaction handler above is the default per Q2E-7).
     * Server-side only; a cache/attachment hit inside
     * {@link LivingPlatformShipManager#getOrCreateShip} is cheap, so running the
     * eager-creation check every tick when the config is on is acceptable — no
     * budget gate for this pass either way (Q2E-6).
     */
    @SubscribeEvent
    static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide() || !(entity.level() instanceof ServerLevel serverLevel)) return;

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId == null) return;
        LivingPlatformDefinition definition = LivingPlatformStore.get(typeId);
        if (definition == null) return;

        LivingPlatformShipManager.tickFreezeState(entity);

        if (MahConfig.livingPlatformEagerCreation) {
            for (LivingPlatformAnchor anchor : definition.anchors()) {
                LivingPlatformShipManager.getOrCreateShip(serverLevel, entity, anchor);
            }
        }
    }

    /**
     * Resync point for a player who just started tracking (came in range of, or
     * reconnected near) a whitelisted entity — VS2's own ship sync and
     * {@link LivingPlatformNetworking}'s sync are on separate channels with no
     * ordering guarantee, and more importantly {@code TRACKING_ENTITY_AND_SELF}
     * at ship-creation time (see {@link LivingPlatformShipManager#createShip})
     * only reaches players tracking the entity <i>at that moment</i> — anyone who
     * starts tracking later (walks into range, reconnects) needs their own resend,
     * which is exactly what this handler is for.
     */
    @SubscribeEvent
    static void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target.level() instanceof ServerLevel serverLevel)) return;

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (typeId == null || !LivingPlatformStore.isWhitelisted(typeId)) return;

        for (var ship : LivingPlatformShipManager.findAllShipsForEntity(serverLevel, target.getUUID())) {
            LivingPlatformAttachment attachment = LivingPlatformShipManager.getAttachment(ship);
            if (attachment == null) continue;
            LivingPlatformNetworking.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new LivingPlatformSyncPacket(ship.getId(), attachment.entityUuid(), attachment.anchorId(), attachment.boneName()));
        }
    }

    private LivingPlatformEvents() {
    }
}
