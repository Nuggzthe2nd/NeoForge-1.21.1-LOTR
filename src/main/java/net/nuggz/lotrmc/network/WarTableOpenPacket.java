package net.nuggz.lotrmc.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.client.WarTableMapRenderer;
import net.nuggz.lotrmc.client.screen.WarTableScreen;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.warmap.*;
import net.nuggz.lotrmc.warmap.RaidManager;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.*;

/**
 * Server → Client: opens the War Table screen.
 *
 * Now also sends:
 *   - Initial map data for discovered chunks in the starting viewport
 *   - Discovered chunk set (fog of war state)
 *   - Active raid parties
 *   - Mudlands origin chunk for centering the map
 */
public record WarTableOpenPacket(
        WarTableData tableData,
        List<ChunkMapEntry> initialMapChunks,
        Set<Long> discoveredChunks,
        List<RaidParty> activeRaids,
        int mudlandsOriginChunkX,
        int mudlandsOriginChunkZ
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WarTableOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "war_table_open"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, WarTableOpenPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    WarTableOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Server-side builder
    // -------------------------------------------------------------------------

    public static void send(ServerPlayer player, BlockPos warTablePos) {
        ServerLevel level     = player.serverLevel();
        MudlandsChunkData mud = MudlandsChunkData.get(level);
        WarMapCache mapCache  = WarMapCache.get(level);
        RaidManager raids     = RaidManager.get(level);

        // Build pit entries
        List<WarTableData.PitEntry> entries = new ArrayList<>();
        int pitIndex = 0;
        for (long packed : mud.getAllConvertedChunkPositions()) {
            net.minecraft.world.level.ChunkPos cp =
                    new net.minecraft.world.level.ChunkPos(packed);
            var chunk = level.getChunk(cp.x, cp.z);
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof MudpitBlockEntity pit)) continue;
                entries.add(buildPitEntry(level, pit, pitIndex++));
            }
        }

        // Get mudlands origin
        BlockPos origin = mud.getSauronOrigin();
        int originCX = 0, originCZ = 0;
        if (origin != null) {
            net.minecraft.world.level.ChunkPos originChunk =
                    new net.minecraft.world.level.ChunkPos(origin);
            originCX = originChunk.x;
            originCZ = originChunk.z;
        }

        // Get initial map chunks for the starting viewport
        int vpOriginX = originCX - WarTableMapRenderer.VIEWPORT_CHUNKS / 2;
        int vpOriginZ = originCZ - WarTableMapRenderer.VIEWPORT_CHUNKS / 2;
        List<long[]> viewportChunks = new ArrayList<>();
        for (int vx = 0; vx < WarTableMapRenderer.VIEWPORT_CHUNKS; vx++) {
            for (int vz = 0; vz < WarTableMapRenderer.VIEWPORT_CHUNKS; vz++) {
                viewportChunks.add(new long[]{ vpOriginX + vx, vpOriginZ + vz });
            }
        }
        List<ChunkMapEntry> initialChunks =
                mapCache.getOrScanBatch(level, viewportChunks, mud);

        PacketDistributor.sendToPlayer(player, new WarTableOpenPacket(
                new WarTableData(entries),
                initialChunks,
                mapCache.getDiscoveredChunks(),
                raids.getActiveRaidsList(),
                originCX, originCZ
        ));
    }

    // -------------------------------------------------------------------------
    // Pit entry builder (unchanged from before)
    // -------------------------------------------------------------------------

    private static WarTableData.PitEntry buildPitEntry(ServerLevel level,
                                                       MudpitBlockEntity pit,
                                                       int index) {
        String leaderName = null;
        int str = 0, tac = 0, pre = 0;
        UUID leaderUUID = pit.getLeaderUUID();

        if (leaderUUID != null) {
            var entity = level.getEntity(leaderUUID);
            if (entity instanceof OrcEntity orc && orc.getLeaderData() != null) {
                leaderName = orc.getCustomName() != null
                        ? orc.getCustomName().getString() : "Unknown";
                str = orc.getLeaderData().strength;
                tac = orc.getLeaderData().tactics;
                pre = orc.getLeaderData().presence;
            }
        }

        List<WarTableData.OrcEntry> orcs = new ArrayList<>();
        int orcNumber = 1;

        // Live orcs at the pit
        for (UUID orcUUID : pit.getTrackedOrcUUIDs()) {
            var entity = level.getEntity(orcUUID);
            if (!(entity instanceof OrcEntity orc)) continue;
            String name = orc.getCustomName() != null
                    ? orc.getCustomName().getString() : "Orc #" + orcNumber;
            orcs.add(new WarTableData.OrcEntry(orcUUID, name, orc.getScarCount(), orc.isLeader()));
            orcNumber++;
        }

        // Orcs on a raid — show from snapshots so pit card stays populated
        // Match by pitPos since RaidParty now stores exact block position
        if (pit.isRaiding()) {
            RaidManager raids = RaidManager.get(level);
            for (net.nuggz.lotrmc.warmap.RaidParty party : raids.getActiveRaids()) {
                if (!party.pitPos.equals(pit.getBlockPos())) continue;
                for (net.nuggz.lotrmc.warmap.RaidOrcSnapshot snap
                        : raids.getSnapshotsForParty(party.partyId)) {
                    String name = snap.customName != null
                            ? snap.customName : "Orc #" + orcNumber;
                    orcs.add(new WarTableData.OrcEntry(
                            snap.originalUUID,
                            name + " §8(raiding)",
                            snap.scarCount,
                            snap.isLeader));
                    orcNumber++;
                }
            }
        }

        orcs.sort((a, b) -> {
            if (a.isLeader) return -1;
            if (b.isLeader) return 1;
            return Integer.compare(b.scarCount, a.scarCount);
        });

        return new WarTableData.PitEntry(
                index, pit.getCapacity(), pit.getBiomass(),
                pit.isGestating(), pit.getGestationPercent(),
                pit.isRaiding(), pit.getSquadOrders().getDisplayName(),
                leaderName, str, tac, pre,
                pit.getMaxPopulation(), orcs);
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        tableData.encode(buf);

        // Initial map chunks
        buf.writeInt(initialMapChunks.size());
        for (ChunkMapEntry e : initialMapChunks) e.encode(buf);

        // Discovered chunks
        buf.writeInt(discoveredChunks.size());
        for (long packed : discoveredChunks) buf.writeLong(packed);

        // Active raids
        buf.writeInt(activeRaids.size());
        for (RaidParty party : activeRaids) party.encode(buf);

        // Origin
        buf.writeInt(mudlandsOriginChunkX);
        buf.writeInt(mudlandsOriginChunkZ);
    }

    public static WarTableOpenPacket decode(FriendlyByteBuf buf) {
        WarTableData tableData = WarTableData.decode(buf);

        int chunkCount = buf.readInt();
        List<ChunkMapEntry> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) chunks.add(ChunkMapEntry.decode(buf));

        int discCount = buf.readInt();
        Set<Long> discovered = new HashSet<>();
        for (int i = 0; i < discCount; i++) discovered.add(buf.readLong());

        int raidCount = buf.readInt();
        List<RaidParty> raids = new ArrayList<>();
        for (int i = 0; i < raidCount; i++) raids.add(RaidParty.decode(buf));

        int ox = buf.readInt();
        int oz = buf.readInt();

        return new WarTableOpenPacket(tableData, chunks, discovered, raids, ox, oz);
    }

    // -------------------------------------------------------------------------
    // Client handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> openScreen(this));
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(WarTableOpenPacket pkt) {
        WarTableScreen screen = new WarTableScreen(
                pkt.tableData(),
                pkt.initialMapChunks(),
                pkt.discoveredChunks(),
                pkt.activeRaids(),
                pkt.mudlandsOriginChunkX(),
                pkt.mudlandsOriginChunkZ());
        Minecraft.getInstance().setScreen(screen);
    }
}