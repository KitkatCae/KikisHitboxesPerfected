package exp.CCnewmods.khp.blueprint.sweep;

import com.iafenvoy.uranus.animation.Animation;
import com.iafenvoy.uranus.animation.IAnimatedEntity;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** Uranus {@code IAnimatedEntity} animations (Ice and Fire: Community Edition). */
public final class UranusAnimDiscovery {

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
        String[] fragments = AnimFragmentNames.assign(animations.length, i -> null,
                i -> animations[i].getID(), i -> animations[i] == null);
        for (int i = 0; i < animations.length; i++) {
            if (animations[i] == null) continue;
            result.add(new AnimInfo(fragments[i], animations[i]));
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

    private UranusAnimDiscovery() {
    }
}
