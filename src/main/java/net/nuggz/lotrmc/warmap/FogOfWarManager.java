package net.nuggz.lotrmc.warmap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

/**
 * Stateless manager for fog of war reveal operations.
 * Delegates storage to WarMapCache.
 *
 * Called from:
 *   - MudlandsManager.initiateSauron()    → initial reveal
 *   - MudlandsManager.expandSauron()      → reveal new ring + 1 border
 *   - MudlandsManager.expandLieutenant()  → reveal lieutenant territory + 1 border
 *   - OrcEntity (future warband system)   → reveal chunks player walks through
 */
public class FogOfWarManager {

    /**
     * Called when the ritual completes — reveals all starting mudlands
     * chunks plus a 1-chunk border around the entire territory.
     */
    public static void onSauronInitiated(ServerLevel level) {
        WarMapCache cache = WarMapCache.get(level);
        cache.revealMudlandsAndBorder(level);
    }

    /**
     * Called when Sauron's territory expands by one ring.
     * Reveals the new outermost ring of mudlands chunks + 1 border.
     *
     * @param origin    Sauron's mudlands origin chunk
     * @param newRadius the NEW radius after expansion
     */
    public static void onSauronExpanded(ServerLevel level, ChunkPos origin, int newRadius) {
        WarMapCache cache = WarMapCache.get(level);

        // Reveal the new outermost ring + 1 border chunk around each new chunk
        for (int dx = -newRadius; dx <= newRadius; dx++) {
            for (int dz = -newRadius; dz <= newRadius; dz++) {
                // Only the new outermost ring (Chebyshev distance == newRadius)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == newRadius) {
                    ChunkPos newChunk = new ChunkPos(origin.x + dx, origin.z + dz);
                    // Reveal this chunk and its 1-chunk border
                    cache.revealWithBorder(newChunk, 1);
                }
            }
        }
    }

    /**
     * Called when a lieutenant's territory expands.
     * Same logic as Sauron expansion but for lieutenant origin.
     */
    public static void onLieutenantExpanded(ServerLevel level,
                                            ChunkPos origin, int newRadius) {
        WarMapCache cache = WarMapCache.get(level);

        for (int dx = -newRadius; dx <= newRadius; dx++) {
            for (int dz = -newRadius; dz <= newRadius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == newRadius) {
                    ChunkPos newChunk = new ChunkPos(origin.x + dx, origin.z + dz);
                    cache.revealWithBorder(newChunk, 1);
                }
            }
        }
    }

    /**
     * Reveal a single chunk and its immediate border.
     * Called when a warband/scout passes through.
     */
    public static void revealChunk(ServerLevel level, ChunkPos pos) {
        WarMapCache cache = WarMapCache.get(level);
        cache.revealWithBorder(pos, 1);
    }

    /**
     * Reveal a circular area around a chunk position.
     * Used by the Eye of Sauron scan (future).
     *
     * @param radius reveal radius in chunks
     */
    public static void revealArea(ServerLevel level, ChunkPos center, int radius) {
        WarMapCache cache = WarMapCache.get(level);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Circular reveal using squared distance
                if (dx * dx + dz * dz <= radius * radius) {
                    cache.reveal(center.x + dx, center.z + dz);
                }
            }
        }
        cache.setDirty();
    }
}
