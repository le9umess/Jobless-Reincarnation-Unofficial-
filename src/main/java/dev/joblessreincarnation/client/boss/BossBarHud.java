package dev.joblessreincarnation.client.boss;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The boss bar the user picked on 11-06 ("甲 黄铜法阵", then "as long as this red box"): an aged-brass set that matches the
 * mana crystal in the corner. From the top: a plate pointed at both ends with the boss's name and a short title; a round
 * medallion holding the boss's emblem at the left end of a long brass-framed bar; behind the bar's pointed tip one gem per
 * phase with its numeral cut in (spent gems go dark and cracked); then, for bosses with a no-casting phase, a small seal
 * that is struck through while casting is barred and glows blue once it is lifted; under it all, what the boss is saying.
 * A hit leaves a short pale trail; an emptied bar refills with one sweep of light. All pixels are drawn here, as in
 * {@code ManaRing}, except the emblem sprite. It sits under any vanilla boss bars, goes down below the open spell panel
 * where that would cover it, and never runs down over the crosshair.
 */
public final class BossBarHud {
    private static final int BRONZE = 0xFF2C2218, BRASS = 0xFFA3845A, DIM = 0xFF7C6341, LIT = 0xFFDCC48F, SHINE = 0xFFFFF0C4;
    private static final int PLATE = 0xFF1D1712, PLATE2 = 0xFF2A2119, TRAIL = 0xFFF0E2B0, EMPTY_TOP = 0xFF0D0A08, EMPTY = 0xFF18120E;
    private static final int SPENT = 0xFF2B2422, CRACK = 0xFF5C5048, SEAL_FACE = 0xFF141A22;
    private static final int NAME = 0xDCC48F, TITLE = 0xB9A27A, SAID = 0xF2EAD8;
    /** The fill at full length (the user's red box), and the least it shrinks to on a narrow screen. */
    private static final int FILL = 264, MIN_FILL = 120;
    private static final int PLATE_H = 23, PLATE_H_UNTITLED = 13, BAR_H = 11, LINE_H = 11, GAP = 4, LINE_WIDTH = 300;
    /** Shading down the five rows of the fill: a bright top edge, a darker underside. */
    private static final float[] ROW = {1.35F, 1.12F, 0.9F, 0.9F, 0.62F};
    /** Row runs of the medallion around its centre pixel: {dy, dx from, dx to, colour}. */
    private static final int[][] MEDALLION = medallion();
    /** The medallion's inner bronze ring, which glows while the fight is paused between bars. */
    private static final int[][] MEDALLION_RIM = rim();
    /** The seal's pixels from its top-left: {x, y, part}, part 0 frame, 1 face, 2 circle. */
    private static final int[][] SEAL = seal();

    private static int vanillaBottom;
    /** HUD drawn after the bars that they must not run under (the open spell panel): {left, top, right, bottom}, right and
     *  bottom exclusive; null when none. */
    private static int[] keepClear, keepClearNext;

    public static void beforeGui(RenderGuiEvent.Pre event) {
        vanillaBottom = 0;
        keepClear = keepClearNext;
        keepClearNext = null;
    }

    /**
     * For HUD layers drawn after the boss bars, such as the open spell panel in the top-left corner: the box they just drew.
     * The bars keep clear of it from the next frame on, and forget it once a frame passes without it.
     */
    public static void keepClear(int left, int top, int right, int bottom) {
        int[] box = keepClearNext;
        keepClearNext = box == null ? new int[] {left, top, right, bottom}
                : new int[] {Math.min(box[0], left), Math.min(box[1], top), Math.max(box[2], right), Math.max(box[3], bottom)};
    }

    /** Vanilla boss bars draw first; ours go under the lowest of them. */
    public static void afterVanillaBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        if (!event.isCanceled()) vanillaBottom = Math.max(vanillaBottom, event.getY() + 6);
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) return;
        List<ClientBossBars.Shown> bars = ClientBossBars.visible(minecraft.player, 2);
        if (bars.isEmpty()) return;
        long now = Util.getMillis();
        // Everything stays above the crosshair. Where room is short a bar gets more compact, step by step: one line of
        // speech instead of two, then no title, then no speech under it (the participants have it in chat anyway), and
        // last a single slim row of name, bar and stones without the medallion. Where the open spell panel is in the way,
        // the bar first stays at the top and moves to the panel's right, shorter if it must be, through the same steps;
        // only when none of that fits does it go down below the panel (it used to go there at once, and with the tall
        // staff panel that was the middle of the screen). A bar that does not fit anywhere is not drawn: it never covers
        // the crosshair or the panel.
        int top = vanillaBottom > 0 ? vanillaBottom + 3 : 4, limit = graphics.guiHeight() / 2 - 12;
        int[] clear = keepClear;
        int besideTop = top;
        int width = graphics.guiWidth();
        for (int k = 0; k < bars.size(); k++) {
            ClientBossBars.Shown bar = bars.get(k);
            Layout at = null;
            for (int compact = 0; compact <= MINIMAL && at == null; compact++) {
                Layout tried = layout(bar, minecraft.font, width, top, now, compact);
                if (clear != null && overlaps(tried, bar.view, clear))
                    tried = layout(bar, minecraft.font, width, top, now, compact, clear[2] + 6, width - 4);
                if ((clear == null || !overlaps(tried, bar.view, clear)) && tried.top + tried.height <= limit) at = tried;
            }
            for (int compact = 0; compact <= MINIMAL && at == null && clear != null; compact++) {
                Layout tried = layout(bar, minecraft.font, width, Math.max(top, clear[3] + 3), now, compact);
                if (tried.top + tried.height <= limit) at = tried;
            }
            if (at == null && clear != null) {
                // Very small screens: nothing fits under the open panel above the crosshair. Rather than lose the bar
                // while the staff is out (the second bar is when it is needed most), the slim row goes beside the panel.
                at = besidePanel(bars.get(k), minecraft.font, graphics.guiWidth(), besideTop, clear);
                if (at != null && at.top + at.height > limit) at = null;
            }
            if (at == null) break;
            draw(graphics, minecraft.font, bars.get(k), at, now);
            int below = at.top + at.height + GAP;
            top = Math.max(top, below);
            if (clear == null || at.top < clear[3]) besideTop = Math.max(besideTop, below);
        }
    }

    /** 0 full; 1 one line of speech; 2 also no title; 3 also no speech; 4 one slim row. */
    private static final int MINIMAL = 4, MINIMAL_HEIGHT = 9, MINIMAL_FILL = 160, MINIMAL_MIN_FILL = 40;

    private record Layout(int top, int cx, int fill, int mx, int my, int bx, int by, int bw, int tx, int sx, int px, int pw,
                          int plateHeight, boolean titled, List<FormattedCharSequence> lines, int lineWidth, int height,
                          boolean minimal, FormattedCharSequence name) {}

    private static Layout layout(ClientBossBars.Shown bar, Font font, int screenWidth, int top, long now, int compact) {
        return layout(bar, font, screenWidth, top, now, compact, 0, screenWidth);
    }

    /**
     * The bar laid out in the columns from {@code from} to {@code to} (exclusive) and centred there: the whole screen
     * normally, the room to the right of the open spell panel when the panel is in the way. It is never longer than it
     * would be on the whole screen and gets shorter to fit the room, down to its minimum length.
     */
    private static Layout layout(ClientBossBars.Shown bar, Font font, int screenWidth, int top, long now, int compact, int from, int to) {
        BossBarView view = bar.view;
        int phases = Math.max(1, view.phases()), room = to - from, longest = Math.min((int) (screenWidth * 0.7F), room - 8);
        if (compact == MINIMAL) {
            // One row: the name, a thin framed bar, small stones, and the small seal when there is one.
            int nameWidth = font.width(view.name()), marks = 7 * phases + (view.sealable() ? 9 : 0);
            int fill = Mth.clamp(longest - nameWidth - 12 - marks, MINIMAL_MIN_FILL, MINIMAL_FILL);
            int total = nameWidth + 6 + fill + 2 + 4 + marks, x0 = from + (room - total) / 2, bx = x0 + nameWidth + 6;
            return new Layout(top, (from + to) / 2, fill, x0, top + 4, bx, top + 1, fill + 2, bx + fill + 2 + 4, 0, 0, 0,
                    0, false, List.of(), 0, MINIMAL_HEIGHT, true, Language.getInstance().getVisualOrder(view.name()));
        }
        boolean titled = compact < 2;
        int plateHeight = titled ? PLATE_H : PLATE_H_UNTITLED;
        // Past the frame: the tip, the gems, and the seal when there is one.
        int right = (view.sealable() ? 28 : 14) + 11 * (phases - 1);
        int fill = Mth.clamp(Math.min(FILL, longest - 29 - right), MIN_FILL, FILL);
        int group = fill + 29 + right, cx = (from + to) / 2;
        int mx = from + (room - group) / 2 + 12, my = top + plateHeight + 12;
        int bx = mx + 10, by = my - 5, bw = fill + 6, tx = bx + bw, sx = tx + 18 + 11 * (phases - 1);
        int pw = Math.max(font.width(view.name()), titled ? font.width(view.title()) : 0) + 14, px = cx - pw / 2;
        List<FormattedCharSequence> lines = List.of();
        int lineWidth = 0;
        if (compact < 3 && bar.lineAlpha(now) > 0.02F && bar.line != null) {
            int wrap = Math.min(LINE_WIDTH, room - 40), most = compact == 0 ? 2 : 1;
            List<FormattedText> parts = font.getSplitter().splitLines(bar.line, wrap, Style.EMPTY);
            if (parts.size() > most) {
                // Cut short where room is tight: the last kept line ends in "…"; the whole line is in chat.
                List<FormattedText> kept = new ArrayList<>(parts.subList(0, most));
                FormattedText cut = font.substrByWidth(kept.get(most - 1), wrap - font.width("…"));
                kept.set(most - 1, FormattedText.composite(cut, FormattedText.of("…")));
                parts = kept;
            }
            lines = parts.stream().map(Language.getInstance()::getVisualOrder).toList();
            for (FormattedCharSequence line : lines) lineWidth = Math.max(lineWidth, font.width(line));
        }
        int height = lines.isEmpty() ? by + BAR_H - top : by + BAR_H + 5 + LINE_H * lines.size() - top;
        return new Layout(top, cx, fill, mx, my, bx, by, bw, tx, sx, px, pw, plateHeight, titled, lines, lineWidth, height, false,
                Language.getInstance().getVisualOrder(view.name()));
    }

    /**
     * The slim row at the top beside the box {left, top, right, bottom} (the open spell panel), for when nothing else fits:
     * the name, cut short with "…" when the room needs it, then the bar, the phase stones and the seal. Null when even
     * that does not fit.
     */
    private static Layout besidePanel(ClientBossBars.Shown bar, Font font, int screenWidth, int top, int[] box) {
        BossBarView view = bar.view;
        int phases = Math.max(1, view.phases()), marks = 7 * phases + (view.sealable() ? 9 : 0);
        int left = box[2] + 4, room = screenWidth - 4 - left, fixed = 6 + 2 + 4 + marks;
        FormattedText name = view.name();
        int nameWidth = font.width(name);
        int fill = Mth.clamp(room - nameWidth - fixed, MINIMAL_MIN_FILL, MINIMAL_FILL);
        if (nameWidth + fixed + fill > room) {
            int keep = room - fixed - fill - font.width("…");
            if (keep < 8) return null;
            name = FormattedText.composite(font.substrByWidth(name, keep), FormattedText.of("…"));
            nameWidth = font.width(name);
        }
        int bx = left + nameWidth + 6;
        return new Layout(top, left + (nameWidth + fixed + fill) / 2, fill, left, top + 4, bx, top + 1, fill + 2, bx + fill + 2 + 4,
                0, 0, 0, 0, false, List.of(), 0, MINIMAL_HEIGHT, true, Language.getInstance().getVisualOrder(name));
    }

    /**
     * Whether a laid-out bar, its speech band included, comes within 2 pixels of the box {left, top, right, bottom}
     * (right and bottom exclusive, as in fill).
     */
    private static boolean overlaps(Layout at, BossBarView view, int[] box) {
        int phases = Math.max(1, view.phases()), left, right;
        if (at.minimal) {
            left = at.mx;
            right = at.tx + 7 * phases + (view.sealable() ? 9 : 0) + 1;
        } else {
            // The plate's points, the medallion's rim, and past the tip the gems or the seal with its glow.
            left = Math.min(at.px - 6, at.mx - 12);
            right = Math.max(at.px + at.pw + 6, view.sealable() ? at.sx + 13 : at.tx + 15 + 11 * (phases - 1));
            if (!at.lines.isEmpty()) {
                int w = at.lineWidth + 12, x0 = at.cx - w / 2;
                left = Math.min(left, x0);
                right = Math.max(right, x0 + w);
            }
        }
        return box[0] - 2 < right && box[2] + 2 > left && box[1] - 2 < at.top + at.height && box[3] + 2 > at.top;
    }

    private static void draw(GuiGraphics g, Font font, ClientBossBars.Shown bar, Layout at, long now) {
        BossBarView view = bar.view;
        int phases = Math.max(1, view.phases()), phase = Mth.clamp(view.phase(), 0, phases - 1);
        if (at.minimal) { drawMinimal(g, font, bar, at, phases, phase, now); return; }
        plate(g, font, view, at);
        // Medallion and emblem; its inner ring breathes while the fight is paused between bars.
        for (int[] run : MEDALLION) g.fill(at.mx + run[1], at.my + run[0], at.mx + run[2] + 1, at.my + run[0] + 1, run[3]);
        long waiting = bar.waiting(now);
        if (waiting > 0) {
            // The inner ring breathes while the fight waits; in its last second it holds bright: about to start (again).
            float strength = waiting <= 1000 ? 0.75F : 0.25F + 0.25F * (float) Math.sin(now / 110.0);
            int glow = alpha(SHINE, strength);
            for (int[] p : MEDALLION_RIM) put(g, at.mx + p[0], at.my + p[1], glow);
        }
        g.blitSprite(view.emblem(), at.mx - 5, at.my - 5, 11, 11);
        frame(g, at);
        fill(g, bar, at.bx + 3, at.by + 3, at.fill, view.colour(phase), now);
        for (int j = 0; j < BAR_H; j++) {
            int run = Math.max(0, 5 - Math.abs(j - 5));
            for (int i = 0; i < run; i++) put(g, at.tx + i, at.by + j, i == run - 1 ? BRONZE : j < 5 ? BRASS : DIM);
        }
        for (int k = 0; k < phases; k++) gem(g, at.tx + 10 + 11 * k, at.my, k, k >= phase ? view.colour(k) : 0, k == phase, now);
        if (view.sealable()) {
            float sweep = bar.sweep(now);
            seal(g, at.sx, at.my - 5, view.sealed(), bar.lifting && sweep >= 0 ? 1 - sweep : 0);
        }
        said(g, font, bar, at, now);
    }

    /** The name plate: pointed at both ends, a rivet at each point, a faint line between the name and the title. */
    private static void plate(GuiGraphics g, Font font, BossBarView view, Layout at) {
        int px = at.px, pw = at.pw, top = at.top, h = at.plateHeight, mid = (h - 1) / 2;
        for (int j = 0; j < h; j++) {
            int inset = Math.max(0, Math.abs(j - mid) - 4);
            int x0 = px + inset - 6, x1 = px + pw + 5 - inset, y = top + j;
            if (j == 0 || j == h - 1) { line(g, x0, x1, y, BRONZE); continue; }
            int rim = j < mid ? LIT : DIM;
            put(g, x0, y, BRONZE);
            put(g, x1, y, BRONZE);
            if (j == 1 || j == h - 2) { line(g, x0 + 1, x1 - 1, y, rim); continue; }
            put(g, x0 + 1, y, rim);
            put(g, x1 - 1, y, rim);
            line(g, x0 + 2, x1 - 2, y, PLATE);
        }
        rivet(g, px - 3, top + mid);
        rivet(g, px + pw + 2, top + mid);
        g.drawString(font, view.name(), at.cx - font.width(view.name()) / 2, top + 2, 0xFF000000 | NAME, true);
        if (!at.titled) return;
        line(g, px + 6, px + pw - 7, top + 11, alpha(BRASS, 0.55F));
        put(g, at.cx, top + 11, LIT);
        put(g, at.cx - 1, top + 11, BRASS);
        g.drawString(font, view.title(), at.cx - font.width(view.title()) / 2, top + 12, 0xFF000000 | TITLE, true);
    }

    private static void frame(GuiGraphics g, Layout at) {
        int bx = at.bx, by = at.by, bw = at.bw;
        g.fill(bx, by, bx + bw, by + BAR_H, BRONZE);
        line(g, bx + 1, bx + bw - 2, by + 1, LIT);
        line(g, bx + 1, bx + bw - 2, by + BAR_H - 2, DIM);
        g.fill(bx + bw - 2, by + 1, bx + bw - 1, by + BAR_H - 1, DIM);
        g.fill(bx + 2, by + 2, bx + bw - 2, by + BAR_H - 2, BRONZE);
        for (int k = 1; k < 7; k++) {
            int x = bx + Math.round(bw * k / 7F);
            put(g, x, by + 1, BRASS);
            put(g, x, by + BAR_H - 2, BRASS);
        }
    }

    /** The five-row fill at (fx, fy): the bar's colour, the pale trail, the empty part, quarter marks, the refill's light. */
    private static void fill(GuiGraphics g, ClientBossBars.Shown bar, int fx, int fy, int width, int colour, long now) {
        int full = Math.round(width * bar.progress(now)), trail = Math.max(full, Math.round(width * bar.trail(now)));
        for (int j = 0; j < 5; j++) {
            int y = fy + j;
            if (full > 0) g.fill(fx, y, fx + full, y + 1, shade(colour, ROW[j]));
            if (trail > full) g.fill(fx + full, y, fx + trail, y + 1, j == 4 ? shade(TRAIL, 0.8F) : TRAIL);
            if (width > trail) g.fill(fx + trail, y, fx + width, y + 1, j == 0 ? EMPTY_TOP : EMPTY);
        }
        // Quarter marks.
        for (int q = 1; q < 4; q++) {
            int x = fx + Math.round(width * q / 4F);
            g.fill(x, fy + 1, x + 1, fy + 4, 0x46000000);
        }
        // One sweep of light while a new bar fills.
        float sweep = bar.sweep(now);
        if (sweep >= 0) {
            int head = Math.round(sweep * (width + 18)) - 18, x0 = Math.max(0, head), x1 = Math.min(full, head + 18);
            if (x1 > x0) g.fill(fx + x0, fy, fx + x1, fy + 5, alpha(SHINE, 0.55F));
        }
    }

    /** The last resort where room is very short: one slim row of name, bar and small stones, never over the crosshair. */
    private static void drawMinimal(GuiGraphics g, Font font, ClientBossBars.Shown bar, Layout at, int phases, int phase, long now) {
        BossBarView view = bar.view;
        g.drawString(font, at.name, at.mx, at.top + 1, 0xFF000000 | NAME, true);
        g.fill(at.bx, at.by, at.bx + at.bw, at.by + 7, BRONZE);
        line(g, at.bx + 1, at.bx + at.bw - 2, at.by, LIT);
        fill(g, bar, at.bx + 1, at.by + 1, at.fill, view.colour(phase), now);
        for (int k = 0; k < phases; k++) {
            int cx = at.tx + 2 + 7 * k, cy = at.by + 3, colour = k >= phase ? view.colour(k) : 0;
            boolean alive = colour != 0;
            int body = alive ? colour : SPENT, light = alive ? mix(colour, 0xFFFFFFFF, 0.45F) : SPENT;
            for (int y = -3; y <= 3; y++) for (int x = -2; x <= 2; x++) {
                float t = Math.abs(x) / 2F + Math.abs(y) / 3F;
                if (t > 1.0001F) continue;
                put(g, cx + x, cy + y, t > 0.67F ? BRONZE : x + y < -1 ? light : body);
            }
            if (!alive) put(g, cx, cy, CRACK);
        }
        if (view.sealable()) {
            int sx = at.tx + 7 * phases + 1, sy = at.by;
            int circle = view.sealed() ? 0xFF5D7896 : 0xFF8FD8FF;
            for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) {
                float d = Mth.sqrt((x + .5F - 3.5F) * (x + .5F - 3.5F) + (y + .5F - 3.5F) * (y + .5F - 3.5F));
                if (d >= 3.6F) continue;
                put(g, sx + x, sy + y, d > 2.9F ? BRONZE : d >= 1.6F && d < 2.5F ? circle : SEAL_FACE);
            }
            if (view.sealed()) for (int k = 1; k < 6; k++) put(g, sx + k, sy + k, 0xFFE0483E);
        }
    }

    /** One phase's stone with its numeral cut in; a spent stone is dark and cracked. */
    private static void gem(GuiGraphics g, int cx, int cy, int index, int colour, boolean current, long now) {
        boolean alive = colour != 0;
        int body = alive ? colour : SPENT;
        if (alive && current) body = mix(body, 0xFFFFFFFF, 0.08F + 0.08F * (float) Math.sin(now / 160.0));
        int light = alive ? mix(body, 0xFFFFFFFF, 0.45F) : SPENT;
        for (int y = -6; y <= 6; y++) for (int x = -4; x <= 4; x++) {
            float t = Math.abs(x) / 4F + Math.abs(y) / 6F;
            if (t > 1.0001F) continue;
            put(g, cx + x, cy + y, t > 0.72F ? BRONZE : x + y < -2 ? light : body);
        }
        // The numeral is cut in pale, so it reads on any colour; on a spent stone it is a dull scratch.
        int mark = alive ? mix(body, 0xFFFFFFFF, 0.62F) : CRACK, strokes = Math.min(index + 1, 3);
        for (int s = 0; s < strokes; s++) {
            int dx = strokes == 1 ? 0 : strokes == 2 ? (s == 0 ? -1 : 1) : (s - 1) * 2;
            for (int dy = -2; dy <= 2; dy++) if (Math.abs(dx) / 4F + Math.abs(dy) / 6F <= 0.72F) put(g, cx + dx, cy + dy, mark);
        }
        if (!alive) {
            put(g, cx - 2, cy - 4, CRACK);
            put(g, cx + 2, cy + 3, CRACK);
            put(g, cx + 3, cy + 2, CRACK);
        }
    }

    /** No casting: a small magic circle struck through in red. Lifted: the circle glows blue, ringed once with light. */
    private static void seal(GuiGraphics g, int x, int y, boolean sealed, float liftGlow) {
        int circle = sealed ? 0xFF5D7896 : 0xFF8FD8FF, core = sealed ? 0xFF3F5268 : 0xFFE6F8FF;
        for (int[] p : SEAL) put(g, x + p[0], y + p[1], p[2] == 0 ? BRONZE : p[2] == 1 ? SEAL_FACE : circle);
        for (int[] p : new int[][] {{5, 1}, {5, 9}, {1, 5}, {9, 5}}) put(g, x + p[0], y + p[1], circle);
        put(g, x + 5, y + 5, core);
        if (sealed) for (int k = 2; k < 9; k++) {
            put(g, x + k, y + k, 0xFFE0483E);
            put(g, x + k + 1, y + k, 0xFF8E2621);
        }
        if (liftGlow > 0) {
            int glow = alpha(SHINE, 0.7F * liftGlow);
            for (int py = -2; py < 13; py++) for (int px = -2; px < 13; px++) {
                float d = Mth.sqrt((px + .5F - 5.5F) * (px + .5F - 5.5F) + (py + .5F - 5.5F) * (py + .5F - 5.5F));
                if (d >= 5.8F && d < 6.9F) put(g, x + px, y + py, glow);
            }
        }
    }

    /** What the boss says, on a dark band under the bar; it fades in and out. */
    private static void said(GuiGraphics g, Font font, ClientBossBars.Shown bar, Layout at, long now) {
        if (at.lines.isEmpty()) return;
        float a = bar.lineAlpha(now);
        int w = at.lineWidth + 12, x0 = at.cx - w / 2, y0 = at.by + BAR_H + 5, h = LINE_H * at.lines.size();
        g.fill(x0, y0, x0 + w, y0 + h, alpha(0xFF0C0907, 0.59F * a * a));
        line(g, x0 + 2, x0 + w - 3, y0, alpha(LIT, 0.35F * a * a));
        int textAlpha = Math.max(4, Math.round(255 * a));
        for (int i = 0; i < at.lines.size(); i++) {
            FormattedCharSequence text = at.lines.get(i);
            g.drawString(font, text, at.cx - font.width(text) / 2, y0 + 2 + i * LINE_H, textAlpha << 24 | SAID, true);
        }
    }

    private static void rivet(GuiGraphics g, int x, int y) {
        put(g, x, y - 2, BRONZE);
        put(g, x, y - 1, LIT);
        put(g, x - 1, y, BRONZE);
        put(g, x, y, LIT);
        put(g, x + 1, y, BRONZE);
        put(g, x, y + 1, LIT);
        put(g, x, y + 2, BRONZE);
    }

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
        // Eight notches round the ring, like the rune notches on the mana ring.
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int x = (int) Math.round(Math.cos(a) * 10.5), y = (int) Math.round(Math.sin(a) * 10.5);
            runs.add(new int[] {y, x, x, BRONZE});
        }
        return runs.toArray(int[][]::new);
    }

    private static int[][] rim() {
        List<int[]> pixels = new ArrayList<>();
        for (int dy = -10; dy <= 10; dy++) for (int dx = -10; dx <= 10; dx++) {
            float d = Mth.sqrt(dx * dx + dy * dy);
            if (d > 8.8F && d <= 9.6F) pixels.add(new int[] {dx, dy});
        }
        return pixels.toArray(int[][]::new);
    }

    private static int[][] seal() {
        List<int[]> pixels = new ArrayList<>();
        for (int y = 0; y < 11; y++) for (int x = 0; x < 11; x++) {
            float d = Mth.sqrt((x + .5F - 5.5F) * (x + .5F - 5.5F) + (y + .5F - 5.5F) * (y + .5F - 5.5F));
            if (d >= 5.6F) continue;
            pixels.add(new int[] {x, y, d > 4.7F ? 0 : d >= 2.6F && d < 3.7F ? 2 : 1});
        }
        return pixels.toArray(int[][]::new);
    }

    private static void put(GuiGraphics g, int x, int y, int colour) {
        g.fill(x, y, x + 1, y + 1, colour);
    }

    private static void line(GuiGraphics g, int x0, int x1, int y, int colour) {
        if (x1 >= x0) g.fill(x0, y, x1 + 1, y + 1, colour);
    }

    private static int shade(int colour, float f) {
        int r = Math.min(255, Math.round((colour >> 16 & 255) * f)), gr = Math.min(255, Math.round((colour >> 8 & 255) * f));
        int b = Math.min(255, Math.round((colour & 255) * f));
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private static int mix(int from, int to, float t) {
        int r = Math.round(Mth.lerp(t, from >> 16 & 255, to >> 16 & 255));
        int gr = Math.round(Mth.lerp(t, from >> 8 & 255, to >> 8 & 255));
        int b = Math.round(Mth.lerp(t, from & 255, to & 255));
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private static int alpha(int colour, float a) {
        return Mth.clamp(Math.round(a * 255), 0, 255) << 24 | colour & 0xFFFFFF;
    }

    private BossBarHud() {}
}
