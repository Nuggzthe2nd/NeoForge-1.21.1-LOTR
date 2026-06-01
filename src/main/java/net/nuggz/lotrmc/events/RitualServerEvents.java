package net.nuggz.lotrmc.events;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.registry.ModItems;
import net.nuggz.lotrmc.ritual.AltarValidator;
import net.nuggz.lotrmc.ritual.RitualEffects;
import net.nuggz.lotrmc.ritual.RitualState;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.nuggz.lotrmc.worlddata.MudlandsManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = LotrMC.MODID)
public class RitualServerEvents {

    // Check altar integrity every 20 ticks (1 second) — cheap enough
    private static final int INTEGRITY_CHECK_INTERVAL = 20;
    // Fire ambient particles every 40 ticks
    private static final int AMBIENT_EFFECT_INTERVAL = 40;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Only fire once (main hand) and only on the server
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.SOUL_SAND)) return;
        if (!event.getItemStack().is(Items.NETHER_STAR)) return;

        // Consume the event so the block doesn't do anything extra
        event.setCanceled(true);

        RitualState ritual = RitualState.get(level);
        MudlandsChunkData mudData = MudlandsChunkData.get(level);

        if (mudData.hasSauron()) {
            player.sendSystemMessage(Component.literal("§cA dark lord already rules this world."));
            return;
        }
        if (ritual.isRitualActive()) {
            player.sendSystemMessage(Component.literal("§cThe ritual is already underway."));
            return;
        }
        if (ritual.isOnCooldown(level)) {
            long days = ritual.getCooldownDaysRemaining(level);
            player.sendSystemMessage(Component.literal(
                    "§cThe ritual cannot be attempted for another §4" + days + " §cdays."));
            return;
        }

        // Always print the full diagnostic so the player knows what is missing
        AltarValidator.DiagnosticResult diag = AltarValidator.diagnose(level, pos);
        for (String line : diag.lines) {
            player.sendSystemMessage(Component.literal(line));
        }
        if (!diag.valid) return;

        // Structure is valid — start the ritual and consume the nether star
        ritual.startRitual(player.getUUID(), pos, level);
        RitualEffects.playStartEffects(level, pos);

        if (!player.isCreative()) {
            event.getItemStack().shrink(1);
        }

        player.sendSystemMessage(Component.literal(
                "§8The ritual has begun. Five days until the crown is forged."));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(ServerLevel.OVERWORLD)) return;

        RitualState ritual = RitualState.get(level);
        if (!ritual.isRitualActive()) return;

        long gameTick = level.getGameTime();
        BlockPos center = ritual.getAltarCenter();

        // --- Integrity check ---
        if (gameTick % INTEGRITY_CHECK_INTERVAL == 0) {
            if (!AltarValidator.isIntact(level, center)) {
                failRitual(level, ritual);
                return;
            }
        }

        // --- Ambient effects ---
        if (gameTick % AMBIENT_EFFECT_INTERVAL == 0) {
            int ritualDay = ritual.getRitualDay(level);
            RitualEffects.tickAmbientEffects(level, center, Math.max(ritualDay, 0));
        }

        // --- Day progression ---
        int ritualDay = ritual.getRitualDay(level);

        if (ritualDay >= 0 && ritual.shouldFireStorm(ritualDay)) {
            ritual.markStormFired(ritualDay);

            if (ritualDay < 4) {
                // Days 0-3: escalate the storm
                RitualEffects.applyDayStorm(level, center, ritualDay);
            } else {
                // Day 4: ritual completes
                completeRitual(level, ritual);
            }
        }
    }

    // -------------------------------------------------------------------------

    private static void completeRitual(ServerLevel level, RitualState ritual) {
        RitualEffects.applyDayStorm(level, ritual.getAltarCenter(), 4);

        // Capture UUID before completeRitual() wipes the fields
        java.util.UUID ritualistUUID = ritual.getRitualistUUID();

        // Designate Sauron and spawn the mudlands
        MudlandsChunkData mudData = MudlandsChunkData.get(level);
        if (!mudData.hasSauron()) {
            MudlandsManager.initiateSauron(level, ritualistUUID, ritual.getAltarCenter());
        }

        ritual.completeRitual();

        // Notify the ritualist and give the crown
        ServerPlayer ritualist = level.getServer().getPlayerList().getPlayer(ritualistUUID);
        if (ritualist != null) {
            ritualist.sendSystemMessage(Component.literal(
                    "§4The Nethercrown is yours. The Mudlands rise."));
            ritualist.getInventory().add(new ItemStack(ModItems.NETHERCROWN.get()));
        }
    }

    private static void failRitual(ServerLevel level, RitualState ritual) {
        // Notify all players
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(
                    "§8The ritual has been interrupted. The darkness recedes... for now."));
        }

        // Clear weather — the storm dies when the ritual fails
        level.setWeatherParameters(6000, 0, false, false);

        ritual.failRitual(level);
    }
}
