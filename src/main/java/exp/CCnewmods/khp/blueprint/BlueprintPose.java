package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.capture.PartOBB;

import java.util.List;

/**
 * One captured pose within an {@link EntityBlueprint}.
 *
 * <h3>{@code poseId} conventions</h3>
 * <ul>
 *   <li>{@code "rest"} — always present, the un-posed capture.</li>
 *   <li>{@code "sitting"}, {@code "sleeping"} — discrete AI-state poses (§4 step 4/5
 *       of the design doc), forced via {@code TamableAnimal#setInSittingPose}/
 *       {@code Entity#setPose(Pose.SLEEPING)} at capture time.</li>
 *   <li>{@code "walk_phase_N"} — vanilla/Tabula-procedural limb-swing sweep sample,
 *       {@code N} the phase point index (not a percentage — see the capture tool's
 *       own doc for the exact phase values used).</li>
 *   <li>{@code "attack_swing_N"} — same shape, for attack-progress sweeps.</li>
 *   <li>{@code "clip_<name>_t<sampleIndex>"} — GeckoLib clip sample, {@code <name>}
 *       the real {@code .animation.json} clip name discovered at capture time,
 *       {@code <sampleIndex>} which of the (default 10, per Q2) samples across the
 *       clip's length this is.</li>
 *   <li>{@code "llib_anim_<id>_t<sampleIndex>"} / {@code "llib_anim_<name>_t<sampleIndex>"}
 *       — LLibrary/LionfishAPI {@code IAnimatedEntity} sample, keyed by whichever of
 *       {@code Animation#getID()} (bare {@code Animation}) or
 *       {@code NamedAnimation#getName()} (when the entity's animations are actually
 *       named) the capture tool found on that entity's {@code getAnimations()} array.</li>
 * </ul>
 * No enum is used for {@code poseId} — the open-ended clip-name/id-derived tail
 * entries make a fixed enum unworkable, and every consumer treats this as an opaque
 * lookup key (see {@link EntityBlueprint#pose}), never a switch target.
 *
 * @param poseId human-readable/derived identifier, see above.
 * @param parts  captured geometry for this pose — same {@link PartOBB} shape and
 *               {@code boneId} space as a live {@code EntityHitboxSnapshot} for the
 *               same entity key, resolved via that entity key's shared
 *               {@code BoneTable} at capture time so ids agree with live captures.
 */
public record BlueprintPose(String poseId, List<PartOBB> parts) {

    public BlueprintPose {
        parts = List.copyOf(parts);
    }
}
