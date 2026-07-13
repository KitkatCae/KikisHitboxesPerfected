package exp.CCnewmods.khp.blueprint.sweep;

import com.github.alexthe666.citadel.animation.Animation;
import com.github.alexthe666.citadel.animation.IAnimatedEntity;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers and forces Citadel {@code IAnimatedEntity} animations. Same lineage and
 * same shape as {@link LLibraryAnimDiscovery}/{@link LionfishAnimDiscovery} —
 * confirmed via {@code javap} against {@code citadel-2_6_3-1_20_1.jar}.
 *
 * <p><b>Real difference from the other two, confirmed this session:</b> Citadel's
 * {@code Animation} has no {@code NamedAnimation} subclass at all — every Citadel
 * animation is purely a numeric {@code getID()}, no name ever. Unlike the other two
 * classes, there's no named-vs-id branch here; the pose id is always
 * {@code "id" + getID()}. This class deliberately doesn't reference a
 * {@code NamedAnimation} type for Citadel — one doesn't exist in the jar, so doing so
 * would fail to compile, not just fall back at runtime.
 */
public final class CitadelAnimDiscovery {

    public record AnimInfo(String poseFragment, Animation animation) {
    }

    public static boolean supports(Entity entity) {
        return entity instanceof IAnimatedEntity;
    }

    public static List<AnimInfo> discover(Entity entity) {
        List<AnimInfo> result = new ArrayList<>();
        if (!(entity instanceof IAnimatedEntity animated)) return result;
        Animation[] animations = animated.getAnimations();
        if (animations == null) return result;
        for (Animation anim : animations) {
            if (anim == null) continue;
            result.add(new AnimInfo("id" + anim.getID(), anim));
        }
        return result;
    }

    public static int durationTicks(Animation animation) {
        return animation.getDuration();
    }

    public static void force(Entity entity, Animation animation) {
        if (!(entity instanceof IAnimatedEntity animated)) return;
        animated.setAnimation(animation);
        animated.setAnimationTick(0);
    }

    private CitadelAnimDiscovery() {
    }
}
