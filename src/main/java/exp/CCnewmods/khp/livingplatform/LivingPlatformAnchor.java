package exp.CCnewmods.khp.livingplatform;

import javax.annotation.Nullable;

/**
 * @param id   stable id, separate from {@code bone} so renaming a bone doesn't orphan built ships.
 * @param bone a bone that doesn't exist leaves the anchor inert.
 * @param seed {@code null} = a single block.
 */
public record LivingPlatformAnchor(String id, String bone, @Nullable LivingPlatformSeedShape seed) {

    public LivingPlatformSeedShape seedOrDefault() {
        return seed != null ? seed : new LivingPlatformSeedShape(2, 2, 2); // 1x1x1 blocks
    }
}
