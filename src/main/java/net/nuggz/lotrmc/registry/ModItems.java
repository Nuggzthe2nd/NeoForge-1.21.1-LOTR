package net.nuggz.lotrmc.registry;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.item.NethercrownItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(LotrMC.MODID);

    public static final DeferredItem<NethercrownItem> NETHERCROWN =
            ITEMS.register("nethercrown",
                    () -> new NethercrownItem(new Item.Properties().stacksTo(1)));
}