package exp.CCnewmods.khp.livingplatform;

/**
 * The permanent understructure volume seeded onto an anchor's ship at creation —
 * see {@code KHP_Phase2E_Design.md} §10. A rectangular cuboid, centered on the
 * anchor bone's position, filled with {@code LivingPlatformSeedBlock} cells (one
 * per grid position — Minecraft blocks are always exactly 1x1x1, so a 4x4x4
 * volume is 64 individual placements, not one oversized block).
 *
 * <p>Deliberately just width/height/depth for this pass, not an arbitrary
 * per-layer footprint (Copycat-style moldable shapes) — the schema and the
 * placement code in {@link LivingPlatformShipManager#seedVolume} are both
 * written so a future {@code layers} field (list of 2D footprints, one per Y
 * level, for non-cuboid silhouettes) could extend this record without changing
 * the cuboid case's behavior, but that extension isn't built yet — flagging so
 * it isn't mistaken for already having been implemented.
 *
 * @param width  X-axis cell count, must be >= 1.
 * @param height Y-axis cell count, must be >= 1.
 * @param depth  Z-axis cell count, must be >= 1.
 */
public record LivingPlatformSeedShape(int width, int height, int depth) {

    public LivingPlatformSeedShape {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Living platform seed shape dimensions must all be >= 1, got "
                    + width + "x" + height + "x" + depth);
        }
    }
}
