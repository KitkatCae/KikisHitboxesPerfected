package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.SnapshotSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side: fills {@link HitboxStore} from blueprints for entities no client is rendering (always the case on
 * a dedicated server), so server queries like arrows still find them. Never replaces a fresh live snapshot.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintLiveSync {

    @SubscribeEvent
    static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!MahConfig.enabled) return;

        // Staggered so entities don't all resolve on the same tick.
        if ((entity.tickCount + entity.getId()) % Math.max(1, MahConfig.blueprintSyncIntervalTicks) != 0) return;

        double radius = Math.sqrt(MahConfig.blueprintSyncRadiusSqr);
        if (!entity.level().hasNearbyAlivePlayer(entity.getX(), entity.getY(), entity.getZ(), radius)) return;

        ResourceLocation entityKey = MahApi.entityKey(entity);
        boolean baby = entity instanceof AgeableMob ageable && ageable.isBaby();
        if (!BlueprintStore.has(entityKey, baby) && !BlueprintStore.has(entityKey, false)) {
            return;
        }

        if (!shouldSync(entity)) return;

        EntityHitboxSnapshot resolved = BlueprintResolver.resolve(entity);
        if (resolved != null) {
            HitboxStore.put(entity.getUUID(), resolved);
        }
    }

    private static boolean shouldSync(LivingEntity entity) {
        EntityHitboxSnapshot existing = HitboxStore.get(entity.getUUID());
        if (existing == null) return true;
        if (existing.source() != SnapshotSource.LIVE_CLIENT) return true;
        return existing.isStale(entity.level().getGameTime(), MahConfig.staleSnapshotTicks);
    }

    private BlueprintLiveSync() {
    }
}
