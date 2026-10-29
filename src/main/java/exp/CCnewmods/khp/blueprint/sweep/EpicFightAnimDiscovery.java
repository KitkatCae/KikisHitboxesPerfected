package exp.CCnewmods.khp.blueprint.sweep;

import net.minecraft.world.entity.Entity;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotion;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forces Epic Fight living-motion animations for the sweep. Assumes {@code getTotalTime()} is in seconds and
 * that the animator exists right after {@code EntityType.create}.
 */
public final class EpicFightAnimDiscovery {

    public record AnimInfo(String poseFragment, AssetAccessor<? extends StaticAnimation> accessor) {
    }

    public static boolean supports(Entity entity) {
        return EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class) != null;
    }

    public static List<AnimInfo> discover(Entity entity) {
        List<AnimInfo> result = new ArrayList<>();
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (patch == null) return result;
        Animator animator = patch.getAnimator();
        if (animator == null) return result;
        Map<LivingMotion, AssetAccessor<? extends StaticAnimation>> living = animator.getLivingAnimations();
        for (Map.Entry<LivingMotion, AssetAccessor<? extends StaticAnimation>> entry : living.entrySet()) {
            AssetAccessor<? extends StaticAnimation> accessor = entry.getValue();
            if (accessor == null || accessor.isEmpty()) continue;
            // LivingMotion's toString() may not be clean.
            String fragment = accessor.registryName() != null
                    ? accessor.registryName().getPath()
                    : entry.getKey().toString();
            result.add(new AnimInfo(fragment, accessor));
        }
        return result;
    }

    public static int durationTicks(AssetAccessor<? extends StaticAnimation> accessor) {
        if (accessor.isEmpty()) return 20;
        return Math.max(1, Math.round(accessor.get().getTotalTime() * 20.0f));
    }

    public static void force(Entity entity, AssetAccessor<? extends StaticAnimation> accessor) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (patch == null) return;
        Animator animator = patch.getAnimator();
        if (animator == null) return;
        animator.playAnimationInstantly(accessor);
    }

    private EpicFightAnimDiscovery() {
    }
}
