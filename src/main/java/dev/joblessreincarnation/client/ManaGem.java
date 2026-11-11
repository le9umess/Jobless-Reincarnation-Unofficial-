package dev.joblessreincarnation.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The resting mana display: a small faceted crystal instead of a number, coloured by how full its owner is. Deep green
 * when full, amber about a third of the way, deep red near empty. The bright core shrinks with it and a crack shows near
 * empty, so it still reads without telling red from green. While a release is pending, the rim takes the colour the
 * crystal will have after paying. Drawn pixel by pixel because the colour is continuous; all original pixels.
 */
final class ManaGem {
    static final int SIZE = 16;
    private static final float[] GREEN = {0x0F, 0x7A, 0x45}, AMBER = {0xB5, 0x79, 0x1C}, RED = {0x8A, 0x12, 0x18};
    private static final float[] CORE = {255, 248, 225}, PALE = {0xC6, 0xCC, 0xCA};
    private static final float AMBER_AT = 0.35F, BREATH_BELOW = 0.15F;
    /** Below this the crystal shows its crack; the brass ring behind it goes still from here too. */
    static final float CRACK_BELOW = 0.2F;
    private static final int FRAME = 0xFF2C2218, EDGE_LIT = 0xFFDCC48F, EDGE = 0xFFA3845A, EDGE_DIM = 0xFF7C6341, CRACK = 0xFF180A0C;
    private static final int[][] CRACK_PIXELS = {{10, 3}, {9, 4}, {9, 5}, {8, 6}, {8, 7}, {7, 8}, {8, 9}, {7, 10}, {6, 11}};

    /**
     * @param fill      current mana over the owner's own maximum
     * @param after     the same ratio after the pending release, or negative when nothing is pending
     * @param shortfall the pending release costs more than the owner has: the rim blinks
     * @param pallor    how far an overdraw has drained the colour, 0 to 1: the crystal goes grey-white and comes back as mana returns
     * @param flare     letting go now overdraws: the whole crystal pulses
     */
    static void draw(GuiGraphics graphics, int x, int y, float fill, float after, boolean shortfall, float pallor, boolean flare) {
        float[] base = mix(tone(fill), PALE, pallor);
        float core = 1.2F + 3.4F * fill;
        long now = Util.getMillis();
        // A slow breath near empty; the rim blinks when the release cannot be paid.
        float breath = fill <= BREATH_BELOW ? 1.0F + 0.14F * Mth.sin(now / 240.0F) : 1.0F;
        int rim = after < 0.0F ? 0 : colour(tone(after), shortfall && now / 200L % 2L == 0L ? 1.4F : 1.0F, 0.0F);
        float flash = flare ? 0.25F + 0.4F * Math.abs(Mth.sin(now / 90.0F)) : 0.0F;
        if (flare) rim = colour(PALE, 1.2F, flash);
        for (int py = 0; py < SIZE; py++) for (int px = 0; px < SIZE; px++) {
            float dx = px - 7.5F, dy = py - 7.5F, reach = Math.abs(dx) + Math.abs(dy);
            if (reach > 8.6F) continue;
            int pixel;
            if (reach > 6.7F) {
                pixel = rim != 0 ? rim : reach > 7.6F ? FRAME : dy < 0.0F ? EDGE_LIT : dx < 0.0F ? EDGE : EDGE_DIM;
            } else {
                // Facets lit from the upper left, as the rest of the panel is.
                float facet = dx + dy < -1.0F ? 1.28F : dx + dy > 1.0F ? 0.72F : 1.0F;
                float distance = Mth.sqrt((dx + 0.6F) * (dx + 0.6F) + (dy + 0.6F) * (dy + 0.6F));
                float glow = distance < core ? 0.5F * (1.0F - distance / (core + 0.01F)) + 0.1F : 0.0F;
                pixel = colour(base, facet * breath, Math.max(glow, flash));
            }
            graphics.fill(x + px, y + py, x + px + 1, y + py + 1, pixel);
        }
        if (fill <= CRACK_BELOW) {
            for (int[] at : CRACK_PIXELS) graphics.fill(x + at[0], y + at[1], x + at[0] + 1, y + at[1] + 1, CRACK);
        }
    }

    private static float[] tone(float ratio) {
        float r = Mth.clamp(ratio, 0.0F, 1.0F);
        return r >= AMBER_AT ? mix(AMBER, GREEN, (r - AMBER_AT) / (1.0F - AMBER_AT)) : mix(RED, AMBER, r / AMBER_AT);
    }

    private static float[] mix(float[] from, float[] to, float t) {
        return new float[] {Mth.lerp(t, from[0], to[0]), Mth.lerp(t, from[1], to[1]), Mth.lerp(t, from[2], to[2])};
    }

    private static int colour(float[] base, float scale, float glow) {
        int red = channel(Mth.lerp(glow, base[0] * scale, CORE[0]));
        int green = channel(Mth.lerp(glow, base[1] * scale, CORE[1]));
        int blue = channel(Mth.lerp(glow, base[2] * scale, CORE[2]));
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int channel(float value) {
        return Mth.clamp(Math.round(value), 0, 255);
    }

    private ManaGem() {}
}
