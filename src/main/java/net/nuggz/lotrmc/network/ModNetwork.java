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

        registrar.playToServer(
                MapChunkRequestPacket.TYPE,
                MapChunkRequestPacket.STREAM_CODEC,
                MapChunkRequestPacket::handle);

        registrar.playToServer(
                RaidStartPacket.TYPE,
                RaidStartPacket.STREAM_CODEC,
                RaidStartPacket::handle);

        // Server → Client
        registrar.playToClient(
                WarTableOpenPacket.TYPE,
                WarTableOpenPacket.STREAM_CODEC,
                WarTableOpenPacket::handle);

        registrar.playToClient(
                MapChunkResponsePacket.TYPE,
                MapChunkResponsePacket.STREAM_CODEC,
                MapChunkResponsePacket::handle);

        registrar.playToClient(
                RaidResultPacket.TYPE,
                RaidResultPacket.STREAM_CODEC,
                RaidResultPacket::handle);
    }

    public static void openWarTable(ServerPlayer player, BlockPos pos) {
        WarTableOpenPacket.send(player, pos);
    }
}