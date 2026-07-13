package exp.CCnewmods.khp.blueprint.sweep;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.fml.ModList;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ordered {@link PlannedPose} list for one spawned test entity, per
 * design doc §4. Called twice by {@link BlueprintCaptureSweep} for any
 * {@code AgeableMob} — once on the normal entity, once after {@code setBaby(true)} —
 * since baby gets a fully separate blueprint file (Q6), not a pose entry; this class
 * itself doesn't know about the baby/adult split, it just plans whatever poses apply
 * to the entity in its <i>current</i> state.
 */
public final class PosePlanBuilder {

    /** Default sample count across a clip/sweep window — Q2, every other tick. */
    public static final int SAMPLE_COUNT = 10;

    /**
     * @param poses        the ordered plan, same as before.
     * @param rendererKind diagnostic-only string stored on the resulting
     *                     {@code EntityBlueprint} (see its javadoc) — resolved here
     *                     from whichever branch below actually matched, rather than
     *                     left as a hardcoded placeholder.
     */
    public record PosePlan(List<PlannedPose> poses, String rendererKind) {
    }

    public static PosePlan build(Entity entity) {
        List<PlannedPose> plan = new ArrayList<>();

        plan.add(new PlannedPose("rest", e -> {
        }));

        if (entity instanceof TamableAnimal) {
            plan.add(new PlannedPose("sitting", e -> ((TamableAnimal) e).setInSittingPose(true)));
        }

        // Base Entity API — generic across anything using vanilla's pose system,
        // not gated on a specific entity family.
        plan.add(new PlannedPose("sleeping", e -> e.setPose(Pose.SLEEPING)));

        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        String rendererKind;

        // Epic Fight checked first — it can overlay its own skeletal animator on an
        // entity regardless of what base renderer type the entity would otherwise
        // use (vanilla, GeckoLib, etc.), so its presence should win the branch
        // dispatch rather than being masked by a renderer-kind check below.
        //
        // Each optional-mod branch is gated on ModList.isLoaded() *before* the
        // short-circuiting &&, not just wrapped in a null-check inside the Discovery
        // class — every XxxAnimDiscovery class imports that mod's own types directly
        // (e.g. yesman.epicfight.*), so simply invoking .supports(entity) when the
        // mod isn't installed throws NoClassDefFoundError the instant the JVM tries
        // to resolve/verify that method. Skipping the call entirely (via the
        // short-circuit) is what actually prevents the crash — matches the same
        // isolation convention as MahCompatBootstrap's own third-party gating.
        if (ModList.get().isLoaded("epicfight") && EpicFightAnimDiscovery.supports(entity)) {
            plan.addAll(buildEpicFightSamples(entity));
            rendererKind = "EPIC_FIGHT";
        } else if (renderer instanceof GeoRenderer) {
            plan.addAll(buildGeckoLibSamples(entity));
            rendererKind = "GECKO";
        } else if (ModList.get().isLoaded("mowziesmobs") && LLibraryAnimDiscovery.supports(entity)) {
            plan.addAll(buildLLibrarySamples(entity));
            rendererKind = "LLIBRARY";
        } else if (ModList.get().isLoaded("lionfishapi") && LionfishAnimDiscovery.supports(entity)) {
            plan.addAll(buildLionfishSamples(entity));
            rendererKind = "LIONFISH";
        } else if (ModList.get().isLoaded("citadel") && CitadelAnimDiscovery.supports(entity)) {
            plan.addAll(buildCitadelSamples(entity));
            rendererKind = "CITADEL";
        } else if (renderer instanceof LivingEntityRenderer && entity instanceof LivingEntity) {
            plan.addAll(buildVanillaProceduralSamples());
            rendererKind = "VANILLA_LIVING";
        } else {
            // OTHER-static (Citadel/LionfishAPI-basic-box models with no IAnimatedEntity
            // and no procedural driver, or a LionfishAPI model using only the newer
            // AdvancedKeyframeAnimations system — see design doc discussion, not
            // handled generically) — rest capture above is the only pose.
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
                // Epic Fight's playAnimationInstantly always restarts at t=0 (unlike
                // GeckoLib's setAnimation, which no-ops on an unchanged RawAnimation —
                // not verified either way for Epic Fight, so this re-forces every
                // sample rather than relying on an assumed no-op) and lets real ticks
                // elapse via BlueprintCaptureSweep's wait mechanism to reach sample i.
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
                // Every sample re-forces the clip from t=0 and waits `waitTicks` real
                // ticks before capturing — AnimationController has no public "seek"
                // API, only "set animation" + "let process() tick forward" (confirmed
                // via javap), so reaching sample i means forcing fresh and letting it
                // run rather than seeking an already-playing controller.
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

    /**
     * Vanilla/Tabula-procedural fallback — no public per-model override point exists
     * here the way GeckoLib/LLibrary have one (design doc §4), so this sweeps the
     * entity's own public animation-state fields directly: {@code walkAnimation}
     * (public, {@code WalkAnimationState} — confirmed via {@code javap}) advanced
     * through a few phase points via its public {@code update(float, float)}, and
     * {@code attackAnim} (public {@code float} field on {@code LivingEntity}) set
     * directly to a few progress fractions. Deliberately a bounded approximation of a
     * continuous space, not exhaustive — same honesty as every other capture path's
     * documented gaps.
     */
    private static List<PlannedPose> buildVanillaProceduralSamples() {
        List<PlannedPose> plan = new ArrayList<>();
        float[] walkPhases = {0.0f, 0.25f, 0.5f, 0.75f};
        for (int i = 0; i < walkPhases.length; i++) {
            float phase = walkPhases[i];
            plan.add(new PlannedPose("walk_phase_" + i, e -> {
                if (e instanceof LivingEntity living) {
                    // WalkAnimationState has no direct "set absolute position" setter
                    // — only setSpeed/update(speed, multiplier) (confirmed via
                    // javap). Driving position via repeated update() calls until it
                    // crosses the target phase is an approximation of exactly how far
                    // each call advances things; likely needs a small tuning pass once
                    // real captures exist to compare against (same "open to tuning"
                    // note as the sample-count default itself).
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

    private PosePlanBuilder() {
    }
}
