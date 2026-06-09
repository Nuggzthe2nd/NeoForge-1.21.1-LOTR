package net.nuggz.lotrmc.wartable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Validates the War Table multiblock structure.
 *
 * Required (top-down, all same Y level):
 *   O O O
 *   O C O   C = center (cartographer's table or war table block)
 *   O O O   O = obsidian
 *
 * Used both when right-clicking to convert AND every time the
 * war table block checks if it is still intact.
 */
public class WarTableStructureValidator {

    /**
     * Returns true if all 8 surrounding blocks at the same Y are obsidian.
     * @param center position of the cartographer's table or war table block
     */
    public static boolean isValid(Level level, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue; // skip center
                if (!level.getBlockState(center.offset(dx, 0, dz))
                        .is(Blocks.OBSIDIAN)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns a diagnostic string describing which obsidian blocks are missing.
     * Used for player feedback when the structure is invalid.
     */
    public static String diagnose(Level level, BlockPos center) {
        int missing = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!level.getBlockState(center.offset(dx, 0, dz)).is(Blocks.OBSIDIAN)) {
                    missing++;
                }
            }
        }
        return missing + "/8 obsidian blocks missing.";
    }
}