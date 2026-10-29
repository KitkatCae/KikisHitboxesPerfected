package exp.CCnewmods.khp.blueprint.sweep;

import com.ilexiconn.llibrary.server.animation.Animation;
import com.ilexiconn.llibrary.server.animation.IAnimatedEntity;
import com.ilexiconn.llibrary.server.animation.NamedAnimation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** LLibrary {@code IAnimatedEntity} animations (Mowzie's Mobs' older creatures). Unnamed animations use their id. */
public final class LLibraryAnimDiscovery {

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
            String fragment = anim instanceof NamedAnimation named ? named.getName() : "id" + anim.getID();
            result.add(new AnimInfo(fragment, anim));
        }
        return result;
    }

    /** Already in ticks. */
    public static int durationTicks(Animation animation) {
        return animation.getDuration();
    }

    public static void force(Entity entity, Animation animation) {
        if (!(entity instanceof IAnimatedEntity animated)) return;
        animated.setAnimation(animation);
        animated.setAnimationTick(0);
    }

    private LLibraryAnimDiscovery() {
    }
}
