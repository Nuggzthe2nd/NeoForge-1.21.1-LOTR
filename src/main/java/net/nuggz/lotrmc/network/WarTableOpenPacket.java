package net.nuggz.lotrmc.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.client.screen.WarTableScreen;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.wartable.WarTableData;
import net.nuggz.lotrmc.wartable.WarTableData.PitEntry;
import net.nuggz.lotrmc.wartable.WarTableData.OrcEntry;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: opens the War Table screen.
 *
 * Built on the server by querying MudlandsChunkData and all MudpitBlockEntities,
 * then sent to the requesting player. The client receives a WarTableData snapshot
 * and opens WarTableScreen with it.
 */
public record WarTableOpenPacket(WarTableData data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WarTableOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "war_table_open"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, WarTableOpenPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.data().encode(buf),
                    buf -> new WarTableOpenPacket(WarTableData.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Server-side builder — collects all pit data into a WarTableData snapshot
    // -------------------------------------------------------------------------

    public static void send(ServerPlayer player, BlockPos warTablePos) {
        ServerLevel level = player.serverLevel();
        MudlandsChunkData mudData = MudlandsChunkData.get(level);

        List<PitEntry> entries = new ArrayList<>();
        int pitIndex = 0;

        // Iterate all converted chunks and find mudpit block entities
        for (long packedChunk : mudData.getAllConvertedChunkPositions()) {
            net.minecraft.world.level.ChunkPos chunkPos =
                    new net.minecraft.world.level.ChunkPos(packedChunk);

            net.minecraft.world.level.chunk.LevelChunk chunk =
                    level.getChunk(chunkPos.x, chunkPos.z);

            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof MudpitBlockEntity pit)) continue;

                entries.add(buildPitEntry(level, pit, pitIndex++));
            }
        }

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new WarTableOpenPacket(new WarTableData(entries)));
    }

    private static PitEntry buildPitEntry(ServerLevel level,
                                          MudpitBlockEntity pit, int index) {
        // Leader data
        String leaderName = null;
        int str = 0, tac = 0, pre = 0;
        UUID leaderUUID = pit.getLeaderUUID();

        if (leaderUUID != null) {
            Entity entity = level.getEntity(leaderUUID);
            if (entity instanceof OrcEntity orc && orc.getLeaderData() != null) {
                leaderName = orc.getCustomName() != null
                        ? orc.getCustomName().getString() : "Unknown";
                str = orc.getLeaderData().strength;
                tac = orc.getLeaderData().tactics;
                pre = orc.getLeaderData().presence;
            }
        }

        // Orc list
        List<OrcEntry> orcs = new ArrayList<>();
        int orcNumber = 1;
        for (UUID orcUUID : pit.getTrackedOrcUUIDs()) {
            Entity entity = level.getEntity(orcUUID);
            if (!(entity instanceof OrcEntity orc)) continue;

            String name = orc.getCustomName() != null
                    ? orc.getCustomName().getString()
                    : "Orc #" + orcNumber;
            orcs.add(new OrcEntry(orcUUID, name, orc.getScarCount(), orc.isLeader()));
            orcNumber++;
        }

        // Sort: leader first, then by scar count descending
        orcs.sort((a, b) -> {
            if (a.isLeader) return -1;
            if (b.isLeader) return 1;
            return Integer.compare(b.scarCount, a.scarCount);
        });

        return new PitEntry(
                index,
                pit.getCapacity(),
                pit.getBiomass(),
                pit.isGestating(),
                pit.getGestationPercent(),
                pit.isRaiding(),
                pit.getDefaultOrder(),
                leaderName, str, tac, pre,
                orcs);
    }

    // -------------------------------------------------------------------------
    // Client-side handler
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> openScreen(data));
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(WarTableData data) {
        Minecraft.getInstance().setScreen(new WarTableScreen(data));
    }
}