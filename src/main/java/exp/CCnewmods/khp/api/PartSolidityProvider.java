package exp.CCnewmods.khp.api;

import net.minecraft.world.entity.Entity;

/**
 * Scales a bone's collision solidity: 0 = intangible, 1 = solid. Register via
 * {@link MahApi#registerSolidityProvider}. TODO: not consumed by solid collision yet.
 */
@FunctionalInterface
public interface PartSolidityProvider {

    /**
     * @param current the solidity folded so far (starts at 1.0)
     * @return the new solidity in [0, 1]
     */
    float getSolidity(Entity entity, String boneName, float current);
}
