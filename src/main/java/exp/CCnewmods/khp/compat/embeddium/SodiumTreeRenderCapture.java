package exp.CCnewmods.khp.compat.embeddium;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import me.jellysquid.mods.sodium.client.render.immediate.model.ModelCuboid;

import java.util.ArrayList;
import java.util.List;

/** Cube capture for Oculus's Embeddium tree-walk path. References Embeddium types; only load with Embeddium. */
public final class SodiumTreeRenderCapture {

    public static void captureCuboids(PoseStack.Pose pose, ModelCuboid[] cuboids) {
        if (cuboids == null || cuboids.length == 0) return;
        if (!HitboxCaptureManager.shouldCaptureSodiumTreeCubes()) return;

        var texture = HitboxCaptureManager.sodiumTreeTexture();
        double threshold = MahConfig.opacityThreshold;
        List<EmbeddiumFastCubeOpacity.FastCubeResult> results = new ArrayList<>(cuboids.length);
        for (ModelCuboid cuboid : cuboids) {
            if (cuboid != null) {
                results.add(EmbeddiumFastCubeOpacity.fromCuboid(cuboid, texture, threshold));
            }
        }
        HitboxCaptureManager.captureSodiumTreeCubes(pose, results);
    }

    private SodiumTreeRenderCapture() {
    }
}
