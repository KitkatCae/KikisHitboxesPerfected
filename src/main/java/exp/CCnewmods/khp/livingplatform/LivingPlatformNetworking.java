package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Owns the S2C channel for {@link LivingPlatformSyncPacket} — see that class's
 * javadoc for why this exists instead of relying on VS2's own ship sync.
 *
 * <p>The handler calls straight into {@link ClientLivingPlatformRegistry} with no
 * {@code DistExecutor}/{@code Dist} guard: this channel only ever sends S2C, so
 * the handler body genuinely never executes on a dedicated server (nothing is
 * delivered there to trigger it) — and {@code DistExecutor} itself isn't present
 * in this Forge version's jar (confirmed absent via search this session; the
 * class was dropped from modern Forge in favor of exactly this kind of lazy
 * method-body class resolution). If this assumption is wrong, the failure mode
 * is a dedicated-server crash on the very first sync packet, which would show up
 * immediately in testing, not silently.
 */
public final class LivingPlatformNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Mah.MODID, "living_platform"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    public static void register() {
        CHANNEL.registerMessage(0, LivingPlatformSyncPacket.class,
                LivingPlatformSyncPacket::encode,
                LivingPlatformSyncPacket::decode,
                LivingPlatformNetworking::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handle(LivingPlatformSyncPacket packet, java.util.function.Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> ClientLivingPlatformRegistry.onSyncReceived(packet));
        ctx.setPacketHandled(true);
    }

    private LivingPlatformNetworking() {
    }
}
