package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** S2C channel for {@link LivingPlatformSyncPacket}. */
public final class LivingPlatformNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Mah.MODID, "living_platform"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            // Peers without VS2 won't have this channel at all.
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION))
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
