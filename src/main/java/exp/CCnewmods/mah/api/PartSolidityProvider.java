package exp.CCnewmods.mah.api;

import net.minecraft.world.entity.Entity;

/**
 * Hook for scaling per-bone collision solidity, registered via
 * {@link MahApi#registerSolidityProvider}.
 *
 * <p>Consumed by the phase-4 solid-collision layer: before an entity's part OBBs are
 * handed to the VS2 collision path, each bone's folded solidity decides whether it
 * collides at all (0 = intangible), collides softly (fractional — movement damping
 * rather than a hard wall), or is a full wall (1). Both solid_player_vs_mob and
 * solid_mob_vs_mob default ON, so providers shape behaviour rather than enable it.
 *
 * <p>This is the public hook MisWorld's material-physics addon drives: a creeper
 * defined as plant matter yields underfoot, a golem's iron does not, and a
 * SPECTRAL-bodied mob can zero out entirely.
 */
@FunctionalInterface
public interface PartSolidityProvider {

    /**
     * @param entity   the entity being collided with
     * @param boneName canonical bone name (see BoneTable naming conventions)
     * @param current  the solidity folded so far (starts at 1.0)
     * @return the new solidity in [0, 1]
     */
    float getSolidity(Entity entity, String boneName, float current);
}
