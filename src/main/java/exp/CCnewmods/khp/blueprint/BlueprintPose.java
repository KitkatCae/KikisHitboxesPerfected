package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.capture.PartOBB;

import java.util.List;

/**
 * One captured pose. {@code poseId} values: {@code rest} (always present), {@code sitting}, {@code sleeping},
 * {@code walk_phase_N}, {@code attack_swing_N}, {@code clip_<name>_t<sample>} (GeckoLib) and
 * {@code llib_anim_<id|name>_t<sample>} (LLibrary/LionfishAPI).
 */
public record BlueprintPose(String poseId, List<PartOBB> parts) {

    public BlueprintPose {
        parts = List.copyOf(parts);
    }
}
