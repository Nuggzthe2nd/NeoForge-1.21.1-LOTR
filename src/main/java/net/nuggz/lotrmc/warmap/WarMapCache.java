package net.nuggz.lotrmc.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import java.util.*;

/**
 * Persistent cache of all war map data.
 *
 * Stores:
 *   - ChunkMapEntry for every chunk that has been scanned
 *   - Set of discovered chunk positions (fog of war)
 *
 * Chunks are scanned lazily — only when they enter the viewport
 * or when fog of war is lifted for them.
 *
 * Biome colors are derived at scan time from BiomeSpecialEffects,
 * making this fully compatible with modded biomes.
 */
public class WarMapCache extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_warmap_cache";

    // All scanned chunks — packed ChunkPos long → entry
    private final Map<Long, ChunkMapEntry> scannedChunks = new HashMap<>();

    // Fog of war — set of discovered (but not necessarily scanned) chunk positions
    private final Set<Long> discoveredChunks = new HashSet<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static WarMapCache get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarMapCache::new, WarMapCache::load),
                DATA_NAME);
    }

    // -------------------------------------------------------------------------
    // Fog of war
    // -------------------------------------------------------------------------

    public boolean isDiscovered(ChunkPos pos) {
        return discoveredChunks.contains(pos.toLong());
    }

    public boolean isDiscovered(int chunkX, int chunkZ) {
        return discoveredChunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * Reveal a single chunk — adds to discovered set.
     * Does NOT scan it yet — scanning happens lazily on viewport request.
     */
    public void reveal(ChunkPos pos) {
        if (discoveredChunks.add(pos.toLong())) setDirty();
    }

    public void reveal(int chunkX, int chunkZ) {
        reveal(new ChunkPos(chunkX, chunkZ));
    }

    /**
     * Reveal a chunk and all chunks within [radius] of it.
     * Used when mudlands expands — reveals the new territory + 1 border ring.
     */
    public void revealWithBorder(ChunkPos center, int borderRadius) {
        for (int dx = -borderRadius; dx <= borderRadius; dx++) {
            for (int dz = -borderRadius; dz <= borderRadius; dz++) {
                reveal(center.x + dx, center.z + dz);
            }
        }
        setDirty();
    }

    /**
     * Reveal all mudlands chunks plus a 1-chunk border.
     * Called on ritual completion and on each expansion.
     */
    public void revealMudlandsAndBorder(ServerLevel level) {
        MudlandsChunkData mudData = MudlandsChunkData.get(level);
        for (long packed : mudData.getAllConvertedChunkPositions()) {
            ChunkPos pos = new ChunkPos(packed);
            // Reveal the chunk itself and 1-chunk border
            revealWithBorder(pos, 1);
        }
        setDirty();
    }

    public Set<Long> getDiscoveredChunks() {
        return Collections.unmodifiableSet(discoveredChunks);
    }

    // -------------------------------------------------------------------------
    // Chunk scanning
    // -------------------------------------------------------------------------

    public boolean isScanned(int chunkX, int chunkZ) {
        return scannedChunks.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    public ChunkMapEntry getEntry(int chunkX, int chunkZ) {
        return scannedChunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * Scan a chunk and cache the result.
     * This is the expensive step — one surface block lookup and one biome query.
     * Should only be called for newly-discovered or newly-requested chunks.
     *
     * Returns the new entry.
     */
    public ChunkMapEntry scanAndCache(ServerLevel level, int chunkX, int chunkZ,
                                      MudlandsChunkData mudData) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);

        // Sample surface Y at chunk center
        BlockPos centerBlock = new BlockPos(
                chunkPos.getMinBlockX() + 8, 0, chunkPos.getMinBlockZ() + 8);
        int surfaceY = chunk.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                centerBlock.getX(), centerBlock.getZ());

        // Get dominant biome at surface
        BlockPos surfacePos = new BlockPos(centerBlock.getX(), surfaceY, centerBlock.getZ());
        Holder<Biome> biomeHolder = level.getBiome(surfacePos);

        // Derive map color from biome
        int mapColor = deriveBiomeMapColor(biomeHolder);

        // Check for POI
        ChunkMapEntry.PoiType poiType = null;
        String poiLabel = null;
        PoiResult poi = PointOfInterestScanner.scan(level, chunkPos);
        if (poi != null) {
            poiType  = poi.type;
            poiLabel = poi.label;
        }

        boolean isMudlands = mudData.isConverted(chunkPos);

        ChunkMapEntry entry = new ChunkMapEntry(
                chunkX, chunkZ, mapColor, surfaceY,
                isMudlands, poiType, poiLabel);

        scannedChunks.put(chunkPos.toLong(), entry);
        setDirty();
        return entry;
    }

    /**
     * Scan or return cached entry for a chunk.
     * Only scans if the chunk has been discovered (respects fog of war).
     * Returns null if the chunk is not discovered.
     */
    public ChunkMapEntry getOrScan(ServerLevel level, int chunkX, int chunkZ,
                                   MudlandsChunkData mudData) {
        if (!isDiscovered(chunkX, chunkZ)) return null;

        ChunkMapEntry cached = getEntry(chunkX, chunkZ);
        if (cached != null) return cached;

        return scanAndCache(level, chunkX, chunkZ, mudData);
    }

    /**
     * Scan a batch of chunks, respecting fog of war.
     * Returns only the entries for discovered chunks.
     * Rate-limited to MAX_SCANS_PER_REQUEST to prevent lag spikes.
     */
    public static final int MAX_SCANS_PER_REQUEST = 64;

    public List<ChunkMapEntry> getOrScanBatch(ServerLevel level,
                                              List<long[]> requestedChunks,
                                              MudlandsChunkData mudData) {
        List<ChunkMapEntry> results = new ArrayList<>();
        int scanned = 0;

        for (long[] xz : requestedChunks) {
            int cx = (int) xz[0];
            int cz = (int) xz[1];

            if (!isDiscovered(cx, cz)) continue;

            ChunkMapEntry cached = getEntry(cx, cz);
            if (cached != null) {
                results.add(cached);
            } else if (scanned < MAX_SCANS_PER_REQUEST) {
                results.add(scanAndCache(level, cx, cz, mudData));
                scanned++;
            }
            // If over scan limit, undiscovered chunks just won't appear yet —
            // the client will request them again on the next viewport update
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Biome color derivation
    // -------------------------------------------------------------------------

    /**
     * Derives a map color from a biome's visual properties.
     *
     * Priority:
     *   1. Custom grass color defined by the biome → use that
     *   2. Foliage color → use that
     *   3. Fog color → use a darkened version
     *   4. Fallback based on temperature (cold=blue-grey, hot=tan, else green)
     *
     * This works for all vanilla biomes and any modded biome that defines
     * BiomeSpecialEffects (virtually all serious biome mods do).
     */
    private static int deriveBiomeMapColor(Holder<Biome> biomeHolder) {
        Biome biome = biomeHolder.value();
        BiomeSpecialEffects effects = biome.getSpecialEffects();

        // Try custom grass color first
        Optional<Integer> grassColor = effects.getGrassColorOverride();
        if (grassColor.isPresent()) {
            return darken(grassColor.get(), 0.6f);
        }

        // Try foliage color
        Optional<Integer> foliageColor = effects.getFoliageColorOverride();
        if (foliageColor.isPresent()) {
            return darken(foliageColor.get(), 0.5f);
        }

        // Fall back to temperature-based color
        float temp = biome.getBaseTemperature();
        if (temp <= 0.05f) {
            // Snow/ice biome — pale blue-white
            return 0xFF8899AA;
        } else if (temp >= 1.5f) {
            // Hot/desert biome — warm tan
            return 0xFFAA8855;
        } else if (temp >= 0.9f) {
            // Warm/savanna — muted yellow-green
            return 0xFF778833;
        } else if (biome.getPrecipitationAt(new BlockPos(0, 64, 0))
                == Biome.Precipitation.NONE) {
            // Dry but not hot — sandy grey
            return 0xFF998866;
        } else {
            // Temperate/forest default — muted green
            return 0xFF446633;
        }
    }

    /**
     * Darken an RGB color by a factor (0.0 = black, 1.0 = original).
     * Used to make biome colors look like map colors rather than grass colors.
     */
    private static int darken(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >>  8) & 0xFF) * factor);
        int b = (int) (((rgb)       & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        // Save scanned chunks
        ListTag chunkList = new ListTag();
        for (ChunkMapEntry entry : scannedChunks.values()) {
            chunkList.add(entry.save());
        }
        tag.put("ScannedChunks", chunkList);

        // Save discovered set as list of longs
        ListTag discoveredList = new ListTag();
        for (long packed : discoveredChunks) {
            net.minecraft.nbt.LongTag lt = net.minecraft.nbt.LongTag.valueOf(packed);
            discoveredList.add(lt);
        }
        tag.put("DiscoveredChunks", discoveredList);

        return tag;
    }

    public static WarMapCache load(CompoundTag tag, HolderLookup.Provider provider) {
        WarMapCache cache = new WarMapCache();

        ListTag chunkList = tag.getList("ScannedChunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunkList.size(); i++) {
            ChunkMapEntry entry = ChunkMapEntry.load(chunkList.getCompound(i));
            cache.scannedChunks.put(ChunkPos.asLong(entry.chunkX, entry.chunkZ), entry);
        }

        ListTag discoveredList = tag.getList("DiscoveredChunks", Tag.TAG_LONG);
        for (int i = 0; i < discoveredList.size(); i++) {
            cache.discoveredChunks.add(((net.minecraft.nbt.LongTag) discoveredList.get(i)).getAsLong());
        }

        return cache;
    }

    // -------------------------------------------------------------------------
    // Helper record for POI scan result
    // -------------------------------------------------------------------------

    public record PoiResult(ChunkMapEntry.PoiType type, String label) {}
}
