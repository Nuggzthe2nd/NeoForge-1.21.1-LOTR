package net.nuggz.lotrmc.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.nuggz.lotrmc.warmap.ChunkMapEntry;
import net.nuggz.lotrmc.warmap.RaidParty;

import java.util.*;

/**
 * Renders the war map inside the right panel of the War Table screen.
 *
 * The map shows a 32x32 chunk viewport that can be panned by dragging.
 * Each chunk is rendered as a 5x5 pixel rectangle with:
 *   - Base color derived from biome (received from server)
 *   - Height shading from north-neighbor comparison
 *   - Mudlands overlay (dark red tint)
 *   - Fog of war (black for undiscovered chunks)
 *   - POI icons
 *   - Active raid party markers
 *
 * Coordinate systems:
 *   - "chunk coords": integer chunk X/Z values (world space)
 *   - "viewport coords": offset from viewport origin (0..31, 0..31)
 *   - "screen coords": pixel position in the panel
 *
 * The viewport is centered on the mudlands origin initially.
 * viewportOriginX/Z = chunk coord of the top-left corner of the viewport.
 */
public class WarTableMapRenderer {

    // Viewport size in chunks
    public static final int VIEWPORT_CHUNKS = 32;
    // Pixels per chunk
    public static final int CHUNK_PX = 5;
    // Total map panel size in pixels
    public static final int MAP_PX = VIEWPORT_CHUNKS * CHUNK_PX; // 160px

    // Colors
    private static final int COL_FOG         = 0xFF050505;
    private static final int COL_MUDLANDS    = 0xFF3A0808; // dark red base
    private static final int COL_BORDER      = 0xFF4A1A1A;
    private static final int COL_VILLAGE     = 0xFFFF8800;
    private static final int COL_STRUCTURE   = 0xFFAAAA00;
    private static final int COL_PLAYER_BASE = 0xFFFFFFFF;
    private static final int COL_RAID_MARKER = 0xFFFF4400;
    private static final int COL_TARGET_MARKER = 0xFFFF0000;
    private static final int COL_SELECTED_CHUNK = 0x884400FF;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    // Chunk coord of top-left viewport corner
    private int viewportOriginX;
    private int viewportOriginZ;

    // Client-side chunk cache — chunkX,chunkZ → entry
    private final Map<Long, ChunkMapEntry> chunkCache = new HashMap<>();

    // Discovered chunks (packed longs) — received from server
    private final Set<Long> discoveredChunks = new HashSet<>();

    // Active raid parties
    private final List<RaidParty> raidParties = new ArrayList<>();

    // Current server game time (for raid marker interpolation)
    private long currentTick = 0;

    // Selected target chunk (for raid targeting)
    private Integer selectedTargetX = null;
    private Integer selectedTargetZ = null;
    private String selectedTargetLabel = null;
    private ChunkMapEntry.PoiType selectedTargetPoiType = null;

    // Drag state
    private boolean dragging = false;
    private double dragStartX, dragStartZ;
    private double dragPixelX, dragPixelZ; // sub-pixel drag accumulator

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public void init(int mudlandsOriginChunkX, int mudlandsOriginChunkZ) {
        // Center viewport on mudlands origin
        viewportOriginX = mudlandsOriginChunkX - VIEWPORT_CHUNKS / 2;
        viewportOriginZ = mudlandsOriginChunkZ - VIEWPORT_CHUNKS / 2;
    }

    public void receiveChunks(List<ChunkMapEntry> entries) {
        for (ChunkMapEntry entry : entries) {
            chunkCache.put(net.minecraft.world.level.ChunkPos.asLong(
                    entry.chunkX, entry.chunkZ), entry);
        }
    }

    public void receiveDiscoveredChunks(Set<Long> discovered) {
        discoveredChunks.addAll(discovered);
    }

    public void receiveDiscoveredChunk(long packed) {
        discoveredChunks.add(packed);
    }

    public void setRaidParties(List<RaidParty> parties) {
        raidParties.clear();
        raidParties.addAll(parties);
    }

    public void setCurrentTick(long tick) { this.currentTick = tick; }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Render the full map into the given panel area.
     *
     * @param g       GuiGraphics
     * @param panelX  top-left X of the map panel in screen coords
     * @param panelY  top-left Y of the map panel in screen coords
     * @param font    for POI labels
     */
    public void render(GuiGraphics g, int panelX, int panelY, Font font) {
        // Clip to panel
        g.enableScissor(panelX, panelY, panelX + MAP_PX, panelY + MAP_PX);

        renderChunks(g, panelX, panelY);
        renderPois(g, panelX, panelY, font);
        renderRaidMarkers(g, panelX, panelY, font);
        renderSelectedTarget(g, panelX, panelY);

        g.disableScissor();

        // Panel border
        g.renderOutline(panelX - 1, panelY - 1, MAP_PX + 2, MAP_PX + 2, COL_BORDER);
    }

    private void renderChunks(GuiGraphics g, int panelX, int panelY) {
        for (int vx = 0; vx < VIEWPORT_CHUNKS; vx++) {
            for (int vz = 0; vz < VIEWPORT_CHUNKS; vz++) {
                int chunkX = viewportOriginX + vx;
                int chunkZ = viewportOriginZ + vz;
                long packed = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);

                int px = panelX + vx * CHUNK_PX;
                int pz = panelY + vz * CHUNK_PX;

                if (!discoveredChunks.contains(packed)) {
                    // Fog of war
                    g.fill(px, pz, px + CHUNK_PX, pz + CHUNK_PX, COL_FOG);
                    continue;
                }

                ChunkMapEntry entry = chunkCache.get(packed);
                if (entry == null) {
                    // Discovered but not yet loaded — show dark grey placeholder
                    g.fill(px, pz, px + CHUNK_PX, pz + CHUNK_PX, 0xFF1A1A1A);
                    continue;
                }

                // Base color with height shading
                int color = applyHeightShading(entry, chunkX, chunkZ);

                // Mudlands overlay — blend dark red on top
                if (entry.isMudlands) {
                    color = blendColors(color, COL_MUDLANDS, 0.5f);
                }

                g.fill(px, pz, px + CHUNK_PX, pz + CHUNK_PX, color);
            }
        }
    }

    private void renderPois(GuiGraphics g, int panelX, int panelY, Font font) {
        for (int vx = 0; vx < VIEWPORT_CHUNKS; vx++) {
            for (int vz = 0; vz < VIEWPORT_CHUNKS; vz++) {
                int chunkX = viewportOriginX + vx;
                int chunkZ = viewportOriginZ + vz;
                long packed = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);

                if (!discoveredChunks.contains(packed)) continue;
                ChunkMapEntry entry = chunkCache.get(packed);
                if (entry == null || !entry.hasPoi()) continue;

                int px = panelX + vx * CHUNK_PX;
                int pz = panelY + vz * CHUNK_PX;

                // Draw POI marker dot
                int poiColor = switch (entry.poiType) {
                    case VILLAGE     -> COL_VILLAGE;
                    case STRUCTURE   -> COL_STRUCTURE;
                    case PLAYER_BASE -> COL_PLAYER_BASE;
                    case CUSTOM      -> 0xFFAAAAAA;
                };

                // 3x3 dot centered in chunk
                g.fill(px + 1, pz + 1, px + 4, pz + 4, poiColor);
            }
        }
    }

    private void renderRaidMarkers(GuiGraphics g, int panelX, int panelY, Font font) {
        for (RaidParty party : raidParties) {
            float[] pos = party.getInterpolatedPosition(currentTick);
            int vx = (int)(pos[0] - viewportOriginX);
            int vz = (int)(pos[1] - viewportOriginZ);

            if (vx < 0 || vx >= VIEWPORT_CHUNKS || vz < 0 || vz >= VIEWPORT_CHUNKS) continue;

            int px = panelX + vx * CHUNK_PX + 2;
            int pz = panelY + vz * CHUNK_PX + 2;

            // Raid marker — small red diamond
            g.fill(px, pz - 1, px + 1, pz + 2, COL_RAID_MARKER);
            g.fill(px - 1, pz, px + 2, pz + 1, COL_RAID_MARKER);

            // Draw target line
            int tvx = party.targetChunkX - viewportOriginX;
            int tvz = party.targetChunkZ - viewportOriginZ;
            if (tvx >= 0 && tvx < VIEWPORT_CHUNKS && tvz >= 0 && tvz < VIEWPORT_CHUNKS) {
                int tpx = panelX + tvx * CHUNK_PX + 2;
                int tpz = panelY + tvz * CHUNK_PX + 2;
                // Draw target marker
                g.fill(tpx - 1, tpz - 1, tpx + 2, tpz + 2, COL_TARGET_MARKER);
            }
        }
    }

    private void renderSelectedTarget(GuiGraphics g, int panelX, int panelY) {
        if (selectedTargetX == null) return;

        int vx = selectedTargetX - viewportOriginX;
        int vz = selectedTargetZ - viewportOriginZ;
        if (vx < 0 || vx >= VIEWPORT_CHUNKS || vz < 0 || vz >= VIEWPORT_CHUNKS) return;

        int px = panelX + vx * CHUNK_PX;
        int pz = panelY + vz * CHUNK_PX;

        // Pulsing highlight — semi-transparent blue overlay
        g.fill(px, pz, px + CHUNK_PX, pz + CHUNK_PX, COL_SELECTED_CHUNK);
        g.renderOutline(px, pz, CHUNK_PX, CHUNK_PX, 0xFF4444FF);
    }

    // -------------------------------------------------------------------------
    // Height shading
    // -------------------------------------------------------------------------

    private int applyHeightShading(ChunkMapEntry entry, int chunkX, int chunkZ) {
        // Compare to north neighbor (chunkZ - 1)
        long northPacked = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ - 1);
        ChunkMapEntry north = chunkCache.get(northPacked);

        int base = entry.mapColor;
        if (north == null) return base; // no neighbor data yet

        int diff = entry.surfaceY - north.surfaceY;
        int offset;
        if (diff > 0)      offset = +25; // going uphill → lighter
        else if (diff < 0) offset = -25; // going downhill → darker
        else               offset = 0;

        return shiftBrightness(base, offset);
    }

    private static int shiftBrightness(int argb, int offset) {
        int a = (argb >> 24) & 0xFF;
        int r = clamp(((argb >> 16) & 0xFF) + offset, 0, 255);
        int g = clamp(((argb >>  8) & 0xFF) + offset, 0, 255);
        int b = clamp(((argb)       & 0xFF) + offset, 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int blendColors(int base, int overlay, float alpha) {
        int br = (base    >> 16) & 0xFF;
        int bg = (base    >>  8) & 0xFF;
        int bb = (base)          & 0xFF;
        int or = (overlay >> 16) & 0xFF;
        int og = (overlay >>  8) & 0xFF;
        int ob = (overlay)       & 0xFF;
        int r = (int)(br * (1 - alpha) + or * alpha);
        int g2 = (int)(bg * (1 - alpha) + og * alpha);
        int b = (int)(bb * (1 - alpha) + ob * alpha);
        return 0xFF000000 | (r << 16) | (g2 << 8) | b;
    }

    // -------------------------------------------------------------------------
    // Mouse interaction
    // -------------------------------------------------------------------------

    /** Convert screen pixel to chunk coords. */
    public int[] screenToChunk(int screenX, int screenY, int panelX, int panelY) {
        int vx = (screenX - panelX) / CHUNK_PX;
        int vz = (screenY - panelY) / CHUNK_PX;
        return new int[]{ viewportOriginX + vx, viewportOriginZ + vz };
    }

    /** Returns true if the screen position is inside the map panel. */
    public boolean isInMapPanel(int screenX, int screenY, int panelX, int panelY) {
        return screenX >= panelX && screenX < panelX + MAP_PX
                && screenY >= panelY && screenY < panelY + MAP_PX;
    }

    public void onMouseClick(int screenX, int screenY, int panelX, int panelY) {
        if (!isInMapPanel(screenX, screenY, panelX, panelY)) return;

        int[] chunk = screenToChunk(screenX, screenY, panelX, panelY);
        int cx = chunk[0], cz = chunk[1];
        long packed = net.minecraft.world.level.ChunkPos.asLong(cx, cz);

        // Ignore fog
        if (!discoveredChunks.contains(packed)) return;

        // Check for POI
        ChunkMapEntry entry = chunkCache.get(packed);
        if (entry != null && entry.hasPoi()) {
            selectedTargetX      = cx;
            selectedTargetZ      = cz;
            selectedTargetLabel  = entry.poiLabel;
            selectedTargetPoiType = entry.poiType;
        } else {
            selectedTargetX      = cx;
            selectedTargetZ      = cz;
            selectedTargetLabel  = "Chunk (" + cx + ", " + cz + ")";
            selectedTargetPoiType = null;
        }
    }

    public void onMouseDragStart(double mouseX, double mouseY) {
        dragging     = true;
        dragStartX   = mouseX;
        dragStartZ   = mouseY;
        dragPixelX   = 0;
        dragPixelZ   = 0;
    }

    public void onMouseDrag(double mouseX, double mouseY) {
        if (!dragging) return;

        dragPixelX += dragStartX - mouseX;
        dragPixelZ += dragStartZ - mouseY;
        dragStartX  = mouseX;
        dragStartZ  = mouseY;

        // Accumulate until we've moved a full chunk width
        int chunkShiftX = (int)(dragPixelX / CHUNK_PX);
        int chunkShiftZ = (int)(dragPixelZ / CHUNK_PX);

        if (chunkShiftX != 0 || chunkShiftZ != 0) {
            viewportOriginX += chunkShiftX;
            viewportOriginZ += chunkShiftZ;
            dragPixelX -= chunkShiftX * CHUNK_PX;
            dragPixelZ -= chunkShiftZ * CHUNK_PX;
        }
    }

    public void onMouseDragEnd() {
        dragging = false;
    }

    // -------------------------------------------------------------------------
    // Getters for WarTableScreen to use when building RaidStartPacket
    // -------------------------------------------------------------------------

    public boolean hasSelectedTarget() { return selectedTargetX != null; }
    public Integer getSelectedTargetX() { return selectedTargetX; }
    public Integer getSelectedTargetZ() { return selectedTargetZ; }
    public String getSelectedTargetLabel() { return selectedTargetLabel; }
    public ChunkMapEntry.PoiType getSelectedTargetPoiType() { return selectedTargetPoiType; }

    public void clearSelectedTarget() {
        selectedTargetX     = null;
        selectedTargetZ     = null;
        selectedTargetLabel = null;
        selectedTargetPoiType = null;
    }

    /**
     * Returns the list of undiscovered chunks currently in the viewport
     * that haven't been cached yet — used to build MapChunkRequestPacket.
     */
    public List<long[]> getUncachedVisibleChunks() {
        List<long[]> uncached = new ArrayList<>();
        for (int vx = 0; vx < VIEWPORT_CHUNKS; vx++) {
            for (int vz = 0; vz < VIEWPORT_CHUNKS; vz++) {
                int cx = viewportOriginX + vx;
                int cz = viewportOriginZ + vz;
                long packed = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
                if (discoveredChunks.contains(packed) && !chunkCache.containsKey(packed)) {
                    uncached.add(new long[]{ cx, cz });
                }
            }
        }
        return uncached;
    }
}