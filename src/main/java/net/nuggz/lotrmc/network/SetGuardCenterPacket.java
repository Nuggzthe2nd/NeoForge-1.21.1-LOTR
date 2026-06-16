package net.nuggz.lotrmc.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

/**
 * Client → Server: player picked a guard area center block while in
 * GuardCenterPlacementMode.
 */
public record SetGuardCenterPacket(
        BlockPos pitPos,
        BlockPos centerPos
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetGuardCenterPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "set_guard_center"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, SetGuardCenterPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    SetGuardCenterPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(pitPos.asLong());
        buf.writeLong(centerPos.asLong());
    }

    public static SetGuardCenterPacket decode(FriendlyByteBuf buf) {
        return new SetGuardCenterPacket(
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

            pit.getSquadOrders().setGuardCenter(centerPos);
            pit.setChanged();

            player.sendSystemMessage(Component.literal(
                    "§7Guard area center set to §f" + centerPos.toShortString()
                            + "§7. Open the pit to set the radius."));
        });
    }
}
