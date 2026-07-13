package exp.CCnewmods.khp.api;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * One part hit by {@link MahApi#raycast}/{@code raycastEntity}/{@code shapecast}.
 *
 * @param entity           the entity hit.
 * @param entityKey        MAH entity key ({@link MahApi#entityKey}) — identifies
 *                         which {@code BoneTable} resolves {@code boneId}.
 * @param boneId           bone id within that entity key's bone table.
 * @param boneName         resolved bone/part name, for convenience — same string
 *                         {@code BoneTable} would give back for {@code boneId}.
 * @param worldEntryPoint  where the ray/shape first touches this part, in world space.
 * @param worldExitPoint   where it last touches this part — true exit-aware depth,
 *                         not a z-axis guess (design doc Q2). For a shapecast hit
 *                         (no single ray direction), this is the far side of the
 *                         part's box along the line from the shape's reference point
 *                         through {@code worldEntryPoint} — an approximation, not a
 *                         true directional exit; see {@code shapecast}'s own javadoc.
 * @param localEntryUVW    normalized [0,1]^3 position of the entry point within the
 *                         part's {@code localBox}.
 * @param depthFraction    true distance traveled through the part between entry and
 *                         exit (world units) — a corner clip is short, a center
 *                         through-shot is long, reflecting actual penetration depth
 *                         for wound-severity scaling.
 * @param faceHit          which face of the part's box was entered.
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
