package exp.CCnewmods.khp.livingplatform;

import java.util.UUID;

/**
 * Stored on a living-platform {@code ServerShip} via
 * {@code AttachmentHolder.setAttachment} (see
 * {@code LivingPlatformShipManager}'s class javadoc for why this — not the
 * deprecated {@code ServerShip.saveAttachment}/{@code LoadedServerShip
 * .setAttachment(Class, T)} — is the real, current VS2 attachment API,
 * bytecode-confirmed this session). Identifies which entity and which of its
 * anchors a given ship belongs to, so the ship can be re-found after a chunk/
 * server reload without re-creating it, and so {@link LivingPlatformDeathHandler}
 * knows which ships belong to a dying entity.
 *
 * <p>Plain mutable-free record — VS2 attachments are looked up by runtime class
 * ({@code getAttachment(LivingPlatformAttachment.class)}), so there's exactly one
 * of these per ship and no need for a wrapper key type.
 */
public record LivingPlatformAttachment(UUID entityUuid, String anchorId, String boneName) {
}
