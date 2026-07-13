package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.MahConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces Phase 2D Part A3. Instead of sweeping a mob's body against terrain
 * part-by-part (removed — see {@code EntityCollideEntityShapesMixin}'s javadoc
 * for why: taking the most-restrictive result across parts zeroed out movement
 * for most mob types whenever any single part's captured geometry drifted
 * slightly into a block), this resizes the mob's single vanilla collision box
 * to cover its model's actual captured extent, then lets vanilla's completely
 * unmodified single-box collision ({@code Entity.move}/{@code collideBoundingBox})
 * do the rest. Wired in via {@code EntityMakeBoundingBoxMixin}, which overrides
 * {@code Entity#makeBoundingBox()}'s return value — the one chokepoint every
 * position update ({@code Entity#setPos}) already rebuilds the box from, so no
 * other vanilla call site needs touching.
 *
 * <p>Off by default ({@code aabb_resize_enabled}); asymmetric/off-center by
 * design (per sign-off) — the box is the actual union of the model's part
 * extents, not a symmetric width/height box centered on the entity like
 * vanilla's own.
 *
 * <h3>STABLE mode</h3>
 * Per-<b>individual</b>-entity (keyed by UUID, not entity type) envelope that
 * only ever grows, never shrinks, as more of that entity's animated poses are
 * observed over its lifetime — each call unions the current pose's local-space
 * box into whatever's cached, then returns the accumulated envelope translated
 * to the entity's current position. Deliberately per-entity rather than
 * per-type: entity types with scale-variant individuals (slimes, magma cubes,
 * baby variants) would otherwise get one individual's envelope applied to every
 * other individual of that type regardless of its own size, which is wrong in
 * the more visible direction — a giant collision box on a tiny slime. The
 * tradeoff is a brief warm-up window right after spawn where the box may not
 * yet cover the model's full extent — acceptable since it can only ever be too
 * small briefly, never wrong-shaped, and it stabilizes within the first few
 * animation cycles.
 *
 * <p>Known limitation: {@link #STABLE_ENVELOPES} is never pruned, so it grows
 * for as long as the server runs and mobs of qualifying types keep spawning —
 * consistent with {@code PreciseWorldParts}'s own per-tick cache, which has the
 * same property. Each entry is one small AABB (~50 bytes); revisit only if this
 * is ever actually observed to matter on a long-running server.
 *
 * <h3>LIVE mode</h3>
 * No caching at all — just returns {@link PreciseWorldParts#union} of the
 * current tick's resolved parts directly. Tighter fit, but the box's size and
 * shape change continuously as the mob animates (e.g. an attack swing
 * temporarily widens it).
 */
public final class AabbResize {

    public enum Mode {
        STABLE,
        LIVE
    }

    /** Local-space (relative to the entity's own position) accumulated envelope, keyed by UUID. */
    private static final Map<UUID, AABB> STABLE_ENVELOPES = new ConcurrentHashMap<>();

    /**
     * @return the resized world-space box, or {@code null} if resizing should not
     *         apply this call (feature disabled, no resolvable snapshot, or over
     *         {@code aabb_resize_max_parts}) — caller should keep vanilla's box
     *         unchanged in that case.
     */
    @Nullable
    public static AABB resizedBoxOrNull(Entity entity) {
        if (!MahConfig.aabbResizeEnabled) {
            return null;
        }

        List<AABB> parts = PreciseWorldParts.resolveOrNull(entity, MahConfig.aabbResizeMaxParts);
        if (parts == null) {
            return null;
        }

        AABB worldUnion = PreciseWorldParts.union(parts);

        if (MahConfig.aabbResizeMode == Mode.LIVE) {
            return worldUnion;
        }

        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        AABB localBox = worldUnion.move(-ex, -ey, -ez);

        // merge() calls (existing, new) -> existing.minmax(new) only when a mapping is
        // already present; on first observation it just seeds the map with localBox.
        AABB envelope = STABLE_ENVELOPES.merge(entity.getUUID(), localBox, AABB::minmax);
        return envelope.move(ex, ey, ez);
    }

    private AabbResize() {
    }
}
