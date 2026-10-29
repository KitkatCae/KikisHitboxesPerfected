package exp.CCnewmods.khp.blueprint.sweep;

import com.github.alexthe666.citadel.animation.Animation;
import com.github.alexthe666.citadel.animation.IAnimatedEntity;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** Citadel {@code IAnimatedEntity} animations. */
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
