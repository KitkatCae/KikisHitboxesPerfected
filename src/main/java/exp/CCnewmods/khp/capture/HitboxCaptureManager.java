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
 * Render-thread capture state machine; all capture mixins call into here. Each entity render is a "bracket"
 * (dispatcher HEAD/RETURN) whose captured parts are re-based to entity-local space via the basis inverse and
 * published to {@link HitboxStore}. Render thread only.
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
    /** Parallel to {@link #PATH_STACK}; used to detect an Oculus-cancelled {@code ModelPart.render}. */
    private static final ArrayDeque<ModelPart> PATH_PARTS = new ArrayDeque<>();
    private static int sodiumTreeDepth = 0;
    private static boolean sodiumTreeAdoptedRoot = false;
    private static final ArrayDeque<RecordingVertexConsumer> RAW_CAPTURE_STACK = new ArrayDeque<>();

    private static ResourceLocation currentTextureLocation;

    /** Cube → index in {@link #PARTS}, so the opacity patch finds its entry whatever order cubes compile in. */
    private static final Map<ModelPart.Cube, Integer> VANILLA_CUBE_PARTS_INDEX = new IdentityHashMap<>();

    /** Inverse of each cube's parent-part pose; see {@link #beginVanillaCubeOpacity}. */
    private static final Map<ModelPart.Cube, Matrix4f> VANILLA_CUBE_POSE_INVERSE = new IdentityHashMap<>();

    /** -1 unset, 1 present, 0 absent. */
    private static byte embeddiumPresent = -1;

    private static boolean isEmbeddiumPresent() {
        if (embeddiumPresent < 0) {
            embeddiumPresent = (byte) (ModList.get().isLoaded("embeddium") ? 1 : 0);
        }
        return embeddiumPresent == 1;
    }

    public static void setLevelRenderPass(boolean inPass) {
        levelRenderPass = inPass;
    }

    // ── Bracket ─────────────────────────────────────────────────────────────

    /**
     * HEAD of EntityRenderDispatcher.render, before its pose push/translate. Always paired with {@link #exitBracket()}.
     */
    public static void enterBracket(Entity e, EntityRenderer<?> renderer,
                                    double x, double y, double z,
                                    float partialTick, PoseStack poseStack) {
        depth++;
        if (depth > 1) {
            // Nested entity render: suppress so geometry isn't attributed to the outer entity.
            suppressDepth++;
            return;
        }
        if (!MahConfig.enabled || !levelRenderPass || renderer == null || !shouldTrack(e)) {
            return;
        }

        // Basis: invert(topPose * translate(entityCameraRelPos + renderOffset)).
        // The dispatcher applies exactly this translate before calling the renderer.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Vec3 off = ((EntityRenderer) renderer).getRenderOffset(e, partialTick);
        Matrix4f basis = new Matrix4f(poseStack.last().pose());
        basis.translate((float) (x + off.x), (float) (y + off.y), (float) (z + off.z));
        basis.invert();
        basisInverse = basis;

        // Lerped entity position + renderer offset (camera-independent).
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

        // GeckoLib's getTextureLocation takes a GeoAnimatable, hence the raw-type cast.
        try {
            currentTextureLocation = kind == RendererKind.GECKO
                    ? ((GeoRenderer) renderer).getTextureLocation((GeoAnimatable) e)
                    : ((EntityRenderer) renderer).getTextureLocation(e);
        } catch (RuntimeException ex) {
            currentTextureLocation = null;
        }

        entity = e;
        boneTable = HitboxStore.getOrCreateBoneTable(MahApi.entityKey(e));
        PARTS.clear();
        PATH_STACK.clear();
        PATH_PARTS.clear();
        sodiumTreeDepth = 0;
        sodiumTreeAdoptedRoot = false;
        RAW_CAPTURE_STACK.clear();
        VANILLA_CUBE_PARTS_INDEX.clear();
        VANILLA_CUBE_POSE_INVERSE.clear();
        // Default open, closed around known RenderLayer windows (LivingEntityRendererMixin). Renderers that
        // override render() without calling super would otherwise capture nothing.
        primaryGate = true;
        active = true;
    }

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
        }
        entity = null;
        currentModel = null;
        boneTable = null;
        basisInverse = null;
        currentTextureLocation = null;
        PARTS.clear();
        PATH_STACK.clear();
        PATH_PARTS.clear();
        sodiumTreeDepth = 0;
        sodiumTreeAdoptedRoot = false;
        RAW_CAPTURE_STACK.clear();
        VANILLA_CUBE_PARTS_INDEX.clear();
        VANILLA_CUBE_POSE_INVERSE.clear();
    }

    private static boolean shouldTrack(Entity e) {
        if (e == null || e.isSpectator()) return false;
        if (e instanceof ItemEntity || e instanceof ExperienceOrb
                || e instanceof Projectile || e instanceof AreaEffectCloud) return false;
        if (!MahConfig.blacklist.isEmpty()
                && MahConfig.blacklist.contains(MahApi.entityTypeKey(e))) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && e != player
                && player.distanceToSqr(e) > MahConfig.captureRadiusSqr) return false;
        return true;
    }

    // ── Anatomical RenderLayer allowlist ────────────────────────────────────

    /**
     * Layers captured as primary even with capture_layers off: real anatomy drawn as a layer (slime body, hydra heads).
     */
    private static final Set<Class<?>> ANATOMICAL_LAYER_CLASSES = ConcurrentHashMap.newKeySet();

    public static void registerAnatomicalRenderLayer(Class<?> layerClass) {
        ANATOMICAL_LAYER_CLASSES.add(layerClass);
    }

    public static boolean isAnatomicalRenderLayer(Class<?> layerClass) {
        return ANATOMICAL_LAYER_CLASSES.contains(layerClass);
    }

    public static void setPrimaryGate(boolean open) {
        primaryGate = open;
    }

    // ── Vanilla ModelPart capture ───────────────────────────────────────────

    /** HEAD of ModelPart.render (8-arg); balanced by a pop at RETURN. */
    public static void pushPartName(ModelPart part) {
        if (!active) return;
        String name = ((MahNamedPart) (Object) part).mah$getName();
        String parent = PATH_STACK.peek();
        PATH_STACK.push(parent == null ? name : parent + "/" + name);
        PATH_PARTS.push(part);
    }

    public static void popPartName() {
        if (!active) return;
        if (!PATH_STACK.isEmpty()) PATH_STACK.pop();
        if (!PATH_PARTS.isEmpty()) PATH_PARTS.pop();
    }

    // ── Embeddium tree-walk capture (Oculus's copied entity renderer) ───────

    /**
     * HEAD of Embeddium's {@code EntityRenderer.render} tree walk, used by Oculus instead of {@code ModelPart.render}.
     * Oculus's cancel and our {@code ModelPart.render} HEAD push have no guaranteed order: if ours ran first, the
     * outermost call adopts that already-pushed root (whose RETURN pop never runs) instead of pushing again.
     */
    public static void enterSodiumTreePart(ModelPart part) {
        if (!active) return;
        if (sodiumTreeDepth++ == 0) {
            if (!PATH_PARTS.isEmpty() && PATH_PARTS.peek() == part) {
                sodiumTreeAdoptedRoot = true;
                return;
            }
            sodiumTreeAdoptedRoot = false;
        }
        pushPartName(part);
    }

    public static void exitSodiumTreePart() {
        if (!active || sodiumTreeDepth == 0) return;
        sodiumTreeDepth--;
        if (sodiumTreeDepth == 0 && sodiumTreeAdoptedRoot) {
            sodiumTreeAdoptedRoot = false;
        }
        popPartName(); // adopted root: pop ModelPart.render's never-returning entry
    }

    public static boolean shouldCaptureSodiumTreeCubes() {
        return sodiumTreeDepth > 0 && shouldCaptureModelGeometry()
                && boneTable != null && basisInverse != null
                && !isBoneExcluded(PATH_STACK.peek());
    }

    /** {@code pose} is after this part's translateAndRotate, the same pose {@code ModelPart.compile} would get. */
    public static void captureSodiumTreeCubes(PoseStack.Pose pose,
                                              List<exp.CCnewmods.khp.compat.embeddium.EmbeddiumFastCubeOpacity.FastCubeResult> cubes) {
        if (cubes.isEmpty() || boneTable == null || basisInverse == null) return;
        String path = PATH_STACK.peek();
        short boneId = boneTable.idFor(path == null ? "unnamed" : path);
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        for (var cube : cubes) {
            PARTS.add(new PartOBB(cube.localBox(), rel, boneId, cube.faceMask(), cube.flatFaceUV()));
        }
    }

    public static ResourceLocation sodiumTreeTexture() { // may be null
        return currentTextureLocation;
    }

    /** Gate for vanilla {@code ModelPart} and Tabula-style {@code AdvancedModelBox} geometry. */
    public static boolean shouldCaptureModelGeometry() {
        if (!active || suppressDepth > 0) return false;
        return switch (kind) {
            case OTHER -> true;
            case VANILLA_LIVING -> primaryGate || MahConfig.captureLayers;
            // In a GeckoLib bracket, ModelPart geometry is armor (GeoArmorRenderer is a HumanoidModel).
            case GECKO -> MahConfig.captureLayers;
        };
    }

    /** Matches the full path or any "/"-separated segment, so "tail" catches "root/body/tail". */
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

    /** ModelPart.compile HEAD, where the pose includes translateAndRotate. */
    public static void captureVanillaCubes(List<ModelPart.Cube> cubes, PoseStack.Pose pose) {
        if (cubes.isEmpty() || boneTable == null) return;
        String path = PATH_STACK.peek();
        if (isBoneExcluded(path)) return;
        short boneId = boneTable.idFor(path == null ? "unnamed" : path);
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        // Cube.compile emits vertices already /16 and posed by this; its inverse recovers `box`'s space.
        Matrix4f poseInverse = new Matrix4f(pose.pose()).invert();
        for (ModelPart.Cube cube : cubes) {
            // TODO: polygon-derived bounds (grow-inclusive) via AccessTransformer.
            AABB box = new AABB(
                    cube.minX / 16.0f, cube.minY / 16.0f, cube.minZ / 16.0f,
                    cube.maxX / 16.0f, cube.maxY / 16.0f, cube.maxZ / 16.0f);

            // Embeddium's fast path (renderCuboidFast) never calls Cube.compile, so compute opacity here instead.
            // Subclasses (e.g. EMF's custom cubes) may not match the cuboid data the fast path reads.
            EmbeddiumFastCubeOpacity.FastCubeResult fast = isEmbeddiumPresent() && cube.getClass() == ModelPart.Cube.class
                    ? EmbeddiumFastCubeOpacity.tryCompute(cube, currentTextureLocation,
                            MahConfig.opacityThreshold)
                    : null;
            if (fast != null) {
                PARTS.add(new PartOBB(fast.localBox(), rel, boneId, fast.faceMask(), fast.flatFaceUV()));
                continue;
            }

            // faceMask 0 for now; ModelPartCubeMixin patches it once this cube's compile() runs.
            PARTS.add(new PartOBB(box, rel, boneId, (byte) 0, null));
            VANILLA_CUBE_PARTS_INDEX.put(cube, PARTS.size() - 1);
            VANILLA_CUBE_POSE_INVERSE.put(cube, poseInverse);
        }
    }

    // ── Vanilla per-cube opacity ────────────────────────────────────────────

    /**
     * Wraps one {@code Cube.compile}'s consumer in a {@link CubeOpacityRecorder}. Returns {@code delegate} unchanged
     * for cubes {@link #captureVanillaCubes} didn't record.
     */
    public static VertexConsumer beginVanillaCubeOpacity(ModelPart.Cube cube, VertexConsumer delegate) {
        Integer index = VANILLA_CUBE_PARTS_INDEX.get(cube);
        if (index == null || index < 0 || index >= PARTS.size()) return delegate;
        Matrix4f poseInverse = VANILLA_CUBE_POSE_INVERSE.get(cube);
        if (poseInverse == null) return delegate; // defensive
        AABB box = PARTS.get(index).localBox();
        return new CubeOpacityRecorder(delegate, currentTextureLocation, box, poseInverse);
    }

    /** Replaces the cube's {@link #PARTS} entry with its real faceMask (and tightened box for flat cubes). */
    public static void endVanillaCubeOpacity(ModelPart.Cube cube, VertexConsumer buffer) {
        if (!(buffer instanceof CubeOpacityRecorder recorder)) return;
        Integer index = VANILLA_CUBE_PARTS_INDEX.get(cube);
        if (index == null || index < 0 || index >= PARTS.size()) return;
        PartOBB old = PARTS.get(index);
        FaceOpacityAccumulator accumulator = recorder.accumulator();
        PARTS.set(index, finalizeStructuredPart(old.localBox(), old.transform(), old.boneId(), accumulator));
    }

    /** Model of the active VANILLA_LIVING bracket, for compat classes whose boxes can't reach their model. */
    public static Object getCurrentModel() {
        return currentModel;
    }

    /**
     * Adds a part with already-known bounds and transform (e.g. Epic Fight joints).
     *
     * @param localTransform maps {@code localBox}'s space to entity-local render space.
     */
    public static void captureExternalPart(String name, AABB localBox, Matrix4f localTransform) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) return;
        if (!shouldCaptureModelGeometry()) return;
        if (isBoneExcluded(name)) return;
        short boneId = boneTable.idFor(name);
        Matrix4f transform = new Matrix4f(basisInverse).mul(localTransform);
        PARTS.add(new PartOBB(localBox, transform, boneId, (byte) 0, null));
    }

    /**
     * Like {@link #captureExternalPart}, but not gated on the primary-model window: Figura draws avatars after the
     * entity's layers, when that gate has already closed.
     */
    public static void captureAvatarPart(String name, AABB localBox, Matrix4f localTransform) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) return;
        if (isBoneExcluded(name)) return;
        short boneId = boneTable.idFor(name);
        Matrix4f transform = new Matrix4f(basisInverse).mul(localTransform);
        PARTS.add(new PartOBB(localBox, transform, boneId, (byte) 0, null));
    }

    // ── Tabula-style box capture (Citadel, LionfishAPI) ─────────────────────

    /**
     * {@code x1..z2} are pre-grow bounds in model units; {@code pose} is the box's {@code doRender} pose (already
     * translated/rotated/scaled). Callers check {@link #shouldCaptureModelGeometry()} first.
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

    /** As above, with real per-face opacity from {@code accumulator}. */
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

    public static ResourceLocation getCurrentTextureLocation() {
        return currentTextureLocation;
    }

    // ── Opaque-region tightening ────────────────────────────────────────────

    /** Only a mixed opaque/transparent face gets its box shrunk and a {@link PartOBB.FlatFaceUV} attached. */
    private static PartOBB finalizeStructuredPart(AABB box, Matrix4f rel, short boneId,
                                                   FaceOpacityAccumulator accumulator) {
        byte faceMask = accumulator.buildFaceMask();

        java.util.Map<Direction, PartOBB.FlatFaceUV> allFaceUVs = new java.util.EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            PartOBB.FlatFaceUV uv = accumulator.buildFlatFaceUV(face);
            if (uv != null) {
                allFaceUVs.put(face, uv);
            }
        }

        if (currentTextureLocation == null) {
            return new PartOBB(box, rel, boneId, faceMask, null, allFaceUVs);
        }
        // All six faces: a mixed face can be on any side.
        for (Direction face : Direction.values()) {
            PartOBB.FlatFaceUV flatUV = allFaceUVs.get(face);
            if (flatUV == null) continue;
            float minU = Math.min(Math.min(flatUV.u0(), flatUV.u1()), Math.min(flatUV.u2(), flatUV.u3()));
            float maxU = Math.max(Math.max(flatUV.u0(), flatUV.u1()), Math.max(flatUV.u2(), flatUV.u3()));
            float minV = Math.min(Math.min(flatUV.v0(), flatUV.v1()), Math.min(flatUV.v2(), flatUV.v3()));
            float maxV = Math.max(Math.max(flatUV.v0(), flatUV.v1()), Math.max(flatUV.v2(), flatUV.v3()));
            TextureAlphaSampler.RectAnalysis analysis = TextureAlphaSampler.analyzeRect(
                    currentTextureLocation, minU, minV, maxU, maxV, (float) MahConfig.opacityThreshold);

            if (!analysis.anyOpaque()) continue;
            if (!analysis.anyTransparent()) continue;

            FaceGeometry.Tightened tightened = FaceGeometry.tightenToOpaqueBounds(box, face, flatUV, analysis);
            return new PartOBB(tightened.box(), rel, boneId, faceMask, tightened.flatUV(), allFaceUVs);
        }
        return new PartOBB(box, rel, boneId, faceMask, null, allFaceUVs);
    }

    // ── Raw vertex capture (geometry with no model object) ──────────────────

    /**
     * Axis-aligned raw capture: bounds in entity-local space, identity transform. Pair with
     * {@link #endRawVertexCapture()}; returns {@code delegate} unchanged outside a bracket.
     */
    public static VertexConsumer beginRawVertexCapture(String name, VertexConsumer delegate) {
        if (!active || suppressDepth > 0 || boneTable == null || basisInverse == null) {
            return delegate;
        }
        RecordingVertexConsumer recorder = new RecordingVertexConsumer(name, delegate, basisInverse, new Matrix4f());
        RAW_CAPTURE_STACK.push(recorder);
        return recorder;
    }

    /** Oriented variant: bounds in {@code localTransform}'s frame, so rotated shapes keep a tight box. */
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

    /** Emits a {@link PartOBB} for the latest begin if anything was recorded; safe if begin was a no-op. */
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

    /**
     * Called at {@code renderRecursively} HEAD, where the pose is the <b>parent</b> bone's; replicates GeckoLib's
     * bone prelude and per-cube pivot rotation (RenderUtils) on a scratch matrix.
     */
    public static void captureGeoBone(GeoBone bone, boolean isReRender, PoseStack poseStack) {
        if (!active || suppressDepth > 0 || kind != RendererKind.GECKO || boneTable == null) return;
        if (isReRender && !MahConfig.captureLayers) return; // glow/layer re-render passes
        if (bone.isHidden()) return; // renderCubesOfBone skips cubes of hidden bones
        if (isBoneExcluded(bone.getName())) return;
        List<GeoCube> cubes = bone.getCubes();
        if (cubes == null || cubes.isEmpty()) return;

        // RenderUtils.prepMatrixForBone sequence.
        Matrix4f boneMat = new Matrix4f(poseStack.last().pose());
        boneMat.translate(-bone.getPosX() / 16.0f, bone.getPosY() / 16.0f, bone.getPosZ() / 16.0f);
        boneMat.translate(bone.getPivotX() / 16.0f, bone.getPivotY() / 16.0f, bone.getPivotZ() / 16.0f);
        if (bone.getRotZ() != 0.0f) boneMat.rotateZ(bone.getRotZ());
        if (bone.getRotY() != 0.0f) boneMat.rotateY(bone.getRotY());
        if (bone.getRotX() != 0.0f) boneMat.rotateX(bone.getRotX());
        boneMat.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        boneMat.translate(-bone.getPivotX() / 16.0f, -bone.getPivotY() / 16.0f, -bone.getPivotZ() / 16.0f);

        short boneId = boneTable.idFor(bone.getName());
        Matrix4f sharedRel = null;

        for (GeoCube cube : cubes) {
            if (cube == null) continue;
            GeoQuad[] quads = cube.quads();
            if (quads == null) continue;

            // RenderUtils.rotateMatrixAroundCube sequence.
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

            // GeckoLib vertices carry no colour/alpha, so texture alpha only.
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

    public static boolean isActive() {
        return active;
    }

    private HitboxCaptureManager() {
    }
}
