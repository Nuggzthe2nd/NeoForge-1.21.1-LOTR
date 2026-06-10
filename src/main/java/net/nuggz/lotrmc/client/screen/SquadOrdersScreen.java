package net.nuggz.lotrmc.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.nuggz.lotrmc.client.GuardCenterPlacementMode;
import net.nuggz.lotrmc.client.WaypointPlacementMode;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.PatrolWaypoint;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.network.SetSquadOrderPacket;

import java.util.List;

/**
 * Squad Orders screen — opened by right-clicking the mudpit core block.
 *
 * Main view shows current order and four order buttons.
 * Guard Area and Patrol buttons open a secondary config panel.
 *
 * Waypoint placement mode is separate — activated client-side
 * and handled by NethercrownItem's sneak+right-click interaction.
 */
public class SquadOrdersScreen extends Screen {

    private static final int PANEL_W    = 200;
    private static final int PANEL_H_BASE = 135; // height with no sub-panel
    private static final int PANEL_H_GUARD = 175; // height with guard area sub-panel
    private static final int PANEL_H_PATROL= 195; // height with patrol sub-panel

    private static final int COL_BG          = 0xEE0A0A0A;
    private static final int COL_BORDER      = 0xFF4A1A1A;
    private static final int COL_BORDER_INNER= 0xFF2A0A0A;
    private static final int COL_HEADER      = 0xFF0A0505;
    private static final int COL_SHADOW      = 0x88000000;
    private static final int COL_BTN_ACTIVE  = 0xFF3A0808;
    private static final int COL_DIVIDER     = 0xFF2A0A0A;

    // Data passed from server
    private final BlockPos pitPos;
    private final SquadOrderData orders;
    private final int orcCount;
    private final int maxOrcs;
    private final boolean hasLeader;

    // Sub-panel state
    private enum SubPanel { NONE, GUARD_AREA, PATROL }
    private SubPanel subPanel = SubPanel.NONE;

    // Guard area config inputs
    private EditBox guardRadiusBox;

    // Patrol config inputs
    private EditBox watchWaitBox;

    public SquadOrdersScreen(BlockPos pitPos, SquadOrderData orders,
                             int orcCount, int maxOrcs, boolean hasLeader) {
        super(Component.literal("Squad Orders"));
        this.pitPos    = pitPos;
        this.orders    = orders;
        this.orcCount  = orcCount;
        this.maxOrcs   = maxOrcs;
        this.hasLeader = hasLeader;
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private int getPanelH() {
        return switch (subPanel) {
            case GUARD_AREA -> PANEL_H_GUARD;
            case PATROL     -> PANEL_H_PATROL;
            default         -> PANEL_H_BASE;
        };
    }

    private int getPanelX() { return (width  - PANEL_W) / 2; }
    private int getPanelY() { return (height - getPanelH()) / 2; }

    private void rebuildButtons() {
        clearWidgets();
        int x = getPanelX();
        int y = getPanelY();
        int bx = x + 10;
        int bw = PANEL_W - 20;

        // Order buttons
        addRenderableWidget(Button.builder(
                        Component.literal("Guard Pit" + (orders.getCurrentOrder() == OrcOrder.GUARD_PIT ? " ✔" : "")),
                        btn -> sendOrder(OrcOrder.GUARD_PIT))
                .bounds(bx, y + 36, bw, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Guard Area" + (orders.getCurrentOrder() == OrcOrder.GUARD_AREA ? " ✔" : "")),
                        btn -> toggleSubPanel(SubPanel.GUARD_AREA))
                .bounds(bx, y + 56, bw, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Patrol" + (orders.getCurrentOrder() == OrcOrder.PATROL ? " ✔" : "")),
                        btn -> toggleSubPanel(SubPanel.PATROL))
                .bounds(bx, y + 76, bw, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Return to Pit"),
                        btn -> sendOrder(OrcOrder.RETURN_TO_PIT))
                .bounds(bx, y + 96, bw, 16).build());

        // Sub-panel widgets
        if (subPanel == SubPanel.GUARD_AREA) {
            guardRadiusBox = new EditBox(font, bx, y + 126, bw - 60, 16,
                    Component.literal("Radius"));
            guardRadiusBox.setValue(String.valueOf(orders.getGuardRadius()));
            guardRadiusBox.setMaxLength(4);
            addWidget(guardRadiusBox);

            addRenderableWidget(Button.builder(
                            Component.literal("Set"),
                            btn -> confirmGuardArea())
                    .bounds(bx + bw - 56, y + 126, 56, 16).build());

            String centerLabel = orders.getGuardCenter() != null
                    ? "Center: " + orders.getGuardCenter().toShortString() + " (click to move)"
                    : "Set Center: Sneak+RClick a block";
            addRenderableWidget(Button.builder(
                            Component.literal(centerLabel),
                            btn -> startGuardCenterPlacement())
                    .bounds(bx, y + 146, bw, 16).build());
        }

        if (subPanel == SubPanel.PATROL) {
            // Watch post wait time
            watchWaitBox = new EditBox(font, bx, y + 126, bw - 90, 16,
                    Component.literal("Wait (ticks)"));
            watchWaitBox.setValue(String.valueOf(orders.getWatchPostWaitTicks()));
            watchWaitBox.setMaxLength(6);
            addWidget(watchWaitBox);

            addRenderableWidget(Button.builder(
                            Component.literal("Set Wait"),
                            btn -> confirmPatrolConfig())
                    .bounds(bx + bw - 86, y + 126, 86, 16).build());

            // Waypoint placement instructions
            addRenderableWidget(Button.builder(
                            Component.literal("Place waypoints: Sneak+RClick blocks"),
                            btn -> startWaypointPlacement())
                    .bounds(bx, y + 146, bw, 16).build());

            addRenderableWidget(Button.builder(
                            Component.literal("Clear Path"),
                            btn -> clearPatrolPath())
                    .bounds(bx, y + 166, bw, 16).build());
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // No blur
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        int x = getPanelX();
        int y = getPanelY();

        // Panel
        int ph = getPanelH();
        g.fill(x + 4, y + 4, x + PANEL_W + 4, y + ph + 4, COL_SHADOW);
        g.fill(x, y, x + PANEL_W, y + ph, COL_BG);
        g.renderOutline(x, y, PANEL_W, ph, COL_BORDER);
        g.renderOutline(x + 1, y + 1, PANEL_W - 2, ph - 2, COL_BORDER_INNER);

        // Title
        g.fill(x + 2, y + 2, x + PANEL_W - 2, y + 16, COL_HEADER);
        g.drawCenteredString(font, "§4Squad Orders", x + PANEL_W / 2, y + 5, 0xFFFFFF);

        // Status row
        g.drawString(font,
                "§7Orcs: §f" + orcCount + "§7/§f" + maxOrcs
                        + "   §7Leader: " + (hasLeader ? "§a✔" : "§c✘"),
                x + 10, y + 20, 0xFFFFFF);

        // Divider
        g.fill(x + 4, y + 30, x + PANEL_W - 4, y + 31, COL_DIVIDER);

        // Current order label — only shown when no sub-panel is open
        if (subPanel == SubPanel.NONE) {
            g.drawString(font, "§8Current: §7" + orders.getDisplayName(),
                    x + 10, y + 117, 0xFFFFFF);
        }

        // Sub-panel headers
        if (subPanel == SubPanel.GUARD_AREA) {
            g.fill(x + 4, y + 116, x + PANEL_W - 4, y + 117, COL_DIVIDER);
            g.drawString(font, "§7Guard radius (blocks):", x + 10, y + 119, 0xAAAAAA);
        }
        if (subPanel == SubPanel.PATROL) {
            g.fill(x + 4, y + 116, x + PANEL_W - 4, y + 117, COL_DIVIDER);
            g.drawString(font, "§7Watch post wait (ticks):", x + 10, y + 119, 0xAAAAAA);

            // Waypoint count only
            int wpCount = orders.getWaypoints().size();
            g.drawString(font, "§8Waypoints: §7" + wpCount + "§8/§7"
                            + SquadOrderData.MAX_WAYPOINTS,
                    x + 10, y + 168, 0xAAAAAA);
        }

        // Edit box rendering
        if (guardRadiusBox != null && subPanel == SubPanel.GUARD_AREA)
            guardRadiusBox.render(g, mx, my, delta);
        if (watchWaitBox != null && subPanel == SubPanel.PATROL)
            watchWaitBox.render(g, mx, my, delta);

        super.render(g, mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (guardRadiusBox != null && guardRadiusBox.isFocused())
            return guardRadiusBox.keyPressed(key, scan, mod) || super.keyPressed(key, scan, mod);
        if (watchWaitBox != null && watchWaitBox.isFocused())
            return watchWaitBox.keyPressed(key, scan, mod) || super.keyPressed(key, scan, mod);
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char c, int mod) {
        if (guardRadiusBox != null && guardRadiusBox.isFocused()) return guardRadiusBox.charTyped(c, mod);
        if (watchWaitBox   != null && watchWaitBox.isFocused())   return watchWaitBox.charTyped(c, mod);
        return super.charTyped(c, mod);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void toggleSubPanel(SubPanel panel) {
        subPanel = (subPanel == panel) ? SubPanel.NONE : panel;
        rebuildButtons();
    }

    private void sendOrder(OrcOrder order) {
        PacketDistributor.sendToServer(new SetSquadOrderPacket(
                pitPos, order, null, 0,
                orders.getWaypoints(),
                orders.getWatchPostWaitTicks()));
        orders.setOrder(order);
        rebuildButtons();
    }

    private void confirmGuardArea() {
        if (orders.getGuardCenter() == null) {
            if (minecraft != null && minecraft.player != null)
                minecraft.player.sendSystemMessage(Component.literal(
                        "§cNo guard center set. Right-click a block with the Nethercrown first."));
            return;
        }

        int radius = 16;
        try { radius = Math.clamp(Integer.parseInt(guardRadiusBox.getValue().trim()), 4, 64); }
        catch (NumberFormatException ignored) {}

        PacketDistributor.sendToServer(new SetSquadOrderPacket(
                pitPos, OrcOrder.GUARD_AREA,
                orders.getGuardCenter(), radius,
                orders.getWaypoints(),
                orders.getWatchPostWaitTicks()));
        orders.setOrder(OrcOrder.GUARD_AREA);
        rebuildButtons();
    }

    private void confirmPatrolConfig() {
        int waitTicks = SquadOrderData.DEFAULT_WATCH_TICKS;
        try { waitTicks = Math.max(20, Integer.parseInt(watchWaitBox.getValue().trim())); }
        catch (NumberFormatException ignored) {}

        orders.setWatchPostWaitTicks(waitTicks);
        PacketDistributor.sendToServer(new SetSquadOrderPacket(
                pitPos, OrcOrder.PATROL,
                null, 0,
                orders.getWaypoints(), waitTicks));
        rebuildButtons();
    }

    private void startWaypointPlacement() {
        WaypointPlacementMode.activate(pitPos, orders);
        onClose();
    }

    private void startGuardCenterPlacement() {
        GuardCenterPlacementMode.activate(pitPos);
        onClose();
    }

    private void clearPatrolPath() {
        orders.clearWaypoints();
        PacketDistributor.sendToServer(new SetSquadOrderPacket(
                pitPos, orders.getCurrentOrder(),
                null, 0,
                orders.getWaypoints(),
                orders.getWatchPostWaitTicks()));
        rebuildButtons();
    }

    @Override public boolean isPauseScreen()    { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }
}