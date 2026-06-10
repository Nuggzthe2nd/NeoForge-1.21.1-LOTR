package net.nuggz.lotrmc.mudpit;

import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.registry.ModBlockEntities;
import net.nuggz.lotrmc.registry.ModEntities;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

public class MudpitBlockEntity extends BlockEntity {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int ITEM_SCAN_INTERVAL   = 10;
    private static final int GESTATION_BASE_TICKS = 1200;
    private static final int GESTATION_COST_SCALE = 60;

    public static final int COST_BASIC_ORC  = 2;
    public static final int COST_COMBAT_ORC = 5;
    public static final int COST_TROLL      = 20;

    // -------------------------------------------------------------------------
    // PitSize — adjust weights and ranges here
    // -------------------------------------------------------------------------

    public enum PitSize {
        SMALL  (2,  4,  1, 60),
        MEDIUM (6,  10, 2, 25),
        LARGE  (20, 40, 4, 13),
        ANCIENT(80, 100,6,  2);

        public final int capacityMin;
        public final int capacityMax;
        public final int carveRadius;
        public final int weight;

        PitSize(int cMin, int cMax, int radius, int weight) {
            this.capacityMin = cMin;
            this.capacityMax = cMax;
            this.carveRadius = radius;
            this.weight      = weight;
        }

        public static PitSize roll(java.util.Random rng) {
            int total = 0;
            for (PitSize s : values()) total += s.weight;
            int roll = rng.nextInt(total);
            int cumulative = 0;
            for (PitSize s : values()) {
                cumulative += s.weight;
                if (roll < cumulative) return s;
            }
            return SMALL;
        }

        public int rollCapacity(java.util.Random rng) {
            return capacityMin + rng.nextInt(capacityMax - capacityMin + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private int capacity  = 6;
    private int pitRadius = 2;
    private int biomass   = 0;
    @Nullable private ResourceLocation seedType = null;

    private boolean gestating        = false;
    private int gestationAge         = 0;
    private int gestationTarget      = 0;
    private int pendingBatchCost     = 0;

    private ArmorSetQueue armorQueue = new ArmorSetQueue();

    // Leader
    @Nullable private UUID leaderUUID = null;

    // Orc tracking — UUIDs of all living orcs spawned from this pit
    private final Set<UUID> trackedOrcs = new HashSet<>();

    // UUIDs of orcs currently on a raid (subset of trackedOrcs)
    // Kept in trackedOrcs so population cap accounts for them naturally
    private final Set<UUID> raidingOrcs = new HashSet<>();

    public void markOrcRaiding(UUID uuid)   { raidingOrcs.add(uuid);    setChanged(); }
    public void unmarkOrcRaiding(UUID uuid) { raidingOrcs.remove(uuid); setChanged(); }
    public Set<UUID> getRaidingOrcUUIDs()   { return Collections.unmodifiableSet(raidingOrcs); }
    public boolean isOrcRaiding(UUID uuid)  { return raidingOrcs.contains(uuid); }

    // Raid state
    private boolean raiding = false;

    // Squad orders — replaces the old defaultOrder string
    private SquadOrderData squadOrders = new SquadOrderData();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public MudpitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUDPIT.get(), pos, state);
    }

    public void initFromCarve(int capacity, int pitRadius) {
        this.capacity  = capacity;
        this.pitRadius = pitRadius;
        setChanged();
    }

    // -------------------------------------------------------------------------
    // Server tick
    // -------------------------------------------------------------------------

    public static void serverTick(ServerLevel level, BlockPos pos,
                                  BlockState state, MudpitBlockEntity pit) {
        long tick = level.getGameTime();

        if (tick % ITEM_SCAN_INTERVAL == 0) {
            pit.scanForItems(level, pos);
        }

        // Sync liquid blocks every 20 ticks — cheap since it only acts on change
        if (tick % 20 == 0) {
            pit.syncLiquidBlocks(level, pos);
        }

        if (pit.gestating) {
            pit.gestationAge++;
            if (pit.gestationAge >= pit.gestationTarget) {
                pit.completeBatch(level, pos);
            }
        } else {
            pit.tryStartGestation();
        }
    }

    // -------------------------------------------------------------------------
    // Item scanning
    // -------------------------------------------------------------------------

    private void scanForItems(ServerLevel level, BlockPos pos) {
        AABB scanBox = new AABB(
                pos.getX() - pitRadius, pos.getY() - 1, pos.getZ() - pitRadius,
                pos.getX() + pitRadius, pos.getY() + pitRadius + 2, pos.getZ() + pitRadius);

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, scanBox);
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (tryAcceptItem(stack)) {
                itemEntity.discard();
                setChanged();
            }
        }
    }

    private boolean tryAcceptItem(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem
                || stack.getItem() instanceof net.minecraft.world.item.SwordItem
                || stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
            return armorQueue.addItem(stack);
        }

        int biomassValue = getMeatBiomass(stack);
        if (biomassValue > 0) {
            biomass += biomassValue * stack.getCount();
            return true;
        }

        return false;
    }

    private int getMeatBiomass(ItemStack stack) {
        // TODO: when seedType is set, delegate to seed-specific whitelist
        return MeatRegistry.getBiomassBasic(stack);
    }

    // -------------------------------------------------------------------------
    // Gestation
    // -------------------------------------------------------------------------

    private void tryStartGestation() {
        int unitCost = getUnitCost();
        if (biomass < unitCost) return;

        // Cap total living orcs at 2x the max batch size.
        // Max batch = capacity / unitCost, so cap = (capacity / unitCost) * 2.
        int maxPopulation = getMaxPopulation();
        if (trackedOrcs.size() >= maxPopulation) return;

        // How many can we spawn without exceeding the population cap
        int headroom = maxPopulation - trackedOrcs.size();

        int unitsToSpawn    = 0;
        int costAccumulated = 0;
        while (costAccumulated + unitCost <= capacity
                && costAccumulated + unitCost <= biomass
                && unitsToSpawn < headroom) {
            unitsToSpawn++;
            costAccumulated += unitCost;
        }

        if (unitsToSpawn <= 0) return;

        pendingBatchCost = costAccumulated;
        gestationTarget  = GESTATION_BASE_TICKS + (costAccumulated * GESTATION_COST_SCALE);
        gestationAge     = 0;
        gestating        = true;
        setChanged();
    }

    private void completeBatch(ServerLevel level, BlockPos pos) {
        int unitCost     = getUnitCost();
        int units        = pendingBatchCost / unitCost;
        biomass         -= pendingBatchCost;
        gestating        = false;
        gestationAge     = 0;
        pendingBatchCost = 0;
        setChanged();

        spawnBatch(level, pos, units);
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    private void spawnBatch(ServerLevel level, BlockPos pos, int count) {
        List<BlockPos> rimPositions = getRimPositions(level, pos, count);

        for (int i = 0; i < count; i++) {
            OrcEntity orc = ModEntities.ORC.get().create(level);
            if (orc == null) continue;

            BlockPos spawnPos = rimPositions.get(i % rimPositions.size());
            orc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);

            // Set pit affiliation BEFORE adding to world so it's present
            // during any initialization hooks fired by addFreshEntity
            orc.setPitPos(pos);
            trackOrc(orc.getUUID());

            ArmorSetQueue.ArmorSet set = armorQueue.popNext();
            if (!set.helmet.isEmpty())     orc.setItemSlot(EquipmentSlot.HEAD,     set.helmet);
            if (!set.chestplate.isEmpty()) orc.setItemSlot(EquipmentSlot.CHEST,    set.chestplate);
            if (!set.leggings.isEmpty())   orc.setItemSlot(EquipmentSlot.LEGS,     set.leggings);
            if (!set.boots.isEmpty())      orc.setItemSlot(EquipmentSlot.FEET,     set.boots);
            if (!set.weapon.isEmpty())     orc.setItemSlot(EquipmentSlot.MAINHAND, set.weapon);

            level.addFreshEntity(orc);
        }
    }

    private List<BlockPos> getRimPositions(ServerLevel level, BlockPos center, int count) {
        List<BlockPos> positions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            int dx = (int) Math.round(Math.cos(angle) * pitRadius);
            int dz = (int) Math.round(Math.sin(angle) * pitRadius);
            BlockPos rimPos = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    center.offset(dx, 0, dz));
            positions.add(rimPos);
        }
        return positions;
    }

    // -------------------------------------------------------------------------
    // Liquid block sync (water → air at cap, air → water below cap)
    // -------------------------------------------------------------------------

    private boolean lastAtCap = false;

    private void syncLiquidBlocks(ServerLevel level, BlockPos corePos) {
        boolean atCap = trackedOrcs.size() >= getMaxPopulation();
        if (atCap == lastAtCap) return;
        lastAtCap = atCap;

        net.minecraft.world.level.block.state.BlockState target = atCap
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();

        int depth  = 4;
        int radius = pitRadius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) == radius && Math.abs(dz) == radius) continue;

                for (int dy = 1; dy <= depth - 3; dy++) {
                    BlockPos pos = corePos.offset(dx, dy, dz);
                    net.minecraft.world.level.block.state.BlockState current =
                            level.getBlockState(pos);

                    if (atCap && current.is(net.minecraft.world.level.block.Blocks.WATER)) {
                        level.setBlock(pos, target, 3);
                    } else if (!atCap && current.is(net.minecraft.world.level.block.Blocks.AIR)) {
                        level.setBlock(pos, target, 3);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Leader management
    // -------------------------------------------------------------------------

    public boolean hasLeader()            { return leaderUUID != null; }
    @Nullable public UUID getLeaderUUID() { return leaderUUID; }

    public boolean hasLivingLeader(ServerLevel level) {
        if (leaderUUID == null) return false;
        net.minecraft.world.entity.Entity e = level.getEntity(leaderUUID);
        if (e instanceof OrcEntity orc && orc.isLeader() && !orc.isDeadOrDying()) return true;
        clearLeader();
        return false;
    }

    public void setLeader(UUID uuid) {
        this.leaderUUID = uuid;
        setChanged();
    }

    public void clearLeader() {
        this.leaderUUID = null;
        setChanged();
    }

    // -------------------------------------------------------------------------
    // Helpers / getters
    // -------------------------------------------------------------------------

    private int getUnitCost() {
        // TODO: return different costs based on seedType when seed system is built
        return COST_BASIC_ORC;
    }

    // Orc tracking
    public void trackOrc(UUID uuid) {
        trackedOrcs.add(uuid);
        setChanged();
    }

    public void untrackOrc(UUID uuid) {
        trackedOrcs.remove(uuid);
        raidingOrcs.remove(uuid);
        setChanged();
    }

    public Set<UUID> getTrackedOrcUUIDs() { return Collections.unmodifiableSet(trackedOrcs); }

    // Raid state
    public boolean isRaiding()          { return raiding; }
    public void setRaiding(boolean val) { this.raiding = val; setChanged(); }

    // Squad orders
    public SquadOrderData getSquadOrders() { return squadOrders; }

    public void setSeed(ResourceLocation seedType)  { this.seedType = seedType; setChanged(); }
    public void clearSeed()                         { this.seedType = null;     setChanged(); }

    @Nullable public ResourceLocation getSeedType() { return seedType; }
    public int getCapacity()                        { return capacity; }
    public int getBiomass()                         { return biomass; }
    public boolean isGestating()                    { return gestating; }
    public int getPitRadius()                       { return pitRadius; }

    public int getMaxPopulation() {
        return (capacity / getUnitCost()) * 2;
    }

    public int getGestationPercent() {
        if (!gestating || gestationTarget == 0) return 0;
        return (gestationAge * 100) / gestationTarget;
    }

    public int getPendingUnits() {
        return gestating ? pendingBatchCost / getUnitCost() : 0;
    }

    public int getGestationTicksRemaining() {
        if (!gestating || gestationTarget == 0) return 0;
        return Math.max(0, gestationTarget - gestationAge);
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("Capacity",        capacity);
        tag.putInt("PitRadius",       pitRadius);
        tag.putInt("Biomass",         biomass);
        tag.putBoolean("Gestating",   gestating);
        tag.putInt("GestationAge",    gestationAge);
        tag.putInt("GestationTarget", gestationTarget);
        tag.putInt("PendingCost",     pendingBatchCost);
        if (seedType   != null) tag.putString("SeedType",  seedType.toString());
        if (leaderUUID != null) tag.putUUID("LeaderUUID",  leaderUUID);
        tag.putBoolean("Raiding",     raiding);

        // Squad orders
        tag.put("SquadOrders", squadOrders.save());

        // Tracked orcs
        net.minecraft.nbt.ListTag orcList = new net.minecraft.nbt.ListTag();
        for (UUID uuid : trackedOrcs) {
            net.minecraft.nbt.CompoundTag orcTag = new net.minecraft.nbt.CompoundTag();
            orcTag.putUUID("UUID", uuid);
            orcList.add(orcTag);
        }
        tag.put("TrackedOrcs", orcList);

        // Raiding orcs
        net.minecraft.nbt.ListTag raidingList = new net.minecraft.nbt.ListTag();
        for (UUID uuid : raidingOrcs) {
            net.minecraft.nbt.CompoundTag rt = new net.minecraft.nbt.CompoundTag();
            rt.putUUID("UUID", uuid);
            raidingList.add(rt);
        }
        tag.put("RaidingOrcs", raidingList);

        tag.putBoolean("LastAtCap", lastAtCap);
        tag.put("ArmorQueue", armorQueue.save(provider));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        capacity         = tag.getInt("Capacity");
        pitRadius        = tag.getInt("PitRadius");
        biomass          = tag.getInt("Biomass");
        gestating        = tag.getBoolean("Gestating");
        gestationAge     = tag.getInt("GestationAge");
        gestationTarget  = tag.getInt("GestationTarget");
        pendingBatchCost = tag.getInt("PendingCost");
        seedType     = tag.contains("SeedType")  ? ResourceLocation.parse(tag.getString("SeedType")) : null;
        leaderUUID   = tag.hasUUID("LeaderUUID") ? tag.getUUID("LeaderUUID") : null;
        raiding      = tag.getBoolean("Raiding");

        // Squad orders — fall back gracefully for worlds that pre-date this field
        squadOrders = tag.contains("SquadOrders")
                ? SquadOrderData.load(tag.getCompound("SquadOrders"))
                : new SquadOrderData();

        // Tracked orcs
        net.minecraft.nbt.ListTag orcList = tag.getList("TrackedOrcs", net.minecraft.nbt.Tag.TAG_COMPOUND);
        trackedOrcs.clear();
        for (int i = 0; i < orcList.size(); i++) {
            trackedOrcs.add(orcList.getCompound(i).getUUID("UUID"));
        }

        // Raiding orcs
        net.minecraft.nbt.ListTag raidingList = tag.getList("RaidingOrcs", net.minecraft.nbt.Tag.TAG_COMPOUND);
        raidingOrcs.clear();
        for (int i = 0; i < raidingList.size(); i++) {
            raidingOrcs.add(raidingList.getCompound(i).getUUID("UUID"));
        }

        lastAtCap  = tag.getBoolean("LastAtCap");
        armorQueue = ArmorSetQueue.load(provider, tag.getCompound("ArmorQueue"));
    }
}