package net.nuggz.lotrmc.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers all client→server and server→client packets for the mod.
 *
 * To add a new packet later:
 *   1. Create a record implementing CustomPacketPayload (like BrandingPacket)
 *   2. Add a registrar.playToServer() or registrar.playToClient() line here
 *
 * Wired into the mod event bus via modEventBus.addListener(ModNetwork::register) in LotrMC.
 */
public class ModNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        // "1" is the network protocol version — increment if you make
        // breaking changes to existing packets so mismatched clients are rejected.
        PayloadRegistrar registrar = event.registrar("1");

        // Client → Server: player confirmed a name in BrandingScreen
        registrar.playToServer(
                BrandingPacket.TYPE,
                BrandingPacket.STREAM_CODEC,
                BrandingPacket::handle
        );

        // Future packets go here, e.g.:
        // registrar.playToServer(CommandOrderPacket.TYPE, CommandOrderPacket.STREAM_CODEC, CommandOrderPacket::handle);
        // registrar.playToClient(RaidResultPacket.TYPE, RaidResultPacket.STREAM_CODEC, RaidResultPacket::handle);
    }
}