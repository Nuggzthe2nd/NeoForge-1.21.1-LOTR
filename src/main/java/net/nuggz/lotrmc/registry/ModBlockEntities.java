package net.nuggz.lotrmc.registry;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.nuggz.lotrmc.LotrMC;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.mudpit.MudpitCoreBlock;
import net.nuggz.lotrmc.wartable.WarTableBlock;
import net.nuggz.lotrmc.wartable.WarTableBlockEntity;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(LotrMC.MODID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(LotrMC.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(
                    net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,
                    LotrMC.MODID);

    // --- Mudpit core ---
    public static final DeferredBlock<MudpitCoreBlock> MUDPIT_CORE_BLOCK =
            BLOCKS.register("mudpit_core", MudpitCoreBlock::new);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MudpitBlockEntity>> MUDPIT =
            BLOCK_ENTITIES.register("mudpit", () ->
                    BlockEntityType.Builder
                            .of(MudpitBlockEntity::new, MUDPIT_CORE_BLOCK.get())
                            .build(null));

    // --- War Table ---
    public static final DeferredBlock<WarTableBlock> WAR_TABLE_BLOCK =
            BLOCKS.register("war_table", WarTableBlock::new);

    public static final net.neoforged.neoforge.registries.DeferredItem<BlockItem> MUDPIT_CORE_ITEM =
            ITEMS.register("mudpit_core", () -> new BlockItem(MUDPIT_CORE_BLOCK.get(), new Item.Properties()));

    public static final net.neoforged.neoforge.registries.DeferredItem<BlockItem> WAR_TABLE_ITEM =
            ITEMS.register("war_table", () -> new BlockItem(WAR_TABLE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarTableBlockEntity>> WAR_TABLE =
            BLOCK_ENTITIES.register("war_table", () ->
                    BlockEntityType.Builder
                            .of(WarTableBlockEntity::new, WAR_TABLE_BLOCK.get())
                            .build(null));

    // -------------------------------------------------------------------------
    // Registration — call in your mod constructor:
    //   ModBlockEntities.BLOCKS.register(modEventBus);
    //   ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
    // -------------------------------------------------------------------------
}