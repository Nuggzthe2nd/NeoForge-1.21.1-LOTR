package net.nuggz.lotrmc.wartable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.nuggz.lotrmc.registry.ModBlockEntities;

/**
 * War Table block entity.
 *
 * Minimal — just drives the periodic structure integrity check.
 * All UI data is pulled live from MudlandsChunkData and MudpitBlockEntity
 * when the screen opens, so nothing needs to be stored here.
 */
public class WarTableBlockEntity extends BlockEntity {

    private static final int INTEGRITY_CHECK_INTERVAL = 40;

    public WarTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WAR_TABLE.get(), pos, state);
    }

    public void serverTick(ServerLevel level, BlockPos pos) {
        if (level.getGameTime() % INTEGRITY_CHECK_INTERVAL == 0) {
            WarTableBlock.checkIntegrity(level, pos);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        // Nothing to save — all data is in MudlandsChunkData / MudpitBlockEntity
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
    }
}