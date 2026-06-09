package net.nuggz.lotrmc.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.nuggz.lotrmc.wartable.WarTableData;
import net.nuggz.lotrmc.wartable.WarTableData.OrcEntry;
import net.nuggz.lotrmc.wartable.WarTableData.PitEntry;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * War Table screen.
 *
 * Layout:
 *   Left half  — scrollable pit card list (3 visible at a time)
 *   Right half — reserved for future systems
 *
 * States:
 *   LIST VIEW    — shows all pit cards, click to expand
 *   EXPANDED VIEW — shows full detail of one pit with orc list and raid controls
 */
public class WarTableScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    private static final int PANEL_WIDTH  = 180;
    private static final int PANEL_HEIGHT = 220;
    private static final int CARD_HEIGHT  = 60;
    private static final int CARD_MARGIN  = 6;
    private static final int CARDS_VISIBLE = 3;
    private static final int ORC_ROW_HEIGHT = 14;

    // Colors
    private static final int COL_BG         = 0xEE0A0A0A;
    private static final int COL_BORDER      = 0xFF4A1A1A;
    private static final int COL_BORDER_INNER= 0xFF2A0A0A;
    private static final int COL_CARD        = 0xFF150505;
    private static final int COL_CARD_HOVER  = 0xFF200808;
    private static final int COL_CARD_LEADER = 0xFF1A0A00;
    private static final int COL_SELECTED    = 0xFF3A1A00;
    private static final int COL_HEADER      = 0xFF0A0505;
    private static final int COL_RAID_BTN    = 0xFF8B0000;
    private static final int COL_RAID_HOVER  = 0xFFAA0000;
    private static final int COL_BACK_BTN    = 0xFF1A1A1A;
    private static final int COL_SHADOW      = 0x88000000;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final WarTableData data;

    // List view scroll
    private int scrollOffset = 0; // index of first visible card

    // Expanded view
    private PitEntry expandedPit = null;
    private int orcScrollOffset  = 0;
    private final Set<UUID> selectedOrcs = new HashSet<>();

    // Buttons — rebuilt on state change
    private Button backButton   = null;
    private Button raidButton   = null;

    // Mouse tracking for hover
    private int mouseX = 0;
    private int mouseY = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public WarTableScreen(WarTableData data) {
        super(Component.literal("War Table"));
        this.data = data;
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();

        int panelX = getPanelX();
        int panelY = getPanelY();

        if (expandedPit != null) {
            // Back button
            backButton = Button.builder(Component.literal("← Back"), btn -> collapseToList())
                    .bounds(panelX + 6, panelY + 6, 50, 14)
                    .build();
            addRenderableWidget(backButton);

            // Raid button — top right of expanded view
            raidButton = Button.builder(Component.literal("RAID →"), btn -> sendRaid())
                    .bounds(panelX + PANEL_WIDTH - 60, panelY + 22, 54, 16)
                    .build();
            addRenderableWidget(raidButton);
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // No-op — suppress blur shader
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        int panelX = getPanelX();
        int panelY = getPanelY();

        drawPanel(graphics, panelX, panelY);

        if (expandedPit == null) {
            renderListView(graphics, panelX, panelY, mouseX, mouseY);
        } else {
            renderExpandedView(graphics, panelX, panelY, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, delta);

        // Tooltip drawn last — after buttons — so its background is on top
        if (expandedPit != null && expandedPit.hasLeader()) {
            int contentX = getPanelX() + 6;
            int headerY  = getPanelY() + 22;
            int nameW    = font.width(expandedPit.leaderName);
            if (mouseX >= contentX && mouseX <= contentX + nameW
                    && mouseY >= headerY + 3 && mouseY <= headerY + 14) {
                drawLeaderTooltip(graphics, expandedPit, mouseX, mouseY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Panel background
    // -------------------------------------------------------------------------

    private void drawPanel(GuiGraphics g, int x, int y) {
        // Shadow
        g.fill(x + 4, y + 4, x + PANEL_WIDTH + 4, y + PANEL_HEIGHT + 4, COL_SHADOW);
        // Background
        g.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, COL_BG);
        // Borders
        g.renderOutline(x, y, PANEL_WIDTH, PANEL_HEIGHT, COL_BORDER);
        g.renderOutline(x + 1, y + 1, PANEL_WIDTH - 2, PANEL_HEIGHT - 2, COL_BORDER_INNER);

        // Title bar
        g.fill(x + 2, y + 2, x + PANEL_WIDTH - 2, y + 16, COL_HEADER);
        g.drawCenteredString(font, "§4War Table", x + PANEL_WIDTH / 2, y + 5, 0xFFFFFF);
    }

    // -------------------------------------------------------------------------
    // List view
    // -------------------------------------------------------------------------

    private void renderListView(GuiGraphics g, int panelX, int panelY,
                                int mouseX, int mouseY) {
        if (data.pits.isEmpty()) {
            g.drawCenteredString(font, "§7No mudpits found.",
                    panelX + PANEL_WIDTH / 2, panelY + PANEL_HEIGHT / 2, 0xAAAAAA);
            return;
        }

        int cardX   = panelX + CARD_MARGIN;
        int cardW   = PANEL_WIDTH - CARD_MARGIN * 2;
        int startY  = panelY + 20;

        for (int i = 0; i < CARDS_VISIBLE; i++) {
            int pitIdx = scrollOffset + i;
            if (pitIdx >= data.pits.size()) break;

            PitEntry pit = data.pits.get(pitIdx);
            int cardY   = startY + i * (CARD_HEIGHT + CARD_MARGIN);
            boolean hovered = isMouseOverCard(mouseX, mouseY, cardX, cardY, cardW, CARD_HEIGHT);

            drawPitCard(g, pit, cardX, cardY, cardW, CARD_HEIGHT, hovered);
        }

        // Scroll indicators
        if (scrollOffset > 0) {
            g.drawCenteredString(font, "§7▲",
                    panelX + PANEL_WIDTH / 2, panelY + 18, 0x888888);
        }
        if (scrollOffset + CARDS_VISIBLE < data.pits.size()) {
            g.drawCenteredString(font, "§7▼",
                    panelX + PANEL_WIDTH / 2, panelY + PANEL_HEIGHT - 10, 0x888888);
        }
    }

    private void drawPitCard(GuiGraphics g, PitEntry pit,
                             int x, int y, int w, int h, boolean hovered) {
        // Card background
        int bg = hovered ? COL_CARD_HOVER : COL_CARD;
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, COL_BORDER);

        int textX = x + 5;
        int textY = y + 5;

        // Leader name (top right) or "No leader"
        String leaderLabel = pit.hasLeader() ? "§c" + pit.leaderName : "§8No leader";
        g.drawString(font, leaderLabel, x + w - font.width(
                        net.minecraft.network.chat.Component.literal(
                                pit.hasLeader() ? pit.leaderName : "No leader").getString()) - 5,
                textY, 0xFFFFFF);

        // Orc count / capacity
        g.drawString(font, "§7" + pit.orcs.size() + "§8/§7" + pit.capacity + " orcs",
                textX, textY, 0xFFFFFF);

        // Default order
        g.drawString(font, "§8Order: §7" + pit.defaultOrder,
                textX, textY + 12, 0xFFFFFF);

        // Gestation bar if active
        if (pit.isGestating) {
            g.drawString(font, "§2Growing: §a" + pit.gestationPercent + "%",
                    textX, textY + 24, 0xFFFFFF);
            // Progress bar
            int barW = w - 10;
            int filled = (barW * pit.gestationPercent) / 100;
            g.fill(textX, textY + 34, textX + barW, textY + 38, 0xFF1A1A1A);
            g.fill(textX, textY + 34, textX + filled, textY + 38, 0xFF005500);
        } else {
            // Biomass
            g.drawString(font, "§8Biomass: §6" + pit.biomass,
                    textX, textY + 24, 0xFFFFFF);
        }

        // Click hint
        g.drawString(font, "§8▶ click to expand",
                textX, textY + 44, 0x555555);
    }

    // -------------------------------------------------------------------------
    // Expanded view
    // -------------------------------------------------------------------------

    private void renderExpandedView(GuiGraphics g, int panelX, int panelY,
                                    int mouseX, int mouseY) {
        PitEntry pit = expandedPit;
        int contentX = panelX + 6;
        int contentW = PANEL_WIDTH - 12;

        // Header row: back button already rendered by widget system
        // Leader stats row — all on one line below the back button
        int headerY = panelY + 22;
        g.fill(panelX + 2, headerY, panelX + PANEL_WIDTH - 2, headerY + 20, COL_HEADER);

        if (pit.hasLeader()) {
            // Leader name only — stats shown as tooltip on hover
            g.drawString(font, "§c" + pit.leaderName, contentX, headerY + 5, 0xFFFFFF);
        } else {
            g.drawString(font, "§8No leader assigned", contentX, headerY + 5, 0xFFFFFF);
        }

        // Raid button is rendered by widget system (top right)

        // Divider
        int dividerY = headerY + 22;
        g.fill(panelX + 4, dividerY, panelX + PANEL_WIDTH - 4, dividerY + 1, COL_BORDER);

        // Orc list
        int listStartY = dividerY + 4;
        int visibleOrcs = (PANEL_HEIGHT - (listStartY - panelY) - 14) / ORC_ROW_HEIGHT;

        for (int i = 0; i < visibleOrcs; i++) {
            int orcIdx = orcScrollOffset + i;
            if (orcIdx >= pit.orcs.size()) break;

            OrcEntry orc = pit.orcs.get(orcIdx);
            int rowY = listStartY + i * ORC_ROW_HEIGHT;
            boolean selected = orc.isLeader || selectedOrcs.contains(orc.uuid);
            boolean hovered  = mouseY >= rowY && mouseY < rowY + ORC_ROW_HEIGHT
                    && mouseX >= contentX && mouseX < contentX + contentW;

            drawOrcRow(g, orc, contentX, rowY, contentW, selected, hovered);
        }

        // Orc scroll indicators
        if (orcScrollOffset > 0) {
            g.drawString(font, "§7▲", contentX, dividerY + 2, 0x888888);
        }
        if (orcScrollOffset + visibleOrcs < pit.orcs.size()) {
            g.drawString(font, "§7▼",
                    contentX, panelY + PANEL_HEIGHT - 12, 0x888888);
        }

        // Selection hint at bottom
        int selectedCount = selectedOrcs.size() + (pit.hasLeader() ? 1 : 0);
        g.drawString(font, "§8" + selectedCount + " selected for raid",
                contentX, panelY + PANEL_HEIGHT - 10, 0x555555);

    }

    private void drawOrcRow(GuiGraphics g, OrcEntry orc,
                            int x, int y, int w,
                            boolean selected, boolean hovered) {
        // Row background
        int bg = orc.isLeader ? COL_CARD_LEADER
                : selected    ? COL_SELECTED
                  : hovered     ? COL_CARD_HOVER
                    :               COL_CARD;
        g.fill(x, y, x + w, y + ORC_ROW_HEIGHT - 1, bg);

        // Selection marker
        String marker = selected ? "§6▶ " : "§8  ";
        g.drawString(font, marker, x + 2, y + 2, 0xFFFFFF);

        // Name
        String nameColor = orc.isLeader ? "§c" : "§7";
        g.drawString(font, nameColor + orc.name, x + 14, y + 2, 0xFFFFFF);

        // Scar icons — ⚔ per scar, up to 5 shown then "+N"
        String scarDisplay = buildScarDisplay(orc.scarCount);
        g.drawString(font, scarDisplay,
                x + w - font.width(scarDisplay) - 2, y + 2, 0xFFFFFF);
    }

    private String buildScarDisplay(int scarCount) {
        if (scarCount == 0) return "§8—";
        int shown = Math.min(scarCount, 5);
        StringBuilder sb = new StringBuilder("§4");
        for (int i = 0; i < shown; i++) sb.append("✦");
        if (scarCount > 5) sb.append("§8+").append(scarCount - 5);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Mouse input
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        int panelX = getPanelX();
        int panelY = getPanelY();

        if (expandedPit == null) {
            // List view — check card clicks
            int cardX  = panelX + CARD_MARGIN;
            int cardW  = PANEL_WIDTH - CARD_MARGIN * 2;
            int startY = panelY + 20;

            for (int i = 0; i < CARDS_VISIBLE; i++) {
                int pitIdx = scrollOffset + i;
                if (pitIdx >= data.pits.size()) break;

                int cardY = startY + i * (CARD_HEIGHT + CARD_MARGIN);
                if (isMouseOverCard((int) mouseX, (int) mouseY,
                        cardX, cardY, cardW, CARD_HEIGHT)) {
                    expandPit(data.pits.get(pitIdx));
                    return true;
                }
            }
        } else {
            // Expanded view — check orc row clicks
            int contentX    = panelX + 6;
            int contentW    = PANEL_WIDTH - 12;
            int headerY     = panelY + 22;
            int dividerY    = headerY + 22;
            int listStartY  = dividerY + 4;
            int visibleOrcs = (PANEL_HEIGHT - (listStartY - panelY) - 14) / ORC_ROW_HEIGHT;

            for (int i = 0; i < visibleOrcs; i++) {
                int orcIdx = orcScrollOffset + i;
                if (orcIdx >= expandedPit.orcs.size()) break;

                OrcEntry orc = expandedPit.orcs.get(orcIdx);
                int rowY = listStartY + i * ORC_ROW_HEIGHT;

                if (mouseY >= rowY && mouseY < rowY + ORC_ROW_HEIGHT
                        && mouseX >= contentX && mouseX < contentX + contentW) {
                    if (!orc.isLeader) toggleOrcSelection(orc.uuid);
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        int delta = scrollY > 0 ? -1 : 1;

        if (expandedPit == null) {
            // Scroll pit list
            int maxScroll = Math.max(0, data.pits.size() - CARDS_VISIBLE);
            scrollOffset = Math.max(0, Math.min(scrollOffset + delta, maxScroll));
        } else {
            // Scroll orc list
            int visibleOrcs = (PANEL_HEIGHT - 60) / ORC_ROW_HEIGHT;
            int maxScroll   = Math.max(0, expandedPit.orcs.size() - visibleOrcs);
            orcScrollOffset = Math.max(0, Math.min(orcScrollOffset + delta, maxScroll));
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
        // Leader is always implicitly selected — no need to add to set
        rebuildButtons();
    }

    private void collapseToList() {
        expandedPit = null;
        selectedOrcs.clear();
        rebuildButtons();
    }

    private void toggleOrcSelection(UUID uuid) {
        if (selectedOrcs.contains(uuid)) {
            selectedOrcs.remove(uuid);
        } else {
            selectedOrcs.add(uuid);
        }
    }

    private void sendRaid() {
        if (expandedPit == null) return;

        // Build the list of orcs to send — always includes leader
        Set<UUID> toSend = new HashSet<>(selectedOrcs);
        if (expandedPit.hasLeader()) {
            // Find leader UUID and add it
            for (OrcEntry orc : expandedPit.orcs) {
                if (orc.isLeader) { toSend.add(orc.uuid); break; }
            }
        }

        if (toSend.isEmpty()) return;

        // TODO: send RaidStartPacket(pitIndex, toSend) to server
        // For now just close — raid system wired up in next step
        net.minecraft.client.Minecraft.getInstance().player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                        "§8Raid order sent for " + toSend.size() + " orc(s). (stub)"));
        collapseToList();
    }

    // -------------------------------------------------------------------------
    // Leader stat tooltip
    // -------------------------------------------------------------------------

    private void drawLeaderTooltip(GuiGraphics g, PitEntry pit, int mx, int my) {
        String[] lines = {
                "§4STR §c" + statBar(pit.statStrength),
                "§2TAC §a" + statBar(pit.statTactics),
                "§5PRE §d" + statBar(pit.statPresence)
        };

        int ttW = 0;
        for (String line : lines) ttW = Math.max(ttW, font.width(line));
        ttW += 8;
        int ttH = lines.length * 10 + 6;

        // Position above cursor, nudge left if too close to right edge
        int ttX = Math.min(mx + 4, width - ttW - 4);
        int ttY = my - ttH - 4;

        g.pose().pushPose();
        g.pose().translate(0, 0, 400);

        g.fill(ttX, ttY, ttX + ttW, ttY + ttH, 0xEE0A0A0A);
        g.renderOutline(ttX, ttY, ttW, ttH, 0xFF4A1A1A);

        for (int i = 0; i < lines.length; i++) {
            g.drawString(font, lines[i], ttX + 4, ttY + 3 + i * 10, 0xFFFFFF);
        }

        g.pose().popPose();
    }

    private static String statBar(int value) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) bar.append(i < value ? "█" : "░");
        return bar + " " + value;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getPanelX() { return (width / 4) - (PANEL_WIDTH / 2); }
    private int getPanelY() { return (height - PANEL_HEIGHT) / 2; }

    private boolean isMouseOverCard(int mx, int my,
                                    int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}