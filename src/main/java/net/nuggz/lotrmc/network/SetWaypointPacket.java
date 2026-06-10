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
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

/**
 * Client → Server: player placed or upgraded a waypoint while in
 * WaypointPlacementMode.
 *
 * The server adds the waypoint to the pit's patrol path and confirms
 * back to the player via chat.
 */
public record SetWaypointPacket(
        BlockPos pitPos,
        BlockPos waypointPos
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetWaypointPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "set_waypoint"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, SetWaypointPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    SetWaypointPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(pitPos.asLong());
        buf.writeLong(waypointPos.asLong());
    }

    public static SetWaypointPacket decode(FriendlyByteBuf buf) {
        return new SetWaypointPacket(
                BlockPos.of(buf.readLong()),
                BlockPos.of(buf.readLong()));
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel level = player.serverLevel();
            MudlandsChunkData mud = MudlandsChunkData.get(level);

            boolean isFaction = player.getUUID().equals(mud.getSauronUUID())
                    || mud.isLieutenant(player.getUUID());
            if (!isFaction) return;

            if (!(level.getBlockEntity(pitPos) instanceof MudpitBlockEntity pit)) return;

            var orders = pit.getSquadOrders();
            boolean wasWatchPost = orders.getWaypoints().stream()
                    .anyMatch(wp -> wp.pos.equals(waypointPos) && wp.isWatchPost());

            orders.addOrUpgradeWaypoint(waypointPos);
            pit.setChanged();

            // Tell the player what was placed
            boolean isNowWatchPost = orders.getWaypoints().stream()
                    .anyMatch(wp -> wp.pos.equals(waypointPos) && wp.isWatchPost());

            int total = orders.getWaypoints().size();
            if (isNowWatchPost && !wasWatchPost) {
                player.sendSystemMessage(Component.literal(
                        "§6Watch post set at §e" + waypointPos.toShortString()
                                + " §8(" + total + "/" + orders.MAX_WAYPOINTS + ")"));
            } else {
                player.sendSystemMessage(Component.literal(
                        "§7Waypoint added at §f" + waypointPos.toShortString()
                                + " §8(" + total + "/" + orders.MAX_WAYPOINTS + ")"));
            }

            // Auto-set order to PATROL when first waypoints are placed
            if (total >= 2 && orders.getCurrentOrder() != OrcOrder.PATROL) {
                orders.setOrder(OrcOrder.PATROL);
                player.sendSystemMessage(Component.literal(
                        "§8Patrol path ready. Squad order set to Patrol."));
            }
        });
    }
}