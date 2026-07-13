package exp.CCnewmods.khp.livingplatform;

import javax.annotation.Nullable;

/**
 * One buildable anchor point on a whitelisted living-platform entity type — see
 * {@code KHP_Phase2E_Design.md} §4.
 *
 * @param id   short stable identity for this anchor, distinct from {@link #bone}
 *             so a model revision that renames the bone doesn't orphan existing
 *             player-built ships (the attachment record keyed by this id is what
 *             actually re-finds a ship after a reload — see
 *             {@link LivingPlatformAttachment}). Unique per entity type, not
 *             globally.
 * @param bone the GeckoLib bone name this anchor tracks — must resolve against
 *             this entity type's {@code BoneTable} at runtime (via
 *             {@code MahApi.getLivingPlatformBoneTransform}); an anchor naming a
 *             bone that doesn't exist on the model simply never resolves and the
 *             anchor is inert, not an error.
 * @param seed the permanent understructure volume seeded onto this anchor's ship
 *             at creation — see {@code KHP_Phase2E_Design.md} §10. {@code null}
 *             (omitted in JSON) defaults to a single 1x1x1 cell, not "no seed" —
 *             every anchor needs at least one placed cell to give players
 *             anything to build against in the first place.
 */
public record LivingPlatformAnchor(String id, String bone, @Nullable LivingPlatformSeedShape seed) {

    public LivingPlatformSeedShape seedOrDefault() {
        return seed != null ? seed : new LivingPlatformSeedShape(1, 1, 1);
    }
}

