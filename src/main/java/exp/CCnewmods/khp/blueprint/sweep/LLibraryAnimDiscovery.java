package exp.CCnewmods.khp.blueprint.sweep;

import com.ilexiconn.llibrary.server.animation.Animation;
import com.ilexiconn.llibrary.server.animation.IAnimatedEntity;
import com.ilexiconn.llibrary.server.animation.NamedAnimation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers and forces LLibrary {@code IAnimatedEntity} animations (Mowzie's Mobs'
 * older, pre-GeckoLib creatures — Frostmaw, Naga, Grottol, Wroughtnaut per the Phase 1
 * handoff). Confirmed via {@code javap} against {@code mowziesmobs-1_8_2.jar}: every
 * method used here ({@code getAnimations}, {@code setAnimation}, {@code setAnimationTick})
 * is declared directly on the public interface, so any concrete implementing entity
 * class is guaranteed to expose them publicly (Java requires public interface-method
 * implementations) — no reflection needed, same as the GeckoLib path.
 *
 * <p>Base {@code Animation} only carries a numeric {@link Animation#getID()}; only
 * entities using {@link NamedAnimation} specifically expose a real name via
 * {@link NamedAnimation#getName()} — this class falls back to the numeric id for the
 * pose id when the entity's animations aren't named, rather than assuming every
 * LLibrary-based entity names its animations.
 */
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

    /** Ticks (game ticks — LLibrary's {@code getDuration()} is already tick-scaled, not seconds). */
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
