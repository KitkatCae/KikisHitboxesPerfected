package exp.CCnewmods.khp.compat.uranus;

import com.iafenvoy.uranus.client.model.AdvancedEntityModel;
import com.iafenvoy.uranus.client.model.AdvancedModelBox;
import com.iafenvoy.uranus.client.model.TabulaModelRenderUtils;
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

/** Uranus counterpart of {@code CitadelBoxCapture}; Uranus's quad classes are public, so no reflection. */
public final class UranusBoxCapture {

    private static final Field MODEL_FIELD =
            MahReflect.findSoleFieldOfType(AdvancedModelBox.class, AdvancedEntityModel.class);

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

    /** Must use the same /16 box as {@code captureAdvancedBoxCube}. */
    private static FaceOpacityAccumulator buildAccumulator(TabulaModelRenderUtils.ModelBox cube,
                                                            ResourceLocation texture, AABB localBox) {
        FaceOpacityAccumulator accumulator = new FaceOpacityAccumulator(localBox);
        if (texture == null || cube.quads == null) return accumulator;
        for (TabulaModelRenderUtils.TexturedQuad quad : cube.quads) {
            if (quad == null || quad.vertexPositions == null) continue;
            Vector3f n = quad.normal;
            Direction face = n != null ? Direction.getNearest(n.x, n.y, n.z) : null;
            if (face == null) continue;
            for (TabulaModelRenderUtils.PositionTextureVertex vertex : quad.vertexPositions) {
                if (vertex == null || vertex.position == null) continue;
                float opacity = TextureAlphaSampler.sampleOpacity(texture, vertex.textureU, vertex.textureV);
                accumulator.addSample(face, vertex.position.x / 16.0f, vertex.position.y / 16.0f,
                        vertex.position.z / 16.0f, vertex.textureU, vertex.textureV,
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

    private UranusBoxCapture() {
    }
}
