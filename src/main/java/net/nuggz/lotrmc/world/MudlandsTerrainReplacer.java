package net.nuggz.lotrmc.world;

import net.nuggz.lotrmc.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Handles block-level terrain replacement when a chunk converts to mudlands,
 * and restoration stubs when it reverts.
 *
 * All replacements run on the server thread inside convertChunk() /
 * revertChunk(), which are called from MudlandsManager. Keep logic here
 * so MudlandsManager stays focused on territory tracking.
 */
public class MudlandsTerrainReplacer {

    // Flag passed to level.setBlock() — notifies neighbors + marks chunk dirty
    private static final int SET_BLOCK_FLAGS = 3;

    // -------------------------------------------------------------------------
    // Conversion  (natural terrain → mudlands terrain)
    // -------------------------------------------------------------------------

    /**
     * Replaces surface and subsurface blocks in the given chunk with mudlands
     * equivalents, then attempts to place a mudpit if the terrain is flat enough.
     */
    public static void convertChunk(ServerLevel level, ChunkPos chunkPos) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // Track flat-ish surface positions for mudpit placement
        List<BlockPos> surfaceCandidates = new ArrayList<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = minX + x;
                int worldZ = minZ + z;

                for (int y = maxY - 1; y >= minY; y--) {
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    BlockState state = level.getBlockState(pos);
                    BlockState replacement = getMudlandsReplacement(state);

                    if (replacement != null) {
                        level.setBlock(pos, replacement, SET_BLOCK_FLAGS);

                        // If this was a surface block, record it as a candidate
                        // for mudpit placement (we check the block above is air)
                        if (isSurfaceBlock(state) && level.getBlockState(pos.above()).isAir()) {
                            surfaceCandidates.add(pos);
                        }
                    }
                }
            }
        }

        // Attempt mudpit placement — only in some chunks, not every one
        tryPlaceMudpit(level, chunkPos, surfaceCandidates);
    }

    /**
     * Maps a vanilla/common block to its mudlands equivalent.
     * Returns null if this block should not be replaced.
     *
     * Add more mappings here as you design your mudlands aesthetic.
     */
    private static BlockState getMudlandsReplacement(BlockState state) {
        // --- Surface layer ---
        if (state.is(Blocks.GRASS_BLOCK))       return ModBlocks.BALT_SURFACE.get().defaultBlockState();
        if (state.is(Blocks.PODZOL))            return ModBlocks.BALT_SURFACE.get().defaultBlockState();
        if (state.is(Blocks.MYCELIUM))          return ModBlocks.BALT_SURFACE.get().defaultBlockState();

        // --- Shallow subsurface ---
        if (state.is(Blocks.DIRT))              return ModBlocks.BALT.get().defaultBlockState();
        if (state.is(Blocks.COARSE_DIRT))       return ModBlocks.BALT.get().defaultBlockState();
        if (state.is(Blocks.ROOTED_DIRT))       return ModBlocks.BALT.get().defaultBlockState();
        // vanilla mud → your mud (keeps things consistent)
        if (state.is(Blocks.MUD))               return ModBlocks.BALT.get().defaultBlockState();

        // --- Deep subsurface ---
        if (state.is(Blocks.STONE))             return ModBlocks.BALGUNDT.get().defaultBlockState();
        if (state.is(Blocks.DEEPSLATE))         return ModBlocks.BALGUNDT.get().defaultBlockState();
        if (state.is(Blocks.COBBLESTONE))       return ModBlocks.BALGUNDT.get().defaultBlockState();
        if (state.is(Blocks.MOSSY_COBBLESTONE)) return ModBlocks.BALGUNDT.get().defaultBlockState();

        // --- Vegetation (remove it) ---
        if (state.is(Blocks.SHORT_GRASS))       return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.TALL_GRASS))        return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.FERN))              return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.LARGE_FERN))        return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.DANDELION))         return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.POPPY))             return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.BLUE_ORCHID))       return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.ALLIUM))            return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.OXEYE_DAISY))       return Blocks.AIR.defaultBlockState();
        if (state.is(Blocks.CORNFLOWER))        return Blocks.AIR.defaultBlockState();

        return null; // don't touch anything else
    }

    private static boolean isSurfaceBlock(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(ModBlocks.BALT_SURFACE.get());
    }

    // -------------------------------------------------------------------------
    // Mudpit placement
    // -------------------------------------------------------------------------

    // How many chunks between mudpits. A value of 3 means roughly 1-in-9 chunks
    // gets a pit attempt (adjusted by terrain flatness check).
    private static final int MUDPIT_CHUNK_INTERVAL = 3;
    // Min number of flat surface candidates required before we even try
    private static final int MIN_FLAT_CANDIDATES = 40;
    // Mudpit dimensions
    private static final int PIT_RADIUS = 3; // 7x7 pit
    private static final int PIT_DEPTH  = 4;

    private static void tryPlaceMudpit(ServerLevel level, ChunkPos chunkPos,
                                       List<BlockPos> surfaceCandidates) {
        // Use chunk coords as a deterministic seed so placement is consistent
        // across server restarts without needing to store pit positions separately.
        long seed = chunkPos.toLong() ^ level.getSeed();
        Random rng = new Random(seed);

        // Only place a pit in roughly 1 in MUDPIT_CHUNK_INTERVAL^2 chunks
        if (Math.abs(chunkPos.x) % MUDPIT_CHUNK_INTERVAL != 0
                || Math.abs(chunkPos.z) % MUDPIT_CHUNK_INTERVAL != 0) return;

        if (surfaceCandidates.size() < MIN_FLAT_CANDIDATES) return;

        // Pick a random surface position from the candidates as the pit center
        BlockPos center = surfaceCandidates.get(rng.nextInt(surfaceCandidates.size()));
        carveMudpit(level, center);
    }

    /**
     * Carves a mudpit at the given center position.
     * The pit is a rounded rectangle depression filled with your mud block,
     * with liquid mud (still water tinted later via biome effects) at the bottom.
     *
     * TODO: replace Blocks.WATER with your custom liquid once you have one.
     */
    public static void carveMudpit(ServerLevel level, BlockPos center) {
        for (int dx = -PIT_RADIUS; dx <= PIT_RADIUS; dx++) {
            for (int dz = -PIT_RADIUS; dz <= PIT_RADIUS; dz++) {
                // Rounded corners — skip the very corners of the bounding box
                if (Math.abs(dx) == PIT_RADIUS && Math.abs(dz) == PIT_RADIUS) continue;

                for (int dy = 0; dy > -PIT_DEPTH; dy--) {
                    BlockPos pos = center.offset(dx, dy, dz);

                    if (dy == -(PIT_DEPTH - 1)) {
                        // Bottom layer — solid mud floor
                        level.setBlock(pos, ModBlocks.BALT.get().defaultBlockState(), SET_BLOCK_FLAGS);
                    } else if (dy < -1) {
                        // Middle layers — liquid mud
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), SET_BLOCK_FLAGS);
                    } else {
                        // Top layer and rim — air to open up the pit
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
                    }
                }

                // Also clear the block directly above the rim so it looks open
                level.setBlock(center.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reversion  (mudlands terrain → air/generic — structures survive)
    // -------------------------------------------------------------------------

    /**
     * Reverts mudlands blocks back when a chunk collapses.
     * Note: we do NOT restore the original terrain — that data is gone.
     * Instead we revert our custom blocks back to their closest vanilla
     * equivalent, leaving player structures untouched.
     *
     * The world will look scarred after collapse, which is intentional —
     * the ruins remain as lore.
     */
    public static void revertChunk(ServerLevel level, ChunkPos chunkPos) {
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = minX + x;
                int worldZ = minZ + z;

                for (int y = maxY - 1; y >= minY; y--) {
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    BlockState state = level.getBlockState(pos);
                    BlockState reverted = getMudlandsRevert(state);

                    if (reverted != null) {
                        level.setBlock(pos, reverted, SET_BLOCK_FLAGS);
                    }
                }
            }
        }
    }

    private static BlockState getMudlandsRevert(BlockState state) {
        if (state.is(ModBlocks.BALT_SURFACE.get())) return Blocks.DIRT.defaultBlockState();
        if (state.is(ModBlocks.BALT.get()))         return Blocks.DIRT.defaultBlockState();
        if (state.is(ModBlocks.BALGUNDT.get()))    return Blocks.STONE.defaultBlockState();
        return null;
    }
}