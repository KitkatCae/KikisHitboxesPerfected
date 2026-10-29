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

// Registered by LivingPlatformWiring only when VS2 is present.
public final class LivingPlatformEvents {

    /** Right-click picks the anchor whose bone is nearest the hit point; shift-right-click toggles a hold. */
    @SubscribeEvent
    static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        Entity target = event.getTarget();

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (typeId == null) return;
        LivingPlatformDefinition definition = LivingPlatformStore.get(typeId);
        if (definition == null) return;

        // Checked first so sneaking never creates a ship.
        if (event.getEntity().isShiftKeyDown()) {
            boolean nowHeld = LivingPlatformShipManager.toggleHeld(target);
            event.getEntity().displayClientMessage(net.minecraft.network.chat.Component.literal(
                    nowHeld ? "[KHP] Holding still." : "[KHP] Released — free to move."), true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        Vec3 worldHit = target.position().add(event.getLocalPos());

        LivingPlatformAnchor nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (LivingPlatformAnchor anchor : definition.anchors()) {
            Matrix4f boneWorld = MahApi.getLivingPlatformBoneTransform(target, anchor.bone());
            if (boneWorld == null) continue;
            Vector3f pos = new Vector3f();
            boneWorld.getTranslation(pos);
            double distSq = worldHit.distanceToSqr(pos.x, pos.y, pos.z);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = anchor;
            }
        }
        if (nearest == null) return;

        LivingPlatformShipManager.getOrCreateShip(serverLevel, target, nearest);
    }

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

    /** Resends ship sync to players who start tracking later than ship creation. */
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
