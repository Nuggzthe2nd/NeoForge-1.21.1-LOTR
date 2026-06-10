package net.nuggz.lotrmc.warmap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Map;

/**
 * Scans a chunk for points of interest to display on the war map.
 *
 * Currently detects:
 *   - Villages (minecraft:village_*)
 *   - Other vanilla structures (stronghold, monument, etc.)
 *
 * Future: player base detection (look for placed blocks above a threshold),
 *         custom structure registration.
 *
 * Returns null if no POI found in the chunk.
 */
public class PointOfInterestScanner {

    public static WarMapCache.PoiResult scan(ServerLevel level, ChunkPos chunkPos) {
        // Check for structure starts in this chunk
        Map<Structure, StructureStart> starts =
                level.getChunk(chunkPos.x, chunkPos.z).getAllStarts();

        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            if (!entry.getValue().isValid()) continue;

            String structureId = level.registryAccess()
                    .registryOrThrow(Registries.STRUCTURE)
                    .getKey(entry.getKey())
                    .toString();

            // Villages
            if (structureId.contains("village")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.VILLAGE,
                        formatStructureName(structureId));
            }

            // Other notable structures
            if (structureId.contains("stronghold")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE, "Stronghold");
            }
            if (structureId.contains("monument")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE, "Ocean Monument");
            }
            if (structureId.contains("fortress")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE, "Fortress");
            }
            if (structureId.contains("mansion")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE, "Mansion");
            }
            if (structureId.contains("outpost")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE, "Pillager Outpost");
            }

            // Any other structure from any mod
            if (!structureId.contains("ruins")
                    && !structureId.contains("shipwreck")
                    && !structureId.contains("mineshaft")) {
                return new WarMapCache.PoiResult(
                        ChunkMapEntry.PoiType.STRUCTURE,
                        formatStructureName(structureId));
            }
        }

        // TODO: player base detection — scan for placed blocks density
        // TODO: custom structure registration API for other mods

        return null;
    }

    /**
     * Convert a registry ID like "minecraft:village_plains" to "Plains Village"
     */
    private static String formatStructureName(String id) {
        // Strip namespace
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        // Replace underscores, title case each word
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }
}