package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
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
 * Client-side counterpart to {@link LivingPlatformServerTransformProvider} — see
 * {@code KHP_Phase2E_Design.md} §2.2/§6.3. VS2's {@code provideNextRenderTransform}
 * is called every rendered frame with a real {@code partialTick}, which is exactly
 * the sub-tick-smooth hook the "tentacles sway, blocks sway with them" requirement
 * needs — no bespoke interpolation system required, since the client render thread
 * is already producing a fresh interpolated bone pose every frame for any tracked
 * mob in range ({@code HitboxCaptureManager}), and
 * {@link MahApi#getLivingPlatformBoneTransform} reads whatever the most recent one
 * is. {@code partialTick} itself isn't consulted directly here — it doesn't need to
 * be, since the pose it would otherwise be used to interpolate between is already
 * pre-interpolated for us by the same render-thread capture that feeds MAH's own
 * debug wireframe and raycasts.
 *
 * <p>{@code provideNextTransform} (the once-per-tick reconciliation-against-network
 * hook, not per-frame) uses the same resolution — deliberately not trying to be any
 * smarter than the render hook, since both ultimately read the same live snapshot.
 *
 * <p><b>{@code positionInModel} set explicitly</b> — same fix and same reasoning as
 * {@link LivingPlatformServerTransformProvider}'s own javadoc (§18/§19, the
 * 0.5-block offset). {@code ChunkClaim} is a real synced ship property, so this
 * side computes the exact same value independently rather than needing it passed
 * over the custom sync packet.
 */
public final class LivingPlatformClientTransformProvider implements ClientShipTransformProvider {

    private final UUID entityUuid;
    private final String boneName;
    private final Vector3d seedCenter;

    public LivingPlatformClientTransformProvider(UUID entityUuid, String boneName, ClientShip ship) {
        this.entityUuid = entityUuid;
        this.boneName = boneName;
        Minecraft mc = Minecraft.getInstance();
        Vector3i centerBlock = ship.getChunkClaim().getCenterBlockCoordinates(
                VSGameUtilsKt.getYRange(mc.level), new Vector3i());
        // NOT centerBlock + 0.5 — see KHP_Phase2E_Design.md §23 (same fix as server side).
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

        // ClientLevel has no public getEntity(UUID) — only getEntity(int) (confirmed
        // via javap). Fast path: HitboxStore already keys snapshots by UUID and every
        // snapshot carries the entity's network id (EntityHitboxSnapshot#entityId,
        // documented there for exactly this kind of cheap lookup) — free almost
        // always, since anything worth driving a visible swaying platform for is
        // also actively being captured for rendering. Fallback: linear scan of
        // entitiesForRendering() (public, confirmed) for the rare case an entity is
        // tracked by the ship but not currently in this client's render set.
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

        // VS2's BodyTransformImpl requires |lengthSquared() - 1.0| < 1e-12 (bytecode-confirmed
        // this session) — far tighter than float precision (~1e-7) can ever satisfy on its own.
        // Converting to Quaterniond first and normalizing THAT (double precision) is what
        // actually closes the gap; normalizing the float quaternion alone (what the previous
        // fix did) wasn't enough — the crash recurred with a quaternion that LOOKED unit at
        // 4-significant-figure precision but wasn't within 1e-12. See KHP_Phase2E_Design.md §12.
        org.joml.Quaterniond rotation = new org.joml.Quaterniond(rotationF.x, rotationF.y, rotationF.z, rotationF.w);
        rotation.normalize();
        if (!isFinite(rotation)) {
            // Same insurance policy as LivingPlatformServerTransformProvider — see that
            // class's javadoc for why, and KHP_Phase2E_Design.md §12 for the actual bug
            // this is guarding against (should be fixed now, kept as a backstop).
            return null;
        }

        return baseTransform.toBuilder()
                .position(new org.joml.Vector3d(translation.x, translation.y, translation.z))
                .rotation(rotation)
                .positionInModel(seedCenter)
                .build();
    }

    private static boolean isFinite(org.joml.Quaterniond q) {
        return Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w)
                && Math.abs(q.lengthSquared() - 1.0) < 1.0e-9;
    }
}
