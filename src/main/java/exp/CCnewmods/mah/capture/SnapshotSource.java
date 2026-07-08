package exp.CCnewmods.mah.capture;

/**
 * Where a hitbox snapshot's geometry came from, in decreasing order of fidelity.
 * Phase 1 only produces {@link #LIVE_CLIENT}; the others arrive with the
 * dedicated-server truth layer (design §4).
 */
public enum SnapshotSource {
    /** Captured from the actual render this frame — fully animated, exact. */
    LIVE_CLIENT,
    /** Produced by the server-side headless GeckoLib animation resolver. */
    HEADLESS_GECKO,
    /** Static rest-pose blueprint re-rotated by live body yaw. */
    BLUEPRINT
}
