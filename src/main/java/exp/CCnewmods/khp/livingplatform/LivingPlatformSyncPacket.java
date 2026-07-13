package exp.CCnewmods.khp.livingplatform;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Server → client sync for one living-platform ship's identity — see
 * {@code KHP_Phase2E_Design.md} §9 (client-side sync wiring). Bytecode-confirmed
 * this session that VS2's own {@code ClientShip} does <b>not</b> expose attachment
 * data (the concrete client-side ship implementation has no attachment holder at
 * all, unlike its server-side counterpart) — so KHP carries this small amount of
 * data itself instead of depending on VS2's attachment sync.
 *
 * <p>Sent once when a ship is created ({@link LivingPlatformShipManager#createShip})
 * and again to any individual player who starts tracking the anchoring entity
 * ({@link LivingPlatformEvents#onStartTracking}) — the two channels (VS2's own ship
 * sync and this one) have no ordering guarantee relative to each other, so the
 * client side ({@link ClientLivingPlatformRegistry}) has to handle this packet and
 * the ship itself arriving in either order.
 */
public final class LivingPlatformSyncPacket {

    public final long shipId;
    public final UUID entityUuid;
    public final String anchorId;
    public final String boneName;

    public LivingPlatformSyncPacket(long shipId, UUID entityUuid, String anchorId, String boneName) {
        this.shipId = shipId;
        this.entityUuid = entityUuid;
        this.anchorId = anchorId;
        this.boneName = boneName;
    }

    public static void encode(LivingPlatformSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.shipId);
        buf.writeUUID(packet.entityUuid);
        buf.writeUtf(packet.anchorId);
        buf.writeUtf(packet.boneName);
    }

    public static LivingPlatformSyncPacket decode(FriendlyByteBuf buf) {
        long shipId = buf.readLong();
        UUID entityUuid = buf.readUUID();
        String anchorId = buf.readUtf();
        String boneName = buf.readUtf();
        return new LivingPlatformSyncPacket(shipId, entityUuid, anchorId, boneName);
    }

    public LivingPlatformAttachment toAttachment() {
        return new LivingPlatformAttachment(entityUuid, anchorId, boneName);
    }
}
