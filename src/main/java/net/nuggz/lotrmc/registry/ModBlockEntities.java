package net.nuggz.lotrmc.registry;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.mudpit.MudpitCoreBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(LotrMC.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(
                    net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,
                    LotrMC.MODID);

    // --- Mudpit core block ---
    public static final DeferredBlock<MudpitCoreBlock> MUDPIT_CORE_BLOCK =
            BLOCKS.register("mudpit_core", MudpitCoreBlock::new);

    // --- Mudpit block entity ---
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MudpitBlockEntity>> MUDPIT =
            BLOCK_ENTITIES.register("mudpit", () ->
                    BlockEntityType.Builder
                            .of(MudpitBlockEntity::new, MUDPIT_CORE_BLOCK.get())
                            .build(null));

    // -------------------------------------------------------------------------
    // Registration — call both in your mod constructor:
    //   ModBlockEntities.BLOCKS.register(modEventBus);
    //   ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
    // -------------------------------------------------------------------------
}
