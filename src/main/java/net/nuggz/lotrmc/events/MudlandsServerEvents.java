package net.nuggz.lotrmc.events;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.worlddata.MudlandsManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = LotrMC.MODID)
public class MudlandsServerEvents {

    /**
     * Server-side level tick — runs every tick for every loaded dimension.
     * We gate on the overworld so collapse logic only runs once per tick.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(ServerLevel.OVERWORLD)) return;

        MudlandsManager.tickCollapse(level);
    }
}