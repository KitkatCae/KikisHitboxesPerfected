package exp.CCnewmods.khp.compat.lionfishapi;

import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedEntityModel;
import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox;
import com.github.L_Ender.lionfishapi.client.model.tools.LionfishModelRenderUtils;
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
 * All LionfishAPI-typed code. Ender's Cataclysm never names its boxes, so the model's field names are used.
 * Same reflective quad access as {@code CitadelBoxCapture}.
 */
public final class LionfishBoxCapture {

    private static final Field MODEL_FIELD =
            MahReflect.findSoleFieldOfType(AdvancedModelBox.class, AdvancedEntityModel.class);

    private static final Field QUAD_VERTICES_FIELD;
    private static final Field QUAD_NORMAL_FIELD;
    private static final Field VERTEX_POSITION_FIELD;
    private static final Field VERTEX_U_FIELD;
    private static final Field VERTEX_V_FIELD;

    static {
        try {
            Class<?> texturedQuadType = LionfishModelRenderUtils.ModelBox.class
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
            throw new IllegalStateException("MAH: LionfishAPI TexturedQuad/PositionTextureVertex "
                    + "field shape changed — buildAccumulator needs an update for this LionfishAPI version.", e);
        }
    }

    private static final Map<Object, Map<Object, String>> NAME_CACHE = new WeakHashMap<>();

    public static void capture(AdvancedModelBox box, PoseStack.Pose pose) {
        if (!HitboxCaptureManager.shouldCaptureModelGeometry()) return;
        if (box.cubeList == null || box.cubeList.isEmpty()) return;
        String name = resolveName(box);
        ResourceLocation texture = HitboxCaptureManager.getCurrentTextureLocation();
        for (LionfishModelRenderUtils.ModelBox cube : box.cubeList) {
            if (cube == null) continue;
            AABB localBox = new AABB(cube.posX1 / 16.0f, cube.posY1 / 16.0f, cube.posZ1 / 16.0f,
                    cube.posX2 / 16.0f, cube.posY2 / 16.0f, cube.posZ2 / 16.0f);
            HitboxCaptureManager.captureAdvancedBoxCube(name,
                    cube.posX1, cube.posY1, cube.posZ1, cube.posX2, cube.posY2, cube.posZ2, pose,
                    buildAccumulator(cube, texture, localBox));
        }
    }

    /** Must use the same /16 box as {@code captureAdvancedBoxCube}. */
    private static FaceOpacityAccumulator buildAccumulator(LionfishModelRenderUtils.ModelBox cube,
                                                            ResourceLocation texture, AABB localBox) {
        FaceOpacityAccumulator accumulator = new FaceOpacityAccumulator(localBox);
        if (texture == null || cube.quads == null) return accumulator;
        Object[] quads = cube.quads;
        for (Object quad : quads) {
            if (quad == null) continue;
            Vector3f n;
            Object[] vertices;
            try {
                n = (Vector3f) QUAD_NORMAL_FIELD.get(quad);
                vertices = (Object[]) QUAD_VERTICES_FIELD.get(quad);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("MAH: reflective read failed on LionfishAPI TexturedQuad", e);
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
                    throw new IllegalStateException("MAH: reflective read failed on LionfishAPI PositionTextureVertex", e);
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

    private LionfishBoxCapture() {
    }
}
