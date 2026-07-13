package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.valkyrienskies.core.api.attachment.AttachmentHolder;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;

/**
 * Q2E-3: when the anchoring mob dies or is removed, its living-platform ships
 * detach and fall under normal VS2 physics — no special deletion, no static-wreck
 * handling. {@code EntityLeaveLevelEvent} is used as the single detach point
 * (fires for death too, once the dying entity is actually removed from the
 * level) rather than duplicating this in a separate {@code LivingDeathEvent}
 * handler.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LivingPlatformDeathHandler {

    @SubscribeEvent
    static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        Entity entity = event.getEntity();

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId == null || !LivingPlatformStore.isWhitelisted(typeId)) return;

        for (ServerShip ship : LivingPlatformShipManager.findAllShipsForEntity(serverLevel, entity.getUUID())) {
            // Detach: drop the kinematic transform provider so VS2's own physics takes
            // over from wherever the ship currently is — this is what "fall under normal
            // VS2 physics" means in practice, not a special wreck state. Nullability of
            // setTransformProvider's parameter isn't visible to javap on a Kotlin interface
            // (erased in the JVM signature) — inferred from getTransformProvider's own
            // nullable return, not separately bytecode-confirmed; if this turns out to
            // reject null at runtime, the fix is a no-op passthrough provider instead.
            ship.setTransformProvider(null);

            if (ship instanceof LoadedServerShip loaded) {
                ((AttachmentHolder) loaded).removeAttachment(LivingPlatformAttachment.class);
            }
        }

        LivingPlatformShipManager.forgetEntity(entity.getUUID());
    }

    private LivingPlatformDeathHandler() {
    }
}
