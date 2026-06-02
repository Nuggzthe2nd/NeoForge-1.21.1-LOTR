package net.nuggz.lotrmc.mudpit;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.SwordItem;

import java.util.*;

/**
 * Manages the armor/weapon queue for a mudpit.
 *
 * Items are stored in per-material buckets. When an orc spawns, we pop
 * one ArmorSet from the front of the assembled queue.
 *
 * Assembly logic (runs whenever an item is added):
 *   1. Group all items by material (determined by ArmorMaterial or Tier)
 *   2. Within each material group, assemble as many complete or partial sets
 *      as possible — one helmet, one chestplate, one leggings, one boots,
 *      one weapon per set
 *   3. Sets are ordered: complete sets first, then partial by piece count desc
 *
 * "Same material" for armor = same ArmorMaterial instance.
 * "Same material" for weapons = same Tier.
 * Weapons are matched to the armor material group if the tier name matches
 * the material name (e.g. diamond sword → diamond armor group), otherwise
 * they go into their own group and get assigned to the next partial set
 * that has no weapon yet.
 */
public class ArmorSetQueue {

    /** One equipment loadout for a single orc. All fields nullable = unequipped. */
    public static class ArmorSet {
        public ItemStack helmet     = ItemStack.EMPTY;
        public ItemStack chestplate = ItemStack.EMPTY;
        public ItemStack leggings   = ItemStack.EMPTY;
        public ItemStack boots      = ItemStack.EMPTY;
        public ItemStack weapon     = ItemStack.EMPTY;

        public int pieceCount() {
            int c = 0;
            if (!helmet.isEmpty())     c++;
            if (!chestplate.isEmpty()) c++;
            if (!leggings.isEmpty())   c++;
            if (!boots.isEmpty())      c++;
            if (!weapon.isEmpty())     c++;
            return c;
        }

        public boolean isEmpty() { return pieceCount() == 0; }

        public CompoundTag save(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            if (!helmet.isEmpty())     tag.put("Helmet",     helmet.save(provider));
            if (!chestplate.isEmpty()) tag.put("Chest",      chestplate.save(provider));
            if (!leggings.isEmpty())   tag.put("Legs",       leggings.save(provider));
            if (!boots.isEmpty())      tag.put("Boots",      boots.save(provider));
            if (!weapon.isEmpty())     tag.put("Weapon",     weapon.save(provider));
            return tag;
        }

        public static ArmorSet load(HolderLookup.Provider provider, CompoundTag tag) {
            ArmorSet set = new ArmorSet();
            if (tag.contains("Helmet"))  set.helmet     = ItemStack.parseOptional(provider, tag.getCompound("Helmet"));
            if (tag.contains("Chest"))   set.chestplate = ItemStack.parseOptional(provider, tag.getCompound("Chest"));
            if (tag.contains("Legs"))    set.leggings   = ItemStack.parseOptional(provider, tag.getCompound("Legs"));
            if (tag.contains("Boots"))   set.boots      = ItemStack.parseOptional(provider, tag.getCompound("Boots"));
            if (tag.contains("Weapon"))  set.weapon     = ItemStack.parseOptional(provider, tag.getCompound("Weapon"));
            return set;
        }
    }

    // -------------------------------------------------------------------------
    // Internal storage — per-material buckets of individual pieces
    // -------------------------------------------------------------------------

    // Material key → lists of pieces by slot
    // We store raw items by slot per material, then assemble on demand.
    private final Map<String, Deque<ItemStack>> helmetsByMaterial     = new LinkedHashMap<>();
    private final Map<String, Deque<ItemStack>> chestsByMaterial      = new LinkedHashMap<>();
    private final Map<String, Deque<ItemStack>> legsByMaterial        = new LinkedHashMap<>();
    private final Map<String, Deque<ItemStack>> bootsByMaterial       = new LinkedHashMap<>();
    // All weapons go here; reassemble() pairs them with armor sets that have no weapon
    private final Deque<ItemStack> unmatchedWeapons = new ArrayDeque<>();

    // Pre-assembled queue — rebuilt whenever items are added
    private final Deque<ArmorSet> assembledQueue = new ArrayDeque<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Add an armor or weapon item to the queue and reassemble.
     * Ignores non-armor, non-weapon items silently.
     * Returns true if the item was accepted.
     */
    public boolean addItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (stack.getItem() instanceof ArmorItem armor) {
            String material = getMaterialKey(stack);
            if (material == null) return false;
            Map<String, Deque<ItemStack>> slotMap = switch (armor.getType()) {
                case HELMET     -> helmetsByMaterial;
                case CHESTPLATE -> chestsByMaterial;
                case LEGGINGS   -> legsByMaterial;
                case BOOTS      -> bootsByMaterial;
                default         -> null;
            };
            if (slotMap == null) return false;
            slotMap.computeIfAbsent(material, k -> new ArrayDeque<>()).add(stack.copyWithCount(1));
        } else if (isWeapon(stack)) {
            unmatchedWeapons.add(stack.copyWithCount(1));
        } else {
            return false;
        }

        reassemble();
        return true;
    }

    /**
     * Pop the next ArmorSet for a spawning orc.
     * Returns an empty ArmorSet if the queue is empty (orc spawns unarmored).
     */
    public ArmorSet popNext() {
        ArmorSet set = assembledQueue.poll();
        return set != null ? set : new ArmorSet();
    }

    public boolean hasAny() {
        return !assembledQueue.isEmpty();
    }

    public int queueSize() {
        return assembledQueue.size();
    }

    // -------------------------------------------------------------------------
    // Assembly
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the assembled queue from current bucket contents.
     * Called whenever an item is added.
     *
     * Algorithm:
     *   For each material group (in insertion order):
     *     While any slot for that material has items:
     *       Pop one from each available slot → one ArmorSet
     *   Then assign unmatched weapons to sets that have no weapon yet.
     *   Sort: complete sets first, then by piece count descending.
     */
    private void reassemble() {
        assembledQueue.clear();

        // Collect all material keys across armor slot maps
        Set<String> allMaterials = new LinkedHashSet<>();
        allMaterials.addAll(helmetsByMaterial.keySet());
        allMaterials.addAll(chestsByMaterial.keySet());
        allMaterials.addAll(legsByMaterial.keySet());
        allMaterials.addAll(bootsByMaterial.keySet());

        List<ArmorSet> sets = new ArrayList<>();

        for (String material : allMaterials) {
            Deque<ItemStack> helmets = helmetsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> chests  = chestsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> legs    = legsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> boots   = bootsByMaterial.getOrDefault(material, new ArrayDeque<>());

            // Keep assembling sets until all armor slots for this material are empty
            while (!helmets.isEmpty() || !chests.isEmpty() || !legs.isEmpty() || !boots.isEmpty()) {
                ArmorSet set = new ArmorSet();
                if (!helmets.isEmpty()) set.helmet     = helmets.poll();
                if (!chests.isEmpty())  set.chestplate = chests.poll();
                if (!legs.isEmpty())    set.leggings   = legs.poll();
                if (!boots.isEmpty())   set.boots      = boots.poll();
                sets.add(set);
            }
        }

        // Assign unmatched weapons to sets that have no weapon yet
        Deque<ItemStack> unmatched = new ArrayDeque<>(unmatchedWeapons);
        for (ArmorSet set : sets) {
            if (set.weapon.isEmpty() && !unmatched.isEmpty()) {
                set.weapon = unmatched.poll();
            }
        }
        // Any remaining unmatched weapons become weapon-only sets
        while (!unmatched.isEmpty()) {
            ArmorSet set = new ArmorSet();
            set.weapon = unmatched.poll();
            sets.add(set);
        }

        // Sort: most pieces first (complete sets bubble to front)
        sets.sort(Comparator.comparingInt(ArmorSet::pieceCount).reversed());
        assembledQueue.addAll(sets);
    }

    // -------------------------------------------------------------------------
    // Material key resolution
    // -------------------------------------------------------------------------

    /**
     * Returns a string key identifying the material of an armor or weapon item.
     * Returns null if the item is neither armor nor a recognized weapon.
     *
     * For armor: uses the ArmorMaterial's name.
     * For weapons: uses the Tier's toString() or a fallback.
     * This means a diamond sword and diamond armor share the key "minecraft:diamond".
     */
    private static String getMaterialKey(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getMaterial().unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse(null);
        }
        return null;
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof net.minecraft.world.item.AxeItem
                || stack.getItem() instanceof net.minecraft.world.item.PickaxeItem;
    }

    // -------------------------------------------------------------------------
    // NBT serialization
    // -------------------------------------------------------------------------

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();

        // Save each armor slot map
        tag.put("Helmets", saveSlotMap(provider, helmetsByMaterial));
        tag.put("Chests",  saveSlotMap(provider, chestsByMaterial));
        tag.put("Legs",    saveSlotMap(provider, legsByMaterial));
        tag.put("Boots",   saveSlotMap(provider, bootsByMaterial));

        // Save unmatched weapons
        ListTag unmatchedTag = new ListTag();
        for (ItemStack s : unmatchedWeapons) unmatchedTag.add(s.save(provider));
        tag.put("Unmatched", unmatchedTag);

        return tag;
    }

    public static ArmorSetQueue load(HolderLookup.Provider provider, CompoundTag tag) {
        ArmorSetQueue q = new ArmorSetQueue();
        loadSlotMap(provider, tag.getCompound("Helmets"), q.helmetsByMaterial);
        loadSlotMap(provider, tag.getCompound("Chests"),  q.chestsByMaterial);
        loadSlotMap(provider, tag.getCompound("Legs"),    q.legsByMaterial);
        loadSlotMap(provider, tag.getCompound("Boots"),   q.bootsByMaterial);

        ListTag unmatchedTag = tag.getList("Unmatched", Tag.TAG_COMPOUND);
        for (int i = 0; i < unmatchedTag.size(); i++) {
            q.unmatchedWeapons.add(ItemStack.parseOptional(provider, unmatchedTag.getCompound(i)));
        }

        q.reassemble();
        return q;
    }

    private static CompoundTag saveSlotMap(HolderLookup.Provider provider, Map<String, Deque<ItemStack>> map) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Deque<ItemStack>> entry : map.entrySet()) {
            ListTag list = new ListTag();
            for (ItemStack s : entry.getValue()) list.add(s.save(provider));
            tag.put(entry.getKey(), list);
        }
        return tag;
    }

    private static void loadSlotMap(HolderLookup.Provider provider, CompoundTag tag, Map<String, Deque<ItemStack>> map) {
        for (String key : tag.getAllKeys()) {
            ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
            Deque<ItemStack> deque = new ArrayDeque<>();
            for (int i = 0; i < list.size(); i++) deque.add(ItemStack.parseOptional(provider, list.getCompound(i)));
            map.put(key, deque);
        }
    }
}
