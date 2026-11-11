package dev.joblessreincarnation.client.naming;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import dev.joblessreincarnation.naming.NamingChoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Choosing what the NPCs call you (user 11-06: "the player is Rudeus, but some would rather hear a name of their own").
 * Shown the first time a player enters a world, and from the magic journal later. Same aged brass as the mana crystal and
 * the boss bar: a panel with a quill medallion, three cards — "Rudy" as in the novel, the player's game name, or a name of
 * their own typed in — a preview of two family lines with the chosen name, and the buttons. Closing it, or "later", keeps
 * whatever was chosen before ("Rudy" if nothing). Every pixel is drawn here except the text.
 */
public final class NamingScreen extends Screen {
    private static final int BRONZE = 0xFF2C2218, BRASS = 0xFFA3845A, DIM = 0xFF7C6341, LIT = 0xFFDCC48F;
    private static final int PLATE = 0xFF1D1712, PLATE2 = 0xFF2A2119, CARD = 0xFF221A14, CARD_HOVER = 0xFF2B2219, CARD_ON = 0xFF372A1C;
    private static final int NAME = 0xFFDCC48F, TITLE = 0xFFB9A27A, SAID = 0xFFF2EAD8, MUTED = 0xFF8C7A5C;
    private static final int GEM_ON = 0xFF2E9E66, GEM_OFF = 0xFF3A3129;
    private static final int W = 300, H_FULL = 214, H_COMPACT = 158;
    private static final String KEY = "screen.jobless_reincarnation.naming.";

    private final NamingChoice before;
    private final Consumer<NamingChoice> onChosen;
    private final Runnable onLater;
    /** Whether this server allows a name of one's own, and how long one may be. */
    private final boolean customAllowed;
    private final int maxLength;
    private NamingChoice.Mode mode;
    /** What has been typed so far; kept here because resizing the window builds the screen again. */
    private String typed;
    private EditBox field;
    private BrassButton confirm;
    private int x0, y0, h;
    private boolean compact;

    /**
     * @param before   what was chosen before (or the default), shown selected
     * @param onChosen called with the new choice when the player confirms
     * @param onLater  called when the player closes the screen or chooses "later"
     */
    public NamingScreen(NamingChoice before, boolean customAllowed, int maxLength, Consumer<NamingChoice> onChosen, Runnable onLater) {
        super(Component.translatable(KEY + "title"));
        this.before = before;
        this.customAllowed = customAllowed;
        this.maxLength = Mth.clamp(maxLength, 1, NamingChoice.MAX_LENGTH);
        this.onChosen = onChosen;
        this.onLater = onLater;
        this.mode = before.mode() == NamingChoice.Mode.CUSTOM && !customAllowed ? NamingChoice.Mode.RUDY : before.mode();
        this.typed = customAllowed ? NamingChoice.clean(before.custom(), this.maxLength) : "";
    }

    @Override
    protected void init() {
        compact = height < H_FULL + 16;
        h = compact ? H_COMPACT : H_FULL;
        x0 = (width - W) / 2;
        y0 = compact ? Math.max(12, (height - h) / 2 + 6) : (height - h) / 2 + 6;
        int cardsTop = y0 + (compact ? 34 : 48), gap = compact ? 26 : 30;
        field = new EditBox(font, x0 + 46, cardsTop + 2 * gap + 15, W - 40 - 34, 9, Component.translatable(KEY + "custom"));
        field.setBordered(false);
        field.setMaxLength(maxLength);
        field.setEditable(customAllowed);
        field.visible = customAllowed;
        field.setFilter(text -> text.indexOf('§') < 0);
        field.setTextColor(SAID);
        field.setValue(typed);
        field.setResponder(text -> {
            typed = text;
            if (!text.isEmpty()) mode = NamingChoice.Mode.CUSTOM;
            refresh();
        });
        // The field lies on the custom card's line: it takes clicks before the cards do, yet is drawn after them.
        addWidget(field);
        for (int i = 0; i < 3; i++) addRenderableWidget(new OptionCard(x0 + 20, cardsTop + i * gap, W - 40, 26, NamingChoice.Mode.values()[i]));
        addRenderableOnly(field);
        int buttons = y0 + h - (compact ? 24 : 38);
        confirm = addRenderableWidget(new BrassButton(width / 2 - 104, buttons, 100, 18, Component.translatable(KEY + "confirm"), true, this::confirm));
        addRenderableWidget(new BrassButton(width / 2 + 4, buttons, 100, 18, Component.translatable(KEY + "later"), false, this::onClose));
        refresh();
    }

    private NamingChoice current() {
        return new NamingChoice(mode, field == null ? "" : NamingChoice.clean(field.getValue(), maxLength));
    }

    private void refresh() {
        if (confirm != null) confirm.active = current().usable();
    }

    private void confirm() {
        NamingChoice chosen = current();
        if (!chosen.usable()) return;
        onChosen.accept(chosen);
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        onLater.run();
        super.onClose();
    }

    /**
     * Clicking the custom card or its line means typing there. The screen focuses whatever was clicked after that element
     * has handled the click, so the field is focused here, afterwards (user 11-07 playtest via GPT: typed text went nowhere).
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!super.mouseClicked(mouseX, mouseY, button)) return false;
        if (customAllowed && (getFocused() == field || getFocused() instanceof OptionCard card && card.option == NamingChoice.Mode.CUSTOM)) {
            mode = NamingChoice.Mode.CUSTOM;
            setFocused(field);
            refresh();
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && confirm.active) {
            confirm();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** The world behind, blurred as usual, then the panel; the cards, the field and the buttons draw on top of it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int cx = width / 2;
        // Panel: bronze outline, a lit rim above and to the left, a shaded one below and to the right.
        g.fill(x0, y0, x0 + W, y0 + h, BRONZE);
        g.fill(x0 + 1, y0 + 1, x0 + W - 1, y0 + h - 1, DIM);
        g.fill(x0 + 1, y0 + 1, x0 + W - 1, y0 + 2, LIT);
        g.fill(x0 + 1, y0 + 1, x0 + 2, y0 + h - 1, BRASS);
        g.fill(x0 + 2, y0 + 2, x0 + W - 2, y0 + h - 2, BRONZE);
        g.fill(x0 + 3, y0 + 3, x0 + W - 3, y0 + h - 3, PLATE);
        for (int[] at : new int[][] {{x0 + 7, y0 + 7}, {x0 + W - 8, y0 + 7}, {x0 + 7, y0 + h - 8}, {x0 + W - 8, y0 + h - 8}}) rivet(g, at[0], at[1]);
        // Quill medallion astride the top edge.
        for (int[] run : MEDALLION) g.fill(cx + run[1], y0 + run[0], cx + run[2] + 1, y0 + run[0] + 1, run[3]);
        quill(g, cx - 5, y0 - 5);
        g.drawCenteredString(font, title, cx, y0 + 16, NAME);
        int divider = y0 + (compact ? 28 : 40);
        if (!compact) g.drawCenteredString(font, Component.translatable(KEY + "subtitle"), cx, y0 + 28, TITLE);
        g.fill(x0 + 24, divider, x0 + W - 24, divider + 1, 0x8CA3845A);
        g.fill(cx - 1, divider, cx + 1, divider + 1, LIT);
        // Preview: how the family will sound with this name.
        int band = y0 + (compact ? 116 : 142), lines = compact ? 1 : 2;
        g.fill(x0 + 16, band, x0 + W - 16, band + 4 + 11 * lines, 0xFF140F0C);
        g.fill(x0 + 18, band, x0 + W - 18, band + 1, 0x59DCC48F);
        String shortName = shortName(), fullName = fullName();
        g.drawCenteredString(font, Component.translatable(KEY + "preview_zenith", shortName), cx, band + 3, SAID);
        if (!compact) g.drawCenteredString(font, Component.translatable(KEY + "preview_lilia", fullName), cx, band + 14, SAID);
        if (!compact) g.drawCenteredString(font, Component.translatable(KEY + "note"), cx, y0 + h - 15, MUTED);
    }

    /** "Rudy" in the novel's family lines comes as a pet name and, from Lilia, the full name; any other choice is both. */
    private String shortName() {
        return switch (mode) {
            case RUDY -> Component.translatable(KEY + "rudy_short").getString();
            case ACCOUNT -> accountName();
            case CUSTOM -> field == null || field.getValue().isBlank() ? "＿＿" : NamingChoice.clean(field.getValue(), maxLength);
        };
    }

    private String fullName() {
        return mode == NamingChoice.Mode.RUDY ? Component.translatable(KEY + "rudy_full").getString() : shortName();
    }

    private String accountName() {
        return Minecraft.getInstance().getUser().getName();
    }

    /** One of the three choices: a stone that lights when chosen, the name in large type, a short line under it. */
    private final class OptionCard extends AbstractWidget {
        private final NamingChoice.Mode option;

        OptionCard(int x, int y, int width, int height, NamingChoice.Mode option) {
            super(x, y, width, height, Component.translatable(KEY + option.name().toLowerCase(java.util.Locale.ROOT)));
            this.option = option;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = mode == option, off = option == NamingChoice.Mode.CUSTOM && !customAllowed;
            int x = getX(), y = getY();
            g.fill(x, y, x + width, y + height, on ? LIT : BRONZE);
            g.fill(x + 1, y + 1, x + width - 1, y + height - 1, on ? CARD_ON : isHovered() ? CARD_HOVER : CARD);
            if (on) g.fill(x + 1, y + 1, x + width - 1, y + 2, 0x40FFF0C4);
            stone(g, x + 12, y + height / 2, on);
            Component main = switch (option) {
                case RUDY -> Component.translatable(KEY + "rudy_short");
                case ACCOUNT -> Component.literal(accountName());
                case CUSTOM -> Component.translatable(KEY + "custom");
            };
            g.drawString(font, main, x + 26, y + 4, off ? MUTED : on ? NAME : TITLE, true);
            if (off) {
                g.drawString(font, Component.translatable(KEY + "custom_off_hint"), x + 26, y + 15, MUTED, false);
            } else if (option == NamingChoice.Mode.CUSTOM) {
                // The field sits on an inked line; while it is empty and not being typed in, a hint shows instead.
                g.fill(x + 26, y + 24, x + width - 8, y + 25, on ? DIM : 0xFF3A3028);
                if (field.getValue().isEmpty() && !field.isFocused())
                    g.drawString(font, Component.translatable(KEY + "custom_hint"), x + 26, y + 15, MUTED, false);
            } else {
                g.drawString(font, Component.translatable(KEY + option.name().toLowerCase(java.util.Locale.ROOT) + "_hint"), x + 26, y + 15, MUTED, false);
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (option == NamingChoice.Mode.CUSTOM && !customAllowed) return;
            // The custom card's field gets the focus in NamingScreen.mouseClicked, after the screen focuses this card.
            mode = option;
            refresh();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static final class BrassButton extends AbstractButton {
        private final boolean primary;
        private final Runnable action;

        BrassButton(int x, int y, int width, int height, Component text, boolean primary, Runnable action) {
            super(x, y, width, height, text);
            this.primary = primary;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = width, hh = height;
            boolean hover = active && isHoveredOrFocused();
            g.fill(x, y, x + w, y + hh, BRONZE);
            g.fill(x + 1, y + 1, x + w - 1, y + hh - 1, !active ? 0xFF3A3028 : primary ? BRASS : DIM);
            g.fill(x + 2, y + 2, x + w - 2, y + hh - 2, !active ? 0xFF1A1511 : primary ? (hover ? 0xFF4A3A22 : 0xFF3A2E1C) : (hover ? 0xFF30271D : 0xFF241C16));
            g.fill(x + 2, y + 2, x + w - 2, y + 3, active && primary ? 0x40FFF0C4 : 0x18FFF0C4);
            Font font = Minecraft.getInstance().font;
            g.drawCenteredString(font, getMessage(), x + w / 2, y + (hh - 8) / 2, !active ? 0xFF6A5E4E : primary ? NAME : TITLE);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Development screenshots only: choose a card, or type into the field, as a player would. */
    void demoPick(int index) {
        mode = NamingChoice.Mode.values()[Mth.clamp(index, 0, 2)];
        if (mode == NamingChoice.Mode.CUSTOM && !customAllowed) mode = before.mode() == NamingChoice.Mode.CUSTOM ? NamingChoice.Mode.RUDY : before.mode();
        refresh();
    }

    void demoType(String text) {
        if (!customAllowed) return;
        mode = NamingChoice.Mode.CUSTOM;
        setFocused(field);
        field.setFocused(true);
        field.setValue(text);
        refresh();
    }

    private static void rivet(GuiGraphics g, int x, int y) {
        g.fill(x, y - 2, x + 1, y - 1, BRONZE);
        g.fill(x, y - 1, x + 1, y + 2, LIT);
        g.fill(x - 1, y, x, y + 1, BRONZE);
        g.fill(x + 1, y, x + 2, y + 1, BRONZE);
        g.fill(x, y + 2, x + 1, y + 3, BRONZE);
    }

    /** The choice stone, cut like the boss bar's phase stones: green and lit when chosen, dull otherwise. */
    private static void stone(GuiGraphics g, int cx, int cy, boolean on) {
        int body = on ? GEM_ON : GEM_OFF, light = on ? 0xFF8FE3B8 : 0xFF51463B;
        for (int y = -5; y <= 5; y++) for (int x = -3; x <= 3; x++) {
            float t = Math.abs(x) / 3F + Math.abs(y) / 5F;
            if (t > 1.0001F) continue;
            g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, t > 0.72F ? BRONZE : x + y < -1 ? light : body);
        }
    }

    /** A quill, 11×11: a pale vane three pixels wide, shaded toward the shaft, a brass shaft, an inked nib at the lower left. */
    private static final String[] QUILL = {
        ".......vvV.",
        "......vvVs.",
        ".....vvVs..",
        "....vvVs...",
        "...vvVs....",
        "...vVs.....",
        "..vVs......",
        "..Vs.......",
        "..s........",
        ".n.........",
        "n..........",
    };

    private static void quill(GuiGraphics g, int x, int y) {
        for (int j = 0; j < QUILL.length; j++) for (int i = 0; i < QUILL[j].length(); i++) {
            int c = switch (QUILL[j].charAt(i)) {
                case 'v' -> 0xFFF4EBD6;
                case 'V' -> 0xFFC9BC9E;
                case 's' -> BRASS;
                case 'n' -> 0xFF2B3550;
                default -> 0;
            };
            if (c != 0) g.fill(x + i, y + j, x + i + 1, y + j + 1, c);
        }
    }

    /** The round brass medallion of the boss bar, as row runs around its centre: {dy, dx from, dx to, colour}. */
    private static final int[][] MEDALLION = medallion();

    private static int[][] medallion() {
        List<int[]> runs = new ArrayList<>();
        for (int dy = -12; dy <= 12; dy++) {
            int start = Integer.MIN_VALUE, colour = 0;
            for (int dx = -13; dx <= 13; dx++) {
                float d = Mth.sqrt(dx * dx + dy * dy);
                int c = d >= 12.6F ? 0 : d > 11.4F ? BRONZE : d > 9.6F ? (dy < -2 ? LIT : dx < 0 ? BRASS : DIM) : d > 8.8F ? BRONZE : PLATE2;
                if (c != colour) {
                    if (colour != 0) runs.add(new int[] {dy, start, dx - 1, colour});
                    start = dx;
                    colour = c;
                }
            }
            if (colour != 0) runs.add(new int[] {dy, start, 13, colour});
        }
        return runs.toArray(int[][]::new);
    }
}
