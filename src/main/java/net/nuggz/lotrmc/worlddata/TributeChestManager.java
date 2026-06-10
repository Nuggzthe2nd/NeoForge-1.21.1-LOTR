package net.nuggz.lotrmc.worlddata;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.List;
import java.util.UUID;

/**
 * Handles delivering raid loot to the tribute chest.
 *
 * Called from RaidManager.completeRaid() when a raid party returns.
 * If the chest is full, excess loot is voided and the Sauron player
 * receives a chat warning.
 */
public class TributeChestManager {

    /**
     * Deliver a list of loot items to the tribute chest.
     * Notifies Sauron on success or if loot is voided.
     *
     * @param level   the server level
     * @param loot    items to deposit
     * @param source  description of where loot came from (e.g. "Plains Village raid")
     */
    public static void deliverLoot(ServerLevel level, List<ItemStack> loot, String source) {
        if (loot.isEmpty()) return;

        TributeChestData data = TributeChestData.get(level);

        if (!data.isChestValid(level)) {
            // No chest designated — notify Sauron and void
            notifySauron(level, "§cNo tribute chest designated. "
                    + "Loot from §4" + source + " §cwas lost. "
                    + "§7Right-click a chest in your mudlands with the Nethercrown.");
            return;
        }

        BlockPos pos = data.getChestPos();
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            data.clearChest();
            notifySauron(level, "§cTribute chest not found. Loot from §4" + source + " §cwas lost.");
            return;
        }

        // Try to insert each item
        List<ItemStack> voided = new java.util.ArrayList<>();
        var inventory = chest;

        for (ItemStack stack : loot) {
            ItemStack remaining = insertItem(inventory, stack.copy());
            if (!remaining.isEmpty()) {
                voided.add(remaining);
            }
        }

        // Notify Sauron
        int deposited = loot.size() - voided.size();
        if (voided.isEmpty()) {
            notifySauron(level, "§8Tribute received from §4" + source
                    + "§8: §7" + deposited + " item stack(s) deposited.");
        } else {
            notifySauron(level, "§8Tribute from §4" + source
                    + "§8: §7" + deposited + " deposited. §c"
                    + voided.size() + " item stack(s) voided — tribute chest is full.");
        }
    }

    /**
     * Try to insert a stack into the chest inventory.
     * Returns any remainder that didn't fit.
     */
    private static ItemStack insertItem(ChestBlockEntity chest, ItemStack stack) {
        int size = chest.getContainerSize();

        // First pass: try to merge with existing stacks of the same type
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slot = chest.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int transfer = Math.min(space, stack.getCount());
                slot.grow(transfer);
                stack.shrink(transfer);
                chest.setItem(i, slot);
            }
        }

        // Second pass: fill empty slots
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, stack.copy());
                stack = ItemStack.EMPTY;
            }
        }

        chest.setChanged();
        return stack;
    }

    private static void notifySauron(ServerLevel level, String message) {
        MudlandsChunkData mud = MudlandsChunkData.get(level);
        UUID sauronUUID = mud.getSauronUUID();
        if (sauronUUID == null) return;
        ServerPlayer sauron = level.getServer().getPlayerList().getPlayer(sauronUUID);
        if (sauron != null) {
            sauron.sendSystemMessage(Component.literal(message));
        }
    }
}