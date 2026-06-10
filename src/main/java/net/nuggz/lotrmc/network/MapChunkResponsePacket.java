package net.nuggz.lotrmc.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.client.screen.WarTableScreen;
import net.nuggz.lotrmc.warmap.ChunkMapEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: delivers chunk map entries requested by MapChunkRequestPacket.
 *
 * Also carries the fog of war discovered set as a list of packed longs,
 * so the client always knows which chunks to render as fog.
 */
public record MapChunkResponsePacket(List<ChunkMapEntry> entries) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapChunkResponsePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "map_chunk_response"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, MapChunkResponsePacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    MapChunkResponsePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(entries.size());
        for (ChunkMapEntry entry : entries) entry.encode(buf);
    }

    public static MapChunkResponsePacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<ChunkMapEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(ChunkMapEntry.decode(buf));
        return new MapChunkResponsePacket(entries);
    }

    // -------------------------------------------------------------------------
    // Client handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> updateClientCache(entries));
    }

    @OnlyIn(Dist.CLIENT)
    private static void updateClientCache(List<ChunkMapEntry> entries) {
        if (Minecraft.getInstance().screen instanceof WarTableScreen screen) {
            screen.receiveMapChunks(entries);
        }
    }
}