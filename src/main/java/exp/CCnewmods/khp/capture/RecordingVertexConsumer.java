package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * A transparent {@link VertexConsumer} decorator that records the bounding box of
 * every vertex position submitted through it, while forwarding every call unchanged
 * to a real delegate so actual rendering is completely unaffected.
 *
 * <h3>Why this exists</h3>
 * Every other MAH capture path (vanilla {@code ModelPart}, GeckoLib {@code GeoBone},
 * the various Tabula-style {@code AdvancedModelBox} frameworks) works by reading
 * geometry off a model <i>object</i> that already exists — cubes with known bounds,
 * transformed by a known matrix. Some mods render parts of an entity with no such
 * object at all: raw {@code VertexConsumer} calls built from scratch every frame
 * (confirmed example: Alex's Caves' Ferrouslime, whose gooey "gel" body and spikes
 * are hand-emitted vertex-by-vertex, verified via bytecode — there is no
 * {@code ModelPart}/{@code AdvancedModelBox} behind that shape anywhere). This class
 * is how MAH captures that kind of geometry instead: wrap the {@code VertexConsumer}
 * the mod's own rendering code already obtained, let its existing logic run
 * completely unmodified against the wrapper, and read back whatever bounds were
 * actually emitted once it's done.
 *
 * <h3>Two capture modes</h3>
 * Every position submitted via {@link #vertex(double, double, double)} — including
 * through the {@code vertex(Matrix4f, float, float, float)} default method, which
 * transforms by the given matrix and then calls this exact method (verified against
 * the mapped jar) — arrives already in whatever space the caller's own PoseStack
 * matrix represents, i.e. the same camera-relative world space every other capture
 * path's incoming pose is in. What happens next depends on which factory method on
 * {@link HitboxCaptureManager} created this instance:
 *
 * <ul>
 *   <li><b>Axis-aligned mode</b> ({@code beginRawVertexCapture(name, delegate)}):
 *   each position is transformed by the bracket's {@code basisInverse} and folded
 *   into a running min/max directly in entity-local space. Correct when the geometry
 *   itself has no per-instance rotation to preserve (Ferrouslime's single gel body).
 *   The resulting {@link PartOBB} carries an <b>identity transform</b> — the box
 *   already <i>is</i> entity-local.</li>
 *
 *   <li><b>Oriented mode</b> ({@code beginRawVertexCapture(name, delegate, localTransform)}):
 *   each position is instead transformed by the <i>inverse</i> of the geometry's own
 *   local transform (e.g. the per-spike rotation matrix Ferrouslime already computes
 *   for each of its eight spikes), landing bounds in that shape's own unrotated local
 *   frame — tight, since axis-aligned geometry stays axis-aligned in its own frame no
 *   matter which way it's been rotated into world space. The resulting
 *   {@link PartOBB} carries {@code basisInverse * localTransform} as its transform,
 *   the same "local box + separate transform" convention every other capture path
 *   uses, so the box rotates with the shape instead of ballooning out to cover a
 *   diagonal orientation the way a purely axis-aligned box would.</li>
 * </ul>
 *
 * <h3>Fidelity</h3>
 * Either mode produces one box over everything emitted in a single begin/end span —
 * not per-triangle geometry, and with no attempt to separate disjoint shapes emitted
 * in the same span. Splitting into more spans (as done for Ferrouslime's spikes,
 * one span per spike rather than one for the whole loop) is the way to get finer
 * granularity; this class itself always just produces one box per span.
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
     * @param toLocalSpace  matrix applied to every incoming (world-space) vertex
     *                      position before folding it into the running bounds —
     *                      {@code basisInverse} for axis-aligned mode, or the inverse
     *                      of the shape's own local transform for oriented mode.
     * @param partTransform the transform the resulting {@link PartOBB} will carry —
     *                      identity for axis-aligned mode, or
     *                      {@code basisInverse * localTransform} for oriented mode.
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

    /** Local-space (per {@code toLocalSpace}) AABB over every recorded vertex, or {@code null} if none arrived. */
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
