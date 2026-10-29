package exp.CCnewmods.khp.api;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * One part hit by a raycast or shapecast.
 *
 * @param worldExitPoint approximate for shapecasts.
 * @param localEntryUVW  entry point within the part's box, [0,1]^3.
 * @param depthFraction  distance travelled through the part (blocks).
 */
public record PartHitResult(
        Entity entity,
        ResourceLocation entityKey,
        short boneId,
        String boneName,
        Vec3 worldEntryPoint,
        Vec3 worldExitPoint,
        Vec3 localEntryUVW,
        double depthFraction,
        Direction faceHit
) {
}
