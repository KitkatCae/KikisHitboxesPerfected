package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.capture.PartOBB;

import java.util.List;

/**
 * One captured pose. {@code poseId} values: {@code rest} (always present), {@code sitting}, {@code sleeping},
 * {@code walk_phase_N}, {@code attack_swing_N}, {@code clip_<name>_t<sample>} (GeckoLib),
 * {@code epicfight_anim_<path>_t<sample>}, and {@code <llib|lionfish|citadel|uranus>_anim_<fragment>_t<sample>}
 * where the fragment is the animation's name or {@code id<getID()>}, plus {@code _<array index>} if not unique
 * (see {@code AnimFragmentNames}).
 */
public record BlueprintPose(String poseId, List<PartOBB> parts) {

    public BlueprintPose {
        parts = List.copyOf(parts);
    }
}
