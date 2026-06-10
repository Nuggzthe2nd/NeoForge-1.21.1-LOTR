package net.nuggz.lotrmc.registry;

import net.minecraft.world.item.Item;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.item.BrandItem;
import net.nuggz.lotrmc.item.NethercrownItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(LotrMC.MODID);

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    /**
     * The Brand — used on an orc to designate it as pit leader.
     * Right-click an orc with pit affiliation to open the naming screen.
     * Consumed on confirmation if validation passes.
     * Stacks to 16 so you can keep a supply without wasting inventory space.
     */
    public static final DeferredHolder<Item, BrandItem> BRAND =
            ITEMS.register("brand", () -> new BrandItem(new Item.Properties()));

    /**
     * The Nethercrown — received after ritual completion.
     * Used to designate tribute chest and (future) command orcs.
     */
    public static final DeferredHolder<Item, NethercrownItem> NETHERCROWN =
            ITEMS.register("nethercrown", () -> new NethercrownItem(new Item.Properties()));

    // Future items:
    // public static final DeferredHolder<Item, Item> ORC_SEED    = ...
    // public static final DeferredHolder<Item, Item> TROLL_SEED  = ...

    // -------------------------------------------------------------------------
    // Registration — call in your mod constructor:
    //   ModItems.ITEMS.register(modEventBus);
    // -------------------------------------------------------------------------
}