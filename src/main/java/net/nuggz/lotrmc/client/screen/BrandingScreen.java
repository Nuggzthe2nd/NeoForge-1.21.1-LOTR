package net.nuggz.lotrmc.client.screen;

import net.nuggz.lotrmc.network.BrandingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Simple naming screen shown when a player right-clicks an orc with a Brand item.
 *
 * Contains:
 *   - A title ("Brand this orc as pit leader")
 *   - A text field for the name
 *   - Confirm and Cancel buttons
 *
 * On confirm: sends BrandingPacket to server.
 * On cancel: closes with no effect (Brand item not consumed).
 */
public class BrandingScreen extends Screen {

    private static final int SCREEN_WIDTH  = 220;
    private static final int SCREEN_HEIGHT = 90;
    private static final int MAX_LENGTH    = BrandingPacket.MAX_NAME_LENGTH;

    private final UUID orcUUID;
    private EditBox nameField;

    public BrandingScreen(UUID orcUUID) {
        super(Component.literal("Brand Pit Leader"));
        this.orcUUID = orcUUID;
    }

    @Override
    protected void init() {
        int x = (width  - SCREEN_WIDTH)  / 2;
        int y = (height - SCREEN_HEIGHT) / 2;

        // Name input field
        nameField = new EditBox(
                font,
                x + 10, y + 30,
                SCREEN_WIDTH - 20, 20,
                Component.literal("Orc name"));
        nameField.setMaxLength(MAX_LENGTH);
        nameField.setFocused(true);
        addWidget(nameField);

        // Confirm button
        addRenderableWidget(Button.builder(
                        Component.literal("Brand"),
                        btn -> confirm())
                .bounds(x + 10, y + 60, 90, 20)
                .build());

        // Cancel button
        addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        btn -> onClose())
                .bounds(x + 120, y + 60, 90, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Skip the default blur + dirt background so the game world stays sharp.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = (width  - SCREEN_WIDTH)  / 2;
        int y = (height - SCREEN_HEIGHT) / 2;

        // Drop shadow
        graphics.fill(x + 4, y + 4, x + SCREEN_WIDTH + 4, y + SCREEN_HEIGHT + 4, 0x88000000);
        // Panel background
        graphics.fill(x, y, x + SCREEN_WIDTH, y + SCREEN_HEIGHT, 0xEE0A0A0A);
        // Dark red border
        graphics.renderOutline(x, y, SCREEN_WIDTH, SCREEN_HEIGHT, 0xFF4A1A1A);
        // Inner border highlight
        graphics.renderOutline(x + 1, y + 1, SCREEN_WIDTH - 2, SCREEN_HEIGHT - 2, 0xFF2A0A0A);

        // Title
        graphics.drawCenteredString(font,
                "§4Brand Pit Leader",
                width / 2, y + 10, 0xFFFFFF);

        // Prompt
        graphics.drawString(font,
                "§7Give this orc a name:",
                x + 10, y + 22, 0xAAAAAA);

        nameField.render(graphics, mouseX, mouseY, delta);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter confirms, Escape cancels
        if (keyCode == 257 || keyCode == 335) { // ENTER or NUMPAD_ENTER
            confirm();
            return true;
        }
        return nameField.keyPressed(keyCode, scanCode, modifiers)
                || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return nameField.charTyped(c, modifiers) || super.charTyped(c, modifiers);
    }

    private void confirm() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) return;

        // Send to server
        PacketDistributor.sendToServer(new BrandingPacket(orcUUID, name));
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // don't pause the game while naming
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // Escape cancels with no cost, same as the Cancel button
    }
}