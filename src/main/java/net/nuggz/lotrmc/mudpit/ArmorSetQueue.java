package net.nuggz.lotrmc.mudpit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;

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

        public CompoundTag save(net.minecraft.core.HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            if (!helmet.isEmpty())     tag.put("Helmet",     helmet.save(provider));
            if (!chestplate.isEmpty()) tag.put("Chest",      chestplate.save(provider));
            if (!leggings.isEmpty())   tag.put("Legs",       leggings.save(provider));
            if (!boots.isEmpty())      tag.put("Boots",      boots.save(provider));
            if (!weapon.isEmpty())     tag.put("Weapon",     weapon.save(provider));
            return tag;
        }

        public static ArmorSet load(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
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
    private final Map<String, Deque<ItemStack>> weaponsByMaterial     = new LinkedHashMap<>();
    // Weapons with no matching armor material go here
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

        String material = getMaterialKey(stack);
        if (material == null) return false; // not armor or weapon

        if (stack.getItem() instanceof ArmorItem armor) {
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
            weaponsByMaterial.computeIfAbsent(material, k -> new ArrayDeque<>()).add(stack.copyWithCount(1));
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
     * Drains the current bucket contents into new ArmorSets and merges them
     * with any sets already sitting in the assembled queue.
     *
     * The buckets act as a staging area: items land there via addItem(), then
     * reassemble() drains them. Previously assembled sets that haven't been
     * popped yet are preserved — without this, every addItem() call would wipe
     * whatever was already queued.
     */
    private void reassemble() {
        // Dissolve previously assembled sets back into the buckets so their pieces
        // can be combined with any newly added items into fuller sets.
        for (ArmorSet set : assembledQueue) {
            dissolveIntoBuffers(set);
        }
        assembledQueue.clear();

        Set<String> allMaterials = new LinkedHashSet<>();
        allMaterials.addAll(helmetsByMaterial.keySet());
        allMaterials.addAll(chestsByMaterial.keySet());
        allMaterials.addAll(legsByMaterial.keySet());
        allMaterials.addAll(bootsByMaterial.keySet());
        allMaterials.addAll(weaponsByMaterial.keySet());

        List<ArmorSet> fresh = new ArrayList<>();

        for (String material : allMaterials) {
            Deque<ItemStack> helmets  = helmetsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> chests   = chestsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> legs     = legsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> boots    = bootsByMaterial.getOrDefault(material, new ArrayDeque<>());
            Deque<ItemStack> weapons  = weaponsByMaterial.getOrDefault(material, new ArrayDeque<>());

            while (!helmets.isEmpty() || !chests.isEmpty()
                    || !legs.isEmpty() || !boots.isEmpty() || !weapons.isEmpty()) {
                ArmorSet set = new ArmorSet();
                if (!helmets.isEmpty())  set.helmet     = helmets.poll();
                if (!chests.isEmpty())   set.chestplate = chests.poll();
                if (!legs.isEmpty())     set.leggings   = legs.poll();
                if (!boots.isEmpty())    set.boots      = boots.poll();
                if (!weapons.isEmpty())  set.weapon     = weapons.poll();
                fresh.add(set);
            }
        }

        // Assign unmatched weapons to sets that have none
        for (ArmorSet set : fresh) {
            if (set.weapon.isEmpty() && !unmatchedWeapons.isEmpty()) set.weapon = unmatchedWeapons.poll();
        }
        while (!unmatchedWeapons.isEmpty()) {
            ArmorSet set = new ArmorSet();
            set.weapon = unmatchedWeapons.poll();
            fresh.add(set);
        }

        fresh.sort(Comparator.comparingInt(ArmorSet::pieceCount).reversed());
        assembledQueue.addAll(fresh);
    }

    private void dissolveIntoBuffers(ArmorSet set) {
        if (!set.helmet.isEmpty()) {
            String mat = getMaterialKey(set.helmet);
            if (mat != null) helmetsByMaterial.computeIfAbsent(mat, k -> new ArrayDeque<>()).add(set.helmet);
        }
        if (!set.chestplate.isEmpty()) {
            String mat = getMaterialKey(set.chestplate);
            if (mat != null) chestsByMaterial.computeIfAbsent(mat, k -> new ArrayDeque<>()).add(set.chestplate);
        }
        if (!set.leggings.isEmpty()) {
            String mat = getMaterialKey(set.leggings);
            if (mat != null) legsByMaterial.computeIfAbsent(mat, k -> new ArrayDeque<>()).add(set.leggings);
        }
        if (!set.boots.isEmpty()) {
            String mat = getMaterialKey(set.boots);
            if (mat != null) bootsByMaterial.computeIfAbsent(mat, k -> new ArrayDeque<>()).add(set.boots);
        }
        if (!set.weapon.isEmpty()) {
            String mat = getMaterialKey(set.weapon);
            if (mat != null) weaponsByMaterial.computeIfAbsent(mat, k -> new ArrayDeque<>()).add(set.weapon);
            else unmatchedWeapons.add(set.weapon);
        }
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
            // ArmorMaterial is a Holder in 1.21 — use the registered name
            return armor.getMaterial().getRegisteredName();
        }
        if (isWeapon(stack) && stack.getItem() instanceof TieredItem tiered) {
            return tiered.getTier().toString();
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

    // After reassemble() the buckets are always empty — the assembled queue IS the
    // persistent state, so we save/load that directly.
    public CompoundTag save(net.minecraft.core.HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag setList = new ListTag();
        for (ArmorSet set : assembledQueue) setList.add(set.save(provider));
        tag.put("Sets", setList);
        return tag;
    }

    public static ArmorSetQueue load(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        ArmorSetQueue q = new ArmorSetQueue();
        ListTag setList = tag.getList("Sets", Tag.TAG_COMPOUND);
        for (int i = 0; i < setList.size(); i++) {
            ArmorSet set = ArmorSet.load(provider, setList.getCompound(i));
            if (!set.isEmpty()) q.assembledQueue.add(set);
        }
        return q;
    }
}