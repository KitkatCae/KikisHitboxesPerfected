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
 * Discovers and forces Epic Fight animations for the sweep tool, confirmed via
 * {@code javap} against {@code epic-fight-20_14_16-mc1_20_1-forge.jar}. A genuinely
 * different (and larger) shape than the {@code IAnimatedEntity} mods:
 *
 * <pre>
 * entity
 *   → EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class)  → LivingEntityPatch&lt;?&gt;
 *   .getAnimator()                                                          → Animator
 *   .getLivingAnimations()                                                  → Map&lt;LivingMotion, AssetAccessor&lt;? extends StaticAnimation&gt;&gt;
 *   animator.playAnimationInstantly(accessor)                               → forces it (public, abstract, confirmed)
 * </pre>
 *
 * <p>Existing capture of Epic Fight's own geometry (skinned-mesh joint boxes) is
 * unaffected by this class — that already works via {@code captureExternalPart} and
 * the existing {@code compat.epicfight} mixins/classes (Phase 1); this class only
 * makes sure the <b>animation</b> driving that geometry is forced to each pose the
 * sweep wants sampled, same division of labor as every other framework here.
 *
 * <p><b>Two unverified assumptions, flagged rather than silently guessed:</b>
 * <ul>
 *   <li>{@code DynamicAnimation.getTotalTime()} is assumed to be seconds (matching
 *       this mod's own GeckoLib-adjacent convention elsewhere), scaled ×20 for tick
 *       sampling in {@link #durationTicks}. Not bytecode-confirmed which unit it
 *       actually returns.</li>
 *   <li>Whether {@code getAnimator()}/capability attachment is guaranteed fully
 *       initialized immediately after {@code EntityType.create(level)} (this sweep's
 *       spawn path) — vs. needing an actual level-join or first-render event first,
 *       the way GeckoLib's {@code AnimatableManager} is created lazily on first
 *       access — isn't confirmed. {@link #supports} returning {@code false} on a
 *       genuinely Epic-Fight-patched entity (rather than {@code true} with a not-yet-
 *       ready animator) is the likely failure mode if this assumption is wrong;
 *       worth checking sweep logs for Epic Fight entity types specifically the first
 *       time this runs.</li>
 * </ul>
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
            // registryName() gives a real ResourceLocation — preferred over
            // LivingMotion's own toString() (an ExtendableEnum, not a plain Java
            // enum — its string form isn't bytecode-confirmed to be stable/clean).
            String fragment = accessor.registryName() != null
                    ? accessor.registryName().getPath()
                    : entry.getKey().toString();
            result.add(new AnimInfo(fragment, accessor));
        }
        return result;
    }

    /** See class javadoc's unit caveat — assumed seconds, not confirmed. */
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
