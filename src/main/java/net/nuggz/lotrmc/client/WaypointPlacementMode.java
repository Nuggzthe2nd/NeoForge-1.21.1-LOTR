package net.nuggz.lotrmc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.nuggz.lotrmc.entity.order.SquadOrderData;

/**
 * Client-side singleton tracking whether the player is currently
 * placing waypoints for a patrol path.
 *
 * Activated when the player clicks "Place waypoints" in SquadOrdersScreen.
 * Deactivated when the player sneak+right-clicks the pit core block again,
 * or presses Escape.
 *
 * While active, NethercrownItem's sneak+right-click places a waypoint
 * at the clicked block position via SetWaypointPacket.
 */
public class WaypointPlacementMode {

    private static boolean active  = false;
    private static BlockPos pitPos = null;
    private static SquadOrderData localOrders = null;

    public static void activate(BlockPos pit, SquadOrderData orders) {
        active      = true;
        pitPos      = pit;
        localOrders = orders;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§8Waypoint placement mode active. "
                            + "§7Sneak+RClick blocks to place waypoints. "
                            + "§8RClick same block again to make it a watch post. "
                            + "§7Sneak+RClick the pit core to finish."));
        }
    }

    public static void deactivate() {
        active      = false;
        pitPos      = null;
        localOrders = null;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§8Waypoint placement finished."));
        }
    }

    public static boolean isActive()               { return active; }
    public static BlockPos getPitPos()             { return pitPos; }
    public static SquadOrderData getLocalOrders()  { return localOrders; }
}