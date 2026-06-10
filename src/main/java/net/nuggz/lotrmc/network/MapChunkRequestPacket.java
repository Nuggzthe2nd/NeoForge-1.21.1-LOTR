package net.nuggz.lotrmc.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.warmap.ChunkMapEntry;
import net.nuggz.lotrmc.warmap.WarMapCache;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: request map data for chunks entering the viewport.
 *
 * Sent when the player drags the war map and new chunks scroll into view.
 * Server responds with MapChunkResponsePacket containing entries for
 * all discovered chunks in the request list.
 *
 * Rate limited server-side to MAX_SCANS_PER_REQUEST new scans per packet.
 */
public record MapChunkRequestPacket(List<long[]> requestedChunks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapChunkRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "map_chunk_request"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, MapChunkRequestPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    MapChunkRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(requestedChunks.size());
        for (long[] xz : requestedChunks) {
            buf.writeInt((int) xz[0]);
            buf.writeInt((int) xz[1]);
        }
    }

    public static MapChunkRequestPacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<long[]> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chunks.add(new long[]{ buf.readInt(), buf.readInt() });
        }
        return new MapChunkRequestPacket(chunks);
    }

    // -------------------------------------------------------------------------
    // Server handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel level     = player.serverLevel();
            WarMapCache cache     = WarMapCache.get(level);
            MudlandsChunkData mud = MudlandsChunkData.get(level);

            List<ChunkMapEntry> results =
                    cache.getOrScanBatch(level, requestedChunks, mud);

            if (!results.isEmpty()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new MapChunkResponsePacket(results));
            }
        });
    }
}
