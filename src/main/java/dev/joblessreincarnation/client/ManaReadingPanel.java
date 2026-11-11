package dev.joblessreincarnation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaReaderItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The measuring crystal's answer as a slim plate just above the health and food rows, gone after a few seconds. The server
 * still sends one private action-bar message; this draws it instead of the plain text, and draws nothing of its own accord.
 */
public final class ManaReadingPanel {
    private static final String PREFIX = "message.jobless_reincarnation.mana_reader.";
    private static final ResourceLocation PLATE = sprite("plate"), GEM = sprite("mana_gem");
    private static final int HOLD_TICKS = 70, FADE_TICKS = 15, HEIGHT = 15, BAR_WIDTH = 60, BAR_COLOR = 0xFF34AEDC, TEXT = 0xFFF2E7CD;

    private static Component reading;
    private static float fill;
    private static int shownAt;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "hud/" + name);
    }

    public static void onSystemMessage(ClientChatReceivedEvent.System event) {
        if (!event.isOverlay() || !(event.getMessage().getContents() instanceof TranslatableContents contents) || !contents.getKey().startsWith(PREFIX)) return;
        Object[] args = contents.getArgs();
        fill = switch (contents.getKey().substring(PREFIX.length())) {
            case "self" -> ratio(args, 0);
            case "player" -> ratio(args, 1);
            default -> -1.0F;
        };
        reading = event.getMessage();
        shownAt = Minecraft.getInstance().gui.getGuiTicks();
        event.setCanceled(true);
    }

    private static float ratio(Object[] args, int at) {
        if (args.length < at + 2) return -1.0F;
        try {
            return Integer.parseInt(text(args[at])) / (float) Math.max(1, Integer.parseInt(text(args[at + 1])));
        } catch (NumberFormatException notANumber) {
            return -1.0F;
        }
    }

    private static String text(Object arg) {
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }

    /** The plate sits where the held item's name would: while it shows, it stands in for that name. */
    public static void hideItemName(RenderGuiLayerEvent.Pre event) {
        if (reading != null && event.getName().equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) event.setCanceled(true);
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (reading == null) return;
        // Putting the crystal away puts the reading away, so the next item's name shows at once.
        if (minecraft.player == null || minecraft.player.isDeadOrDying() || !ManaReaderItem.inMainHand(minecraft.player)) {
            reading = null;
            return;
        }
        if (minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) return;
        float age = minecraft.gui.getGuiTicks() - shownAt + delta.getGameTimeDeltaPartialTick(false);
        if (age < 0 || age >= HOLD_TICKS + FADE_TICKS) {
            reading = null;
            return;
        }
        var font = minecraft.font;
        String line = reading.getString();
        boolean hasBar = fill >= 0;
        int width = 4 + 9 + 3 + font.width(line) + (hasBar ? 5 + BAR_WIDTH : 0) + 6;
        int x = (graphics.guiWidth() - width) / 2;
        // Directly above whatever the status rows reach this frame: health, food, armour, air, a mount.
        int y = graphics.guiHeight() - Math.max(minecraft.gui.leftHeight, minecraft.gui.rightHeight) + 9 - 1 - HEIGHT;
        graphics.setColor(1.0F, 1.0F, 1.0F, age <= HOLD_TICKS ? 1.0F : 1.0F - (age - HOLD_TICKS) / FADE_TICKS);
        RenderSystem.enableBlend();
        graphics.blitSprite(PLATE, x, y, width, HEIGHT);
        graphics.blitSprite(GEM, x + 4, y + 3, 9, 9);
        graphics.drawString(font, line, x + 16, y + 4, TEXT, false);
        if (hasBar) ManaHud.bar(graphics, x + 16 + font.width(line) + 5, y + 4, BAR_WIDTH, fill, BAR_COLOR);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private ManaReadingPanel() {}
}
