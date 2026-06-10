package net.nuggz.lotrmc.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.client.screen.SquadOrdersScreen;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

/**
 * Server → Client: opens the Squad Orders screen.
 * Sent when the player right-clicks the mudpit core block.
 */
public record OpenSquadOrdersPacket(
        BlockPos pitPos,
        SquadOrderData orders,
        int orcCount,
        int maxOrcs,
        boolean hasLeader
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenSquadOrdersPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "open_squad_orders"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, OpenSquadOrdersPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    OpenSquadOrdersPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Server-side builder
    // -------------------------------------------------------------------------

    public static void send(ServerPlayer player, MudpitBlockEntity pit) {
        PacketDistributor.sendToPlayer(player, new OpenSquadOrdersPacket(
                pit.getBlockPos(),
                pit.getSquadOrders(),
                pit.getTrackedOrcUUIDs().size(),
                pit.getMaxPopulation(),
                pit.hasLeader()));
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(pitPos.asLong());
        orders.encode(buf);
        buf.writeInt(orcCount);
        buf.writeInt(maxOrcs);
        buf.writeBoolean(hasLeader);
    }

    public static OpenSquadOrdersPacket decode(FriendlyByteBuf buf) {
        BlockPos pos     = BlockPos.of(buf.readLong());
        SquadOrderData d = SquadOrderData.decode(buf);
        int orcs         = buf.readInt();
        int max          = buf.readInt();
        boolean leader   = buf.readBoolean();
        return new OpenSquadOrdersPacket(pos, d, orcs, max, leader);
    }

    // -------------------------------------------------------------------------
    // Client handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> openScreen(this));
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(OpenSquadOrdersPacket pkt) {
        Minecraft.getInstance().setScreen(new SquadOrdersScreen(
                pkt.pitPos(), pkt.orders(), pkt.orcCount(),
                pkt.maxOrcs(), pkt.hasLeader()));
    }
}