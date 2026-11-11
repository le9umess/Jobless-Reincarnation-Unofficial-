package dev.joblessreincarnation.client;

import dev.joblessreincarnation.training.TrainingBoardMenu;
import dev.joblessreincarnation.training.TrainingTrials;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.world.entity.player.Inventory;

public final class TrainingBoardScreen extends AbstractContainerScreen<TrainingBoardMenu> {
    private static final int INK = 0x493D2F;
    private static final int MUTED = 0x77654E;
    private static final int WATER = 0x36798D;
    private static final int COMPLETE = 0x527854;
    private Button claimButton;

    public TrainingBoardScreen(TrainingBoardMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 300;
        imageHeight = 220;
    }

    @Override
    protected void init() {
        super.init();
        claimButton = addRenderableWidget(Button.builder(claimLabel(), button -> {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, TrainingBoardMenu.CLAIM_BUTTON);
            }
        }).bounds(leftPos + 70, topPos + 189, 160, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(leftPos + 240, topPos + 189, 42, 20).build());
        updateClaimButton();
    }

    private Component claimLabel() {
        return Component.translatable(menu.claimed() ? "screen.jobless_reincarnation.training.claimed"
                : "screen.jobless_reincarnation.training.claim");
    }

    private void updateClaimButton() {
        claimButton.active = menu.completed() && !menu.claimed();
        claimButton.setMessage(claimLabel());
    }

    @Override
    protected void containerTick() {
        updateClaimButton();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateClaimButton();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x + 3, y + 3, x + imageWidth + 3, y + imageHeight + 3, 0x70000000);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF624735);
        graphics.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, 0xFFA07950);
        graphics.fill(x + 7, y + 7, x + imageWidth - 7, y + imageHeight - 7, 0xFFE5D5B3);
        graphics.fill(x + 10, y + 10, x + imageWidth - 10, y + imageHeight - 10, 0xFFF3E7CA);
        graphics.fill(x + 18, y + 41, x + imageWidth - 18, y + 42, 0xFFBEA780);
        graphics.fill(x + 18, y + 145, x + imageWidth - 18, y + 146, 0xFFBEA780);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        centered(graphics, title, 15, INK);
        centered(graphics, Component.translatable("screen.jobless_reincarnation.training.subtitle"), 29, WATER);
        objective(graphics, 49, "learn", menu.learned(), flag(menu.learned()));
        objective(graphics, 73, "cast", menu.casts() >= TrainingTrials.CAST_GOAL,
                Component.literal(menu.casts() + " / " + TrainingTrials.CAST_GOAL));
        objective(graphics, 97, "target", menu.hits() >= TrainingTrials.HIT_GOAL,
                Component.literal(menu.hits() + " / " + TrainingTrials.HIT_GOAL));
        objective(graphics, 121, "charge", menu.chargedHit(), flag(menu.chargedHit()));
        graphics.drawWordWrap(font, Component.translatable("screen.jobless_reincarnation.training.hint"),
                20, 152, imageWidth - 40, MUTED);
        boolean inventoryFull = !menu.claimed() && menu.completed() && minecraft != null
                && minecraft.player != null && minecraft.player.getInventory().getFreeSlot() < 0;
        centered(graphics, Component.translatable(menu.claimed()
                ? "screen.jobless_reincarnation.training.finished"
                : inventoryFull ? "screen.jobless_reincarnation.training.inventory_full"
                : menu.completed() ? "screen.jobless_reincarnation.training.ready"
                : "screen.jobless_reincarnation.training.reward"), 178, inventoryFull ? 0xA45232 : menu.completed() ? COMPLETE : MUTED);
    }

    private void objective(GuiGraphics graphics, int y, String key, boolean done, Component progress) {
        graphics.fill(18, y - 2, imageWidth - 18, y + 17, done ? 0x18527854 : 0x125A422B);
        graphics.fill(25, y + 2, 33, y + 10, 0xFF000000 | (done ? COMPLETE : 0xB6A07A));
        graphics.fill(27, y + 4, 31, y + 8, done ? 0xFFEAF4D5 : 0xFFF3E7CA);
        graphics.drawString(font, Component.translatable("screen.jobless_reincarnation.training." + key), 40, y + 2, INK, false);
        graphics.drawString(font, progress, imageWidth - 26 - font.width(progress), y + 2, done ? COMPLETE : WATER, false);
    }

    private Component flag(boolean done) {
        return Component.translatable(done ? "screen.jobless_reincarnation.training.done" : "screen.jobless_reincarnation.training.pending");
    }

    private void centered(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(font, text, (imageWidth - font.width(text)) / 2, y, color, false);
    }
}
