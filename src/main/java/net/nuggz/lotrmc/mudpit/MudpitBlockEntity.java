package net.nuggz.lotrmc.mudpit;

import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.registry.ModBlockEntities;
import net.nuggz.lotrmc.registry.ModEntities;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Core block entity for the mudpit.
 *
 * Placed on the pit floor by MudlandsTerrainReplacer.carveMudpit().
 * Never crafted or placed by the player.
 *
 * State:
 *   capacity     — max unit-cost that can spawn per batch (fixed at carve time)
 *   pitRadius    — detection radius for thrown items (fixed at carve time)
 *   biomass      — accumulated from thrown meat
 *   seedType     — null = basic orcs, otherwise registry key of seed item
 *   gestating    — true while a batch is growing
 *   gestationAge — ticks elapsed toward gestationTarget
 *   armorQueue   — assembled armor sets for upcoming spawns
 *
 * Tick flow:
 *   1. Scan for thrown items in pitRadius every 10 ticks
 *   2. If biomass >= cost of at least one unit and not already gestating → start gestation
 *   3. Gestation counts up to gestationTarget ticks
 *   4. On completion → spawn batch on rim, deduct biomass, clear armor queue entries used
 */
public class MudpitBlockEntity extends BlockEntity {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    // How often (ticks) we scan for thrown items
    private static final int ITEM_SCAN_INTERVAL = 10;

    // Base gestation ticks — scaled by total unit cost of the batch
    // Final gestation = BASE + (totalCost * COST_SCALE)
    private static final int GESTATION_BASE_TICKS  = 1200; // 1 min base
    private static final int GESTATION_COST_SCALE  = 60;   // +3s per unit cost point

    // Unit costs — how much biomass each orc type costs
    // Add more here as you add creature types
    public static final int COST_BASIC_ORC   = 2;
    public static final int COST_COMBAT_ORC  = 5;
    public static final int COST_TROLL       = 20;

    // -------------------------------------------------------------------------
    // Capacity roll table — adjust weights and ranges here
    // -------------------------------------------------------------------------

    public enum PitSize {
        SMALL  (2,  4,  1, 60), // capacity range, carve radius, weight
        MEDIUM (6, 10, 2, 25),
        LARGE  (20, 40, 4, 13),
        ANCIENT(80, 100,6, 2);

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

        /**
         * Roll a random PitSize using weighted selection.
         * To adjust rarity, change the weight values in the enum above.
         */
        public static PitSize roll(java.util.Random rng) {
            int total = 0;
            for (PitSize s : values()) total += s.weight;
            int roll = rng.nextInt(total);
            int cumulative = 0;
            for (PitSize s : values()) {
                cumulative += s.weight;
                if (roll < cumulative) return s;
            }
            return SMALL; // fallback
        }

        public int rollCapacity(java.util.Random rng) {
            return capacityMin + rng.nextInt(capacityMax - capacityMin + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private int capacity   = 6;   // set at carve time
    private int pitRadius  = 2;   // set at carve time, used for item detection
    private int biomass    = 0;
    private ResourceLocation seedType = null; // null = basic orcs

    private boolean gestating      = false;
    private int gestationAge       = 0;
    private int gestationTarget    = 0;
    private int pendingBatchCost   = 0; // total cost of current batch being gestated

    private ArmorSetQueue armorQueue = new ArmorSetQueue();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public MudpitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUDPIT.get(), pos, state);
    }

    /**
     * Called by MudlandsTerrainReplacer immediately after placing this block entity.
     * Sets the fixed capacity and detection radius for this pit's lifetime.
     */
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

        // Scan for thrown items periodically
        if (tick % ITEM_SCAN_INTERVAL == 0) {
            pit.scanForItems(level, pos);
        }

        // Gestation progress
        if (pit.gestating) {
            pit.gestationAge++;
            if (pit.gestationAge >= pit.gestationTarget) {
                pit.completeBatch(level, pos);
            }
        } else {
            // Try to start a new batch if we have enough biomass
            pit.tryStartGestation();
        }
    }

    // -------------------------------------------------------------------------
    // Item scanning
    // -------------------------------------------------------------------------

    private void scanForItems(ServerLevel level, BlockPos pos) {
        // Scan a box around the core block out to pitRadius
        AABB scanBox = new AABB(
                pos.getX() - pitRadius, pos.getY() - 1, pos.getZ() - pitRadius,
                pos.getX() + pitRadius, pos.getY() + pitRadius + 2, pos.getZ() + pitRadius
        );

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, scanBox);
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (tryAcceptItem(stack)) {
                itemEntity.discard(); // consume the item entity
                setChanged();
            }
        }
    }

    /**
     * Attempts to accept a thrown item into the pit.
     * Returns true if the item was consumed (meat or armor/weapon).
     */
    private boolean tryAcceptItem(ItemStack stack) {
        // --- Armor / weapon ---
        if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem
                || stack.getItem() instanceof net.minecraft.world.item.SwordItem
                || stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
            return armorQueue.addItem(stack);
        }

        // --- Meat ---
        int biomassValue = getMeatBiomass(stack);
        if (biomassValue > 0) {
            biomass += biomassValue * stack.getCount();
            return true;
        }

        return false;
    }

    /**
     * Returns the biomass value of this meat item based on the current seed type.
     * If no seed → soft whitelist.
     * If seeded → delegate to the seed item's whitelist logic.
     * Returns 0 if the item should be rejected.
     */
    private int getMeatBiomass(ItemStack stack) {
        if (seedType == null) {
            // Unseeded — soft whitelist, any meat works
            return MeatRegistry.getBiomassBasic(stack);
        }
        // Seeded — strict whitelist defined by the seed
        // TODO: look up the seed item from registry and delegate
        // For now fall back to soft whitelist; replace with:
        // return MudpitSeedItem.getForSeed(seedType).getMeatBiomass(stack);
        return MeatRegistry.getBiomassBasic(stack);
    }

    // -------------------------------------------------------------------------
    // Gestation
    // -------------------------------------------------------------------------

    private void tryStartGestation() {
        int unitCost = getUnitCost();
        if (biomass < unitCost) return; // not enough food for even one unit

        // Calculate how many units we can spawn this batch (limited by capacity)
        int unitsToSpawn = 0;
        int costAccumulated = 0;
        while (costAccumulated + unitCost <= capacity
                && costAccumulated + unitCost <= biomass) {
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
        int unitCost   = getUnitCost();
        int units      = pendingBatchCost / unitCost;
        biomass       -= pendingBatchCost;
        gestating      = false;
        gestationAge   = 0;
        pendingBatchCost = 0;
        setChanged();

        spawnBatch(level, pos, units);
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    private void spawnBatch(ServerLevel level, BlockPos pos, int count) {
        // Get rim positions — evenly distributed around the pit edge
        List<BlockPos> rimPositions = getRimPositions(level, pos, count);

        for (int i = 0; i < count; i++) {
            OrcEntity orc = ModEntities.ORC.get().create(level);
            if (orc == null) continue;

            BlockPos spawnPos = rimPositions.get(i % rimPositions.size());
            orc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);

            // Apply armor from queue
            ArmorSetQueue.ArmorSet set = armorQueue.popNext();
            if (!set.helmet.isEmpty())     orc.setItemSlot(EquipmentSlot.HEAD,      set.helmet);
            if (!set.chestplate.isEmpty()) orc.setItemSlot(EquipmentSlot.CHEST,     set.chestplate);
            if (!set.leggings.isEmpty())   orc.setItemSlot(EquipmentSlot.LEGS,      set.leggings);
            if (!set.boots.isEmpty())      orc.setItemSlot(EquipmentSlot.FEET,      set.boots);
            if (!set.weapon.isEmpty())     orc.setItemSlot(EquipmentSlot.MAINHAND,  set.weapon);

            // Store pit affiliation so orcs know which pit they came from
            orc.setPitPos(pos);

            level.addFreshEntity(orc);
        }
    }

    /**
     * Returns count evenly spaced positions around the pit rim at pitRadius distance.
     */
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
    // Helpers
    // -------------------------------------------------------------------------

    private int getUnitCost() {
        if (seedType == null) return COST_BASIC_ORC;
        // TODO: return cost based on seed type
        // e.g. if seedType == TROLL_SEED return COST_TROLL
        return COST_BASIC_ORC;
    }

    public void setSeed(ResourceLocation seedType) {
        this.seedType = seedType;
        setChanged();
    }

    public void clearSeed() {
        this.seedType = null;
        setChanged();
    }

    public ResourceLocation getSeedType()  { return seedType; }
    public int getCapacity()               { return capacity; }
    public int getBiomass()                { return biomass; }
    public boolean isGestating()           { return gestating; }
    public int getPitRadius()              { return pitRadius; }

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
    protected void saveAdditional(CompoundTag tag,
                                  net.minecraft.core.HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("Capacity",       capacity);
        tag.putInt("PitRadius",      pitRadius);
        tag.putInt("Biomass",        biomass);
        tag.putBoolean("Gestating",  gestating);
        tag.putInt("GestationAge",   gestationAge);
        tag.putInt("GestationTarget",gestationTarget);
        tag.putInt("PendingCost",    pendingBatchCost);
        if (seedType != null) tag.putString("SeedType", seedType.toString());
        tag.put("ArmorQueue", armorQueue.save(provider));
    }

    @Override
    protected void loadAdditional(CompoundTag tag,
                                  net.minecraft.core.HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        capacity         = tag.getInt("Capacity");
        pitRadius        = tag.getInt("PitRadius");
        biomass          = tag.getInt("Biomass");
        gestating        = tag.getBoolean("Gestating");
        gestationAge     = tag.getInt("GestationAge");
        gestationTarget  = tag.getInt("GestationTarget");
        pendingBatchCost = tag.getInt("PendingCost");
        seedType = tag.contains("SeedType")
                ? ResourceLocation.parse(tag.getString("SeedType")) : null;
        armorQueue = ArmorSetQueue.load(provider, tag.getCompound("ArmorQueue"));
    }
}