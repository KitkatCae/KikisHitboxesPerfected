package exp.CCnewmods.mah.compat.lionfishapi;

import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedEntityModel;
import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox;
import com.github.L_Ender.lionfishapi.client.model.tools.LionfishModelRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.mah.capture.HitboxCaptureManager;
import exp.CCnewmods.mah.util.MahReflect;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * All LionfishAPI-typed geometry-capture logic lives here, isolated from
 * {@link HitboxCaptureManager} so that class stays loadable when LionfishAPI isn't
 * installed. Only reached from {@code LionfishAdvancedModelBoxMixin}, which is itself
 * skipped entirely (mah.lionfishapi.mixins.json is "required": false) when LionfishAPI is absent.
 *
 * <p>Naming: unlike Citadel's own consumers, Ender's Cataclysm's own entity models
 * (verified directly against its jar: {@code Deepling_Brute_Model},
 * {@code Endermaptera_Model}, and others) construct every {@code AdvancedModelBox}
 * with the no-name constructor — {@code boxName} is empty on every part. The
 * reflective fallback (the concrete model's own field name for that box instance,
 * e.g. {@code this.left_leg}) is therefore the <b>primary</b> naming source here,
 * not just a fallback, for every Cataclysm mob.
 */
public final class LionfishBoxCapture {

    private static final Field MODEL_FIELD =
            MahReflect.findSoleFieldOfType(AdvancedModelBox.class, AdvancedEntityModel.class);

    // model instance (identity) -> (box instance (identity) -> declared field name)
    private static final Map<Object, Map<Object, String>> NAME_CACHE = new WeakHashMap<>();

    public static void capture(AdvancedModelBox box, PoseStack.Pose pose) {
        if (!HitboxCaptureManager.shouldCaptureModelGeometry()) return;
        if (box.cubeList == null || box.cubeList.isEmpty()) return;
        String name = resolveName(box);
        for (LionfishModelRenderUtils.ModelBox cube : box.cubeList) {
            if (cube == null) continue;
            HitboxCaptureManager.captureAdvancedBoxCube(name,
                    cube.posX1, cube.posY1, cube.posZ1, cube.posX2, cube.posY2, cube.posZ2, pose);
        }
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
