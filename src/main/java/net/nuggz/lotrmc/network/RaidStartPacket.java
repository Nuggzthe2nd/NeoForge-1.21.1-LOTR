package net.nuggz.lotrmc.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.warmap.RaidManager;
import net.nuggz.lotrmc.warmap.RaidParty;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: player confirms a raid order from the war table.
 *
 * Carries:
 *   - pitIndex: which pit's orcs are going
 *   - selectedOrcUUIDs: which orcs to send (leader always included)
 *   - targetChunkX/Z: destination chunk
 *   - targetType: POI or FREE_TARGET
 *   - targetLabel: display name of target
 */
public record RaidStartPacket(
        int pitIndex,
        List<UUID> selectedOrcUUIDs,
        int targetChunkX,
        int targetChunkZ,
        RaidParty.TargetType targetType,
        String targetLabel
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaidStartPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "raid_start"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, RaidStartPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    RaidStartPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(pitIndex);
        buf.writeInt(selectedOrcUUIDs.size());
        for (UUID uuid : selectedOrcUUIDs) buf.writeUUID(uuid);
        buf.writeInt(targetChunkX);
        buf.writeInt(targetChunkZ);
        buf.writeUtf(targetType.name());
        buf.writeUtf(targetLabel != null ? targetLabel : "");
    }

    public static RaidStartPacket decode(FriendlyByteBuf buf) {
        int pitIndex = buf.readInt();
        int orcCount = buf.readInt();
        List<UUID> orcs = new ArrayList<>();
        for (int i = 0; i < orcCount; i++) orcs.add(buf.readUUID());
        int tx = buf.readInt();
        int tz = buf.readInt();
        RaidParty.TargetType type;
        try { type = RaidParty.TargetType.valueOf(buf.readUtf()); }
        catch (Exception e) { buf.readUtf(); type = RaidParty.TargetType.FREE_TARGET; }
        String label = buf.readUtf();
        return new RaidStartPacket(pitIndex, orcs, tx, tz, type, label);
    }

    // -------------------------------------------------------------------------
    // Server handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel level     = player.serverLevel();
            MudlandsChunkData mud = MudlandsChunkData.get(level);

            // Validate faction
            boolean isFaction = player.getUUID().equals(mud.getSauronUUID())
                    || mud.isLieutenant(player.getUUID());
            if (!isFaction) {
                player.sendSystemMessage(Component.literal(
                        "§cOnly Sauron or his lieutenants may command raids."));
                return;
            }

            // Find pit by index
            MudpitBlockEntity pit = findPitByIndex(level, mud, pitIndex);
            if (pit == null) {
                player.sendSystemMessage(Component.literal(
                        "§cPit not found."));
                return;
            }

            if (pit.isRaiding()) {
                player.sendSystemMessage(Component.literal(
                        "§cThis pit's warband is already on a raid."));
                return;
            }

            // Validate at least one orc selected
            if (selectedOrcUUIDs.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "§cNo orcs selected."));
                return;
            }

            // Create the raid
            RaidManager manager = RaidManager.get(level);
            RaidParty party = manager.createRaidParty(
                    level, pit, pit.getBlockPos(),
                    selectedOrcUUIDs,
                    targetChunkX, targetChunkZ,
                    targetType, targetLabel);

            if (party == null) {
                player.sendSystemMessage(Component.literal(
                        "§cFailed to dispatch warband — are the orcs still alive?"));
                return;
            }

            player.sendSystemMessage(Component.literal(
                    "§8Warband dispatched to §4" + targetLabel
                            + "§8. " + party.orcUUIDs.size() + " orc(s) sent."));
        });
    }

    private MudpitBlockEntity findPitByIndex(ServerLevel level,
                                             MudlandsChunkData mud, int idx) {
        int i = 0;
        for (long packed : mud.getAllConvertedChunkPositions()) {
            net.minecraft.world.level.ChunkPos cp =
                    new net.minecraft.world.level.ChunkPos(packed);
            var chunk = level.getChunk(cp.x, cp.z);
            for (var be : chunk.getBlockEntities().values()) {
                if (be instanceof MudpitBlockEntity pit) {
                    if (i == idx) return pit;
                    i++;
                }
            }
        }
        return null;
    }
}
