package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.MahConfig;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A transparent {@link VertexConsumer} decorator that computes a real per-face
 * {@link PartOBB#faceMask} — and, for flat parts, the corner UV data
 * {@link FaceOpacityAccumulator} needs to build a {@link PartOBB.FlatFaceUV} — for a
 * single vanilla {@code ModelPart.Cube}, by observing the exact
 * {@code vertex()}/{@code uv()}/{@code color()}/{@code normal()} calls
 * {@code Cube.compile} makes for that one cube's vertices and forwarding everything
 * unchanged to a real delegate — same non-invasive wrapping principle as
 * {@link RecordingVertexConsumer}.
 *
 * <h3>Why this exists instead of reflecting into {@code Cube.polygons}</h3>
 * {@code ModelPart.Cube}, {@code ModelPart.Polygon} and {@code ModelPart.Vertex} are
 * all package-private, so any field on them not exposed by an accessible supertype
 * can only be read via {@code java.lang.reflect}. {@code Vertex} has <b>two</b>
 * {@code float} fields, {@code u} and {@code v}, which type-based lookup
 * ({@code MahReflect.findSoleFieldOfType}) can't disambiguate, and name-based lookup
 * risks breaking in a production Forge environment where vanilla field names are
 * SRG-obfuscated at runtime. Reading the real values the renderer itself computes,
 * off the public {@link VertexConsumer} interface every {@code Cube.compile} call
 * already uses, sidesteps that problem entirely: no reflection into {@code Vertex}
 * or {@code Polygon} at all.
 *
 * <h3>Unit note — corrected, bytecode-verified against the real mapped jar</h3>
 * Incoming {@code vertex(x,y,z)} calls are <b>not</b> raw model units. Decompiling
 * the actual {@code ModelPart.Cube.compile} (Parchment 2023.09.03, Forge
 * 47.4.20) shows it computes {@code v = vertex.pos / 16.0f} <i>and then transforms
 * that by {@code pose.pose()}</i> before ever calling {@code consumer.vertex(...)} —
 * so what arrives here is already both scaled to block units <i>and</i> fully posed
 * (translated/rotated into wherever this cube's part currently sits in the render
 * chain), not the bare local cube-space coordinate {@link PartOBB#localBox()} is in.
 * An earlier version of this class assumed the opposite (raw, unposed, un-scaled
 * model units) and divided by 16 a second time — silently comparing wrong-scale,
 * wrong-space positions against {@link FaceGeometry#faceCorners}' local-space
 * reference corners in {@link FaceOpacityAccumulator#addSample}, which is why
 * {@code buildFlatFaceUV} was observed to always fail (real per-run log evidence:
 * 4 samples arriving, at most 1–2 of a face's 4 corner slots ever actually set,
 * for every flat/thin part across every mod tested — nothing face- or mod-specific
 * about it). Fixed by inverting {@code pose.pose()} instead: {@link #poseInverse},
 * supplied by the caller ({@code HitboxCaptureManager#beginVanillaCubeOpacity}, from
 * the exact same pose {@code captureVanillaCubes} used to build this cube's
 * {@code PartOBB}), maps the incoming already-posed vertex directly back to local
 * cube space with <b>no further scaling</b> — vanilla's own {@code /16.0} already
 * happened before the pose transform it's undoing. The same fix applies to
 * {@link #normal(float, float, float)} — vanilla poses the normal too, via a
 * separate {@code pose.normal()} (Matrix3f) — see that method for details.
 *
 * <h3>Effective opacity</h3>
 * {@code texture_alpha(u,v) × vertex_color_alpha} — texture alpha alone was the
 * original (wrong) plan; a face can be legitimately faded by its vertex color call
 * (dye tint, hurt-flash, a translucency animation some third-party bosses use) over
 * a fully-opaque backing texture, and vice versa.
 */
public final class CubeOpacityRecorder implements VertexConsumer {

    private final VertexConsumer delegate;
    private final ResourceLocation texture;
    private final FaceOpacityAccumulator accumulator;
    private final Matrix4f poseInverse;

    // Scratch for the vertex currently being assembled — order isn't load-bearing,
    // every field is just accumulated until endVertex() reads them all back.
    private float pendingX, pendingY, pendingZ;
    private float pendingU, pendingV;
    private int pendingAlpha = 255;
    private float pendingNormalX, pendingNormalY, pendingNormalZ = 1.0f;

    /**
     * @param localBox    this cube's bounds in local (unposed) space — same one
     *                    {@link PartOBB#localBox()} carries, what {@code faceCorners}
     *                    reference points are computed from.
     * @param poseInverse inverse of the exact {@code pose.pose()} matrix
     *                    {@code ModelPart.compile} is running with for this cube's
     *                    parent part right now — see this class's javadoc for why.
     */
    public CubeOpacityRecorder(VertexConsumer delegate, ResourceLocation texture, AABB localBox, Matrix4f poseInverse) {
        this.delegate = delegate;
        this.texture = texture;
        this.accumulator = new FaceOpacityAccumulator(localBox);
        this.poseInverse = poseInverse;
    }

    /** Call once, after the wrapped {@code Cube.compile} call returns. */
    public byte buildFaceMask() {
        return accumulator.buildFaceMask();
    }

    /** Only meaningful (non-null) if all four corners of {@code face} were actually sampled. */
    public PartOBB.FlatFaceUV buildFlatFaceUV(Direction face) {
        return accumulator.buildFlatFaceUV(face);
    }

    /** The underlying accumulator, for {@code HitboxCaptureManager}'s shared Option A finalization. */
    public FaceOpacityAccumulator accumulator() {
        return accumulator;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        // x,y,z arrive already /16'd and already pose-transformed (see class
        // javadoc) — poseInverse undoes exactly the transform, landing back in the
        // same local, already-/16'd space `localBox`/faceCorners are in. No
        // additional division here; that step already happened inside vanilla's
        // own Cube.compile before pose.pose() was ever applied.
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
        // Same posed-vs-local issue as vertex() above: Cube.compile transforms the
        // cube's real local face normal by pose.normal() (a Matrix3f — rotation/scale
        // only, no translation) before calling consumer.normal(...). Direction.getNearest
        // needs the LOCAL face normal to correctly identify which of the cube's own six
        // faces this vertex belongs to; the posed one is only equal to it when this
        // bone's rotation happens to be identity. transformDirection ignores
        // poseInverse's translation column (correct for a direction, not a position) and
        // inverts the same rotation pose.normal() applied — exact for pure rotation and
        // uniform scale (every case this modpack's models actually use), a harmless
        // approximation only under non-uniform per-axis xScale/yScale/zScale, which
        // Direction.getNearest's axis-snap doesn't need sub-degree precision from anyway.
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
        // Reset alpha only — u/v/normal/position are expected to be re-set by every
        // vertex; a missing color() call (rare, some paths skip it and rely on
        // defaultColor) would otherwise leak the previous vertex's alpha, so default
        // back to opaque.
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
