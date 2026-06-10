package net.nuggz.lotrmc.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.PatrolWaypoint;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: player changed squad orders from SquadOrdersScreen.
 *
 * Carries the full updated SquadOrderData so the server can apply
 * all changes atomically.
 */
public record SetSquadOrderPacket(
        BlockPos pitPos,
        OrcOrder order,
        @Nullable BlockPos guardCenter,
        int guardRadius,
        List<PatrolWaypoint> waypoints,
        int watchPostWaitTicks
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetSquadOrderPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "set_squad_order"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, SetSquadOrderPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    SetSquadOrderPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(pitPos.asLong());
        buf.writeUtf(order.name());
        buf.writeBoolean(guardCenter != null);
        if (guardCenter != null) buf.writeLong(guardCenter.asLong());
        buf.writeInt(guardRadius);
        buf.writeInt(waypoints.size());
        for (PatrolWaypoint wp : waypoints) wp.encode(buf);
        buf.writeInt(watchPostWaitTicks);
    }

    public static SetSquadOrderPacket decode(FriendlyByteBuf buf) {
        BlockPos pitPos = BlockPos.of(buf.readLong());
        OrcOrder order;
        try { order = OrcOrder.valueOf(buf.readUtf()); }
        catch (Exception e) { order = OrcOrder.GUARD_PIT; }
        BlockPos guardCenter = buf.readBoolean() ? BlockPos.of(buf.readLong()) : null;
        int guardRadius      = buf.readInt();
        int wpCount          = buf.readInt();
        List<PatrolWaypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < wpCount; i++) waypoints.add(PatrolWaypoint.decode(buf));
        int waitTicks = buf.readInt();
        return new SetSquadOrderPacket(pitPos, order, guardCenter,
                guardRadius, waypoints, waitTicks);
    }

    // -------------------------------------------------------------------------
    // Server handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel level = player.serverLevel();
            MudlandsChunkData mud = MudlandsChunkData.get(level);

            // Validate faction
            boolean isFaction = player.getUUID().equals(mud.getSauronUUID())
                    || mud.isLieutenant(player.getUUID());
            if (!isFaction) {
                player.sendSystemMessage(Component.literal(
                        "§cOnly Sauron or his lieutenants may issue orders."));
                return;
            }

            // Find pit
            if (!(level.getBlockEntity(pitPos) instanceof MudpitBlockEntity pit)) {
                player.sendSystemMessage(Component.literal("§cPit not found."));
                return;
            }

            // Apply all order data
            SquadOrderData orders = pit.getSquadOrders();
            orders.setOrder(order);
            orders.setWatchPostWaitTicks(watchPostWaitTicks);

            if (order == OrcOrder.GUARD_AREA && guardCenter != null) {
                orders.setGuardArea(guardCenter, guardRadius);
            }

            if (order == OrcOrder.PATROL) {
                orders.setWaypoints(waypoints);
            }

            pit.setChanged();

            player.sendSystemMessage(Component.literal(
                    "§8Squad order set: §7" + orders.getDisplayName()));
        });
    }
}