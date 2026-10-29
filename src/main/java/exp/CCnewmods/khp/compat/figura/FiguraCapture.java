package exp.CCnewmods.khp.compat.figura;

import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.world.phys.AABB;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.Vertex;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Captures Figura avatar parts: one oriented box per part, from the part's local vertices and the matrix Figura
 * transforms them with. All Figura types live here and in the mixin, so nothing else loads without Figura.
 * Render thread only.
 */
public final class FiguraCapture {

    private static final ArrayDeque<String> PATH = new ArrayDeque<>();
    private static final Matrix4f SCRATCH = new Matrix4f();

    public static void reset() {
        PATH.clear();
    }

    public static void enterPart(FiguraModelPart part) {
        String parent = PATH.peek();
        PATH.push(parent == null ? part.name : parent + "/" + part.name);
    }

    public static void exitPart() {
        if (!PATH.isEmpty()) PATH.pop();
    }

    public static void capture(PartCustomization customization, List<Vertex> vertices) {
        if (customization == null || vertices == null || vertices.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vertex v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        // Vertices are in pixels (the root applies the 1/16 scale); store the box in blocks so minimum-thickness
        // padding means the same as on every other capture path.
        AABB box = new AABB(minX / 16.0, minY / 16.0, minZ / 16.0, maxX / 16.0, maxY / 16.0, maxZ / 16.0);
        customization.positionMatrix.copyDataTo(SCRATCH);
        SCRATCH.scale(16.0f);
        String name = PATH.peek();
        HitboxCaptureManager.captureAvatarPart(name == null ? "figura" : name, box, SCRATCH);
    }

    private FiguraCapture() {
    }
}
