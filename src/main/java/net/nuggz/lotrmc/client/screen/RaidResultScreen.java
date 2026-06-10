package net.nuggz.lotrmc.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.nuggz.lotrmc.network.RaidResultPacket;

import java.util.List;

/**
 * Shown when a raid party returns home.
 *
 * Displays:
 *   - Header: "Warband Returns"
 *   - Leader name + target
 *   - Casualties / survivors
 *   - Scars gained
 *   - Loot list
 *   - Narrative paragraph
 *   - Dismiss button
 */
public class RaidResultScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 200;

    private static final int COL_BG     = 0xEE0A0A0A;
    private static final int COL_BORDER = 0xFF4A1A1A;
    private static final int COL_HEADER = 0xFF0A0505;
    private static final int COL_SHADOW = 0x88000000;

    private final RaidResultPacket data;

    public RaidResultScreen(RaidResultPacket data) {
        super(Component.literal("Raid Result"));
        this.data = data;
    }

    @Override
    protected void init() {
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;

        addRenderableWidget(Button.builder(
                        Component.literal("Dismiss"),
                        btn -> onClose())
                .bounds(x + PANEL_W / 2 - 40, y + PANEL_H - 24, 80, 16)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // No blur
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;

        // Shadow + panel
        g.fill(x + 4, y + 4, x + PANEL_W + 4, y + PANEL_H + 4, COL_SHADOW);
        g.fill(x, y, x + PANEL_W, y + PANEL_H, COL_BG);
        g.renderOutline(x, y, PANEL_W, PANEL_H, COL_BORDER);
        g.renderOutline(x + 1, y + 1, PANEL_W - 2, PANEL_H - 2, 0xFF2A0A0A);

        // Title bar
        g.fill(x + 2, y + 2, x + PANEL_W - 2, y + 16, COL_HEADER);
        g.drawCenteredString(font, "§4Warband Returns", x + PANEL_W / 2, y + 5, 0xFFFFFF);

        int tx = x + 8;
        int ty = y + 20;

        // Leader + target
        String leaderRef = data.leaderName() != null && !data.leaderName().isEmpty()
                ? "§c" + data.leaderName() : "§7Your warband";
        g.drawString(font, leaderRef + " §8→ §7" + data.targetLabel(), tx, ty, 0xFFFFFF);
        ty += 12;

        // Divider
        g.fill(x + 4, ty, x + PANEL_W - 4, ty + 1, COL_BORDER);
        ty += 4;

        // Stats row
        g.drawString(font, "§2Survived: §a" + data.survivors(), tx, ty, 0xFFFFFF);
        g.drawString(font, "§4Fell: §c" + data.casualties(),
                tx + 80, ty, 0xFFFFFF);
        if (data.scarsGained() > 0) {
            g.drawString(font, "§6Scars: §e" + data.scarsGained(),
                    tx + 160, ty, 0xFFFFFF);
        }
        ty += 14;

        // Loot
        if (!data.loot().isEmpty()) {
            g.drawString(font, "§6Spoils:", tx, ty, 0xFFFFFF);
            ty += 11;
            for (ItemStack stack : data.loot()) {
                g.drawString(font,
                        "§7• " + stack.getCount() + "x "
                                + stack.getHoverName().getString(),
                        tx + 4, ty, 0xFFFFFF);
                ty += 10;
            }
        }

        ty += 4;

        // Divider
        g.fill(x + 4, ty, x + PANEL_W - 4, ty + 1, COL_BORDER);
        ty += 6;

        // Narrative — word-wrapped
        List<net.minecraft.util.FormattedCharSequence> lines =
                font.split(Component.literal("§7" + data.narrative()),
                        PANEL_W - 16);
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawString(font, line, tx, ty, 0xAAAAAA);
            ty += 10;
        }

        super.render(g, mx, my, delta);
    }

    @Override public boolean isPauseScreen()    { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }
}