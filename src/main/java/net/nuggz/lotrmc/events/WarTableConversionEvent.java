package net.nuggz.lotrmc.events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.registry.ModBlockEntities;
import net.nuggz.lotrmc.wartable.WarTableStructureValidator;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Handles right-clicking a cartographer's table surrounded by obsidian
 * to convert it into a War Table block.
 *
 * Only Sauron or lieutenants can perform this conversion.
 * Gives diagnostic feedback if the structure is incomplete.
 */
@EventBusSubscriber(modid = LotrMC.MODID)
public class WarTableConversionEvent {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = event.getPos();

        // Only trigger on cartographer's table
        if (!level.getBlockState(pos).is(Blocks.CARTOGRAPHY_TABLE)) return;

        // Check faction
        MudlandsChunkData data = MudlandsChunkData.get(level);
        boolean isFaction = player.getUUID().equals(data.getSauronUUID())
                || data.isLieutenant(player.getUUID());
        if (!isFaction) return; // let vanilla handle it normally

        // Check structure
        if (!WarTableStructureValidator.isValid(level, pos)) {
            player.sendSystemMessage(Component.literal(
                    "§8[War Table] Structure incomplete: §c"
                            + WarTableStructureValidator.diagnose(level, pos)));
            // Don't cancel — let vanilla cartography table open normally
            return;
        }

        // Convert to war table
        level.setBlock(pos,
                ModBlockEntities.WAR_TABLE_BLOCK.get().defaultBlockState(), 3);

        player.sendSystemMessage(Component.literal(
                "§4The War Table awakens."));

        event.setCanceled(true);
    }
}