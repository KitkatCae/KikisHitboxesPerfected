package exp.CCnewmods.khp.blueprint.sweep;

import com.github.L_Ender.lionfishapi.server.animation.Animation;
import com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity;
import com.github.L_Ender.lionfishapi.server.animation.NamedAnimation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** LionfishAPI {@code IAnimatedEntity} animations. Models using only its newer keyframe system aren't handled. */
public final class LionfishAnimDiscovery {

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
        String[] fragments = AnimFragmentNames.assign(animations.length,
                i -> animations[i] instanceof NamedAnimation named ? named.getName() : null,
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

    private LionfishAnimDiscovery() {
    }
}
