package net.nuggz.lotrmc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client-side singleton tracking whether the player is currently
 * picking a guard area center block.
 *
 * Activated when the player clicks "Set Center" in SquadOrdersScreen.
 * A single sneak+right-click with the Nethercrown sets the center and
 * deactivates the mode automatically.
 */
public class GuardCenterPlacementMode {

    private static boolean active = false;
    private static BlockPos pitPos = null;

    public static void activate(BlockPos pit) {
        active = true;
        pitPos = pit;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§8Guard center placement active. "
                            + "§7Sneak+RClick a block to set the guard area center."));
        }
    }

    public static void deactivate() {
        active = false;
        pitPos = null;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§8Guard center set."));
        }
    }

    public static boolean isActive() { return active; }
    public static BlockPos getPitPos() { return pitPos; }
}
