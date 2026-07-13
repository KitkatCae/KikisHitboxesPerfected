package exp.CCnewmods.khp.api;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An arbitrary volume for {@link MahApi#shapecast} — a cheaper alternative to
 * sweeping hundreds of individual raycasts when something (a shockwave, a cone-shaped
 * breath attack, an expanding ring) needs to hit everything it touches at once.
 *
 * <p>Callers build one of the concrete shapes below (in world space) and hand it to
 * {@code shapecast} for a single moment — an <i>expanding</i> shockwave is expressed
 * by the caller re-calling {@code shapecast} each tick with a larger
 * {@link SphereShape#radius()}, not by this interface animating anything itself, the
 * same way {@link MahApi#raycast} takes one origin/direction snapshot rather than a
 * moving ray.
 *
 * <p>Implementations provide a point-containment test ({@link #contains(Vec3)}) and a
 * coarse world-space {@link #boundingBox()} for broad-phase rejection before the
 * (potentially per-shape-expensive) containment test runs per candidate point.
 */
public interface CastShape {

    /** True if {@code worldPoint} is inside (or on the surface of) this shape. */
    boolean contains(Vec3 worldPoint);

    /** Conservative world-space AABB fully containing this shape — broad-phase only, never used for the actual hit decision. */
    AABB boundingBox();

    /** Solid sphere. */
    record SphereShape(Vec3 center, double radius) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            return center.distanceToSqr(p) <= radius * radius;
        }

        @Override
        public AABB boundingBox() {
            return new AABB(center.x - radius, center.y - radius, center.z - radius,
                    center.x + radius, center.y + radius, center.z + radius);
        }
    }

    /**
     * Oriented box — {@code halfExtents} along the box's own local X/Y/Z, rotated by
     * {@code rotation} (a normalized forward/up/right-style basis is overkill for
     * most callers; this takes the box's world-space axes directly since that's what
     * a shockwave/attack volume's caller almost always already has computed).
     */
    record BoxShape(Vec3 center, Vec3 halfExtents, Vec3 axisX, Vec3 axisY, Vec3 axisZ) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            Vec3 d = p.subtract(center);
            double px = d.dot(axisX), py = d.dot(axisY), pz = d.dot(axisZ);
            return Math.abs(px) <= halfExtents.x && Math.abs(py) <= halfExtents.y && Math.abs(pz) <= halfExtents.z;
        }

        @Override
        public AABB boundingBox() {
            // Conservative: sphere-of-the-box radius in every direction, cheap and
            // always correct regardless of rotation (tight AABB of a rotated box
            // needs the actual corners; broad-phase doesn't need it tight).
            double r = halfExtents.length();
            return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
        }
    }

    /**
     * Solid cone: apex at {@code apex}, opening along {@code axis} (normalized) for
     * {@code height}, with half-angle {@code halfAngleRadians} at the apex.
     */
    record ConeShape(Vec3 apex, Vec3 axis, double height, double halfAngleRadians) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            Vec3 d = p.subtract(apex);
            double along = d.dot(axis);
            if (along < 0 || along > height) return false;
            double radiusAtAlong = along * Math.tan(halfAngleRadians);
            double distFromAxisSqr = d.lengthSqr() - along * along;
            return distFromAxisSqr <= radiusAtAlong * radiusAtAlong;
        }

        @Override
        public AABB boundingBox() {
            double baseRadius = height * Math.tan(halfAngleRadians);
            Vec3 base = apex.add(axis.scale(height));
            Vec3 lo = new Vec3(Math.min(apex.x, base.x) - baseRadius, Math.min(apex.y, base.y) - baseRadius, Math.min(apex.z, base.z) - baseRadius);
            Vec3 hi = new Vec3(Math.max(apex.x, base.x) + baseRadius, Math.max(apex.y, base.y) + baseRadius, Math.max(apex.z, base.z) + baseRadius);
            return new AABB(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
        }
    }

    /**
     * Torus ("donut"): center circle of radius {@code majorRadius} in the plane
     * perpendicular to {@code axis}, with circular cross-section radius {@code minorRadius}.
     */
    record TorusShape(Vec3 center, Vec3 axis, double majorRadius, double minorRadius) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            Vec3 d = p.subtract(center);
            double alongAxis = d.dot(axis);
            Vec3 inPlane = d.subtract(axis.scale(alongAxis));
            double distFromRing = inPlane.length() - majorRadius;
            return distFromRing * distFromRing + alongAxis * alongAxis <= minorRadius * minorRadius;
        }

        @Override
        public AABB boundingBox() {
            double r = majorRadius + minorRadius;
            return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
        }
    }

    /**
     * Flat ring (annulus) — a donut flattened to near-zero cross-section thickness in
     * the plane perpendicular to {@code axis}, with independent inner and outer radii
     * (a true torus's minor radius is always the same all the way around; this is the
     * "expanding shockwave ring on the ground" shape a torus can't cleanly express).
     */
    record RingShape(Vec3 center, Vec3 axis, double innerRadius, double outerRadius, double thickness) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            Vec3 d = p.subtract(center);
            double alongAxis = d.dot(axis);
            if (Math.abs(alongAxis) > thickness * 0.5) return false;
            double distFromCenter = d.subtract(axis.scale(alongAxis)).length();
            return distFromCenter >= innerRadius && distFromCenter <= outerRadius;
        }

        @Override
        public AABB boundingBox() {
            // Axis-independent conservative bound (sphere of the ring's full extent) —
            // the tight box would need to know axis orientation; broad-phase doesn't
            // need it tight, just correct for any axis.
            double r = outerRadius + thickness * 0.5;
            return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
        }
    }
}
