package net.nuggz.lotrmc.warmap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.nuggz.lotrmc.entity.OrcEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of a single orc's state before it leaves on a raid.
 * Used to restore the orc faithfully when it returns.
 *
 * Stores: identity, leader status, scars, custom name, equipment.
 * Does NOT store AI state or position — those are irrelevant for a
 * returning orc which always spawns at its pit.
 */
public class RaidOrcSnapshot {

    public final UUID originalUUID;
    public final boolean isLeader;
    public final int scarCount;
    public final String customName; // null if unnamed

    // Equipment
    public final ItemStack helmet;
    public final ItemStack chestplate;
    public final ItemStack leggings;
    public final ItemStack boots;
    public final ItemStack weapon;

    // Leader stats — null if not a leader
    public final net.nuggz.lotrmc.entity.OrcLeaderData leaderData;

    public RaidOrcSnapshot(OrcEntity orc) {
        this.originalUUID  = orc.getUUID();
        this.isLeader      = orc.isLeader();
        this.scarCount     = orc.getScarCount();
        this.customName    = orc.getCustomName() != null
                ? orc.getCustomName().getString() : null;
        this.helmet        = orc.getItemBySlot(EquipmentSlot.HEAD).copy();
        this.chestplate    = orc.getItemBySlot(EquipmentSlot.CHEST).copy();
        this.leggings      = orc.getItemBySlot(EquipmentSlot.LEGS).copy();
        this.boots         = orc.getItemBySlot(EquipmentSlot.FEET).copy();
        this.weapon        = orc.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        this.leaderData    = orc.getLeaderData();
    }

    // Private constructor for NBT loading
    private RaidOrcSnapshot(UUID uuid, boolean isLeader, int scarCount,
                            String customName, ItemStack helmet, ItemStack chestplate,
                            ItemStack leggings, ItemStack boots, ItemStack weapon,
                            net.nuggz.lotrmc.entity.OrcLeaderData leaderData) {
        this.originalUUID  = uuid;
        this.isLeader      = isLeader;
        this.scarCount     = scarCount;
        this.customName    = customName;
        this.helmet        = helmet;
        this.chestplate    = chestplate;
        this.leggings      = leggings;
        this.boots         = boots;
        this.weapon        = weapon;
        this.leaderData    = leaderData;
    }

    /**
     * Apply this snapshot's data to a freshly created OrcEntity.
     * Call this after moveTo() but before addFreshEntity().
     */
    public void applyTo(OrcEntity orc,
                        net.minecraft.core.BlockPos pitPos,
                        net.nuggz.lotrmc.mudpit.MudpitBlockEntity pit,
                        int bonusScars) {
        // Pit affiliation
        orc.setPitPos(pitPos);

        // Scars — restore original count then add bonus from raid
        orc.restoreScarCount(scarCount);
        if (bonusScars > 0) orc.addScars(bonusScars);

        // Equipment
        if (!helmet.isEmpty())     orc.setItemSlot(EquipmentSlot.HEAD,     helmet.copy());
        if (!chestplate.isEmpty()) orc.setItemSlot(EquipmentSlot.CHEST,    chestplate.copy());
        if (!leggings.isEmpty())   orc.setItemSlot(EquipmentSlot.LEGS,     leggings.copy());
        if (!boots.isEmpty())      orc.setItemSlot(EquipmentSlot.FEET,     boots.copy());
        if (!weapon.isEmpty())     orc.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());

        // Leader state
        if (isLeader && leaderData != null) {
            orc.restoreLeaderState(customName, leaderData);
            pit.setLeader(orc.getUUID());
        } else if (customName != null) {
            orc.setCustomName(net.minecraft.network.chat.Component.literal(customName));
            orc.setCustomNameVisible(true);
        }
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public CompoundTag save(net.minecraft.core.HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID",      originalUUID);
        tag.putBoolean("Leader", isLeader);
        tag.putInt("Scars",      scarCount);
        if (customName != null) tag.putString("Name", customName);

        tag.put("Helmet",     helmet.isEmpty()     ? new CompoundTag() : helmet.save(provider));
        tag.put("Chest",      chestplate.isEmpty() ? new CompoundTag() : chestplate.save(provider));
        tag.put("Legs",       leggings.isEmpty()   ? new CompoundTag() : leggings.save(provider));
        tag.put("Boots",      boots.isEmpty()      ? new CompoundTag() : boots.save(provider));
        tag.put("Weapon",     weapon.isEmpty()     ? new CompoundTag() : weapon.save(provider));

        if (leaderData != null) tag.put("LeaderData", leaderData.save());
        return tag;
    }

    public static RaidOrcSnapshot load(CompoundTag tag,
                                       net.minecraft.core.HolderLookup.Provider provider) {
        UUID uuid       = tag.getUUID("UUID");
        boolean leader  = tag.getBoolean("Leader");
        int scars       = tag.getInt("Scars");
        String name     = tag.contains("Name") ? tag.getString("Name") : null;

        ItemStack helmet     = ItemStack.parseOptional(provider, tag.getCompound("Helmet"));
        ItemStack chestplate = ItemStack.parseOptional(provider, tag.getCompound("Chest"));
        ItemStack leggings   = ItemStack.parseOptional(provider, tag.getCompound("Legs"));
        ItemStack boots      = ItemStack.parseOptional(provider, tag.getCompound("Boots"));
        ItemStack weapon     = ItemStack.parseOptional(provider, tag.getCompound("Weapon"));

        net.nuggz.lotrmc.entity.OrcLeaderData leaderData = null;
        if (tag.contains("LeaderData"))
            leaderData = net.nuggz.lotrmc.entity.OrcLeaderData.load(
                    tag.getCompound("LeaderData"));

        return new RaidOrcSnapshot(uuid, leader, scars, name,
                helmet, chestplate, leggings, boots, weapon, leaderData);
    }

    // -------------------------------------------------------------------------
    // List helpers
    // -------------------------------------------------------------------------

    public static ListTag saveList(List<RaidOrcSnapshot> snapshots,
                                   net.minecraft.core.HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (RaidOrcSnapshot s : snapshots) list.add(s.save(provider));
        return list;
    }

    public static List<RaidOrcSnapshot> loadList(ListTag list,
                                                 net.minecraft.core.HolderLookup.Provider provider) {
        List<RaidOrcSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            snapshots.add(load(list.getCompound(i), provider));
        return snapshots;
    }
}