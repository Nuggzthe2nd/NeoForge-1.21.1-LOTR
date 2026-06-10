package net.nuggz.lotrmc.warmap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;

/**
 * Cached map data for a single chunk.
 *
 * Computed once when the chunk first enters the war map viewport,
 * then stored in WarMapCache permanently.
 *
 * mapColor is a pre-derived ARGB int computed from the chunk's
 * dominant biome at scan time. This makes the system mod-compatible —
 * any biome that defines grass/fog colors gets a reasonable map color
 * automatically without needing a hardcoded lookup table.
 */
public class ChunkMapEntry {

    public final int chunkX;
    public final int chunkZ;
    public final int mapColor;    // ARGB, derived from biome at scan time
    public final int surfaceY;    // surface height at chunk center, for height shading
    public final boolean isMudlands;

    // POI data — null if no point of interest in this chunk
    public final PoiType poiType;
    public final String poiLabel;

    public enum PoiType {
        VILLAGE,
        PLAYER_BASE,
        STRUCTURE,
        CUSTOM
    }

    public ChunkMapEntry(int chunkX, int chunkZ, int mapColor, int surfaceY,
                         boolean isMudlands, PoiType poiType, String poiLabel) {
        this.chunkX     = chunkX;
        this.chunkZ     = chunkZ;
        this.mapColor   = mapColor;
        this.surfaceY   = surfaceY;
        this.isMudlands = isMudlands;
        this.poiType    = poiType;
        this.poiLabel   = poiLabel;
    }

    public boolean hasPoi() { return poiType != null; }

    // -------------------------------------------------------------------------
    // NBT (for SavedData)
    // -------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X",          chunkX);
        tag.putInt("Z",          chunkZ);
        tag.putInt("Color",      mapColor);
        tag.putInt("SurfaceY",   surfaceY);
        tag.putBoolean("Mudlands", isMudlands);
        if (poiType != null) {
            tag.putString("PoiType",  poiType.name());
            tag.putString("PoiLabel", poiLabel != null ? poiLabel : "");
        }
        return tag;
    }

    public static ChunkMapEntry load(CompoundTag tag) {
        PoiType poiType = null;
        String poiLabel = null;
        if (tag.contains("PoiType")) {
            try { poiType = PoiType.valueOf(tag.getString("PoiType")); }
            catch (IllegalArgumentException ignored) {}
            poiLabel = tag.getString("PoiLabel");
        }
        return new ChunkMapEntry(
                tag.getInt("X"),
                tag.getInt("Z"),
                tag.getInt("Color"),
                tag.getInt("SurfaceY"),
                tag.getBoolean("Mudlands"),
                poiType,
                poiLabel
        );
    }

    // -------------------------------------------------------------------------
    // Network (for packets)
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeInt(mapColor);
        buf.writeInt(surfaceY);
        buf.writeBoolean(isMudlands);
        buf.writeBoolean(hasPoi());
        if (hasPoi()) {
            buf.writeUtf(poiType.name());
            buf.writeUtf(poiLabel != null ? poiLabel : "");
        }
    }

    public static ChunkMapEntry decode(FriendlyByteBuf buf) {
        int x          = buf.readInt();
        int z          = buf.readInt();
        int color      = buf.readInt();
        int y          = buf.readInt();
        boolean mud    = buf.readBoolean();
        boolean hasPoi = buf.readBoolean();
        PoiType poiType  = null;
        String poiLabel  = null;
        if (hasPoi) {
            try { poiType = PoiType.valueOf(buf.readUtf()); }
            catch (IllegalArgumentException ignored) { buf.readUtf(); }
            poiLabel = buf.readUtf();
        }
        return new ChunkMapEntry(x, z, color, y, mud, poiType, poiLabel);
    }
}