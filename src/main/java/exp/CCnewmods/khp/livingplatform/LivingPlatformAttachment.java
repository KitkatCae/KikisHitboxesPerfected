package exp.CCnewmods.khp.livingplatform;

import java.util.UUID;

/** Which entity and anchor a ship belongs to. */
public record LivingPlatformAttachment(UUID entityUuid, String anchorId, String boneName) {
}
