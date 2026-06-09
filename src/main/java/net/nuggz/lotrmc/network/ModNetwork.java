package net.nuggz.lotrmc.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // Client → Server
        registrar.playToServer(
                BrandingPacket.TYPE,
                BrandingPacket.STREAM_CODEC,
                BrandingPacket::handle);

        // Server → Client
        registrar.playToClient(
                WarTableOpenPacket.TYPE,
                WarTableOpenPacket.STREAM_CODEC,
                WarTableOpenPacket::handle);
    }

    /** Called from WarTableBlock.useWithoutItem() to open the UI for a player. */
    public static void openWarTable(ServerPlayer player, BlockPos pos) {
        WarTableOpenPacket.send(player, pos);
    }
}