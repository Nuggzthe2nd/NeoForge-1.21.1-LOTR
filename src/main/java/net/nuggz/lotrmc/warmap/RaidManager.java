package net.nuggz.lotrmc.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.*;

/**
 * Manages all active raid parties.
 *
 * Key design decisions:
 *   - RaidParty stores pitPos (BlockPos) not pitIndex — reliable lookup
 *   - RaidResult stored in RaidManager NBT — survives server restarts
 *   - Orcs stay in pit.trackedOrcs while raiding (population cap aware)
 *   - pit.raidingOrcs tracks which tracked orcs are physically absent
 */
public class RaidManager extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_raid_manager";

    private final Map<UUID, RaidParty> activeRaids = new LinkedHashMap<>();
    private final Map<UUID, List<RaidOrcSnapshot>> orcSnapshots = new LinkedHashMap<>();

    // Stored results — persisted in NBT so they survive restarts
    // partyId → serialized RaidResult narrative + loot (lightweight)
    private final Map<UUID, StoredResult> storedResults = new LinkedHashMap<>();

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

    public RaidParty createRaidParty(ServerLevel level,
                                     MudpitBlockEntity pit,
                                     BlockPos pitPos,
                                     List<UUID> selectedOrcUUIDs,
                                     int targetChunkX, int targetChunkZ,
                                     RaidParty.TargetType targetType,
                                     String targetLabel) {
        if (selectedOrcUUIDs.isEmpty()) return null;

        String leaderName  = null;
        int leaderStrength = 0, leaderTactics = 0, leaderPresence = 0;
        int partyScarTotal = 0;
        List<UUID> validOrcs = new ArrayList<>();
        List<RaidOrcSnapshot> snapshots = new ArrayList<>();

        for (UUID uuid : selectedOrcUUIDs) {
            var entity = level.getEntity(uuid);
            if (!(entity instanceof OrcEntity orc)) continue;
            if (orc.isDeadOrDying()) continue;

            validOrcs.add(uuid);
            partyScarTotal += orc.getScarCount();
            snapshots.add(new RaidOrcSnapshot(orc));

            if (orc.isLeader() && orc.getLeaderData() != null) {
                leaderName     = orc.getCustomName() != null
                        ? orc.getCustomName().getString() : "Leader";
                leaderStrength = orc.getLeaderData().strength;
                leaderTactics  = orc.getLeaderData().tactics;
                leaderPresence = orc.getLeaderData().presence;
            }
        }

        if (validOrcs.isEmpty()) return null;

        net.minecraft.world.level.ChunkPos originChunk = new net.minecraft.world.level.ChunkPos(pitPos);
        long now      = level.getGameTime();
        long arrival  = RaidParty.calculateArrivalTime(now, originChunk.x, originChunk.z, targetChunkX, targetChunkZ);
        long retStart = RaidParty.calculateReturnTime(arrival, validOrcs.size());
        long complete = RaidParty.calculateCompletionTime(retStart, originChunk.x, originChunk.z, targetChunkX, targetChunkZ);

        RaidParty party = new RaidParty(
                UUID.randomUUID(), pitPos, validOrcs,
                leaderName, leaderStrength, leaderTactics, leaderPresence, partyScarTotal,
                targetChunkX, targetChunkZ, targetType, targetLabel,
                originChunk.x, originChunk.z,
                now, arrival, retStart, complete);

        // Mark orcs as raiding (keep in trackedOrcs for population cap)
        // and remove their physical entity
        for (UUID uuid : validOrcs) {
            var entity = level.getEntity(uuid);
            if (entity instanceof OrcEntity orc) {
                pit.markOrcRaiding(uuid);
                if (orc.isLeader()) pit.clearLeader();
                orc.discard();
            }
        }

        pit.setRaiding(true);
        activeRaids.put(party.partyId, party);
        orcSnapshots.put(party.partyId, snapshots);
        setDirty();
        return party;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tickRaids(ServerLevel level) {
        if (activeRaids.isEmpty()) return;

        long now = level.getGameTime();
        List<UUID> toComplete = new ArrayList<>();

        for (RaidParty party : activeRaids.values()) {
            switch (party.state) {
                case OUTBOUND -> {
                    if (now >= party.arrivalTime) {
                        party.state = RaidParty.RaidState.AT_TARGET;
                        StoredResult result = RaidSimulator.simulate(level, party);
                        storedResults.put(party.partyId, result);
                        setDirty();
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
            orcSnapshots.remove(id);
            storedResults.remove(id);
            setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Completion
    // -------------------------------------------------------------------------

    private void completeRaid(ServerLevel level, RaidParty party) {
        StoredResult stored = storedResults.get(party.partyId);
        List<RaidOrcSnapshot> snapshots = orcSnapshots.getOrDefault(
                party.partyId, new ArrayList<>());

        // Find pit directly by position — reliable unlike index
        var pitBE = level.getBlockEntity(party.pitPos);
        if (!(pitBE instanceof MudpitBlockEntity pit)) {
            // Pit was destroyed while raid was out — orcs are lost
            return;
        }

        pit.setRaiding(false);

        Set<UUID> survivorSet = stored != null
                ? new HashSet<>(stored.survivorUUIDs) : new HashSet<>();

        // Re-spawn surviving orcs from snapshots
        for (RaidOrcSnapshot snapshot : snapshots) {
            // Unmark from raiding regardless of survival
            pit.unmarkOrcRaiding(snapshot.originalUUID);

            if (!survivorSet.contains(snapshot.originalUUID)) {
                // Orc died — untrack completely
                pit.untrackOrc(snapshot.originalUUID);
                continue;
            }

            // Spawn restored orc
            OrcEntity orc = net.nuggz.lotrmc.registry.ModEntities.ORC.get().create(level);
            if (orc == null) {
                pit.untrackOrc(snapshot.originalUUID);
                continue;
            }

            orc.moveTo(party.pitPos.getX() + 0.5,
                    party.pitPos.getY() + 1,
                    party.pitPos.getZ() + 0.5);

            int bonusScars = stored != null
                    ? stored.scarsPerOrc.getOrDefault(snapshot.originalUUID, 0) : 0;
            snapshot.applyTo(orc, party.pitPos, pit, bonusScars);

            // The orc gets a new UUID — update tracking
            pit.untrackOrc(snapshot.originalUUID);
            pit.trackOrc(orc.getUUID());

            level.addFreshEntity(orc);
        }

        // Notify Sauron
        notifySauron(level, party, stored);
    }

    private void notifySauron(ServerLevel level, RaidParty party, StoredResult stored) {
        MudlandsChunkData mudData = MudlandsChunkData.get(level);
        UUID sauronUUID = mudData.getSauronUUID();
        if (sauronUUID == null) return;
        ServerPlayer sauron = level.getServer().getPlayerList().getPlayer(sauronUUID);
        if (sauron == null) return;

        net.nuggz.lotrmc.network.RaidResultPacket.send(sauron, party,
                stored != null ? stored.toRaidResult() : RaidSimulator.RaidResult.EMPTY);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Collection<RaidParty> getActiveRaids() {
        return Collections.unmodifiableCollection(activeRaids.values());
    }

    public List<RaidParty> getActiveRaidsList() {
        return new ArrayList<>(activeRaids.values());
    }

    public List<RaidOrcSnapshot> getSnapshotsForParty(UUID partyId) {
        return orcSnapshots.getOrDefault(partyId, new ArrayList<>());
    }

    // -------------------------------------------------------------------------
    // StoredResult — lightweight NBT-serializable wrapper around RaidResult
    // -------------------------------------------------------------------------

    public static class StoredResult {
        public final List<UUID> survivorUUIDs;
        public final List<UUID> casualtyUUIDs;
        public final Map<UUID, Integer> scarsPerOrc;
        public final List<net.minecraft.world.item.ItemStack> loot;
        public final String narrative;
        public final float powerRatio;

        public StoredResult(RaidSimulator.RaidResult result) {
            this.survivorUUIDs = new ArrayList<>(result.survivorUUIDs);
            this.casualtyUUIDs = new ArrayList<>(result.casualtyUUIDs);
            this.scarsPerOrc   = new HashMap<>(result.scarsPerOrc);
            this.loot          = new ArrayList<>(result.loot);
            this.narrative     = result.narrative;
            this.powerRatio    = result.powerRatio;
        }

        // Private for loading
        private StoredResult(List<UUID> survivors, List<UUID> casualties,
                             Map<UUID, Integer> scars,
                             List<net.minecraft.world.item.ItemStack> loot,
                             String narrative, float ratio) {
            this.survivorUUIDs = survivors;
            this.casualtyUUIDs = casualties;
            this.scarsPerOrc   = scars;
            this.loot          = loot;
            this.narrative     = narrative;
            this.powerRatio    = ratio;
        }

        public RaidSimulator.RaidResult toRaidResult() {
            return new RaidSimulator.RaidResult(survivorUUIDs, casualtyUUIDs,
                    scarsPerOrc, loot, narrative, powerRatio);
        }

        public CompoundTag save(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Narrative", narrative);
            tag.putFloat("Ratio", powerRatio);

            ListTag survivors = new ListTag();
            for (UUID u : survivorUUIDs) { CompoundTag t = new CompoundTag(); t.putUUID("U", u); survivors.add(t); }
            tag.put("Survivors", survivors);

            ListTag casualties = new ListTag();
            for (UUID u : casualtyUUIDs) { CompoundTag t = new CompoundTag(); t.putUUID("U", u); casualties.add(t); }
            tag.put("Casualties", casualties);

            CompoundTag scarsTag = new CompoundTag();
            for (Map.Entry<UUID, Integer> e : scarsPerOrc.entrySet())
                scarsTag.putInt(e.getKey().toString(), e.getValue());
            tag.put("Scars", scarsTag);

            ListTag lootTag = new ListTag();
            for (var stack : loot) {
                CompoundTag lt = new CompoundTag();
                lt.putString("Item", stack.getItem().builtInRegistryHolder().key().location().toString());
                lt.putInt("Count", stack.getCount());
                lootTag.add(lt);
            }
            tag.put("Loot", lootTag);
            return tag;
        }

        public static StoredResult load(CompoundTag tag, HolderLookup.Provider provider) {
            String narrative = tag.getString("Narrative");
            float ratio      = tag.getFloat("Ratio");

            List<UUID> survivors = new ArrayList<>();
            tag.getList("Survivors", Tag.TAG_COMPOUND)
                    .forEach(t -> survivors.add(((CompoundTag)t).getUUID("U")));

            List<UUID> casualties = new ArrayList<>();
            tag.getList("Casualties", Tag.TAG_COMPOUND)
                    .forEach(t -> casualties.add(((CompoundTag)t).getUUID("U")));

            Map<UUID, Integer> scars = new HashMap<>();
            CompoundTag scarsTag = tag.getCompound("Scars");
            for (String key : scarsTag.getAllKeys()) {
                try { scars.put(UUID.fromString(key), scarsTag.getInt(key)); }
                catch (Exception ignored) {}
            }

            List<net.minecraft.world.item.ItemStack> loot = new ArrayList<>();
            ListTag lootTag = tag.getList("Loot", Tag.TAG_COMPOUND);
            for (int i = 0; i < lootTag.size(); i++) {
                CompoundTag lt = lootTag.getCompound(i);
                var loc = net.minecraft.resources.ResourceLocation.parse(lt.getString("Item"));
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
                loot.add(new net.minecraft.world.item.ItemStack(item, lt.getInt("Count")));
            }

            return new StoredResult(survivors, casualties, scars, loot, narrative, ratio);
        }
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag raidList = new ListTag();
        for (RaidParty p : activeRaids.values()) raidList.add(p.save());
        tag.put("ActiveRaids", raidList);

        CompoundTag snapshotMap = new CompoundTag();
        for (var e : orcSnapshots.entrySet())
            snapshotMap.put(e.getKey().toString(),
                    RaidOrcSnapshot.saveList(e.getValue(), provider));
        tag.put("OrcSnapshots", snapshotMap);

        CompoundTag resultMap = new CompoundTag();
        for (var e : storedResults.entrySet())
            resultMap.put(e.getKey().toString(), e.getValue().save(provider));
        tag.put("StoredResults", resultMap);

        return tag;
    }

    public static RaidManager load(CompoundTag tag, HolderLookup.Provider provider) {
        RaidManager mgr = new RaidManager();

        ListTag raidList = tag.getList("ActiveRaids", Tag.TAG_COMPOUND);
        for (int i = 0; i < raidList.size(); i++) {
            RaidParty p = RaidParty.load(raidList.getCompound(i));
            mgr.activeRaids.put(p.partyId, p);
        }

        CompoundTag snapshotMap = tag.getCompound("OrcSnapshots");
        for (String key : snapshotMap.getAllKeys()) {
            try {
                UUID id = UUID.fromString(key);
                mgr.orcSnapshots.put(id, RaidOrcSnapshot.loadList(
                        snapshotMap.getList(key, Tag.TAG_COMPOUND), provider));
            } catch (Exception ignored) {}
        }

        CompoundTag resultMap = tag.getCompound("StoredResults");
        for (String key : resultMap.getAllKeys()) {
            try {
                UUID id = UUID.fromString(key);
                mgr.storedResults.put(id, StoredResult.load(
                        resultMap.getCompound(key), provider));
            } catch (Exception ignored) {}
        }

        return mgr;
    }
}