package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.internal.world.VsiClientShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side: attaches the transform provider once both the sync packet and the ship have arrived, in either order.
 */
public final class ClientLivingPlatformRegistry {

    private static final Map<Long, LivingPlatformAttachment> KNOWN = new ConcurrentHashMap<>();

    public static void onSyncReceived(LivingPlatformSyncPacket packet) {
        LivingPlatformAttachment attachment = packet.toAttachment();
        KNOWN.put(packet.shipId, attachment);

        // Ship already loaded: attach now.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        VsiClientShipWorld world = VSGameUtilsKt.getShipObjectWorld(mc.level);
        ClientShip ship = world.getAllShips().getById(packet.shipId);
        if (ship != null) {
            attach(ship, attachment);
        }
    }

    public static void onShipLoaded(ClientShip ship) {
        LivingPlatformAttachment attachment = KNOWN.get(ship.getId());
        if (attachment != null) {
            attach(ship, attachment);
        }
    }

    public static void onShipUnloaded(ClientShip ship) {
        KNOWN.remove(ship.getId());
    }

    /** Client ships have no attachments, so the client uses this map. */
    @Nullable
    public static LivingPlatformAttachment getAttachmentByShipId(long shipId) {
        return KNOWN.get(shipId);
    }

    private static void attach(ClientShip ship, LivingPlatformAttachment attachment) {
        ship.setTransformProvider(new LivingPlatformClientTransformProvider(attachment.entityUuid(), attachment.boneName(), ship));
        Mah.LOGGER.info("[KHP] Attached client transform provider for living platform ship {} (entity {}, bone \"{}\").",
                ship.getId(), attachment.entityUuid(), attachment.boneName());
    }

    private ClientLivingPlatformRegistry() {
    }
}
