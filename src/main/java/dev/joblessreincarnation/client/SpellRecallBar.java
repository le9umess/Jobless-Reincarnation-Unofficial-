package dev.joblessreincarnation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.ManaDepletion;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * While the spell key is held, the hotbar shows the nine places of the owner's spells instead of the items, at the same
 * positions and with the keys actually bound to them. Display only: the inventory and the held slot do not change.
 */
public final class SpellRecallBar {
    private static final ResourceLocation PLATE = sprite("plate"), SOCKET = sprite("spell_socket");
    private static final int BAR_WIDTH = 182, BAR_HEIGHT = 22, CELL = 20, LABEL_HEIGHT = 15;
    private static final int TEXT = 0xFFF2E7CD, KEY_EMPTY = 0xFF8D8778, CURRENT = 0xFFE8C27A, BROWSED = 0xFFFFF2C4, DIM = 0x99060A12, COOLING = 0x66FFFFFF;

    private static final int CRACK = 0xFFE8ECEA;
    private static final int[][] CRACK_PIXELS = {{13, 4}, {12, 5}, {12, 6}, {11, 7}, {11, 8}, {10, 9}, {11, 10}, {10, 11}, {9, 12}, {9, 13}};

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "hud/" + name);
    }

    /** The items and the held item's name make room while the bar shows. */
    public static void hideVanilla(RenderGuiLayerEvent.Pre event) {
        if (SpellRecall.open() && (event.getName().equals(VanillaGuiLayers.HOTBAR) || event.getName().equals(VanillaGuiLayers.SELECTED_ITEM_NAME))) event.setCanceled(true);
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!SpellRecall.open() || player == null || minecraft.options.hideGui) return;
        Font font = minecraft.font;
        int barX = graphics.guiWidth() / 2 - BAR_WIDTH / 2, barY = graphics.guiHeight() - BAR_HEIGHT;
        SpellCatalog current = SpellCatalog.selected(player);
        int browsed = SpellRecall.browsed();
        var mana = player.getData(ModAttachments.MANA);
        float cooling = player.getCooldowns().getCooldownPercent(ModContent.TRAINING_STAFF.get(), delta.getGameTimeDeltaPartialTick(false));
        RenderSystem.enableBlend();
        graphics.blitSprite(PLATE, barX - 2, barY - 1, BAR_WIDTH + 4, BAR_HEIGHT + 1);
        for (int slot = 0; slot < CastingData.SLOT_COUNT; slot++) {
            int x = barX + 1 + slot * CELL, y = barY + 1;
            SpellCatalog spell = SpellRecall.spellAt(player, slot);
            if (spell == null) graphics.setColor(1.0F, 1.0F, 1.0F, 0.38F);
            graphics.blitSprite(SOCKET, x, y, CELL, CELL);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (spell != null) {
                ManaHud.spellIcon(graphics, spell, x + 4, y + 5);
                // Still choosable; it just cannot be paid for right now.
                if (!player.getAbilities().instabuild && mana.mana() < spell.manaCost(0.0F)) graphics.fill(x + 4, y + 4, x + 16, y + 16, DIM);
                // A crack: this one can still be forced, at a price.
                if (ManaDepletion.canOvercast(player, spell)) for (int[] at : CRACK_PIXELS) graphics.fill(x + at[0], y + at[1], x + at[0] + 1, y + at[1] + 1, CRACK);
                if (cooling > 0.0F) graphics.fill(x + 4, y + 4 + (int) (12 * (1.0F - cooling)), x + 16, y + 16, COOLING);
            }
            String key = minecraft.options.keyHotbarSlots[slot].getTranslatedKeyMessage().getString();
            if (font.width(key) > CELL - 2) key = font.plainSubstrByWidth(key, CELL - 2);
            graphics.drawString(font, key, x + CELL - 1 - font.width(key), y + CELL - 10, spell != null ? TEXT : KEY_EMPTY, true);
            if (spell == current) graphics.fill(x + 3, y + CELL - 2, x + CELL - 3, y + CELL, CURRENT);
            if (slot == browsed) graphics.renderOutline(x - 1, y - 1, CELL + 2, CELL + 2, BROWSED);
        }
        SpellCatalog named = browsed >= 0 && SpellRecall.spellAt(player, browsed) != null ? SpellRecall.spellAt(player, browsed) : current;
        Component label = Component.translatable("hud.jobless_reincarnation.recall_label", named.title(), named.manaCost(0.0F));
        int width = font.width(label) + 12, labelX = barX + BAR_WIDTH + 4, labelY = barY + 3;
        // Beside the bar when there is room; otherwise above whatever the status rows reach this frame.
        if (labelX + width > graphics.guiWidth() - 2) {
            labelX = (graphics.guiWidth() - width) / 2;
            labelY = graphics.guiHeight() - Math.max(minecraft.gui.leftHeight, minecraft.gui.rightHeight) + 9 - 1 - LABEL_HEIGHT;
        }
        graphics.blitSprite(PLATE, labelX, labelY, width, LABEL_HEIGHT);
        graphics.drawString(font, label, labelX + 6, labelY + 4, TEXT, false);
        RenderSystem.disableBlend();
    }

    private SpellRecallBar() {}
}
