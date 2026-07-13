package exp.CCnewmods.khp.capture;

/**
 * Where a hitbox snapshot's geometry came from, in decreasing order of fidelity.
 *
 * <p><b>Revised</b> — the earlier plan for a third, "headless GeckoLib resolver"
 * tier (running animation logic server-side without a renderer) was abandoned in
 * favor of pre-baked blueprints (see {@code KHP_Headless_Resolver_Design_v2.md}):
 * most third-party animation frameworks are client-only classloaded, so a headless
 * tier distinct from a static replay never made sense to build. There are only two
 * real producers now — this enum previously had a stale {@code HEADLESS_GECKO}
 * value left over from that plan, removed as part of the blueprint system landing.
 */
public enum SnapshotSource {
    /** Captured from the actual render this frame — fully animated, exact. */
    LIVE_CLIENT,
    /**
     * Replayed from a pre-baked {@code EntityBlueprint} pose (see
     * {@code exp.CCnewmods.khp.blueprint}) — used when no live snapshot exists
     * (real dedicated server, or an entity currently unrendered on every connected
     * client). Carries a full pose set (rest, baby, sitting, sleeping, walk/attack
     * sweep samples, GeckoLib clip samples) resolved server-side from cheap AI
     * state, not a single rest pose re-rotated by yaw.
     */
    BLUEPRINT
}
