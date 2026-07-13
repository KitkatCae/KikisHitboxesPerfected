package exp.CCnewmods.khp.compat.citadel;

import com.github.alexthe666.citadel.client.model.AdvancedEntityModel;
import com.github.alexthe666.citadel.client.model.AdvancedModelBox;
import com.github.alexthe666.citadel.client.model.TabulaModelRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.capture.FaceOpacityAccumulator;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import exp.CCnewmods.khp.util.MahReflect;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * All Citadel-typed geometry-capture logic lives here, isolated from
 * {@link HitboxCaptureManager} so that class stays loadable when Citadel isn't
 * installed. Only reached from {@code CitadelAdvancedModelBoxMixin}, which is itself
 * skipped entirely (mah.citadel.mixins.json is "required": false) when Citadel is absent — this
 * class is never loaded in that case either.
 *
 * <p>Naming: Citadel's own consumers (e.g. Alex's Mobs) generally pass a real
 * {@code boxName} at construction ({@code new AdvancedModelBox(this, "left_leg")}),
 * used directly when present. Some Citadel-based models still use the no-name
 * constructor, so a reflective fallback (the concrete model's own field name for
 * that box instance, e.g. {@code this.left_leg}) covers those too.
 */
public final class CitadelBoxCapture {

    private static final Field MODEL_FIELD =
            MahReflect.findSoleFieldOfType(AdvancedModelBox.class, AdvancedEntityModel.class);

    // Phase 2c: TabulaModelRenderUtils.TexturedQuad and .PositionTextureVertex are
    // both package-private (confirmed against citadel-2.6.3-1.20.1: empty access
    // flags on both class files) even though their fields are public — so a loop
    // variable typed as either can't be *declared* in this package, only reached via
    // reflection. This is safe, ordinary name-based reflection (not MahReflect's
    // type-based scheme): Citadel is a third-party jar, its field names are real and
    // stable in both dev and production, none of the SRG-remap risk that rules out
    // name-based lookups on vanilla classes (see ModelPartCubeMixin/CubeOpacityRecorder
    // for where that distinction mattered). Resolved once from ModelBox's own
    // (accessible, public) declared field types — no instance needed at class-load time.
    private static final Field QUAD_VERTICES_FIELD;
    private static final Field QUAD_NORMAL_FIELD;
    private static final Field VERTEX_POSITION_FIELD;
    private static final Field VERTEX_U_FIELD;
    private static final Field VERTEX_V_FIELD;

    static {
        try {
            Class<?> texturedQuadType = TabulaModelRenderUtils.ModelBox.class
                    .getDeclaredField("quads").getType().getComponentType();
            QUAD_VERTICES_FIELD = texturedQuadType.getDeclaredField("vertexPositions");
            QUAD_VERTICES_FIELD.setAccessible(true);
            QUAD_NORMAL_FIELD = texturedQuadType.getDeclaredField("normal");
            QUAD_NORMAL_FIELD.setAccessible(true);
            Class<?> vertexType = QUAD_VERTICES_FIELD.getType().getComponentType();
            VERTEX_POSITION_FIELD = vertexType.getDeclaredField("position");
            VERTEX_POSITION_FIELD.setAccessible(true);
            VERTEX_U_FIELD = vertexType.getDeclaredField("textureU");
            VERTEX_U_FIELD.setAccessible(true);
            VERTEX_V_FIELD = vertexType.getDeclaredField("textureV");
            VERTEX_V_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("MAH: Citadel TexturedQuad/PositionTextureVertex "
                    + "field shape changed — computeFaceMask needs an update for this Citadel version.", e);
        }
    }

    // model instance (identity) -> (box instance (identity) -> declared field name)
    private static final Map<Object, Map<Object, String>> NAME_CACHE = new WeakHashMap<>();

    public static void capture(AdvancedModelBox box, PoseStack.Pose pose) {
        if (!HitboxCaptureManager.shouldCaptureModelGeometry()) return;
        if (box.cubeList == null || box.cubeList.isEmpty()) return;
        String name = resolveName(box);
        ResourceLocation texture = HitboxCaptureManager.getCurrentTextureLocation();
        for (TabulaModelRenderUtils.ModelBox cube : box.cubeList) {
            if (cube == null) continue;
            AABB localBox = new AABB(cube.posX1 / 16.0f, cube.posY1 / 16.0f, cube.posZ1 / 16.0f,
                    cube.posX2 / 16.0f, cube.posY2 / 16.0f, cube.posZ2 / 16.0f);
            HitboxCaptureManager.captureAdvancedBoxCube(name,
                    cube.posX1, cube.posY1, cube.posZ1, cube.posX2, cube.posY2, cube.posZ2, pose,
                    buildAccumulator(cube, texture, localBox));
        }
    }

    /**
     * Phase 2c: {@code ModelBox.quads} is directly accessible (public field on a
     * public class), but its element type isn't nameable in this package — see the
     * reflective field lookups above. {@code TexturedQuad}'s constructor takes a
     * {@code Direction} but (like vanilla's {@code Polygon}) doesn't retain it as a
     * field, so the face bucket is derived from the quad's own {@code normal} via
     * {@link Direction#getNearest} instead of assuming array-index ordering.
     *
     * <p>{@code localBox} must be built the exact same way (same divide-by-16) as
     * the box {@code captureAdvancedBoxCube} ultimately constructs — the accumulator
     * matches each vertex to its nearest corner of *this* box, so a mismatched box
     * here would silently corrupt that correspondence.
     *
     * <p>Vertex position units: assumed same raw model-unit scale as {@code posX1}
     * etc. (matching vanilla's equivalent convention) — not bytecode-instruction-
     * verified (can't decode Citadel's constructor body with the tooling available),
     * worth double-checking against the debug overlay if a Citadel-backed flat part's
     * tightened box looks wrong.
     */
    private static FaceOpacityAccumulator buildAccumulator(TabulaModelRenderUtils.ModelBox cube,
                                                            ResourceLocation texture, AABB localBox) {
        FaceOpacityAccumulator accumulator = new FaceOpacityAccumulator(localBox);
        if (texture == null || cube.quads == null) return accumulator;
        Object[] quads = cube.quads; // TexturedQuad[], upcast — legal regardless of element accessibility
        for (Object quad : quads) {
            if (quad == null) continue;
            Vector3f n;
            Object[] vertices;
            try {
                n = (Vector3f) QUAD_NORMAL_FIELD.get(quad);
                vertices = (Object[]) QUAD_VERTICES_FIELD.get(quad);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("MAH: reflective read failed on Citadel TexturedQuad", e);
            }
            if (vertices == null) continue;
            Direction face = n != null ? Direction.getNearest(n.x, n.y, n.z) : null;
            if (face == null) continue;
            for (Object vertex : vertices) {
                if (vertex == null) continue;
                Vector3f pos;
                float u, v;
                try {
                    pos = (Vector3f) VERTEX_POSITION_FIELD.get(vertex);
                    u = VERTEX_U_FIELD.getFloat(vertex);
                    v = VERTEX_V_FIELD.getFloat(vertex);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("MAH: reflective read failed on Citadel PositionTextureVertex", e);
                }
                if (pos == null) continue;
                float opacity = TextureAlphaSampler.sampleOpacity(texture, u, v);
                accumulator.addSample(face, pos.x / 16.0f, pos.y / 16.0f, pos.z / 16.0f, u, v,
                        opacity, MahConfig.opacityThreshold);
            }
        }
        return accumulator;
    }

    private static String resolveName(AdvancedModelBox box) {
        if (box.boxName != null && !box.boxName.isEmpty()) return box.boxName;
        Object model = MahReflect.get(MODEL_FIELD, box);
        if (model == null) return "unnamed";
        Map<Object, String> byIdentity = NAME_CACHE.computeIfAbsent(model,
                m -> MahReflect.mapFieldNamesByIdentity(m, AdvancedModelBox.class));
        String fieldName = byIdentity.get(box);
        return fieldName != null ? fieldName : "unnamed";
    }

    private CitadelBoxCapture() {
    }
}
