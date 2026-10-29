package exp.CCnewmods.khp.livingplatform;

/**
 * The seed volume built on an anchor's ship: a cuboid centered on the bone, sized in half-blocks so it can
 * match e.g. a 3.5-block hitbox ({@code widthQ = 7}).
 */
public record LivingPlatformSeedShape(int widthQ, int heightQ, int depthQ) {

    public LivingPlatformSeedShape {
        if (widthQ < 1 || heightQ < 1 || depthQ < 1) {
            throw new IllegalArgumentException("Living platform seed shape dimensions must all be >= 1 quadrant, "
                    + "got " + widthQ + "x" + heightQ + "x" + depthQ + " (quadrants)");
        }
    }

    /** Rounded up to whole blocks. */
    public int widthBlocks() {
        return (widthQ + 1) / 2;
    }

    public int heightBlocks() {
        return (heightQ + 1) / 2;
    }

    public int depthBlocks() {
        return (depthQ + 1) / 2;
    }

    /** 0.25 for an odd quadrant count, else 0: how far the floor-centered cells sit off-center. */
    public double centroidOffsetX() {
        return (widthQ % 2 == 0) ? 0.0 : 0.25;
    }

    public double centroidOffsetY() {
        return (heightQ % 2 == 0) ? 0.0 : 0.25;
    }

    public double centroidOffsetZ() {
        return (depthQ % 2 == 0) ? 0.0 : 0.25;
    }
}
