package exp.CCnewmods.khp.livingplatform;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** Tells clients which entity and anchor a ship belongs to (VS2 doesn't sync attachments). */
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
