package exp.CCnewmods.khp.blueprint.sweep;

import com.github.L_Ender.lionfishapi.server.animation.Animation;
import com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity;
import com.github.L_Ender.lionfishapi.server.animation.NamedAnimation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers and forces LionfishAPI {@code IAnimatedEntity} animations. Same
 * mechanism and same author lineage as {@link LLibraryAnimDiscovery} — confirmed via
 * {@code javap} against {@code lionfishapi-3_0.jar} that the interface shape
 * ({@code getAnimations}/{@code setAnimation}/{@code setAnimationTick}) is identical —
 * but a genuinely separate Java type with no shared interface between the two mods,
 * so this is a second small class rather than one generalized over both.
 *
 * <p><b>Known gap, not handled by this class:</b> LionfishAPI additionally ships a
 * second, newer keyframe-channel animation system
 * ({@code client.model.AdvancedAnimations.AdvancedKeyframeAnimations}/
 * {@code AdvancedAnimationDefinition}) alongside this basic id/duration one — some
 * LionfishAPI-based models may use that system instead of (or as well as)
 * {@code IAnimatedEntity}. Not investigated this session; entities using only the
 * newer system will discover zero animations here and fall through to the
 * vanilla-procedural sweep tier, same as any other unhandled case.
 */
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
        for (Animation anim : animations) {
            if (anim == null) continue;
            String fragment = anim instanceof NamedAnimation named ? named.getName() : "id" + anim.getID();
            result.add(new AnimInfo(fragment, anim));
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
