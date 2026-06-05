package net.nuggz.lotrmc.registry;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.item.BrandItem;
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

    public static final DeferredHolder<Item, BrandItem> BRAND =
            ITEMS.register("brand", () -> new BrandItem(new Item.Properties()));

}