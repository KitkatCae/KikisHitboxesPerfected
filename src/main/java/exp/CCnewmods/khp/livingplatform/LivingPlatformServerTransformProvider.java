package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Drives a living-platform ship from its anchor bone's transform each tick. Returns {@code null} (hold position)
 * when there's no pose data. {@code positionInModel} is the seed volume's center plus the odd-quadrant
 * correction (see {@link LivingPlatformSeedShape#centroidOffsetX}).
 *
 * <p>Rotation: models render under {@code rotateY(180 - yaw)} and GeckoLib rigs add a 180° roll, so the ship
 * rotation is {@code yawQ · RIG_ROLL_FLIP · yawQ⁻¹ · boneRotation} with {@code yawQ = rotateY(180° - getYRot())}.
 * TODO: uses getYRot, not yBodyRot; velocity has no smoothing.
 */
public final class LivingPlatformServerTransformProvider implements ServerShipTransformProvider {

    private static final double TICK_SECONDS = 1.0 / 20.0;

    /** Self-inverse; shared, so copy before mutating. */
    private static final Quaterniond RIG_ROLL_FLIP = new Quaterniond().rotateZ(Math.PI);

    private final UUID entityUuid;
    private final String boneName;
    private final ServerLevel level;
    private final Vector3d seedCenter;

    @Nullable
    private Vector3d lastPosition;
    @Nullable
    private Quaterniond lastRotation;

    public LivingPlatformServerTransformProvider(UUID entityUuid, String boneName, ServerLevel level, ServerShip ship,
                                                  LivingPlatformSeedShape seedShape) {
        this.entityUuid = entityUuid;
        this.boneName = boneName;
        this.level = level;
        Vector3i centerBlock = ship.getChunkClaim().getCenterBlockCoordinates(VSGameUtilsKt.getYRange(level), new Vector3i());
        // Not centerBlock + 0.5: the seed loop centers the volume on the integer centerBlock.
        this.seedCenter = new Vector3d(
                centerBlock.x + seedShape.centroidOffsetX(),
                centerBlock.y + seedShape.centroidOffsetY(),
                centerBlock.z + seedShape.centroidOffsetZ());
    }

    @Override
    @Nullable
    public NextTransformAndVelocityData provideNextTransformAndVelocity(ShipTransform prevShipTransform,
                                                                          ShipTransform shipTransform) {
        Entity entity = level.getEntity(entityUuid);
        if (entity == null) return null;

        Matrix4f boneWorld = MahApi.getLivingPlatformBoneTransform(entity, boneName);
        if (boneWorld == null) return null; // no data: hold last transform

        Vector3f translationF = new Vector3f();
        boneWorld.getTranslation(translationF);
        Quaternionf rotationF = new Quaternionf();
        boneWorld.getUnnormalizedRotation(rotationF);

        Vector3d position = new Vector3d(translationF.x, translationF.y, translationF.z);
        // No world-space offset here; it would be rotation-blind. positionInModel handles it.

        // VS2 requires |length² - 1| < 1e-12, tighter than float, so normalize as doubles.
        Quaterniond rotation = new Quaterniond(rotationF.x, rotationF.y, rotationF.z, rotationF.w);
        rotation.normalize();
        if (!isFinite(rotation)) {
            // A degenerate transform here would crash the server tick loop.
            Mah.LOGGER.warn("[KHP] Living platform: degenerate rotation extracted for entity {} bone \"{}\" "
                    + "— skipping this tick's transform update rather than risk a server crash.", entityUuid, boneName);
            return null;
        }

        double yawRad = Math.toRadians(180.0 - entity.getYRot());
        Quaterniond yawQ = new Quaterniond().rotateY(yawRad);
        Quaterniond yawQInverse = new Quaterniond(yawQ).conjugate();
        Quaterniond correction = new Quaterniond(yawQ).mul(RIG_ROLL_FLIP).mul(yawQInverse);
        rotation = new Quaterniond(correction).mul(rotation);
        rotation.normalize();

        Vector3d nextVel = new Vector3d();
        Vector3d nextOmega = new Vector3d();
        if (lastPosition != null && lastRotation != null) {
            position.sub(lastPosition, nextVel).mul(1.0 / TICK_SECONDS);

            Quaterniond deltaRotation = new Quaterniond(rotation).mul(new Quaterniond(lastRotation).conjugate());
            deltaRotation.normalize();
            double angle = 2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, deltaRotation.w)));
            double sinHalfAngle = Math.sqrt(1.0 - deltaRotation.w * deltaRotation.w);
            if (sinHalfAngle > 1.0e-6 && angle > 1.0e-9) {
                nextOmega.set(deltaRotation.x, deltaRotation.y, deltaRotation.z)
                        .mul(1.0 / sinHalfAngle)
                        .mul(angle / TICK_SECONDS);
            }
        }

        lastPosition = new Vector3d(position);
        lastRotation = new Quaterniond(rotation);

        BodyTransform nextTransform = prevShipTransform.toBuilder()
                .position(position)
                .rotation(rotation)
                .positionInModel(seedCenter)
                .build();

        return new NextTransformAndVelocityData(nextTransform, nextVel, nextOmega);
    }

    /** Finite and unit-length within VS2's 1e-12 tolerance. */
    private static boolean isFinite(Quaterniond q) {
        return Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w)
                && Math.abs(q.lengthSquared() - 1.0) < 1.0e-9;
    }
}
