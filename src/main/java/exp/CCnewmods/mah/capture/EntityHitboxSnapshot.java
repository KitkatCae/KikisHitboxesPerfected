package exp.CCnewmods.mah.capture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Immutable snapshot of one entity's captured hitbox geometry.
 *
 * <p>Safe to hand across threads: all fields are final and {@link #parts} is an
 * unmodifiable copy whose elements honour the {@link PartOBB} immutability contract.
 *
 * @param entityKey          the entity key (usually the EntityType id; corpses key by
 *                           mob type via a registered EntityKeyProvider) — identifies
 *                           which {@link BoneTable} resolves {@link PartOBB#boneId()}.
 * @param entityId           network entity id, for cheap {@code Level.getEntity(int)}
 *                           lookups by consumers that only hold the snapshot.
 * @param anchorPos          world position of the render origin at capture time
 *                           (lerped entity position + renderer offset).
 * @param capturedEntityPos  {@code entity.position()} at capture time. Consumers track
 *                           the entity between captures via
 *                           {@code entity.position() - capturedEntityPos} added to
 *                           {@code anchorPos}.
 * @param bodyYaw            body yaw at capture (degrees) — used by later phases to
 *                           re-rotate blueprint geometry; recorded for live snapshots
 *                           for diagnostics/consistency.
 * @param gameTime           level game time at capture; staleness is judged against
 *                           {@code MahConfig.staleSnapshotTicks}.
 * @param parts              captured OBBs, unmodifiable.
 * @param source             fidelity class of this snapshot.
 */
public record EntityHitboxSnapshot(ResourceLocation entityKey,
                                   int entityId,
                                   Vec3 anchorPos,
                                   Vec3 capturedEntityPos,
                                   float bodyYaw,
                                   long gameTime,
                                   List<PartOBB> parts,
                                   SnapshotSource source) {

    public EntityHitboxSnapshot {
        parts = List.copyOf(parts);
    }

    /** True if this snapshot is older than the given staleness window. */
    public boolean isStale(long now, int staleTicks) {
        return now - gameTime > staleTicks;
    }
}
