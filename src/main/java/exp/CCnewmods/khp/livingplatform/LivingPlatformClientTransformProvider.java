package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.ClientShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client counterpart of {@link LivingPlatformServerTransformProvider}, computed the same way from the client's own
 * data. The client captures an interpolated pose every frame, so {@code partialTick} isn't needed.
 */
public final class LivingPlatformClientTransformProvider implements ClientShipTransformProvider {

    private static final Quaterniond RIG_ROLL_FLIP = new Quaterniond().rotateZ(Math.PI);

    private final UUID entityUuid;
    private final String boneName;
    private final Vector3d seedCenter;

    public LivingPlatformClientTransformProvider(UUID entityUuid, String boneName, ClientShip ship) {
        this.entityUuid = entityUuid;
        this.boneName = boneName;
        Minecraft mc = Minecraft.getInstance();
        Vector3i centerBlock = ship.getChunkClaim().getCenterBlockCoordinates(
                VSGameUtilsKt.getYRange(mc.level), new Vector3i());
        // Not centerBlock + 0.5 (as on the server).
        this.seedCenter = new Vector3d(centerBlock.x, centerBlock.y, centerBlock.z);
    }

    @Override
    @Nullable
    public BodyTransform provideNextTransform(ShipTransform prevShipTransform, ShipTransform shipTransform,
                                               ShipTransform latestNetworkTransform) {
        return resolve(prevShipTransform);
    }

    @Override
    @Nullable
    public BodyTransform provideNextRenderTransform(ShipTransform prevShipTransform, ShipTransform shipTransform,
                                                      double partialTick) {
        return resolve(prevShipTransform);
    }

    @Nullable
    private BodyTransform resolve(ShipTransform baseTransform) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        // ClientLevel only has getEntity(int): try the snapshot's network id, then scan.
        Entity entity = null;
        EntityHitboxSnapshot snap = HitboxStore.get(entityUuid);
        if (snap != null) {
            entity = mc.level.getEntity(snap.entityId());
        }
        if (entity == null) {
            for (Entity candidate : mc.level.entitiesForRendering()) {
                if (entityUuid.equals(candidate.getUUID())) {
                    entity = candidate;
                    break;
                }
            }
        }
        if (entity == null) return null;

        Matrix4f boneWorld = MahApi.getLivingPlatformBoneTransform(entity, boneName);
        if (boneWorld == null) return null;

        Vector3f translation = new Vector3f();
        boneWorld.getTranslation(translation);
        Quaternionf rotationF = new Quaternionf();
        boneWorld.getUnnormalizedRotation(rotationF);

        // VS2 requires |length² - 1| < 1e-12, tighter than float, so normalize as doubles.
        Quaterniond rotation = new Quaterniond(rotationF.x, rotationF.y, rotationF.z, rotationF.w);
        rotation.normalize();
        if (!isFinite(rotation)) {
            return null;
        }

        double yawRad = Math.toRadians(180.0 - entity.getYRot());
        Quaterniond yawQ = new Quaterniond().rotateY(yawRad);
        Quaterniond yawQInverse = new Quaterniond(yawQ).conjugate();
        Quaterniond correction = new Quaterniond(yawQ).mul(RIG_ROLL_FLIP).mul(yawQInverse);
        rotation = new Quaterniond(correction).mul(rotation);
        rotation.normalize();

        return baseTransform.toBuilder()
                .position(new org.joml.Vector3d(translation.x, translation.y, translation.z))
                .rotation(rotation)
                .positionInModel(seedCenter)
                .build();
    }

    private static boolean isFinite(Quaterniond q) {
        return Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w)
                && Math.abs(q.lengthSquared() - 1.0) < 1.0e-9;
    }
}
