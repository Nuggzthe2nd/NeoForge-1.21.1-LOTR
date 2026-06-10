package net.nuggz.lotrmc.events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.registry.ModItems;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.nuggz.lotrmc.worlddata.TributeChestData;

@EventBusSubscriber(modid = LotrMC.MODID)
public class TributeChestEvents {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Only trigger when holding the Nethercrown
        if (!event.getItemStack().is(ModItems.NETHERCROWN.get())) return;

        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.CHEST)
                && !level.getBlockState(pos).is(Blocks.TRAPPED_CHEST)) return;

        // Cancel first so the chest GUI never opens
        event.setCanceled(true);

        MudlandsChunkData mudData = MudlandsChunkData.get(level);

        if (!player.getUUID().equals(mudData.getSauronUUID())) {
            player.sendSystemMessage(Component.literal(
                    "§cOnly the dark lord may designate a tribute chest."));
            return;
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        if (!mudData.isConverted(chunkPos)) {
            player.sendSystemMessage(Component.literal(
                    "§cThe tribute chest must be placed within your mudlands."));
            return;
        }

        TributeChestData data = TributeChestData.get(level);

        if (data.hasChest() && !data.getChestPos().equals(pos)) {
            player.sendSystemMessage(Component.literal(
                    "§8Previous tribute chest at §7"
                            + data.getChestPos().toShortString()
                            + " §8has been released."));
        }

        data.setChest(pos);
        player.sendSystemMessage(Component.literal(
                "§4This chest has been bound as the tribute chest. "
                        + "§8All raid spoils will be delivered here."));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        if (!event.getState().is(Blocks.CHEST)
                && !event.getState().is(Blocks.TRAPPED_CHEST)) return;

        TributeChestData data = TributeChestData.get(level);
        if (!data.hasChest()) return;

        if (data.getChestPos().equals(event.getPos())) {
            data.clearChest();
            event.getPlayer().sendSystemMessage(
                    Component.literal(
                            "§8The tribute chest has been destroyed. "
                                    + "§7Designate a new one with the Nethercrown."));
        }
    }
}
