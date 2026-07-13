package exp.CCnewmods.khp.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written JSON (de)serialization for {@link EntityBlueprint}/{@link BlueprintPose}/
 * {@link PartOBB}.
 *
 * <p><b>Deliberately not left to Gson's default reflective mapping</b> (confirmed via
 * source check — no serialization convention existed anywhere in the mod before this,
 * see design doc Q1): {@link PartOBB} holds a JOML {@code Matrix4f} and a vanilla
 * {@code AABB}, neither of which is a plain POJO Gson could map safely on its own —
 * writing every field explicitly here means the on-disk shape is exactly what's
 * intended, not whatever Gson's reflection happens to produce for a third-party
 * library type today.
 *
 * <p>{@code Matrix4f} round-trips via its 16 individual {@code mRC()} scalar getters
 * and the matching 16-float constructor — confirmed present on the actual JOML jar
 * this project builds against, not assumed. Deliberately <b>not</b> using
 * {@code Matrix4f}'s {@code FloatBuffer}-based get/set: the project's own hard-won
 * lesson (Phase 1 handoff §5) is that a heap-backed {@code FloatBuffer} crashes the
 * JVM natively on JOML's {@code Unsafe} fast path. The scalar-getter/16-arg-constructor
 * path used here never touches that code at all.
 */
public final class BlueprintSerializer {

    // ── EntityBlueprint ──────────────────────────────────────────────────────

    public static JsonObject toJson(EntityBlueprint blueprint) {
        JsonObject root = new JsonObject();
        root.addProperty("entity_type", blueprint.entityType().toString());
        root.addProperty("baby", blueprint.baby());
        root.addProperty("renderer_kind", blueprint.rendererKind());
        if (blueprint.texture() != null) {
            root.addProperty("texture", blueprint.texture().toString());
        }
        JsonArray poses = new JsonArray();
        for (BlueprintPose pose : blueprint.poses()) {
            poses.add(toJson(pose));
        }
        root.add("poses", poses);
        return root;
    }

    /**
     * The file is fully self-describing — {@code entity_type} and {@code baby} are
     * read from the JSON body itself, <b>not</b> derived from the resource path/file
     * name. Splitting {@code minecraft_cow_baby.json} back into {@code minecraft:cow}
     * + baby=true by string convention is ambiguous in general (namespaces and entity
     * path segments can themselves contain underscores), so the file name is purely
     * organizational; the reload listener cross-checks it against this and logs a
     * warning on mismatch rather than trusting the path.
     */
    public static EntityBlueprint fromJson(JsonObject root) {
        ResourceLocation entityType = new ResourceLocation(root.get("entity_type").getAsString());
        boolean baby = root.has("baby") && root.get("baby").getAsBoolean();
        String rendererKind = root.has("renderer_kind") ? root.get("renderer_kind").getAsString() : "OTHER";
        ResourceLocation texture = root.has("texture") ? new ResourceLocation(root.get("texture").getAsString()) : null;
        List<BlueprintPose> poses = new ArrayList<>();
        JsonArray posesJson = root.getAsJsonArray("poses");
        if (posesJson != null) {
            for (var el : posesJson) {
                poses.add(poseFromJson(el.getAsJsonObject()));
            }
        }
        return new EntityBlueprint(entityType, baby, rendererKind, texture, poses);
    }

    // ── BlueprintPose ─────────────────────────────────────────────────────────

    public static JsonObject toJson(BlueprintPose pose) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pose_id", pose.poseId());
        JsonArray parts = new JsonArray();
        for (PartOBB part : pose.parts()) {
            parts.add(toJson(part));
        }
        obj.add("parts", parts);
        return obj;
    }

    public static BlueprintPose poseFromJson(JsonObject obj) {
        String poseId = obj.get("pose_id").getAsString();
        List<PartOBB> parts = new ArrayList<>();
        JsonArray partsJson = obj.getAsJsonArray("parts");
        if (partsJson != null) {
            for (var el : partsJson) {
                parts.add(partFromJson(el.getAsJsonObject()));
            }
        }
        return new BlueprintPose(poseId, parts);
    }

    // ── PartOBB ───────────────────────────────────────────────────────────────

    public static JsonObject toJson(PartOBB part) {
        JsonObject obj = new JsonObject();
        obj.add("local_box", toJson(part.localBox()));
        obj.add("transform", toJson(part.transform()));
        obj.addProperty("bone_id", part.boneId());
        obj.addProperty("face_mask", part.faceMask());
        if (part.flatFaceUV() != null) {
            obj.add("flat_face_uv", toJson(part.flatFaceUV()));
        }
        return obj;
    }

    public static PartOBB partFromJson(JsonObject obj) {
        AABB localBox = aabbFromJson(obj.getAsJsonObject("local_box"));
        Matrix4f transform = matrixFromJson(obj.getAsJsonArray("transform"));
        short boneId = obj.get("bone_id").getAsShort();
        byte faceMask = obj.get("face_mask").getAsByte();
        PartOBB.FlatFaceUV flatFaceUV = obj.has("flat_face_uv")
                ? flatFaceUVFromJson(obj.getAsJsonObject("flat_face_uv"))
                : null;
        return new PartOBB(localBox, transform, boneId, faceMask, flatFaceUV);
    }

    // ── AABB ──────────────────────────────────────────────────────────────────

    private static JsonObject toJson(AABB box) {
        JsonObject obj = new JsonObject();
        obj.addProperty("min_x", box.minX);
        obj.addProperty("min_y", box.minY);
        obj.addProperty("min_z", box.minZ);
        obj.addProperty("max_x", box.maxX);
        obj.addProperty("max_y", box.maxY);
        obj.addProperty("max_z", box.maxZ);
        return obj;
    }

    private static AABB aabbFromJson(JsonObject obj) {
        return new AABB(
                obj.get("min_x").getAsDouble(), obj.get("min_y").getAsDouble(), obj.get("min_z").getAsDouble(),
                obj.get("max_x").getAsDouble(), obj.get("max_y").getAsDouble(), obj.get("max_z").getAsDouble());
    }

    // ── Matrix4f ──────────────────────────────────────────────────────────────
    // Row-major m00..m33 flat array of 16 floats — matches JOML's own mRC() naming
    // (first digit = row, second = column) so the on-disk order is self-describing
    // against JOML's own documentation, not an arbitrary flattening.

    private static JsonArray toJson(Matrix4f m) {
        JsonArray arr = new JsonArray(16);
        arr.add(m.m00()); arr.add(m.m01()); arr.add(m.m02()); arr.add(m.m03());
        arr.add(m.m10()); arr.add(m.m11()); arr.add(m.m12()); arr.add(m.m13());
        arr.add(m.m20()); arr.add(m.m21()); arr.add(m.m22()); arr.add(m.m23());
        arr.add(m.m30()); arr.add(m.m31()); arr.add(m.m32()); arr.add(m.m33());
        return arr;
    }

    private static Matrix4f matrixFromJson(JsonArray arr) {
        if (arr == null || arr.size() != 16) {
            throw new IllegalArgumentException("Blueprint transform array must have exactly 16 elements, got: "
                    + (arr == null ? "null" : arr.size()));
        }
        float[] v = new float[16];
        for (int i = 0; i < 16; i++) v[i] = arr.get(i).getAsFloat();
        // Matrix4f(float, ...) constructor is in COLUMN-major argument order per JOML's
        // own convention (m00, m01, m02, m03, m10, ...) matching mRC() naming used above,
        // so this is a direct positional match, not a transpose.
        return new Matrix4f(
                v[0], v[1], v[2], v[3],
                v[4], v[5], v[6], v[7],
                v[8], v[9], v[10], v[11],
                v[12], v[13], v[14], v[15]);
    }

    // ── FlatFaceUV ────────────────────────────────────────────────────────────

    private static JsonObject toJson(PartOBB.FlatFaceUV uv) {
        JsonObject obj = new JsonObject();
        obj.addProperty("face", uv.face().getName());
        obj.addProperty("u0", uv.u0()); obj.addProperty("v0", uv.v0());
        obj.addProperty("u1", uv.u1()); obj.addProperty("v1", uv.v1());
        obj.addProperty("u2", uv.u2()); obj.addProperty("v2", uv.v2());
        obj.addProperty("u3", uv.u3()); obj.addProperty("v3", uv.v3());
        return obj;
    }

    @Nullable
    private static PartOBB.FlatFaceUV flatFaceUVFromJson(JsonObject obj) {
        Direction face = Direction.byName(obj.get("face").getAsString());
        if (face == null) {
            throw new IllegalArgumentException("Unknown Direction name in flat_face_uv: " + obj.get("face"));
        }
        return new PartOBB.FlatFaceUV(face,
                obj.get("u0").getAsFloat(), obj.get("v0").getAsFloat(),
                obj.get("u1").getAsFloat(), obj.get("v1").getAsFloat(),
                obj.get("u2").getAsFloat(), obj.get("v2").getAsFloat(),
                obj.get("u3").getAsFloat(), obj.get("v3").getAsFloat());
    }

    private BlueprintSerializer() {
    }
}
