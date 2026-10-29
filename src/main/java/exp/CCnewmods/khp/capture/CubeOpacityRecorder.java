package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.MahConfig;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Pass-through {@link VertexConsumer} that watches one vanilla {@code Cube.compile} call to compute per-face
 * opacity (texture alpha × vertex alpha). Used instead of reflecting into {@code Cube.polygons}, whose fields
 * can't be found reliably once obfuscated.
 */
public final class CubeOpacityRecorder implements VertexConsumer {

    private final VertexConsumer delegate;
    private final ResourceLocation texture;
    private final FaceOpacityAccumulator accumulator;
    private final Matrix4f poseInverse;

    private float pendingX, pendingY, pendingZ;
    private float pendingU, pendingV;
    private int pendingAlpha = 255;
    private float pendingNormalX, pendingNormalY, pendingNormalZ = 1.0f;

    /**
     * @param poseInverse inverse of the part pose {@code Cube.compile} is using; maps posed vertices back to {@code
     * localBox}'s space.
     */
    public CubeOpacityRecorder(VertexConsumer delegate, ResourceLocation texture, AABB localBox, Matrix4f poseInverse) {
        this.delegate = delegate;
        this.texture = texture;
        this.accumulator = new FaceOpacityAccumulator(localBox);
        this.poseInverse = poseInverse;
    }

    public byte buildFaceMask() {
        return accumulator.buildFaceMask();
    }

    public PartOBB.FlatFaceUV buildFlatFaceUV(Direction face) {
        return accumulator.buildFlatFaceUV(face);
    }

    public FaceOpacityAccumulator accumulator() {
        return accumulator;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        // Vertices arrive already /16 and posed.
        Vector3f local = poseInverse.transformPosition(new Vector3f((float) x, (float) y, (float) z));
        pendingX = local.x;
        pendingY = local.y;
        pendingZ = local.z;
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        pendingAlpha = alpha;
        delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        pendingU = u;
        pendingV = v;
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
        // The normal is posed too; Direction.getNearest needs the local one.
        Vector3f local = poseInverse.transformDirection(new Vector3f(x, y, z));
        pendingNormalX = local.x;
        pendingNormalY = local.y;
        pendingNormalZ = local.z;
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        Direction face = Direction.getNearest(pendingNormalX, pendingNormalY, pendingNormalZ);
        float opacity = TextureAlphaSampler.sampleOpacity(texture, pendingU, pendingV)
                * (pendingAlpha / 255.0f);
        accumulator.addSample(face, pendingX, pendingY, pendingZ, pendingU, pendingV,
                opacity, MahConfig.opacityThreshold);
        // Some paths skip color(); don't leak the previous vertex's alpha.
        pendingAlpha = 255;
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
