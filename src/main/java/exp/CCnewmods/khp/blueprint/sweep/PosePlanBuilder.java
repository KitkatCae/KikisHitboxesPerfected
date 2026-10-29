package exp.CCnewmods.khp.blueprint.sweep;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.fml.ModList;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.util.ArrayList;
import java.util.List;

/** Builds the ordered poses to capture for one sweep entity. Babies get their own call and blueprint file. */
public final class PosePlanBuilder {

    public static final int SAMPLE_COUNT = 10;

    /** @param rendererKind diagnostic label stored on the blueprint. */
    public record PosePlan(List<PlannedPose> poses, String rendererKind) {
    }

    public static PosePlan build(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();

        plan.add(new PlannedPose("rest", e -> {
        }));

        if (entity instanceof TamableAnimal) {
            plan.add(new PlannedPose("sitting", e -> ((TamableAnimal) e).setInSittingPose(true)));
        }

        plan.add(new PlannedPose("sleeping", e -> e.setPose(Pose.SLEEPING)));

        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        String rendererKind;

        // Epic Fight first: it overrides any renderer type.
        // Check ModList before calling the XxxAnimDiscovery classes; they import the mod's types.
        if (ModList.get().isLoaded("epicfight") && EpicFightAnimDiscovery.supports(entity)) {
            plan.addAll(buildEpicFightSamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "EPIC_FIGHT";
        } else if (renderer instanceof GeoRenderer) {
            plan.addAll(buildGeckoLibSamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "GECKO";
        } else if (ModList.get().isLoaded("mowziesmobs") && LLibraryAnimDiscovery.supports(entity)) {
            plan.addAll(buildLLibrarySamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "LLIBRARY";
        } else if (ModList.get().isLoaded("lionfishapi") && LionfishAnimDiscovery.supports(entity)) {
            plan.addAll(buildLionfishSamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "LIONFISH";
        } else if (ModList.get().isLoaded("citadel") && CitadelAnimDiscovery.supports(entity)) {
            plan.addAll(buildCitadelSamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "CITADEL";
        } else if (ModList.get().isLoaded("uranus") && UranusAnimDiscovery.supports(entity)) {
            // Uranus = Ice and Fire: Community Edition.
            plan.addAll(buildUranusSamples(entity));
            plan.addAll(buildGenericMotionSamples(entity));
            rendererKind = "URANUS";
        } else if (renderer instanceof LivingEntityRenderer && entity instanceof LivingEntity) {
            plan.addAll(buildVanillaProceduralSamples());
            rendererKind = "VANILLA_LIVING";
        } else {
            // No animation system to drive: the rest pose is the only one.
            rendererKind = "OTHER";
        }

        return new PosePlan(plan, rendererKind);
    }

    private static List<PlannedPose> buildEpicFightSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (EpicFightAnimDiscovery.AnimInfo info : EpicFightAnimDiscovery.discover(entity)) {
            int duration = Math.max(1, EpicFightAnimDiscovery.durationTicks(info.accessor()));
            int step = Math.max(1, duration / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                String poseId = "epicfight_anim_" + info.poseFragment() + "_t" + i;
                int waitTicks = i * step;
                // Re-force from t=0 every sample and let ticks elapse to reach sample i.
                PlannedPose pose = new PlannedPose(poseId, e -> EpicFightAnimDiscovery.force(e, info.accessor()));
                BlueprintCaptureSweep.registerExtraWait(pose, waitTicks);
                plan.add(pose);
            }
        }
        return plan;
    }

    private static List<PlannedPose> buildGeckoLibSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (GeckoLibClipDiscovery.ClipInfo clip : GeckoLibClipDiscovery.discoverClips(entity)) {
            double stepTicks = Math.max(1.0, clip.lengthTicks() / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                String poseId = "clip_" + clip.name() + "_t" + i;
                int waitTicks = (int) Math.round(i * stepTicks);
                // No seek API: re-force from t=0 and wait `waitTicks`.
                PlannedPose pose = new PlannedPose(poseId, e -> GeckoLibClipDiscovery.forceClip(e, clip.name()));
                BlueprintCaptureSweep.registerExtraWait(pose, waitTicks);
                plan.add(pose);
            }
        }
        return plan;
    }

    private static List<PlannedPose> buildLLibrarySamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (LLibraryAnimDiscovery.AnimInfo info : LLibraryAnimDiscovery.discover(entity)) {
            int duration = Math.max(1, LLibraryAnimDiscovery.durationTicks(info.animation()));
            int step = Math.max(1, duration / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                int tick = Math.min(duration - 1, i * step);
                String poseId = "llib_anim_" + info.poseFragment() + "_t" + i;
                plan.add(new PlannedPose(poseId, e -> {
                    LLibraryAnimDiscovery.force(e, info.animation());
                    forceLLibraryTick(e, tick);
                }));
            }
        }
        return plan;
    }

    private static List<PlannedPose> buildLionfishSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (LionfishAnimDiscovery.AnimInfo info : LionfishAnimDiscovery.discover(entity)) {
            int duration = Math.max(1, LionfishAnimDiscovery.durationTicks(info.animation()));
            int step = Math.max(1, duration / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                int tick = Math.min(duration - 1, i * step);
                String poseId = "lionfish_anim_" + info.poseFragment() + "_t" + i;
                plan.add(new PlannedPose(poseId, e -> {
                    LionfishAnimDiscovery.force(e, info.animation());
                    forceLionfishTick(e, tick);
                }));
            }
        }
        return plan;
    }

    private static List<PlannedPose> buildCitadelSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (CitadelAnimDiscovery.AnimInfo info : CitadelAnimDiscovery.discover(entity)) {
            int duration = Math.max(1, CitadelAnimDiscovery.durationTicks(info.animation()));
            int step = Math.max(1, duration / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                int tick = Math.min(duration - 1, i * step);
                String poseId = "citadel_anim_" + info.poseFragment() + "_t" + i;
                plan.add(new PlannedPose(poseId, e -> {
                    CitadelAnimDiscovery.force(e, info.animation());
                    forceCitadelTick(e, tick);
                }));
            }
        }
        return plan;
    }

    private static List<PlannedPose> buildUranusSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();
        for (UranusAnimDiscovery.AnimInfo info : UranusAnimDiscovery.discover(entity)) {
            int duration = Math.max(1, UranusAnimDiscovery.durationTicks(info.animation()));
            int step = Math.max(1, duration / SAMPLE_COUNT);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                int tick = Math.min(duration - 1, i * step);
                String poseId = "uranus_anim_" + info.poseFragment() + "_t" + i;
                plan.add(new PlannedPose(poseId, e -> {
                    UranusAnimDiscovery.force(e, info.animation());
                    forceUranusTick(e, tick);
                }));
            }
        }
        return plan;
    }

    private static final int GENERIC_WALK_SAMPLE_COUNT = 8;
    private static final int GENERIC_ATTACK_SAMPLE_COUNT = 8;
    private static final int GENERIC_WALK_WINDOW_TICKS = 20;
    private static final int GENERIC_ATTACK_WINDOW_TICKS = 20;

    /**
     * Framework-agnostic walk/attack samples: drives real velocity and a vanilla swing and records whatever the
     * entity's animator does. Mods with custom attack triggers may produce rest-like attack samples.
     */
    private static List<PlannedPose> buildGenericMotionSamples(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();

        // Re-apply velocity every sample; drag decays it otherwise.
        for (int i = 0; i < GENERIC_WALK_SAMPLE_COUNT; i++) {
            String poseId = "walk_phase_" + i;
            int waitTicks = (i * GENERIC_WALK_WINDOW_TICKS) / GENERIC_WALK_SAMPLE_COUNT;
            PlannedPose pose = new PlannedPose(poseId, e -> {
                e.setDeltaMovement(0.0, e.getDeltaMovement().y, -0.2);
                e.hasImpulse = true;
            });
            BlueprintCaptureSweep.registerExtraWait(pose, waitTicks);
            plan.add(pose);
        }

        for (int i = 0; i < GENERIC_ATTACK_SAMPLE_COUNT; i++) {
            String poseId = "attack_swing_" + i;
            int waitTicks = (i * GENERIC_ATTACK_WINDOW_TICKS) / GENERIC_ATTACK_SAMPLE_COUNT;
            int index = i;
            PlannedPose pose = new PlannedPose(poseId, e -> {
                if (index == 0 && e instanceof LivingEntity living) {
                    living.swing(InteractionHand.MAIN_HAND);
                }
            });
            BlueprintCaptureSweep.registerExtraWait(pose, waitTicks);
            plan.add(pose);
        }

        return plan;
    }

    /** Vanilla/Tabula-procedural fallback: steps {@code walkAnimation} and {@code attackAnim} through a few values. */
    private static List<PlannedPose> buildVanillaProceduralSamples() {
        List<PlannedPose> plan = new ArrayList<>();
        float[] walkPhases = {0.0f, 0.25f, 0.5f, 0.75f};
        for (int i = 0; i < walkPhases.length; i++) {
            float phase = walkPhases[i];
            plan.add(new PlannedPose("walk_phase_" + i, e -> {
                if (e instanceof LivingEntity living) {
                    // No absolute setter; repeated update() calls approximate the phase. TODO: tune.
                    living.walkAnimation.setSpeed(1.0f);
                    for (int step = 0; step < Math.round(phase * 20); step++) {
                        living.walkAnimation.update(1.0f, 0.6f);
                    }
                }
            }));
        }
        float[] attackPhases = {0.0f, 0.5f, 1.0f};
        for (int i = 0; i < attackPhases.length; i++) {
            float progress = attackPhases[i];
            plan.add(new PlannedPose("attack_swing_" + i, e -> {
                if (e instanceof LivingEntity living) {
                    living.attackAnim = progress;
                    living.oAttackAnim = progress;
                }
            }));
        }
        return plan;
    }

    private static void forceLLibraryTick(Entity e, int tick) {
        if (e instanceof com.ilexiconn.llibrary.server.animation.IAnimatedEntity animated) {
            animated.setAnimationTick(tick);
        }
    }

    private static void forceLionfishTick(Entity e, int tick) {
        if (e instanceof com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity animated) {
            animated.setAnimationTick(tick);
        }
    }

    private static void forceCitadelTick(Entity e, int tick) {
        if (e instanceof com.github.alexthe666.citadel.animation.IAnimatedEntity animated) {
            animated.setAnimationTick(tick);
        }
    }

    private static void forceUranusTick(Entity e, int tick) {
        if (e instanceof com.iafenvoy.uranus.animation.IAnimatedEntity animated) {
            animated.setAnimationTick(tick);
        }
    }

    private PosePlanBuilder() {
    }
}