package exp.CCnewmods.khp.livingplatform;

import org.valkyrienskies.mod.common.VSGameUtilsKt;

/** Kept out of LivingPlatformClientBootstrap so that auto-registered class has no VS2 references. */
final class LivingPlatformClientVs2Hooks {

    static void register() {
        VSGameUtilsKt.getVsCore().getShipLoadEventClient().on(e ->
                ClientLivingPlatformRegistry.onShipLoaded(e.getShip()));
        VSGameUtilsKt.getVsCore().getShipUnloadEventClient().on(e ->
                ClientLivingPlatformRegistry.onShipUnloaded(e.getShip()));
    }

    private LivingPlatformClientVs2Hooks() {
    }
}
