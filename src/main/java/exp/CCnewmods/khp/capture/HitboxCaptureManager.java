package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.compat.embeddium.EmbeddiumFastCubeOpacity;
import exp.CCnewmods.khp.duck.MahNamedPart;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Render-thread capture state machine. All capture mixins are thin calls into here.
 *
 * <h3>Bracket</h3>
 * {@code EntityRenderDispatcher.render} HEAD/RETURN open and close a capture bracket.
 * At open we snapshot the inverse of (current top pose × the entity translation the
 * dispatcher is about to apply) — the <b>basis inverse</b>. Every part pose captured
 * inside the bracket is multiplied by it, yielding pure entity-local transforms
 * (design §1). Nested dispatcher renders (mods rendering entities inside entity
 * renderers) are depth-tracked and suppressed rather than misattributed.
 *
 * <h3>Threading</h3>
 * All mutable state here is touched only by the render thread. Publication hands
 * immutable snapshots to the thread-safe {@link HitboxStore}.
 */
public final class HitboxCaptureManager {

    public enum RendererKind { VANILLA_LIVING, GECKO, OTHER }

    // ── Bracket state (render thread only) ──────────────────────────────────

    private static int depth = 0;
    private static int suppressDepth = 0;
    private static boolean active = false;
    private static boolean levelRenderPass = false;
    /** Open while LivingEntityRenderer is inside the primary model's renderToBuffer. */
    private static boolean primaryGate = false;

    private static Entity entity;
    private static Object currentModel;
    private static RendererKind kind = RendererKind.OTHER;
    private static Matrix4f basisInverse;
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 capturedEntityPos = Vec3.ZERO;
    private static float bodyYaw;
    private static BoneTable boneTable;

    private static final List<PartOBB> PARTS = new ArrayList<>(64);
    private static final ArrayDeque<String> PATH_STACK = new ArrayDeque<>();
    private static final ArrayDeque<RecordingVertexConsumer> RAW_CAPTURE_STACK = new ArrayDeque<>();

    /**
     * Phase 2c: the texture this bracket's entity renders with, resolved once at
     * {@link #enterBracket} via {@code EntityRenderer.getTextureLocation}/
     * {@code GeoRenderer.getTextureLocation} — the source {@link TextureAlphaSampler}
     * reads from for every opacity sample taken during this bracket.
     */
    private static ResourceLocation currentTextureLocation;

    /**
     * Phase 2c: identity-keyed correlation from a vanilla {@code ModelPart.Cube} to
     * its already-added index in {@link #PARTS} — see
     * {@link #captureVanillaCubes(List, PoseStack.Pose)} and
     * {@link #endVanillaCubeOpacity(ModelPart.Cube, VertexConsumer)}. Deliberately
     * identity (not call-order) correlation: robust regardless of what order
     * {@code ModelPart.compile}'s own body iterates cubes in.
     */
    private static final Map<ModelPart.Cube, Integer> VANILLA_CUBE_PARTS_INDEX = new IdentityHashMap<>();

    /**
     * Identity-keyed, parallel to {@link #VANILLA_CUBE_PARTS_INDEX}: the inverse of
     * the exact {@code PoseStack.Pose} matrix {@code ModelPart.compile} was called
     * with for this cube's parent part — see {@link #beginVanillaCubeOpacity} for
     * why {@link CubeOpacityRecorder} needs this rather than a bare {@code /16.0}.
     * Same lifecycle as {@link #VANILLA_CUBE_PARTS_INDEX}: populated in
     * {@link #captureVanillaCubes}, cleared alongside it on bracket enter/exit.
     */
    private static final Map<ModelPart.Cube, Matrix4f> VANILLA_CUBE_POSE_INVERSE = new IdentityHashMap<>();

    /**
     * Cached once, lazily, on first use — {@code ModList.get()} is safe here (unlike
     * {@code IMixinConfigPlugin.shouldApplyMixin}, which must use {@code
     * LoadingModList} instead — see {@code TaczMixinPlugin}/{@code Vs2MixinPlugin}'s
     * own javadoc for why): this runs during real gameplay/capture, long after mod
     * loading has finished, not during the mixin PREPARE phase. {@code -1}/unset,
     * {@code 1}/present, {@code 0}/absent, avoiding a second nullable-Boolean
     * allocation for what's checked on every {@link #captureVanillaCubes} call.
     */
    private static byte embeddiumPresent = -1;

    private static boolean isEmbeddiumPresent() {
        if (embeddiumPresent < 0) {
            embeddiumPresent = (byte) (ModList.get().isLoaded("embeddium") ? 1 : 0);
        }
        return embeddiumPresent == 1;
    }

    // ── Level render pass flag (set from RenderLevelStageEvent) ─────────────

    public static void setLevelRenderPass(boolean inPass) {
        levelRenderPass = inPass;
    }

    // ── Bracket ─────────────────────────────────────────────────────────────

    /**
     * Called at HEAD of EntityRenderDispatcher.render, before the dispatcher pushes
     * its pose and applies the entity translation. Always paired with
     * {@link #exitBracket()} at RETURN.
     */
    public static void enterBracket(Entity e, EntityRenderer<?> renderer,
                                    double x, double y, double z,
                                    float partialTick, PoseStack poseStack) {
        depth++;
        if (depth > 1) {
            // Nested entity render inside another entity's render — suppress capture
            // for its duration so geometry is never attributed to the outer entity.
            suppressDepth++;
            return;
        }
        if (!MahConfig.enabled || !levelRenderPass || renderer == null || !shouldTrack(e)) {
            return; // bracket open but inactive; exitBracket still balances depth
        }

        // Basis: invert(topPose * translate(entityCameraRelPos + renderOffset)).
        // The dispatcher applies exactly this translate before calling the renderer
        // (verified against forge-1.20.1-47.4.20 mapped jar).
        @SuppressWarnings({"unchecked", "rawtypes"})
        Vec3 off = ((EntityRenderer) renderer).getRenderOffset(e, partialTick);
        Matrix4f basis = new Matrix4f(poseStack.last().pose());
        basis.translate((float) (x + off.x), (float) (y + off.y), (float) (z + off.z));
        basis.invert();
        basisInverse = basis;

        // World-space render origin: lerped entity position + renderer offset.
        // (LevelRenderer passes x,y,z = lerped pos − camera pos; adding the camera back
        //  is equivalent to lerping the entity position directly — camera-independent.)
        anchorPos = new Vec3(
                Mth.lerp(partialTick, e.xOld, e.getX()),
                Mth.lerp(partialTick, e.yOld, e.getY()),
                Mth.lerp(partialTick, e.zOld, e.getZ())).add(off);
        capturedEntityPos = e.position();
        bodyYaw = e instanceof LivingEntity living
                ? Mth.lerp(partialTick, living.yBodyRotO, living.yBodyRot)
                : e.getYRot();

        kind = renderer instanceof LivingEntityRenderer ? RendererKind.VANILLA_LIVING
                : renderer instanceof GeoRenderer ? RendererKind.GECKO
                : RendererKind.OTHER;
        currentModel = kind == RendererKind.VANILLA_LIVING ? ((LivingEntityRenderer<?, ?>) renderer).getModel() : null;

        // Phase 2c: resolved once per bracket, not per cube/quad — both accessors are
        // cheap lookups on the renderer, but there's no reason to repeat either per
        // face. GeckoLib's own getTextureLocation takes a GeoAnimatable, not Entity;
        // raw-type unchecked cast, same pattern already used above for getRenderOffset.
        try {
            currentTextureLocation = kind == RendererKind.GECKO
                    ? ((GeoRenderer) renderer).getTextureLocation((GeoAnimatable) e)
                    : ((EntityRenderer) renderer).getTextureLocation(e);
        } catch (RuntimeException ex) {
            currentTextureLocation = null; // opacity capture degrades to "always opaque" below
        }

        entity = e;
        boneTable = HitboxStore.getOrCreateBoneTable(MahApi.entityKey(e));
        PARTS.clear();
        PATH_STACK.clear();
        RAW_CAPTURE_STACK.clear();
        VANILLA_CUBE_PARTS_INDEX.clear();
        VANILLA_CUBE_POSE_INVERSE.clear();
        // Phase 1C: default OPEN, closed only around known RenderLayer windows (see
        // LivingEntityRendererMixin). Renderers that fully override render() without
        // calling super — e.g. Ice and Fire's RenderGhost, confirmed via bytecode —
        // never reach the injection that used to open this gate, silently producing
        // zero capture. Defaulting open fixes that class of bug outright; the only
        // cost is that such renderers' own RenderLayers (if any) go uncaptured-by-
        // default only insofar as they're never explicitly excluded either — a
        // strict improvement over capturing nothing.
        primaryGate = true;
        active = true;
    }

    /** Called at RETURN of EntityRenderDispatcher.render. */
    public static void exitBracket() {
        if (depth > 1) {
            if (suppressDepth > 0) suppressDepth--;
            depth--;
            return;
        }
        depth = 0;
        if (!active) return;
        active = false;
        primaryGate = false;

        if (!PARTS.isEmpty() && entity != null) {
            khp$logPublish(entity);
            HitboxStore.put(entity.getUUID(), new EntityHitboxSnapshot(
                    MahApi.entityKey(entity),
                    entity.getId(),
                    anchorPos,
                    capturedEntityPos,
                    bodyYaw,
                    entity.level().getGameTime(),
                    PARTS,
                    EntityHitboxSnapshot.computeLocalBounds(PARTS),
                    SnapshotSource.LIVE_CLIENT,
                    currentTextureLocation));
            // Published every rendered frame (immutable snapshot, atomic map put — the
            // cheapest and freshest option client-side). The design's once-per-tick
            // gate becomes relevant when snapshots cross the network in phase 3.
        }
        entity = null;
        currentModel = null;
        boneTable = null;
        basisInverse = null;
        currentTextureLocation = null;
        PARTS.clear();
        PATH_STACK.clear();
        RAW_CAPTURE_STACK.clear();
        VANILLA_CUBE_PARTS_INDEX.clear();
        VANILLA_CUBE_POSE_INVERSE.clear();
    }

    // TEMP DIAGNOSTIC — logs the exact UUID this bracket just published under, so it
    // can be directly compared against the UUID the collision code queries with on
    // the server thread for the "same" real-world mob. Throttled per-entity so this
    // doesn't spam every frame. Remove once resolved.
    private static final Map<java.util.UUID, Long> khp$publishLogCooldown = new ConcurrentHashMap<>();
    private static final long khp$PUBLISH_LOG_COOLDOWN_MS = 5000;

    private static void khp$logPublish(Entity e) {
        long now = System.currentTimeMillis();
        Long last = khp$publishLogCooldown.get(e.getUUID());
        if (last != null && now - last < khp$PUBLISH_LOG_COOLDOWN_MS) return;
        khp$publishLogCooldown.put(e.getUUID(), now);
        exp.CCnewmods.khp.Mah.LOGGER.info("[KHP DIAG] publishing live snapshot: type={}, uuid={}, entityId(client)={}, parts={}",
                e.getType(), e.getUUID(), e.getId(), PARTS.size());
    }

    private static boolean shouldTrack(Entity e) {
        if (e == null || e.isSpectator()) return false;
        // Always-excluded categories (design §7): items, xp orbs, projectiles, AECs.
        if (e instanceof ItemEntity || e instanceof ExperienceOrb
                || e instanceof Projectile || e instanceof AreaEffectCloud) return false;
        if (!MahConfig.blacklist.isEmpty()
                && MahConfig.blacklist.contains(MahApi.entityTypeKey(e))) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && e != player
                && player.distanceToSqr(e) > MahConfig.captureRadiusSqr) return false;
        return true;
    }

    // ── Anatomical RenderLayer allowlist (Phase 1D) ─────────────────────────

    /**
     * RenderLayer classes registered here are captured as primary geometry even while
     * {@link MahConfig#captureLayers} is off — for the rare case where a mod's actual
     * anatomy (not a cosmetic overlay) is implemented as a RenderLayer. Confirmed
     * examples: vanilla Slime's visible gooey body ({@code SlimeOuterLayer} — the
     * "primary" model is only the small solid inner core with the face) and Ice and
     * Fire's Hydra heads ({@code LayerHydraHead} — body+tail is primary, heads are a
     * repeating layer since head count varies). Toggled per-layer-class rather than
     * globally so cosmetic overlays (armor, saddles) keep their current excluded-by-
     * default behavior.
     */
    private static final Set<Class<?>> ANATOMICAL_LAYER_CLASSES = ConcurrentHashMap.newKeySet();

    public static void registerAnatomicalRenderLayer(Class<?> layerClass) {
        ANATOMICAL_LAYER_CLASSES.add(layerClass);
    }

    public static boolean isAnatomicalRenderLayer(Class<?> layerClass) {
        return ANATOMICAL_LAYER_CLASSES.contains(layerClass);
    }

    // ── Primary-model gate (LivingEntityRenderer) ───────────────────────────

    public static void setPrimaryGate(boolean open) {
        primaryGate = open;
    }

    // ── Vanilla ModelPart capture ───────────────────────────────────────────

    /** Push a part name at HEAD of ModelPart.render (8-arg). Balanced with pop at RETURN. */
    public static void pushPartName(ModelPart part) {
        if (!active) return;
        String name = ((MahNamedPart) (Object) part).mah$getName();
        String parent = PATH_STACK.peek();
        PATH_STACK.push(parent == null ? name : parent + "/" + name);
    }

    public static void popPartName() {
        if (!active) return;
        if (!PATH_STACK.isEmpty()) PATH_STACK.pop();
    }

    /**
     * Gate shared by every "flat ModelPart-style" geometry source: vanilla
     * {@code ModelPart}, and third-party Tabula-style boxes (Citadel's and
     * LionfishAPI's {@code AdvancedModelBox}) — anything that renders through a
     * {@code LivingEntityRenderer}/{@code MobRenderer}'s primary model slot or
     * through a non-vanilla, non-Geo renderer wholesale.
     */
    public static boolean shouldCaptureModelGeometry() {
        if (!active || suppressDepth > 0) return false;
        return switch (kind) {
            case OTHER -> true;
            case VANILLA_LIVING -> primaryGate || MahConfig.captureLayers;
            // During a GeckoLib bracket, any ModelPart/AdvancedModelBox geometry is
            // layer equipment (GeoArmorRenderer is a HumanoidModel) — base geometry
            // arrives via the Geo bone hook instead.
            case GECKO -> MahConfig.captureLayers;
        };
    }

    /**
     * True if {@code name} — the current part/bone identifier, whatever shape the
     * calling capture path uses (vanilla's full "root/.../name" path, or a flat
     * bone/box name for GeckoLib/Citadel/LLibrary/EpicFight/raw-vertex capture) — is
     * excluded for the bracket's current entity type by
     * {@link MahConfig#boneExclusions}. Matches either the exact string passed in, or
     * any single "/"-separated segment of it, so a config entry of just "tail"
     * catches both a flat GeckoLib bone named "tail" and a nested vanilla
     * "root/body/tail" path without the caller needing to know vanilla's nesting.
     * Checked once per part/bone/cube-group at the top of every capture entry point
     * (see each caller) — never a re-check per individual cube or vertex within it.
     */
    private static boolean isBoneExcluded(String path) {
        if (entity == null || path == null || MahConfig.boneExclusions.isEmpty()) return false;
        Set<String> patterns = MahConfig.boneExclusions.get(EntityType.getKey(entity.getType()));
        if (patterns == null || patterns.isEmpty()) return false;
        if (patterns.contains(path)) return true;
        for (String segment : path.split("/")) {
            if (patterns.contains(segment)) return true;
        }
        return false;
    }

    /**
     * Capture all cubes of the part currently compiling. Called from ModelPart.compile
     * HEAD, where the pose includes translateAndRotate — the exact vertex transform.
     */
    public static void captureVanillaCubes(List<ModelPart.Cube> cubes, PoseStack.Pose pose) {
        if (cubes.isEmpty() || boneTable == null) return;
        String path = PATH_STACK.peek();
        if (isBoneExcluded(path)) return; // MahConfig#boneExclusions — dead-weight part, skip capture entirely
        short boneId = boneTable.idFor(path == null ? "unnamed" : path);
        // One shared, never-mutated transform for all cubes of this part.
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        // Inverse of pose.pose() ALONE (not rel — see beginVanillaCubeOpacity's
        // javadoc for why): ModelPart.Cube.compile (bytecode-verified against the
        // real mapped jar) calls vertex(x,y,z) with each raw local corner already
        // divided by 16 AND already transformed by exactly this pose — inverting it
        // is what recovers a position back in the same untransformed, /16'd local
        // space captureVanillaCubes' own `box` (below) is built in.
        Matrix4f poseInverse = new Matrix4f(pose.pose()).invert();
        for (ModelPart.Cube cube : cubes) {
            // PHASE2-TODO: polygon-derived bounds (grow-inclusive) via AccessTransformer.
            AABB box = new AABB(
                    cube.minX / 16.0f, cube.minY / 16.0f, cube.minZ / 16.0f,
                    cube.maxX / 16.0f, cube.maxY / 16.0f, cube.maxZ / 16.0f);

            // Embeddium's fast_render entity path (when present) calls
            // EntityRenderer.renderCuboidFast(...) directly for any "simple" cube —
            // confirmed via decompile against embeddium-0.3.31 this session — which
            // never calls Cube.compile() at all. The two-phase patch below
            // (VANILLA_CUBE_PARTS_INDEX + beginVanillaCubeOpacity/
            // endVanillaCubeOpacity, patched once Cube.compile() actually runs) would
            // silently never fire for such a cube, permanently leaving faceMask=0/
            // flatFaceUV=null — real per-run evidence: zero MAH opacity-debug/
            // flat-debug log lines ever produced despite hundreds of confirmed
            // successful vanilla-mob captures. Computed eagerly here instead, from
            // the same box/UV data Embeddium's own fast path already reads — see
            // EmbeddiumFastCubeOpacity's own class javadoc for the mirrored-cube
            // scope boundary (flatFaceUV falls back to null for mirrored cubes only).
            EmbeddiumFastCubeOpacity.FastCubeResult fast = isEmbeddiumPresent()
                    ? EmbeddiumFastCubeOpacity.tryCompute(cube, currentTextureLocation,
                            MahConfig.opacityThreshold)
                    : null;
            if (fast != null) {
                PARTS.add(new PartOBB(fast.localBox(), rel, boneId, fast.faceMask(), fast.flatFaceUV()));
                continue; // this cube will never reach Cube.compile() — no two-phase patch to register
            }

            // faceMask starts at 0 (fully solid) here; ModelPartCubeMixin's per-cube
            // opacity wrap patches this entry in place once this cube's own compile()
            // call actually runs and its real UVs/colors are observed (Phase 2c —
            // see CubeOpacityRecorder). The identity correlation below is what lets
            // that later patch find the right entry regardless of iteration order.
            PARTS.add(new PartOBB(box, rel, boneId, (byte) 0, null));
            VANILLA_CUBE_PARTS_INDEX.put(cube, PARTS.size() - 1);
            VANILLA_CUBE_POSE_INVERSE.put(cube, poseInverse);
        }
    }

    // ── Vanilla per-cube opacity (Phase 2c) ─────────────────────────────────

    /**
     * Wrap the {@code VertexConsumer} for one {@code Cube.compile} call so its real
     * per-vertex UV/color/normal data can be observed — see
     * {@link CubeOpacityRecorder}. Returns {@code delegate} unchanged if this cube
     * isn't one {@link #captureVanillaCubes} already recorded (outside an active
     * bracket, or a cube belonging to a part that wasn't captured), so the caller
     * mixin never needs its own gating logic.
     *
     * <h3>Why {@code CubeOpacityRecorder} needs a pose inverse, not a bare {@code /16.0}</h3>
     * Bytecode-verified against the real mapped {@code ModelPart.Cube.compile}: the
     * {@code x,y,z} this wrapped {@code VertexConsumer} receives are <b>not</b> raw
     * local cube coordinates — vanilla already divides by 16 <i>and</i> applies the
     * full {@code pose.pose()} transform internally before ever calling
     * {@code vertex(x,y,z)}. A recorder that just divided by 16 again was comparing
     * already-posed, wrong-scale positions against {@link FaceGeometry#faceCorners}
     * reference points still in raw local space — the actual cause of every
     * {@code buildFlatFaceUV=NULL (missing corner samples)} miss (all four real
     * samples landing arbitrarily close to whichever one reference corner the
     * systematic offset happened to favor, never spreading across all four).
     * Multiplying the incoming vertex by {@link #VANILLA_CUBE_POSE_INVERSE}'s stored
     * inverse of that same {@code pose.pose()} undoes exactly that transform, with no
     * further scaling needed — vanilla's own {@code /16.0} already happened before
     * the transform, so inverting the transform alone lands back in the same
     * already-{@code /16}'d local space {@code box} (and therefore
     * {@link FaceGeometry#faceCorners}) is in.
     */
    public static VertexConsumer beginVanillaCubeOpacity(ModelPart.Cube cube, VertexConsumer delegate) {
        Integer index = VANILLA_CUBE_PARTS_INDEX.get(cube);
        if (index == null || index < 0 || index >= PARTS.size()) return delegate;
        Matrix4f poseInverse = VANILLA_CUBE_POSE_INVERSE.get(cube);
        if (poseInverse == null) return delegate; // shouldn't happen (populated in lockstep with the index above) — defensive, matches this method's existing no-op-on-miss contract
        AABB box = PARTS.get(index).localBox();
        return new CubeOpacityRecorder(delegate, currentTextureLocation, box, poseInverse);
    }

    /**
     * Finalize {@link #beginVanillaCubeOpacity}: if {@code buffer} is actually a
     * {@link CubeOpacityRecorder} (i.e. the begin call above didn't no-op) and this
     * cube still has a live entry in {@link #PARTS} (bracket could have been torn
     * down mid-render in a pathological case; defensive), patch that entry in place
     * via {@link #finalizeStructuredPart} — real faceMask, and for a flat cube, a
     * tightened box + {@link PartOBB.FlatFaceUV} (Option A). Records are immutable,
     * so "patch" means replacing the list slot with a new record.
     */
    public static void endVanillaCubeOpacity(ModelPart.Cube cube, VertexConsumer buffer) {
        if (!(buffer instanceof CubeOpacityRecorder recorder)) return;
        Integer index = VANILLA_CUBE_PARTS_INDEX.get(cube);
        if (index == null || index < 0 || index >= PARTS.size()) return;
        PartOBB old = PARTS.get(index);
        FaceOpacityAccumulator accumulator = recorder.accumulator();
        PARTS.set(index, finalizeStructuredPart(old.localBox(), old.transform(), old.boneId(), accumulator));
    }

    // ── Current model instance (for third-party naming reflection) ─────────

    /**
     * The concrete {@code EntityModel} (or, for third-party Tabula-style frameworks,
     * whatever object type stands in for it) currently open in the active
     * VANILLA_LIVING bracket — {@code null} outside such a bracket, or for
     * GECKO/OTHER kinds. Used by third-party compat classes (e.g. Mowzie's Mobs' own
     * LLibrary-based models, which have no back-reference field from box to owning
     * model) to resolve box → declared-field-name maps via
     * {@link exp.CCnewmods.khp.util.MahReflect#mapFieldNamesByIdentity}, without
     * needing a reflective field lookup on the box itself.
     */
    public static Object getCurrentModel() {
        return currentModel;
    }

    // ── External pre-computed parts (Phase 1G — Epic Fight skinned mesh) ────

    /**
     * Add a part whose local bounds and transform are already fully known — no
     * further coordinate work needed beyond composing with the bracket's own
     * {@code basisInverse}. Used by integrations that derive their own geometry
     * independently of every other capture path here (Epic Fight's skinned-mesh
     * joints: a rest-pose bounding box baked once per joint from the mesh's own
     * vertex/weight data, re-transformed every frame by that joint's live pose
     * matrix — see {@code exp.CCnewmods.mah.compat.epicfight}).
     *
     * @param localBox       bounds in the part's own local space (whatever space
     *                       {@code localTransform} maps to entity-local render space).
     * @param localTransform maps {@code localBox}'s space to entity-local render
     *                       space; composed with {@code basisInverse} the same way
     *                       every other capture path composes its own transform.
     */
    public static void captureExternalPart(String name, AABB localBox, Matrix4f localTransform) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) return;
        if (!shouldCaptureModelGeometry()) return;
        if (isBoneExcluded(name)) return;
        short boneId = boneTable.idFor(name);
        Matrix4f transform = new Matrix4f(basisInverse).mul(localTransform);
        PARTS.add(new PartOBB(localBox, transform, boneId, (byte) 0, null));
    }

    // ── Third-party Tabula-style box capture (Citadel, LionfishAPI) ─────────

    /**
     * Capture one already-resolved cube from a third-party Tabula-style model box
     * (Citadel's or LionfishAPI's {@code AdvancedModelBox}). Callers (the small
     * per-mod compat classes in {@code exp.CCnewmods.mah.compat.*}) are expected to
     * check {@link #shouldCaptureModelGeometry()} themselves before doing any
     * reflective name resolution, so this method stays a cheap, unconditional add.
     *
     * <p>{@code x1..z2} are pre-grow bounds in model units (the {@code posX1..posZ2}
     * fields both mods expose directly) — the same "no margins, no reconstruction"
     * convention as vanilla's {@code Cube.minX..maxZ}. {@code pose} is the incoming
     * render pose at the box's own {@code doRender} HEAD, which for these frameworks
     * already includes translate + rotate + scale (unlike vanilla ModelPart, whose
     * scale is applied by the caller renderer instead) — captured as-is either way.
     */
    public static void captureAdvancedBoxCube(String name, float x1, float y1, float z1,
                                              float x2, float y2, float z2, PoseStack.Pose pose) {
        if (!active || suppressDepth > 0 || boneTable == null) return;
        if (isBoneExcluded(name)) return;
        short boneId = boneTable.idFor(name);
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        PARTS.add(new PartOBB(new AABB(x1 / 16.0f, y1 / 16.0f, z1 / 16.0f,
                x2 / 16.0f, y2 / 16.0f, z2 / 16.0f), rel, boneId, (byte) 0, null));
    }

    /**
     * Phase 2c overload: same as above, plus a caller-populated
     * {@link FaceOpacityAccumulator} — real {@code faceMask}, and for a flat cube, a
     * tightened box + {@link PartOBB.FlatFaceUV} via {@link #finalizeStructuredPart}.
     * Kept as a separate overload (rather than changing the original's signature) so
     * any other caller of the no-opacity version keeps compiling unchanged.
     * Accumulator population itself lives in the mod-specific compat class (e.g.
     * {@code CitadelBoxCapture}) — this method stays mod-agnostic, same as every
     * other method here.
     */
    public static void captureAdvancedBoxCube(String name, float x1, float y1, float z1,
                                              float x2, float y2, float z2, PoseStack.Pose pose,
                                              FaceOpacityAccumulator accumulator) {
        if (!active || suppressDepth > 0 || boneTable == null) return;
        if (isBoneExcluded(name)) return;
        short boneId = boneTable.idFor(name);
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        AABB box = new AABB(x1 / 16.0f, y1 / 16.0f, z1 / 16.0f, x2 / 16.0f, y2 / 16.0f, z2 / 16.0f);
        PARTS.add(finalizeStructuredPart(box, rel, boneId, accumulator));
    }

    /** Phase 2c: the texture the active bracket's entity renders with — see {@link #currentTextureLocation}. */
    public static ResourceLocation getCurrentTextureLocation() {
        return currentTextureLocation;
    }

    // ── Opaque-region tightening: Option A (shared by vanilla/GeckoLib/Citadel) ──

    /**
     * Given a structured cube's already-known box/transform/boneId/faceMask, decide
     * whether Option A's tightening applies — run unconditionally on every part,
     * regardless of size — a face that's fully transparent already has
     * {@code faceMask} handling it; a face that's fully opaque needs no
     * tightening or Option B; only a genuinely mixed face gets the box shrunk and a
     * {@link PartOBB.FlatFaceUV} attached for Option B's raycast-time per-pixel test.
     * Tries both faces on the thin axis (front/back of the plane) and uses whichever
     * actually has corner data — a real cuboid quad on both.
     */
    private static final org.slf4j.Logger FLAT_DEBUG_LOGGER = org.slf4j.LoggerFactory.getLogger("MAH/flat-debug");
    // Per-texture cap, not global — a global cap (even raised to 4000) got fully
    // consumed by whichever ambient mob captures most often (piranhas, then flies/
    // minnows/chickens on the next run) before the entity actually being tested ever
    // logged a single line. Every distinct texture gets its own small quota instead,
    // so common wildlife exhausts its own budget and stops without starving out
    // whatever's actually being investigated.
    private static final java.util.Map<ResourceLocation, java.util.concurrent.atomic.AtomicInteger> FLAT_DEBUG_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FLAT_DEBUG_LIMIT_PER_TEXTURE = 24;

    private static void flatDebug(String msg, Object... args) {
        ResourceLocation texture = currentTextureLocation;
        java.util.concurrent.atomic.AtomicInteger count = FLAT_DEBUG_COUNTS.computeIfAbsent(
                texture, t -> new java.util.concurrent.atomic.AtomicInteger(0));
        if (count.getAndIncrement() >= FLAT_DEBUG_LIMIT_PER_TEXTURE) return;
        FLAT_DEBUG_LOGGER.info(msg, args);
    }

    /**
     * TEMPORARY diagnostic for the "still not shrinking after the threshold fix"
     * report: logs which branch every structured-part finalization actually takes,
     * capped per-texture ({@link #FLAT_DEBUG_LIMIT_PER_TEXTURE} lines each) rather
     * than globally. Remove once the cause is confirmed.
     */
    private static PartOBB finalizeStructuredPart(AABB box, Matrix4f rel, short boneId,
                                                   FaceOpacityAccumulator accumulator) {
        byte faceMask = accumulator.buildFaceMask();
        if (currentTextureLocation == null) {
            flatDebug("MAH flat-debug: texture NULL, skipping flat check entirely");
            return new PartOBB(box, rel, boneId, faceMask, null);
        }
        double dx = box.maxX - box.minX, dy = box.maxY - box.minY, dz = box.maxZ - box.minZ;
        // No size gate — Option A/B tightening is attempted on every structured part
        // regardless of its thinnest-axis extent, not just ones under some "flat"
        // threshold.
        Direction[] facesForAxis = dx <= dy && dx <= dz ? new Direction[]{Direction.EAST, Direction.WEST}
                : dy <= dz ? new Direction[]{Direction.UP, Direction.DOWN}
                : new Direction[]{Direction.SOUTH, Direction.NORTH};
        flatDebug("MAH flat-debug: dims=({},{},{}) texture={} candidateFaces={}",
                dx, dy, dz, currentTextureLocation, facesForAxis);

        for (Direction face : facesForAxis) {
            PartOBB.FlatFaceUV flatUV = accumulator.buildFlatFaceUV(face);
            if (flatUV == null) {
                flatDebug("MAH flat-debug: face={} buildFlatFaceUV=NULL (missing corner samples) [{}]",
                        face, accumulator.describeFace(face));
                continue;
            }
            float minU = Math.min(Math.min(flatUV.u0(), flatUV.u1()), Math.min(flatUV.u2(), flatUV.u3()));
            float maxU = Math.max(Math.max(flatUV.u0(), flatUV.u1()), Math.max(flatUV.u2(), flatUV.u3()));
            float minV = Math.min(Math.min(flatUV.v0(), flatUV.v1()), Math.min(flatUV.v2(), flatUV.v3()));
            float maxV = Math.max(Math.max(flatUV.v0(), flatUV.v1()), Math.max(flatUV.v2(), flatUV.v3()));
            TextureAlphaSampler.RectAnalysis analysis = TextureAlphaSampler.analyzeRect(
                    currentTextureLocation, minU, minV, maxU, maxV, (float) MahConfig.opacityThreshold);
            flatDebug("MAH flat-debug: face={} uvRect=({},{})-({},{}) anyOpaque={} anyTransparent={}",
                    face, minU, minV, maxU, maxV, analysis.anyOpaque(), analysis.anyTransparent());

            if (!analysis.anyOpaque()) continue; // fully transparent — faceMask already covers it, nothing to tighten
            if (!analysis.anyTransparent()) {
                flatDebug("MAH flat-debug: face={} fully opaque, no tightening needed", face);
                return new PartOBB(box, rel, boneId, faceMask, null); // fully opaque — no ambiguity
            }

            FaceGeometry.Tightened tightened = FaceGeometry.tightenToOpaqueBounds(box, face, flatUV, analysis);
            flatDebug("MAH flat-debug: face={} TIGHTENED old=({},{},{})-({},{},{}) new=({},{},{})-({},{},{})",
                    face, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    tightened.box().minX, tightened.box().minY, tightened.box().minZ,
                    tightened.box().maxX, tightened.box().maxY, tightened.box().maxZ);
            return new PartOBB(tightened.box(), rel, boneId, faceMask, tightened.flatUV());
        }
        flatDebug("MAH flat-debug: exhausted both candidate faces, no tightening applied");
        return new PartOBB(box, rel, boneId, faceMask, null);
    }

    // ── Raw vertex capture (Phase 1F — geometry with no model object at all) ────

    /**
     * Wrap a VertexConsumer a mod is about to emit hand-rolled geometry into,
     * axis-aligned mode: bounds are computed directly in entity-local space, and the
     * resulting {@link PartOBB} carries an identity transform. Correct when the
     * geometry has no per-instance rotation worth preserving.
     *
     * <p>Callers (typically a mixin's {@code @ModifyVariable} at a private render
     * method's HEAD, substituting the wrapped consumer in place of the original
     * parameter so the method's own unmodified body renders into it transparently)
     * must pair this with a matching {@link #endRawVertexCapture()} — same
     * HEAD/RETURN balance discipline as every other bracket in this class.
     *
     * <p>Returns the original {@code delegate} unchanged (recording disabled) when
     * called outside an active bracket, so callers are always safe to fire this
     * unconditionally without their own gating logic.
     */
    public static VertexConsumer beginRawVertexCapture(String name, VertexConsumer delegate) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) {
            return delegate;
        }
        RecordingVertexConsumer recorder = new RecordingVertexConsumer(name, delegate, basisInverse, new Matrix4f());
        RAW_CAPTURE_STACK.push(recorder);
        return recorder;
    }

    /**
     * Oriented variant of {@link #beginRawVertexCapture(String, VertexConsumer)}: bounds
     * are computed in the shape's own local frame (by transforming each incoming
     * vertex by the inverse of {@code localTransform} — the same matrix the caller's
     * own rendering code already used to place that shape in the world, e.g. a
     * per-spike rotation), and the resulting {@link PartOBB} carries
     * {@code basisInverse * localTransform} as its transform. This is what keeps a
     * rotated shape's box tight and rotated with it, rather than axis-aligned in
     * entity-local space and therefore ballooning out for any diagonal orientation.
     */
    public static VertexConsumer beginRawVertexCapture(String name, VertexConsumer delegate, Matrix4f localTransform) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) {
            return delegate;
        }
        Matrix4f toLocalSpace = new Matrix4f(localTransform).invert();
        Matrix4f partTransform = new Matrix4f(basisInverse).mul(localTransform);
        RecordingVertexConsumer recorder = new RecordingVertexConsumer(name, delegate, toLocalSpace, partTransform);
        RAW_CAPTURE_STACK.push(recorder);
        return recorder;
    }

    /**
     * Finalize whatever {@link #beginRawVertexCapture} pushed most recently, emitting
     * a {@link PartOBB} (transform per whichever overload created the recorder — see
     * {@link RecordingVertexConsumer}) if any vertices were actually recorded. Safe
     * to call even if the matching {@code begin} call was a no-op (outside an active
     * bracket): the stack will simply be empty and this does nothing.
     */
    public static void endRawVertexCapture() {
        if (RAW_CAPTURE_STACK.isEmpty()) return;
        RecordingVertexConsumer recorder = RAW_CAPTURE_STACK.pop();
        if (boneTable == null) return;
        if (isBoneExcluded(recorder.getName())) return;
        AABB box = recorder.buildLocalBox();
        if (box == null) return;
        short boneId = boneTable.idFor(recorder.getName());
        PARTS.add(new PartOBB(box, recorder.getPartTransform(), boneId, (byte) 0, null));
    }

    // ── GeckoLib bone capture ───────────────────────────────────────────────

    /**
     * Capture all cubes of a Geo bone. Called at HEAD of the concrete renderers'
     * {@code renderRecursively} overrides, where the pose is the <b>parent</b> bone
     * space; we replicate GeckoLib's own bone prelude
     * (translateMatrixToBone → translateToPivotPoint → rotateMatrixAroundBone →
     * scaleMatrixForBone → translateAwayFromPivotPoint) and per-cube pivot rotation
     * (rotateMatrixAroundCube) on a scratch matrix — math copied verbatim from
     * GeckoLib RenderUtils, verified against the pack's pinned GeckoLib jar.
     */
    public static void captureGeoBone(GeoBone bone, boolean isReRender, PoseStack poseStack) {
        if (!active || suppressDepth > 0 || kind != RendererKind.GECKO || boneTable == null) return;
        if (isReRender && !MahConfig.captureLayers) return; // glow/layer re-render passes
        if (bone.isHidden()) return; // renderCubesOfBone skips cubes of hidden bones
        if (isBoneExcluded(bone.getName())) return;
        List<GeoCube> cubes = bone.getCubes();
        if (cubes == null || cubes.isEmpty()) return;

        // Bone prelude (RenderUtils.prepMatrixForBone sequence).
        Matrix4f boneMat = new Matrix4f(poseStack.last().pose());
        boneMat.translate(-bone.getPosX() / 16.0f, bone.getPosY() / 16.0f, bone.getPosZ() / 16.0f);
        boneMat.translate(bone.getPivotX() / 16.0f, bone.getPivotY() / 16.0f, bone.getPivotZ() / 16.0f);
        if (bone.getRotZ() != 0.0f) boneMat.rotateZ(bone.getRotZ());
        if (bone.getRotY() != 0.0f) boneMat.rotateY(bone.getRotY());
        if (bone.getRotX() != 0.0f) boneMat.rotateX(bone.getRotX());
        boneMat.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        boneMat.translate(-bone.getPivotX() / 16.0f, -bone.getPivotY() / 16.0f, -bone.getPivotZ() / 16.0f);

        short boneId = boneTable.idFor(bone.getName());
        Matrix4f sharedRel = null; // lazily built basis⁻¹·boneMat for rotation-free cubes

        for (GeoCube cube : cubes) {
            if (cube == null) continue;
            GeoQuad[] quads = cube.quads();
            if (quads == null) continue;

            // Per-cube pivot rotation (RenderUtils.rotateMatrixAroundCube sequence).
            Vec3 pivot = cube.pivot();
            Vec3 rot = cube.rotation();
            boolean rotated = rot != null && (rot.x != 0.0 || rot.y != 0.0 || rot.z != 0.0);
            Matrix4f cubeMat;
            if (rotated) {
                cubeMat = new Matrix4f(boneMat);
                if (pivot != null) {
                    cubeMat.translate((float) (pivot.x / 16.0), (float) (pivot.y / 16.0), (float) (pivot.z / 16.0));
                }
                cubeMat.rotateZ((float) rot.z);
                cubeMat.rotateY((float) rot.y);
                cubeMat.rotateX((float) rot.x);
                if (pivot != null) {
                    cubeMat.translate((float) (-pivot.x / 16.0), (float) (-pivot.y / 16.0), (float) (-pivot.z / 16.0));
                }
            } else {
                cubeMat = boneMat;
            }

            // Bounds over raw quad vertices (already in block units after GeckoLib bake).
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            boolean any = false;
            for (GeoQuad quad : quads) {
                if (quad == null) continue;
                GeoVertex[] vertices = quad.vertices();
                if (vertices == null) continue;
                for (GeoVertex v : vertices) {
                    if (v == null) continue;
                    Vector3f p = v.position();
                    if (p == null) continue;
                    any = true;
                    if (p.x < minX) minX = p.x;
                    if (p.y < minY) minY = p.y;
                    if (p.z < minZ) minZ = p.z;
                    if (p.x > maxX) maxX = p.x;
                    if (p.y > maxY) maxY = p.y;
                    if (p.z > maxZ) maxZ = p.z;
                }
            }
            if (!any) continue;
            AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

            // Phase 2c: GeoQuad.direction() is a real stored field (unlike vanilla's
            // Polygon/Citadel's TexturedQuad, whose constructors take a Direction but
            // don't retain it) — no normal-snapping needed, GeckoLib already tells us
            // which face this is. GeckoLib's baked GeoVertex carries no per-vertex
            // color/alpha (that's applied at render time via renderCube's own
            // r,g,b,a params, not captured here) — texture alpha only, same
            // known-limitation shape as every other capture path's documented gaps.
            FaceOpacityAccumulator accumulator = new FaceOpacityAccumulator(box);
            if (currentTextureLocation != null) {
                for (GeoQuad quad : quads) {
                    if (quad == null) continue;
                    GeoVertex[] vertices = quad.vertices();
                    if (vertices == null) continue;
                    Direction dir = quad.direction();
                    if (dir == null) continue;
                    for (GeoVertex v : vertices) {
                        if (v == null) continue;
                        Vector3f p = v.position();
                        if (p == null) continue;
                        float opacity = TextureAlphaSampler.sampleOpacity(currentTextureLocation, v.texU(), v.texV());
                        accumulator.addSample(dir, p.x, p.y, p.z, v.texU(), v.texV(), opacity, MahConfig.opacityThreshold);
                    }
                }
            }

            Matrix4f rel;
            if (rotated) {
                rel = new Matrix4f(basisInverse).mul(cubeMat);
            } else {
                if (sharedRel == null) sharedRel = new Matrix4f(basisInverse).mul(boneMat);
                rel = sharedRel;
            }
            PARTS.add(finalizeStructuredPart(box, rel, boneId, accumulator));
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    public static boolean isActive() {
        return active;
    }

    private HitboxCaptureManager() {
    }
}
