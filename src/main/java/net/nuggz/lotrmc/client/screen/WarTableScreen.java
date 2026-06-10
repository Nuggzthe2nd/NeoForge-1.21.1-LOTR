package net.nuggz.lotrmc.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.nuggz.lotrmc.client.WarTableMapRenderer;
import net.nuggz.lotrmc.network.MapChunkRequestPacket;
import net.nuggz.lotrmc.network.RaidStartPacket;
import net.nuggz.lotrmc.warmap.ChunkMapEntry;
import net.nuggz.lotrmc.warmap.RaidParty;
import net.nuggz.lotrmc.warmap.WarTableData;
import net.nuggz.lotrmc.warmap.WarTableData.OrcEntry;
import net.nuggz.lotrmc.warmap.WarTableData.PitEntry;

import java.util.*;

/**
 * War Table screen.
 *
 * Layout:
 *   Left panel  (180px) — scrollable pit card list / expanded card view
 *   Right panel (160px) — scrollable war map
 *
 * The two panels are independent — you can scroll the map while
 * viewing a pit card, or expand a pit and select orcs then click
 * a map target to send the raid.
 */
public class WarTableScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private static final int LEFT_W   = 180;
    private static final int MAP_W    = WarTableMapRenderer.MAP_PX; // 160
    private static final int TOTAL_W  = LEFT_W + MAP_W + 8; // gap between panels
    private static final int PANEL_H  = 220;

    private static final int CARD_HEIGHT   = 60;
    private static final int CARD_MARGIN   = 6;
    private static final int CARDS_VISIBLE = 3;
    private static final int ORC_ROW_H     = 14;

    // Colors
    private static final int COL_BG          = 0xEE0A0A0A;
    private static final int COL_BORDER      = 0xFF4A1A1A;
    private static final int COL_BORDER_INNER= 0xFF2A0A0A;
    private static final int COL_CARD        = 0xFF150505;
    private static final int COL_CARD_HOVER  = 0xFF200808;
    private static final int COL_CARD_LEADER = 0xFF1A0A00;
    private static final int COL_SELECTED    = 0xFF3A1A00;
    private static final int COL_HEADER      = 0xFF0A0505;
    private static final int COL_SHADOW      = 0x88000000;
    private static final int COL_RAID_BTN    = 0xFF8B0000;

    // -------------------------------------------------------------------------
    // State — left panel
    // -------------------------------------------------------------------------

    private final WarTableData data;
    private int scrollOffset    = 0;
    private PitEntry expandedPit = null;
    private int orcScrollOffset  = 0;
    private final Set<UUID> selectedOrcs = new HashSet<>();

    // Buttons
    private Button backButton  = null;
    private Button raidButton  = null;

    // Mouse
    private int mouseX = 0, mouseY = 0;

    // -------------------------------------------------------------------------
    // State — right panel (map)
    // -------------------------------------------------------------------------

    private final WarTableMapRenderer mapRenderer = new WarTableMapRenderer();
    private boolean mapDragging = false;

    // Pending chunk request timer — batch requests every 10 ticks
    private int chunkRequestTimer = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public WarTableScreen(WarTableData data,
                          List<ChunkMapEntry> initialChunks,
                          Set<Long> discoveredChunks,
                          List<RaidParty> raidParties,
                          int originChunkX, int originChunkZ) {
        super(Component.literal("War Table"));
        this.data = data;
        mapRenderer.init(originChunkX, originChunkZ);
        mapRenderer.receiveChunks(initialChunks);
        mapRenderer.receiveDiscoveredChunks(discoveredChunks);
        mapRenderer.setRaidParties(raidParties);
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    // -------------------------------------------------------------------------
    // Panel positions
    // -------------------------------------------------------------------------

    private int getLeftPanelX() { return (width - TOTAL_W) / 2; }
    private int getMapPanelX()  { return getLeftPanelX() + LEFT_W + 8; }
    private int getPanelY()     { return (height - PANEL_H) / 2; }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // No blur
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;

        int lx = getLeftPanelX();
        int rx = getMapPanelX();
        int y  = getPanelY();

        // --- Left panel ---
        drawPanel(g, lx, y, LEFT_W, PANEL_H, "§4War Table");
        if (expandedPit == null) renderListView(g, lx, y, mx, my);
        else                     renderExpandedView(g, lx, y, mx, my);

        // --- Right panel background ---
        g.fill(rx + 4, y + 4, rx + MAP_W + 4, y + PANEL_H + 4, COL_SHADOW);
        g.fill(rx, y, rx + MAP_W, y + PANEL_H, COL_BG);
        g.fill(rx + 2, y + 2, rx + MAP_W - 2, y + 16, COL_HEADER);
        g.drawCenteredString(font, "§4War Map", rx + MAP_W / 2, y + 5, 0xFFFFFF);

        // Map rendered centered in right panel
        int mapY = y + 18;
        mapRenderer.setCurrentTick(
                net.minecraft.client.Minecraft.getInstance().level != null
                        ? net.minecraft.client.Minecraft.getInstance().level.getGameTime() : 0);
        mapRenderer.render(g, rx, mapY, font);

        // Map instructions
        g.drawCenteredString(font, "§8Drag to pan  •  Click to target",
                rx + MAP_W / 2, y + PANEL_H - 10, 0x444444);

        // Selected target label
        if (mapRenderer.hasSelectedTarget()) {
            String label = "§7Target: §c" + mapRenderer.getSelectedTargetLabel();
            g.drawCenteredString(font, label,
                    rx + MAP_W / 2, y + MAP_W + 22, 0xFFFFFF);
        }

        // Raid button if target selected and expanded pit with orcs
        if (mapRenderer.hasSelectedTarget() && expandedPit != null) {
            // Raid button is rendered by widget system
        }

        // Widget rendering (buttons)
        super.render(g, mx, my, delta);

        // Leader tooltip (must be drawn after super.render so it's on top)
        if (expandedPit != null && expandedPit.hasLeader()) {
            int headerY = y + 22;
            int contentX = lx + 6;
            if (mx >= contentX && mx <= contentX + font.width(expandedPit.leaderName)
                    && my >= headerY + 3 && my <= headerY + 14) {
                g.pose().translate(0, 0, 400);
                drawLeaderTooltip(g, expandedPit, mx, my);
                g.pose().translate(0, 0, -400);
            }
        }

        // Periodically request uncached map chunks
        chunkRequestTimer++;
        if (chunkRequestTimer >= 10) {
            chunkRequestTimer = 0;
            requestUncachedChunks();
        }
    }

    // -------------------------------------------------------------------------
    // Left panel — list view
    // -------------------------------------------------------------------------

    private void renderListView(GuiGraphics g, int panelX, int panelY, int mx, int my) {
        if (data.pits.isEmpty()) {
            g.drawCenteredString(font, "§7No mudpits found.",
                    panelX + LEFT_W / 2, panelY + PANEL_H / 2, 0xAAAAAA);
            return;
        }

        int cardX  = panelX + CARD_MARGIN;
        int cardW  = LEFT_W - CARD_MARGIN * 2;
        int startY = panelY + 20;

        for (int i = 0; i < CARDS_VISIBLE; i++) {
            int idx = scrollOffset + i;
            if (idx >= data.pits.size()) break;
            PitEntry pit  = data.pits.get(idx);
            int cardY     = startY + i * (CARD_HEIGHT + CARD_MARGIN);
            boolean hover = isOver(mx, my, cardX, cardY, cardW, CARD_HEIGHT);
            drawPitCard(g, pit, cardX, cardY, cardW, CARD_HEIGHT, hover);
        }

        if (scrollOffset > 0)
            g.drawCenteredString(font, "§7▲", panelX + LEFT_W / 2, panelY + 18, 0x888888);
        if (scrollOffset + CARDS_VISIBLE < data.pits.size())
            g.drawCenteredString(font, "§7▼", panelX + LEFT_W / 2, panelY + PANEL_H - 10, 0x888888);
    }

    private void drawPitCard(GuiGraphics g, PitEntry pit,
                             int x, int y, int w, int h, boolean hover) {
        g.fill(x, y, x + w, y + h, hover ? COL_CARD_HOVER : COL_CARD);
        g.renderOutline(x, y, w, h, COL_BORDER);

        int tx = x + 5, ty = y + 5;
        String leaderLabel = pit.hasLeader() ? "§c" + pit.leaderName : "§8No leader";
        g.drawString(font, leaderLabel,
                x + w - font.width(pit.hasLeader() ? pit.leaderName : "No leader") - 5,
                ty, 0xFFFFFF);
        g.drawString(font, "§7" + pit.orcs.size() + "§8/§7" + pit.maxPopulation + " orcs",
                tx, ty, 0xFFFFFF);
        g.drawString(font, "§8Order: §7" + pit.defaultOrder, tx, ty + 12, 0xFFFFFF);

        if (pit.isGestating) {
            g.drawString(font, "§2Growing: §a" + pit.gestationPercent + "%", tx, ty + 24, 0xFFFFFF);
            int barW = w - 10;
            g.fill(tx, ty + 34, tx + barW, ty + 38, 0xFF1A1A1A);
            g.fill(tx, ty + 34, tx + (barW * pit.gestationPercent / 100), ty + 38, 0xFF005500);
        } else {
            g.drawString(font, "§8Biomass: §6" + pit.biomass, tx, ty + 24, 0xFFFFFF);
        }
        g.drawString(font, "§8▶ click to expand", tx, ty + 44, 0x555555);
    }

    // -------------------------------------------------------------------------
    // Left panel — expanded view
    // -------------------------------------------------------------------------

    private void renderExpandedView(GuiGraphics g, int panelX, int panelY, int mx, int my) {
        PitEntry pit   = expandedPit;
        int contentX   = panelX + 6;
        int contentW   = LEFT_W - 12;
        int headerY    = panelY + 22;

        g.fill(panelX + 2, headerY, panelX + LEFT_W - 2, headerY + 20, COL_HEADER);
        if (pit.hasLeader()) {
            g.drawString(font, "§c" + pit.leaderName, contentX, headerY + 5, 0xFFFFFF);
        } else {
            g.drawString(font, "§8No leader assigned", contentX, headerY + 5, 0xFFFFFF);
        }

        int dividerY   = headerY + 22;
        g.fill(panelX + 4, dividerY, panelX + LEFT_W - 4, dividerY + 1, COL_BORDER);

        int listStartY  = dividerY + 4;
        int visibleOrcs = (PANEL_H - (listStartY - panelY) - 14) / ORC_ROW_H;

        for (int i = 0; i < visibleOrcs; i++) {
            int idx = orcScrollOffset + i;
            if (idx >= pit.orcs.size()) break;
            OrcEntry orc = pit.orcs.get(idx);
            int rowY     = listStartY + i * ORC_ROW_H;
            boolean sel  = orc.isLeader || selectedOrcs.contains(orc.uuid);
            boolean hov  = my >= rowY && my < rowY + ORC_ROW_H
                    && mx >= contentX && mx < contentX + contentW;
            drawOrcRow(g, orc, contentX, rowY, contentW, sel, hov);
        }

        if (orcScrollOffset > 0)
            g.drawString(font, "§7▲", contentX, dividerY + 2, 0x888888);
        if (orcScrollOffset + visibleOrcs < pit.orcs.size())
            g.drawString(font, "§7▼", contentX, panelY + PANEL_H - 12, 0x888888);

        int selectedCount = selectedOrcs.size() + (pit.hasLeader() ? 1 : 0);
        g.drawString(font, "§8" + selectedCount + " selected",
                contentX, panelY + PANEL_H - 10, 0x555555);
    }

    private void drawOrcRow(GuiGraphics g, OrcEntry orc,
                            int x, int y, int w, boolean selected, boolean hover) {
        int bg = orc.isLeader ? COL_CARD_LEADER
                : selected    ? COL_SELECTED
                  : hover       ? COL_CARD_HOVER
                    :               COL_CARD;
        g.fill(x, y, x + w, y + ORC_ROW_H - 1, bg);
        g.drawString(font, selected ? "§6▶ " : "§8  ", x + 2, y + 2, 0xFFFFFF);
        g.drawString(font, (orc.isLeader ? "§c" : "§7") + orc.name, x + 14, y + 2, 0xFFFFFF);

        String scars = buildScarDisplay(orc.scarCount);
        g.drawString(font, scars, x + w - font.width(scars) - 2, y + 2, 0xFFFFFF);
    }

    private String buildScarDisplay(int count) {
        if (count == 0) return "§8—";
        int shown = Math.min(count, 5);
        StringBuilder sb = new StringBuilder("§4");
        for (int i = 0; i < shown; i++) sb.append("✦");
        if (count > 5) sb.append("§8+").append(count - 5);
        return sb.toString();
    }

    private void drawLeaderTooltip(GuiGraphics g, PitEntry pit, int mx, int my) {
        String[] lines = {
                "§4STR §c" + statBar(pit.statStrength),
                "§2TAC §a" + statBar(pit.statTactics),
                "§5PRE §d" + statBar(pit.statPresence)
        };
        int ttW = 0;
        for (String l : lines) ttW = Math.max(ttW, font.width(l));
        ttW += 8;
        int ttH = lines.length * 10 + 6;
        int ttX = Math.min(mx + 4, width - ttW - 4);
        int ttY = my - ttH - 4;

        g.fill(ttX, ttY, ttX + ttW, ttY + ttH, 0xEE0A0A0A);
        g.renderOutline(ttX, ttY, ttW, ttH, 0xFF4A1A1A);
        for (int i = 0; i < lines.length; i++)
            g.drawString(font, lines[i], ttX + 4, ttY + 3 + i * 10, 0xFFFFFF);
    }

    private static String statBar(int value) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) bar.append(i < value ? "█" : "░");
        return bar + " " + value;
    }

    // -------------------------------------------------------------------------
    // Panel background helper
    // -------------------------------------------------------------------------

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h, String title) {
        g.fill(x + 4, y + 4, x + w + 4, y + h + 4, COL_SHADOW);
        g.fill(x, y, x + w, y + h, COL_BG);
        g.renderOutline(x, y, w, h, COL_BORDER);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, COL_BORDER_INNER);
        g.fill(x + 2, y + 2, x + w - 2, y + 16, COL_HEADER);
        g.drawCenteredString(font, title, x + w / 2, y + 5, 0xFFFFFF);
    }

    // -------------------------------------------------------------------------
    // Button management
    // -------------------------------------------------------------------------

    private void rebuildButtons() {
        clearWidgets();
        int lx = getLeftPanelX();
        int y  = getPanelY();

        if (expandedPit != null) {
            backButton = Button.builder(Component.literal("← Back"),
                            btn -> collapseToList())
                    .bounds(lx + 6, y + 6, 50, 14)
                    .build();
            addRenderableWidget(backButton);

            raidButton = Button.builder(Component.literal("RAID →"),
                            btn -> sendRaid())
                    .bounds(lx + LEFT_W - 60, y + 22, 54, 16)
                    .build();
            addRenderableWidget(raidButton);
        }
    }

    // -------------------------------------------------------------------------
    // Mouse input
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int lx  = getLeftPanelX();
        int rx  = getMapPanelX();
        int py  = getPanelY();
        int mapY = py + 18;

        // Map click
        if (mapRenderer.isInMapPanel((int) mx, (int) my, rx, mapY)) {
            if (button == 0) {
                mapDragging = true;
                mapRenderer.onMouseDragStart(mx, my);
                // On release (mouseReleased) we check for click vs drag
            }
            return true;
        }

        // Left panel clicks
        if (expandedPit == null) {
            int cardX  = lx + CARD_MARGIN;
            int cardW  = LEFT_W - CARD_MARGIN * 2;
            int startY = py + 20;
            for (int i = 0; i < CARDS_VISIBLE; i++) {
                int idx = scrollOffset + i;
                if (idx >= data.pits.size()) break;
                int cardY = startY + i * (CARD_HEIGHT + CARD_MARGIN);
                if (isOver((int) mx, (int) my, cardX, cardY, cardW, CARD_HEIGHT)) {
                    expandPit(data.pits.get(idx));
                    return true;
                }
            }
        } else {
            // Orc row clicks in expanded view
            int contentX   = lx + 6;
            int contentW   = LEFT_W - 12;
            int headerY    = py + 22;
            int dividerY   = headerY + 22;
            int listStartY = dividerY + 4;
            int visOrcs    = (PANEL_H - (listStartY - py) - 14) / ORC_ROW_H;

            for (int i = 0; i < visOrcs; i++) {
                int idx  = orcScrollOffset + i;
                if (idx >= expandedPit.orcs.size()) break;
                OrcEntry orc = expandedPit.orcs.get(idx);
                int rowY = listStartY + i * ORC_ROW_H;
                if (my >= rowY && my < rowY + ORC_ROW_H
                        && mx >= contentX && mx < contentX + contentW) {
                    if (!orc.isLeader) toggleOrcSelection(orc.uuid);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (mapDragging) {
            // If we barely moved, treat as a click
            mapRenderer.onMouseDragEnd();
            mapDragging = false;
            // Register as click if mouse didn't move much (handled in onMouseDragEnd)
            int rx   = getMapPanelX();
            int mapY = getPanelY() + 18;
            mapRenderer.onMouseClick((int) mx, (int) my, rx, mapY);
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button,
                                double dragX, double dragY) {
        if (mapDragging && button == 0) {
            mapRenderer.onMouseDrag(mx, my);
            requestUncachedChunks();
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int delta = sy > 0 ? -1 : 1;
        if (expandedPit == null) {
            scrollOffset = Math.max(0, Math.min(
                    scrollOffset + delta,
                    Math.max(0, data.pits.size() - CARDS_VISIBLE)));
        } else {
            int visOrcs = (PANEL_H - 60) / ORC_ROW_H;
            orcScrollOffset = Math.max(0, Math.min(
                    orcScrollOffset + delta,
                    Math.max(0, expandedPit.orcs.size() - visOrcs)));
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    private void expandPit(PitEntry pit) {
        expandedPit     = pit;
        orcScrollOffset = 0;
        selectedOrcs.clear();
        rebuildButtons();
    }

    private void collapseToList() {
        expandedPit = null;
        selectedOrcs.clear();
        mapRenderer.clearSelectedTarget();
        rebuildButtons();
    }

    private void toggleOrcSelection(UUID uuid) {
        if (!selectedOrcs.remove(uuid)) selectedOrcs.add(uuid);
    }

    private void sendRaid() {
        if (expandedPit == null) return;
        if (!mapRenderer.hasSelectedTarget()) {
            assert minecraft != null;
            assert minecraft.player != null;
            minecraft.player.sendSystemMessage(Component.literal(
                    "§cSelect a target on the map first."));
            return;
        }

        Set<UUID> toSend = new HashSet<>(selectedOrcs);
        for (OrcEntry orc : expandedPit.orcs) {
            if (orc.isLeader) { toSend.add(orc.uuid); break; }
        }
        if (toSend.isEmpty()) return;

        RaidParty.TargetType targetType = mapRenderer.getSelectedTargetPoiType() != null
                ? RaidParty.TargetType.POI
                : RaidParty.TargetType.FREE_TARGET;

        PacketDistributor.sendToServer(new RaidStartPacket(
                expandedPit.pitIndex,
                new ArrayList<>(toSend),
                mapRenderer.getSelectedTargetX(),
                mapRenderer.getSelectedTargetZ(),
                targetType,
                mapRenderer.getSelectedTargetLabel()));

        collapseToList();
    }

    // -------------------------------------------------------------------------
    // Map chunk requests
    // -------------------------------------------------------------------------

    private void requestUncachedChunks() {
        List<long[]> uncached = mapRenderer.getUncachedVisibleChunks();
        if (!uncached.isEmpty()) {
            PacketDistributor.sendToServer(new MapChunkRequestPacket(uncached));
        }
    }

    /** Called by MapChunkResponsePacket when server sends new chunk data. */
    public void receiveMapChunks(List<ChunkMapEntry> entries) {
        mapRenderer.receiveChunks(entries);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isOver(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override public boolean isPauseScreen()    { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }
}