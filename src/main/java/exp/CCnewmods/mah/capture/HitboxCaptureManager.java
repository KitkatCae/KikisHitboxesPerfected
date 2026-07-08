package exp.CCnewmods.mah.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.mah.MahConfig;
import exp.CCnewmods.mah.api.MahApi;
import exp.CCnewmods.mah.duck.MahNamedPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.renderer.GeoRenderer;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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
    private static RendererKind kind = RendererKind.OTHER;
    private static Matrix4f basisInverse;
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 capturedEntityPos = Vec3.ZERO;
    private static float bodyYaw;
    private static BoneTable boneTable;

    private static final List<PartOBB> PARTS = new ArrayList<>(64);
    private static final ArrayDeque<String> PATH_STACK = new ArrayDeque<>();

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

        entity = e;
        boneTable = HitboxStore.getOrCreateBoneTable(MahApi.entityKey(e));
        PARTS.clear();
        PATH_STACK.clear();
        primaryGate = false;
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
            HitboxStore.put(entity.getUUID(), new EntityHitboxSnapshot(
                    MahApi.entityKey(entity),
                    entity.getId(),
                    anchorPos,
                    capturedEntityPos,
                    bodyYaw,
                    entity.level().getGameTime(),
                    PARTS,
                    SnapshotSource.LIVE_CLIENT));
            // Published every rendered frame (immutable snapshot, atomic map put — the
            // cheapest and freshest option client-side). The design's once-per-tick
            // gate becomes relevant when snapshots cross the network in phase 3.
        }
        entity = null;
        boneTable = null;
        basisInverse = null;
        PARTS.clear();
        PATH_STACK.clear();
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
     * Capture all cubes of the part currently compiling. Called from ModelPart.compile
     * HEAD, where the pose includes translateAndRotate — the exact vertex transform.
     */
    public static void captureVanillaCubes(List<ModelPart.Cube> cubes, PoseStack.Pose pose) {
        if (cubes.isEmpty() || boneTable == null) return;
        String path = PATH_STACK.peek();
        short boneId = boneTable.idFor(path == null ? "unnamed" : path);
        // One shared, never-mutated transform for all cubes of this part.
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        for (ModelPart.Cube cube : cubes) {
            // PHASE2-TODO: polygon-derived bounds (grow-inclusive) via AccessTransformer.
            AABB box = new AABB(
                    cube.minX / 16.0f, cube.minY / 16.0f, cube.minZ / 16.0f,
                    cube.maxX / 16.0f, cube.maxY / 16.0f, cube.maxZ / 16.0f);
            PARTS.add(new PartOBB(box, rel, boneId, (byte) 0));
        }
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
        short boneId = boneTable.idFor(name);
        Matrix4f rel = new Matrix4f(basisInverse).mul(pose.pose());
        PARTS.add(new PartOBB(new AABB(x1 / 16.0f, y1 / 16.0f, z1 / 16.0f,
                x2 / 16.0f, y2 / 16.0f, z2 / 16.0f), rel, boneId, (byte) 0));
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

            Matrix4f rel;
            if (rotated) {
                rel = new Matrix4f(basisInverse).mul(cubeMat);
            } else {
                if (sharedRel == null) sharedRel = new Matrix4f(basisInverse).mul(boneMat);
                rel = sharedRel;
            }
            PARTS.add(new PartOBB(new AABB(minX, minY, minZ, maxX, maxY, maxZ), rel, boneId, (byte) 0));
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    public static boolean isActive() {
        return active;
    }

    private HitboxCaptureManager() {
    }
}
