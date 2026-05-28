package net.nuggz.lotrmc.events;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.command.MudlandsDebugCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = LotrMC.MODID)
public class MudlandsCommandEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MudlandsDebugCommand.register(event.getDispatcher());
    }
}

