package exp.CCnewmods.khp.api;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A world-space volume for {@link MahApi#shapecast}. For an expanding shape, call {@code shapecast} each tick
 * with new parameters.
 */
public interface CastShape {

    boolean contains(Vec3 worldPoint);

    /** Broad-phase only. */
    AABB boundingBox();

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

    /** Axes are given in world space. */
    record BoxShape(Vec3 center, Vec3 halfExtents, Vec3 axisX, Vec3 axisY, Vec3 axisZ) implements CastShape {
        @Override
        public boolean contains(Vec3 p) {
            Vec3 d = p.subtract(center);
            double px = d.dot(axisX), py = d.dot(axisY), pz = d.dot(axisZ);
            return Math.abs(px) <= halfExtents.x && Math.abs(py) <= halfExtents.y && Math.abs(pz) <= halfExtents.z;
        }

        @Override
        public AABB boundingBox() {
            // Bounding sphere: valid for any rotation.
            double r = halfExtents.length();
            return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
        }
    }

    /** Apex, normalized axis, height and half-angle. */
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

    /** Ring of radius {@code majorRadius} perpendicular to {@code axis}, tube radius {@code minorRadius}. */
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

    /** Flat annulus perpendicular to {@code axis}. */
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
            double r = outerRadius + thickness * 0.5;
            return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
        }
    }
}
