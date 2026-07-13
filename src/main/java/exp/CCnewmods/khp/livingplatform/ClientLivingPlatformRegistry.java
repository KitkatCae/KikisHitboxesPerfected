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
 * Client-side counterpart to {@link LivingPlatformShipManager} — holds whatever
 * {@link LivingPlatformSyncPacket}s have arrived, and attaches
 * {@link LivingPlatformClientTransformProvider} to the matching {@link ClientShip}
 * whichever of "packet arrives first" or "ship loads first" actually happens (see
 * {@link LivingPlatformSyncPacket}'s javadoc — the two channels aren't ordered
 * relative to each other). {@link LivingPlatformClientBootstrap} wires the
 * VS2 {@code ShipLoadEventClient}/{@code ShipUnloadEventClient} hooks that call
 * into this class; this class itself has no event registration of its own.
 */
public final class ClientLivingPlatformRegistry {

    private static final Map<Long, LivingPlatformAttachment> KNOWN = new ConcurrentHashMap<>();

    /** Called from the network handler ({@link LivingPlatformNetworking}) on the client thread. */
    public static void onSyncReceived(LivingPlatformSyncPacket packet) {
        LivingPlatformAttachment attachment = packet.toAttachment();
        KNOWN.put(packet.shipId, attachment);

        // Ship may already be loaded (packet arrived after VS2's own ship sync) —
        // attach immediately in that case rather than waiting for a ShipLoadEventClient
        // that already fired before this packet got here.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        VsiClientShipWorld world = VSGameUtilsKt.getShipObjectWorld(mc.level);
        ClientShip ship = world.getAllShips().getById(packet.shipId);
        if (ship != null) {
            attach(ship, attachment);
        }
    }

    /** Called from {@link LivingPlatformClientBootstrap}'s {@code ShipLoadEventClient} hook. */
    public static void onShipLoaded(ClientShip ship) {
        LivingPlatformAttachment attachment = KNOWN.get(ship.getId());
        if (attachment != null) {
            attach(ship, attachment);
        }
        // If attachment is null here, the sync packet just hasn't arrived yet — 
        // onSyncReceived's own late-arrival branch above covers that case when it does.
    }

    /** Called from {@link LivingPlatformClientBootstrap}'s {@code ShipUnloadEventClient} hook — housekeeping only. */
    public static void onShipUnloaded(ClientShip ship) {
        KNOWN.remove(ship.getId());
    }

    /**
     * Looked up from {@link LivingPlatformSeedBlock#getCollisionShape} on the client
     * side — the server-only {@code AttachmentHolder} route
     * ({@link LivingPlatformShipManager#getAttachment}) isn't available there
     * (confirmed this session: {@code ClientShip}'s real implementation has no
     * attachment holder at all — see {@code KHP_Phase2E_Design.md} §9's client-sync
     * writeup), so the collision fix needs this client-side map instead.
     */
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
