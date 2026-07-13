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
 * Server-authoritative {@link ServerShipTransformProvider} for one living-platform
 * anchor — see {@code KHP_Phase2E_Design.md} §6.3/§6.4.
 *
 * <p>Reads the anchor bone's current world transform via
 * {@link MahApi#getLivingPlatformBoneTransform}, which already implements the
 * live→blueprint fallback chain (§3). If that returns {@code null} (genuinely no
 * data — {@link LivingPlatformShipManager#tickFreezeState} is responsible for
 * halting the entity in this case, not this class), this provider returns
 * {@code null} for the tick, which VS2 treats as "no update" — the ship simply
 * holds its last transform, matching the entity being frozen in place at the
 * same instant.
 *
 * <p>{@code nextVel}/{@code nextOmega} are derived from the bone's frame-to-frame
 * delta (Q2E-4: real momentum handoff, not purely kinematic) using a fixed
 * {@code 1/20s} tick assumption — VS2 calls
 * {@link #provideNextTransformAndVelocity} once per physics tick, so this matches
 * the server tick rate directly. Angular velocity is derived from the shortest-arc
 * relative rotation between this tick's and last tick's orientation, converted to
 * an axis-angle rate. Not yet playtested for feel — the fixed-dt assumption and
 * the raw per-tick delta (no smoothing) are the first things to revisit if the
 * platform feels jerky under fast animation.
 *
 * <p><b>{@code positionInModel} set explicitly</b> (§18/§19 — fixes a consistent
 * 0.5-block offset seen in-game on all three axes). Previously left unset, which
 * meant it silently inherited whatever pivot {@code createNewShipAtBlock} defaulted
 * to at creation time — an assumption that was never actually verified and turned
 * out to not line up with where {@link LivingPlatformShipManager#seedVolume}
 * actually centers the seed blocks. Now computed the same way {@code seedVolume}
 * computes its own center ({@code ChunkClaim.getCenterBlockCoordinates}, a real
 * {@code BlockPos}-style integer whose own true geometric center is {@code +0.5}
 * on every axis — standard Minecraft block-grid convention, a block at integer
 * {@code x} spans {@code [x, x+1)}) and cached once at construction, since a
 * ship's chunk claim never changes after creation.
 *
 * <p>One instance per anchor, held for the lifetime of that anchor's ship — the
 * {@code lastPosition}/{@code lastRotation} fields are safe as plain (non-atomic)
 * fields because VS2 only ever calls this from the single server/physics tick
 * thread, same threading assumption {@code HitboxStore}'s own javadoc already
 * documents for integrated-server reads.
 */
public final class LivingPlatformServerTransformProvider implements ServerShipTransformProvider {

    private static final double TICK_SECONDS = 1.0 / 20.0;

    private final UUID entityUuid;
    private final String boneName;
    private final ServerLevel level;
    private final Vector3d seedCenter;

    @Nullable
    private Vector3d lastPosition;
    @Nullable
    private Quaterniond lastRotation;

    public LivingPlatformServerTransformProvider(UUID entityUuid, String boneName, ServerLevel level, ServerShip ship) {
        this.entityUuid = entityUuid;
        this.boneName = boneName;
        this.level = level;
        Vector3i centerBlock = ship.getChunkClaim().getCenterBlockCoordinates(VSGameUtilsKt.getYRange(level), new Vector3i());
        // NOT centerBlock + 0.5 — see KHP_Phase2E_Design.md §23. The seedVolume loop
        // (-halfW .. width-halfW) already centers the whole symmetric volume exactly
        // ON the raw integer centerBlock; +0.5 would be correct for a single block
        // cell's own center, but this is the center of the whole multi-block volume,
        // which is a different quantity. That extra +0.5 was itself the bug.
        this.seedCenter = new Vector3d(centerBlock.x, centerBlock.y, centerBlock.z);
    }

    @Override
    @Nullable
    public NextTransformAndVelocityData provideNextTransformAndVelocity(ShipTransform prevShipTransform,
                                                                          ShipTransform shipTransform) {
        Entity entity = level.getEntity(entityUuid);
        if (entity == null) return null;

        Matrix4f boneWorld = MahApi.getLivingPlatformBoneTransform(entity, boneName);
        if (boneWorld == null) return null; // no data at all — hold last transform, entity is being frozen elsewhere

        Vector3f translationF = new Vector3f();
        boneWorld.getTranslation(translationF);
        Quaternionf rotationF = new Quaternionf();
        boneWorld.getUnnormalizedRotation(rotationF);

        Vector3d position = new Vector3d(translationF.x, translationF.y, translationF.z);
        // No manual offset here — see KHP_Phase2E_Design.md §23. The earlier -0.5
        // world-space correction was rotation-blind (fixed one axis by coincidence,
        // broke another depending on facing) because it's applied in WORLD space,
        // not local space. The real fix is entirely in positionInModel above, which
        // is correctly rotated by the ship's own orientation before combining with
        // this raw, uncorrected bone position.

        // VS2's BodyTransformImpl requires |lengthSquared() - 1.0| < 1e-12 (bytecode-confirmed
        // this session) — far tighter than float precision (~1e-7) can ever satisfy. Normalizing
        // must happen AFTER converting to Quaterniond, not on the float quaternion beforehand —
        // that was the first fix's mistake, and the crash recurred with a quaternion that looked
        // unit at 4-significant-figure precision but wasn't within 1e-12. See
        // KHP_Phase2E_Design.md §12.
        Quaterniond rotation = new Quaterniond(rotationF.x, rotationF.y, rotationF.z, rotationF.w);
        rotation.normalize();
        if (!isFinite(rotation)) {
            // Should be unreachable now, but a crash here previously took down the whole server
            // tick loop, so this stays as insurance against some other future degenerate
            // transform rather than trusting the upstream data unconditionally a second time.
            Mah.LOGGER.warn("[KHP] Living platform: degenerate rotation extracted for entity {} bone \"{}\" "
                    + "— skipping this tick's transform update rather than risk a server crash.", entityUuid, boneName);
            return null;
        }

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

    /** True if {@code q}'s components are all finite and its length is within VS2's own 1e-12 tolerance of unit. */
    private static boolean isFinite(Quaterniond q) {
        return Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w)
                && Math.abs(q.lengthSquared() - 1.0) < 1.0e-9;
    }
}
