package net.nuggz.lotrmc.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

import java.util.*;

/**
 * Manages all active raid parties.
 *
 * Stored on the overworld SavedData so raids persist across sessions.
 * Ticked every server tick from MudlandsServerEvents.
 *
 * Lifecycle:
 *   1. RaidStartPacket received → createRaidParty() called
 *      → orcs removed as entities, RaidParty created
 *   2. Server ticks → tickRaids() advances state machine
 *   3. AT_TARGET → RaidSimulator.simulate() called
 *   4. RETURNING + completionTime reached → completeRaid()
 *      → results sent to Sauron player, orcs re-spawned at pit
 */
public class RaidManager extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_raid_manager";

    // All active raids — partyId → RaidParty
    private final Map<UUID, RaidParty> activeRaids = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static RaidManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaidManager::new, RaidManager::load),
                DATA_NAME);
    }

    // -------------------------------------------------------------------------
    // Raid creation
    // -------------------------------------------------------------------------

    /**
     * Create a new raid party. Called when RaidStartPacket is received.
     *
     * Removes the selected orcs as live entities and replaces them with
     * a RaidParty SavedData entry.
     *
     * @return the created RaidParty, or null if validation fails
     */
    public RaidParty createRaidParty(ServerLevel level,
                                     MudpitBlockEntity pit,
                                     BlockPos pitPos,
                                     List<UUID> selectedOrcUUIDs,
                                     int targetChunkX, int targetChunkZ,
                                     RaidParty.TargetType targetType,
                                     String targetLabel) {
        if (selectedOrcUUIDs.isEmpty()) return null;

        // Collect orc data before removing them
        String leaderName    = null;
        int leaderStrength   = 0;
        int leaderTactics    = 0;
        int leaderPresence   = 0;
        int partyScarTotal   = 0;
        List<UUID> validOrcs = new ArrayList<>();

        for (UUID uuid : selectedOrcUUIDs) {
            var entity = level.getEntity(uuid);
            if (!(entity instanceof OrcEntity orc)) continue;
            if (orc.isDeadOrDying()) continue;

            validOrcs.add(uuid);
            partyScarTotal += orc.getScarCount();

            if (orc.isLeader() && orc.getLeaderData() != null) {
                leaderName     = orc.getCustomName() != null
                        ? orc.getCustomName().getString() : "Leader";
                leaderStrength = orc.getLeaderData().strength;
                leaderTactics  = orc.getLeaderData().tactics;
                leaderPresence = orc.getLeaderData().presence;
            }
        }

        if (validOrcs.isEmpty()) return null;

        // Calculate timing
        ChunkPos originChunk = new ChunkPos(pitPos);
        long now             = level.getGameTime();
        long arrival         = RaidParty.calculateArrivalTime(
                now, originChunk.x, originChunk.z, targetChunkX, targetChunkZ);
        long returnStart     = RaidParty.calculateReturnTime(arrival, validOrcs.size());
        long completion      = RaidParty.calculateCompletionTime(
                returnStart, originChunk.x, originChunk.z, targetChunkX, targetChunkZ);

        RaidParty party = new RaidParty(
                UUID.randomUUID(), pit.getCapacity(), validOrcs,
                leaderName, leaderStrength, leaderTactics, leaderPresence, partyScarTotal,
                targetChunkX, targetChunkZ, targetType, targetLabel,
                originChunk.x, originChunk.z,
                now, arrival, returnStart, completion
        );

        // Remove orcs as live entities
        for (UUID uuid : validOrcs) {
            var entity = level.getEntity(uuid);
            if (entity instanceof OrcEntity orc) {
                pit.untrackOrc(uuid);
                orc.discard(); // remove without dropping loot
            }
        }

        // Mark pit as raiding
        pit.setRaiding(true);

        activeRaids.put(party.partyId, party);
        setDirty();
        return party;
    }

    // -------------------------------------------------------------------------
    // Tick logic
    // -------------------------------------------------------------------------

    /**
     * Called every server tick from MudlandsServerEvents.
     * Advances state machines and triggers simulation/completion.
     */
    public void tickRaids(ServerLevel level) {
        if (activeRaids.isEmpty()) return;

        long now = level.getGameTime();
        List<UUID> toComplete = new ArrayList<>();

        for (RaidParty party : activeRaids.values()) {
            switch (party.state) {
                case OUTBOUND -> {
                    if (now >= party.arrivalTime) {
                        party.state = RaidParty.RaidState.AT_TARGET;
                        setDirty();
                        // Run simulation immediately
                        RaidSimulator.simulate(level, party);
                    }
                }
                case AT_TARGET -> {
                    if (now >= party.returnTime) {
                        party.state = RaidParty.RaidState.RETURNING;
                        setDirty();
                    }
                }
                case RETURNING -> {
                    if (now >= party.completionTime) {
                        toComplete.add(party.partyId);
                    }
                }
            }
        }

        for (UUID id : toComplete) {
            completeRaid(level, activeRaids.get(id));
            activeRaids.remove(id);
            setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Raid completion
    // -------------------------------------------------------------------------

    /**
     * Called when a raid party returns home.
     * Re-spawns surviving orcs at the pit and sends results to the player.
     */
    private void completeRaid(ServerLevel level, RaidParty party) {
        RaidSimulator.RaidResult result = RaidSimulator.getStoredResult(party.partyId);

        // Find the pit by index — scan mudlands chunks
        MudpitBlockEntity pit = findPitByIndex(level, party.pitIndex);

        if (pit != null) {
            pit.setRaiding(false);

            // Re-spawn surviving orcs at the pit
            BlockPos pitPos = pit.getBlockPos();
            for (UUID uuid : result.survivorUUIDs) {
                OrcEntity orc = net.nuggz.lotrmc.registry.ModEntities.ORC.get().create(level);
                if (orc == null) continue;

                orc.setPitPos(pitPos);
                pit.trackOrc(orc.getUUID());

                // Apply scars from raid
                int newScars = result.scarsPerOrc.getOrDefault(uuid, 0);
                if (newScars > 0) orc.addScars(newScars);

                // Spawn at pit rim
                orc.moveTo(pitPos.getX() + 0.5, pitPos.getY() + 1, pitPos.getZ() + 0.5);
                level.addFreshEntity(orc);
            }
        }

        // Notify Sauron player
        notifySauron(level, party, result);

        // Clear stored result
        RaidSimulator.clearStoredResult(party.partyId);
    }

    private void notifySauron(ServerLevel level, RaidParty party,
                              RaidSimulator.RaidResult result) {
        net.nuggz.lotrmc.worlddata.MudlandsChunkData mudData =
                net.nuggz.lotrmc.worlddata.MudlandsChunkData.get(level);
        UUID sauronUUID = mudData.getSauronUUID();
        if (sauronUUID == null) return;

        ServerPlayer sauron = level.getServer().getPlayerList().getPlayer(sauronUUID);
        if (sauron == null) return;

        // Send raid result packet to open result screen
        net.nuggz.lotrmc.network.RaidResultPacket.send(sauron, party, result);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public Collection<RaidParty> getActiveRaids() {
        return Collections.unmodifiableCollection(activeRaids.values());
    }

    public List<RaidParty> getActiveRaidsList() {
        return new ArrayList<>(activeRaids.values());
    }

    private MudpitBlockEntity findPitByIndex(ServerLevel level, int pitIndex) {
        net.nuggz.lotrmc.worlddata.MudlandsChunkData mudData =
                net.nuggz.lotrmc.worlddata.MudlandsChunkData.get(level);
        int idx = 0;
        for (long packed : mudData.getAllConvertedChunkPositions()) {
            net.minecraft.world.level.ChunkPos chunkPos =
                    new net.minecraft.world.level.ChunkPos(packed);
            var chunk = level.getChunk(chunkPos.x, chunkPos.z);
            for (var be : chunk.getBlockEntities().values()) {
                if (be instanceof MudpitBlockEntity pit) {
                    if (idx == pitIndex) return pit;
                    idx++;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (RaidParty party : activeRaids.values()) list.add(party.save());
        tag.put("ActiveRaids", list);
        return tag;
    }

    public static RaidManager load(CompoundTag tag, HolderLookup.Provider provider) {
        RaidManager manager = new RaidManager();
        ListTag list = tag.getList("ActiveRaids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            RaidParty party = RaidParty.load(list.getCompound(i));
            manager.activeRaids.put(party.partyId, party);
        }
        return manager;
    }
}