package net.nuggz.lotrmc.registry;

import net.nuggz.lotrmc.LotrMC;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(LotrMC.MODID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(LotrMC.MODID);

    /**
     * The topmost surface layer — replaces grass_block.
     * Visually dark, wet-looking mud with a flat top.
     * TODO: give this a proper Block subclass with custom sounds/properties later.
     */
    public static final DeferredBlock<Block> BALT_SURFACE = BLOCKS.registerSimpleBlock(
            "balt_surface",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(0.5f)
                    .sound(SoundType.MUD)
    );
    public static final DeferredItem<BlockItem> BALT_SURFACE_ITEM =
            ITEMS.register("balt_surface", () -> new BlockItem(BALT_SURFACE.get(), new Item.Properties()));

    /**
     * Subsurface mud — replaces dirt.
     * Also used as the pit walls and floor.
     */
    public static final DeferredBlock<Block> BALT = BLOCKS.registerSimpleBlock(
            "balt",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(0.5f)
                    .sound(SoundType.MUD)
    );
    public static final DeferredItem<BlockItem> BALT_ITEM =
            ITEMS.register("balt", () -> new BlockItem(BALT.get(), new Item.Properties()));

    /**
     * Deep mudstone — replaces stone/deepslate.
     * Harder than mud, gives the underground a different feel.
     */
    public static final DeferredBlock<Block> BALGUNDT = BLOCKS.registerSimpleBlock(
            "balgundt",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5f, 6.0f)
                    .sound(SoundType.STONE)
    );
    public static final DeferredItem<BlockItem> BALGUNDT_ITEM =
            ITEMS.register("balgundt", () -> new BlockItem(BALGUNDT.get(), new Item.Properties()));

    // -------------------------------------------------------------------------
    // Registration — call both from your mod constructor:
    //   ModBlocks.BLOCKS.register(modEventBus);
    //   ModBlocks.ITEMS.register(modEventBus);
    // -------------------------------------------------------------------------
}
