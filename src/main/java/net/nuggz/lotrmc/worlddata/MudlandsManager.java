package net.nuggz.lotrmc.worlddata;

import net.nuggz.lotrmc.world.MudlandsTerrainReplacer;
import net.nuggz.lotrmc.warmap.FogOfWarManager;
import net.nuggz.lotrmc.warmap.FogOfWarManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.UUID;

/**
 * Stateless utility layer on top of MudlandsChunkData.
 * All methods take a ServerLevel and delegate storage to MudlandsChunkData.
 *
 * Call these from your event handlers, block entity ticks, etc.
 */
public class MudlandsManager {

    // How many game ticks between each chunk revert step during collapse.
    // 200 ticks = 10 seconds per ring. Tune this for feel.
    public static final int COLLAPSE_TICK_INTERVAL = 200;

    // -------------------------------------------------------------------------
    // Ritual / setup
    // -------------------------------------------------------------------------

    /**
     * Called when a player completes the Nethercrown ritual.
     * Designates them as Sauron and kicks off the initial biome conversion.
     */
    public static void initiateSauron(ServerLevel level, UUID playerUUID, BlockPos ritualPos) {
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (data.hasSauron()) {
            // Already a Sauron — should never reach here if you gate the ritual properly
            return;
        }

        data.setSauron(playerUUID, ritualPos);
        convertRadius(level, data, playerUUID, new ChunkPos(ritualPos), data.getSauronRadius());
        FogOfWarManager.onSauronInitiated(level);
    }

    // -------------------------------------------------------------------------
    // Expansion
    // -------------------------------------------------------------------------

    /**
     * Expand Sauron's mudlands outward by one chunk ring.
     * Call this when the player meets the expansion requirement (e.g. enough orcs alive).
     */
    public static void expandSauron(ServerLevel level) {
        MudlandsChunkData data = MudlandsChunkData.get(level);
        if (!data.hasSauron()) return;

        int newRadius = data.expandSauronRadius();
        ChunkPos origin = new ChunkPos(data.getSauronOrigin());

        // Only convert the new outermost ring
        convertRing(level, data, data.getSauronUUID(), origin, newRadius);
        FogOfWarManager.onSauronExpanded(level, origin, newRadius);
    }

    /**
     * Expand a lieutenant's territory by one chunk ring.
     * Returns false if the lieutenant has already hit the Sauron-imposed cap.
     */
    public static boolean expandLieutenant(ServerLevel level, UUID lieutenantUUID) {
        MudlandsChunkData data = MudlandsChunkData.get(level);
        ChunkPos origin = data.getLieutenantOrigin(lieutenantUUID);
        if (origin == null) return false;

        boolean expanded = data.expandLieutenantRadius(lieutenantUUID);
        if (expanded) {
            int newRadius = data.getLieutenantRadius(lieutenantUUID);
            convertRing(level, data, lieutenantUUID, origin, newRadius);
        }
        return expanded;
    }

    // -------------------------------------------------------------------------
    // Collapse
    // -------------------------------------------------------------------------

    /**
     * Begin the collapse sequence. Call this when all of Sauron's orcs are dead.
     */
    public static void beginCollapse(ServerLevel level) {
        MudlandsChunkData data = MudlandsChunkData.get(level);
        if (!data.isCollapseActive()) {
            data.startCollapse(level.getGameTime());
        }
    }

    /**
     * Called every server tick (from your mod's server tick event).
     * Handles the gradual chunk-by-chunk revert during collapse.
     */
    public static void tickCollapse(ServerLevel level) {
        MudlandsChunkData data = MudlandsChunkData.get(level);
        if (!data.isCollapseActive() || !data.hasSauron()) return;

        long elapsed = level.getGameTime() - data.getCollapseStartGameTime();
        if (elapsed % COLLAPSE_TICK_INTERVAL != 0) return; // only act on intervals

        if (data.getSauronRadius() <= 0) {
            // Collapse complete — crown is now recraftable
            onCollapseComplete(level, data);
            return;
        }

        // Revert one ring per interval
        revertOutermostRing(level, data, data.getSauronUUID(),
                new ChunkPos(data.getSauronOrigin()), data.getSauronRadius());
        data.shrinkSauronRadius();
        data.clampLieutenantRadii(); // ensure lieutenant territories shrink with Sauron
    }

    /**
     * Abort collapse (Sauron reclaimed control before it completed).
     * Call this when Sauron recrafts the crown or re-enters the mudpit.
     */
    public static void abortCollapse(ServerLevel level) {
        MudlandsChunkData data = MudlandsChunkData.get(level);
        data.stopCollapse();
    }

    private static void onCollapseComplete(ServerLevel level, MudlandsChunkData data) {
        // Remove remaining chunks (the origin chunk itself)
        List<ChunkPos> remaining = data.getChunksSortedOutward(
                data.getSauronUUID(), data.getSauronOrigin());
        for (ChunkPos pos : remaining) {
            revertChunk(level, pos);
            data.unmarkChunk(pos);
        }

        // Remove all lieutenants
        for (UUID lt : data.getLieutenants()) {
            List<ChunkPos> ltChunks = data.getChunksSortedOutward(
                    lt, data.getLieutenantOrigin(lt).getWorldPosition());
            for (ChunkPos pos : ltChunks) {
                revertChunk(level, pos);
                data.unmarkChunk(pos);
            }
            data.removeLieutenant(lt);
        }

        data.clearSauron();
        data.stopCollapse();

        // TODO: unlock the Nethercrown recipe here
        // e.g. level.getServer().getRecipeManager() or send an advancement trigger
    }

    // -------------------------------------------------------------------------
    // Chunk ring helpers
    // -------------------------------------------------------------------------

    /**
     * Convert all chunks within [radius] of origin to mudlands.
     * Skips chunks that are already converted.
     */
    private static void convertRadius(ServerLevel level, MudlandsChunkData data,
                                      UUID owner, ChunkPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
                if (!data.isConverted(pos)) {
                    convertChunk(level, pos);
                    data.markChunkConverted(pos, owner);
                }
            }
        }
    }

    /**
     * Convert only the outermost ring at exactly [radius] Chebyshev distance.
     * More efficient than re-scanning the whole area on each expansion.
     */
    private static void convertRing(ServerLevel level, MudlandsChunkData data,
                                    UUID owner, ChunkPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                    ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
                    if (!data.isConverted(pos)) {
                        convertChunk(level, pos);
                        data.markChunkConverted(pos, owner);
                    }
                }
            }
        }
    }

    /**
     * Revert only the outermost ring at exactly [radius] Chebyshev distance.
     */
    private static void revertOutermostRing(ServerLevel level, MudlandsChunkData data,
                                            UUID owner, ChunkPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                    ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
                    revertChunk(level, pos);
                    data.unmarkChunk(pos);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Biome manipulation
    // -------------------------------------------------------------------------

    /**
     * Convert a single chunk's biome to the Mudlands biome.
     *
     * NOTE: In 1.21.1 true per-chunk biome replacement at runtime is complex.
     * The cleanest approach is to store your mudlands chunks in SavedData (done above)
     * and override biome resolution in a MixinNoiseChunk or via a DimensionDataStorage
     * hook so that the game READS the mudlands biome for those chunks.
     *
     * The stub here marks the chunk dirty so clients re-sync, and is where you
     * would apply your biome holder swap once that system is implemented.
     */
    private static void convertChunk(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);

        // TODO: swap biome palette here once your biome injection mixin is ready.
        // Example approach:
        //   Holder<Biome> mudlandsBiome = level.registryAccess()
        //       .registryOrThrow(Registries.BIOME)
        //       .getHolderOrThrow(YOUR_BIOME_KEY);
        //   PalettedContainerRO<Holder<Biome>> ... (see ChunkAccess#fillBiomesFromNoise)

        // Replace terrain blocks (grass→mud surface, dirt→mud, stone→mudstone, etc.)
        // and carve a mudpit if this chunk qualifies
        MudlandsTerrainReplacer.convertChunk(level, pos);

        chunk.setUnsaved(true);
        // TODO: send ClientboundLevelChunkWithLightPacket to nearby players
    }

    /**
     * Revert a single chunk back to its natural (pre-conversion) biome.
     *
     * The same biome-injection approach applies — you stop overriding this chunk
     * in your resolver, and the vanilla biome comes back on next chunk reload.
     * Structures the player built remain; only the biome atmosphere changes.
     */
    private static void revertChunk(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);

        // Revert our custom blocks back to closest vanilla equivalent.
        // Player-built structures survive — only mudlands terrain blocks change.
        MudlandsTerrainReplacer.revertChunk(level, pos);

        chunk.setUnsaved(true);
        // TODO: send ClientboundLevelChunkWithLightPacket to nearby players
    }
}