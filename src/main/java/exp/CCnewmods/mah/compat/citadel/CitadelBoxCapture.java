package exp.CCnewmods.mah.compat.citadel;

import com.github.alexthe666.citadel.client.model.AdvancedEntityModel;
import com.github.alexthe666.citadel.client.model.AdvancedModelBox;
import com.github.alexthe666.citadel.client.model.TabulaModelRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.mah.capture.HitboxCaptureManager;
import exp.CCnewmods.mah.util.MahReflect;

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

    // model instance (identity) -> (box instance (identity) -> declared field name)
    private static final Map<Object, Map<Object, String>> NAME_CACHE = new WeakHashMap<>();

    public static void capture(AdvancedModelBox box, PoseStack.Pose pose) {
        if (!HitboxCaptureManager.shouldCaptureModelGeometry()) return;
        if (box.cubeList == null || box.cubeList.isEmpty()) return;
        String name = resolveName(box);
        for (TabulaModelRenderUtils.ModelBox cube : box.cubeList) {
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

    private CitadelBoxCapture() {
    }
}
