package net.nuggz.lotrmc.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.nuggz.lotrmc.worlddata.TributeChestData;

/**
 * The Nethercrown — given to the player when the ritual completes.
 *
 * This is Sauron's primary command item. Current uses:
 *   - Right-click a chest inside the mudlands → designate as tribute chest
 *
 * Future uses:
 *   - Radial command menu for ordering orcs
 *   - Lieutenant designation
 *
 * Register in ModItems as "nethercrown".
 */
public class NethercrownItem extends Item {

    public NethercrownItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // Client-side: handle placement modes first
        if (context.getLevel().isClientSide()) {
            return handleClientSidePlacement(context);
        }

        // Server-side only below this point
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos clickedPos = context.getClickedPos();

        // --- Tribute chest designation ---
        if (level.getBlockState(clickedPos).is(Blocks.CHEST)
                || level.getBlockState(clickedPos).is(Blocks.TRAPPED_CHEST)) {
            return tryDesignateTributeChest(level, player, clickedPos);
        }

        // Future: other right-click uses go here
        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // Placement modes (waypoints + guard center)
    // -------------------------------------------------------------------------

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private InteractionResult handleClientSidePlacement(
            net.minecraft.world.item.context.UseOnContext context) {
        if (!context.getPlayer().isShiftKeyDown()) return InteractionResult.PASS;

        net.minecraft.core.BlockPos clickedPos = context.getClickedPos();

        // --- Guard center placement ---
        if (net.nuggz.lotrmc.client.GuardCenterPlacementMode.isActive()) {
            net.minecraft.core.BlockPos pitPos =
                    net.nuggz.lotrmc.client.GuardCenterPlacementMode.getPitPos();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new net.nuggz.lotrmc.network.SetGuardCenterPacket(pitPos, clickedPos));
            net.nuggz.lotrmc.client.GuardCenterPlacementMode.deactivate();
            return InteractionResult.SUCCESS;
        }

        // --- Waypoint placement ---
        if (net.nuggz.lotrmc.client.WaypointPlacementMode.isActive()) {
            net.minecraft.core.BlockPos pitPos =
                    net.nuggz.lotrmc.client.WaypointPlacementMode.getPitPos();

            // Sneak+right-click the pit core itself → finish placement
            if (clickedPos.equals(pitPos)) {
                net.nuggz.lotrmc.client.WaypointPlacementMode.deactivate();
                return InteractionResult.SUCCESS;
            }

            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new net.nuggz.lotrmc.network.SetWaypointPacket(pitPos, clickedPos));

            net.nuggz.lotrmc.entity.order.SquadOrderData local =
                    net.nuggz.lotrmc.client.WaypointPlacementMode.getLocalOrders();
            if (local != null) local.addOrUpgradeWaypoint(clickedPos);

            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // Tribute chest designation
    // -------------------------------------------------------------------------

    private InteractionResult tryDesignateTributeChest(ServerLevel level,
                                                       ServerPlayer player,
                                                       BlockPos chestPos) {
        MudlandsChunkData mudData = MudlandsChunkData.get(level);

        // Only Sauron can designate the tribute chest
        if (!player.getUUID().equals(mudData.getSauronUUID())) {
            player.sendSystemMessage(Component.literal(
                    "§cOnly the dark lord may designate a tribute chest."));
            return InteractionResult.FAIL;
        }

        // Chest must be inside the mudlands
        ChunkPos chunkPos = new ChunkPos(chestPos);
        if (!mudData.isConverted(chunkPos)) {
            player.sendSystemMessage(Component.literal(
                    "§cThe tribute chest must be placed within your mudlands."));
            return InteractionResult.FAIL;
        }

        TributeChestData data = TributeChestData.get(level);

        // Notify if replacing an existing chest
        if (data.hasChest() && !data.getChestPos().equals(chestPos)) {
            player.sendSystemMessage(Component.literal(
                    "§8Previous tribute chest at §7"
                            + data.getChestPos().toShortString()
                            + " §8has been released."));
        }

        data.setChest(chestPos);

        player.sendSystemMessage(Component.literal(
                "§4This chest has been bound as the tribute chest. "
                        + "§8All raid spoils will be delivered here."));

        return InteractionResult.CONSUME;
    }
}