package net.nuggz.lotrmc.worlddata;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.nuggz.lotrmc.LotrMC;

import javax.annotation.Nullable;

/**
 * Stores the position of the single designated tribute chest.
 *
 * The tribute chest is designated by right-clicking any chest block
 * inside the mudlands with the Nethercrown item.
 *
 * If the chest is destroyed the position is cleared automatically
 * by TributeChestEvents. A new chest can be designated at any time.
 */
public class TributeChestData extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_tribute_chest";

    @Nullable private BlockPos chestPos = null;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static TributeChestData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TributeChestData::new, TributeChestData::load),
                DATA_NAME);
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    public boolean hasChest() { return chestPos != null; }

    @Nullable
    public BlockPos getChestPos() { return chestPos; }

    public void setChest(BlockPos pos) {
        this.chestPos = pos;
        setDirty();
    }

    public void clearChest() {
        this.chestPos = null;
        setDirty();
    }

    /**
     * Returns true if the chest block still exists at the stored position.
     * Clears the position if the block is gone.
     */
    public boolean isChestValid(ServerLevel level) {
        if (chestPos == null) return false;
        var state = level.getBlockState(chestPos);
        if (!state.is(net.minecraft.world.level.block.Blocks.CHEST)
                && !state.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
            clearChest();
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (chestPos != null) tag.putLong("ChestPos", chestPos.asLong());
        return tag;
    }

    public static TributeChestData load(CompoundTag tag, HolderLookup.Provider provider) {
        TributeChestData data = new TributeChestData();
        if (tag.contains("ChestPos"))
            data.chestPos = BlockPos.of(tag.getLong("ChestPos"));
        return data;
    }
}