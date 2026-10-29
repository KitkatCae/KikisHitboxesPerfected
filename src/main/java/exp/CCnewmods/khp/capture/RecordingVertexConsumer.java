package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Pass-through {@link VertexConsumer} that records the bounds of everything drawn through it, for mods that emit
 * raw vertices with no model object (e.g. Ferrouslime). One box per begin/end span.
 */
public final class RecordingVertexConsumer implements VertexConsumer {

    private final String name;
    private final VertexConsumer delegate;
    private final Matrix4f toLocalSpace;
    private final Matrix4f partTransform;

    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private float maxZ = -Float.MAX_VALUE;
    private boolean any = false;

    /**
     * @param toLocalSpace  {@code basisInverse} (axis-aligned) or the shape's inverse local transform (oriented).
     * @param partTransform identity (axis-aligned) or {@code basisInverse * localTransform} (oriented).
     */
    RecordingVertexConsumer(String name, VertexConsumer delegate, Matrix4f toLocalSpace, Matrix4f partTransform) {
        this.name = name;
        this.delegate = delegate;
        this.toLocalSpace = new Matrix4f(toLocalSpace);
        this.partTransform = new Matrix4f(partTransform);
    }

    String getName() {
        return name;
    }

    Matrix4f getPartTransform() {
        return partTransform;
    }

    AABB buildLocalBox() {
        if (!any) return null;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        Vector4f local = new Vector4f((float) x, (float) y, (float) z, 1.0f);
        local.mul(toLocalSpace);
        if (local.x() < minX) minX = local.x();
        if (local.y() < minY) minY = local.y();
        if (local.z() < minZ) minZ = local.z();
        if (local.x() > maxX) maxX = local.x();
        if (local.y() > maxY) maxY = local.y();
        if (local.z() > maxZ) maxZ = local.z();
        any = true;
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        delegate.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
