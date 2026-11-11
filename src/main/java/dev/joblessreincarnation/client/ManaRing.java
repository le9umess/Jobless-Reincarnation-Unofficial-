package dev.joblessreincarnation.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The brass ring behind the resting mana crystal: a thin aged-brass circle round the 26-pixel plate, outlined in bronze and
 * lit from the upper left like the plate, with a three-pixel rune notch at each compass point. A short glint travels round
 * it once every ten seconds. When the crystal cracks or its owner is drained the ring goes still and dull, and it also stays
 * still when the client turns the animation off. Drawn pixel by pixel before the plate, which covers its inner side; all
 * original pixels.
 */
final class ManaRing {
    private static final float RADIUS = 17.4F, CENTRE = 12.5F, TAIL = 0.7F, GLINT = 0.8F;
    private static final long TURN_MILLIS = 10_000L;
    private static final int BRONZE = 0xFF2C2218, BRASS = 0xFFA3845A, BRASS_DIM = 0xFF7C6341, LIT = 0xFFDCC48F, SHINE = 0xFFFFF0C4;
    /** Offsets from the plate's top-left corner: {x, y, kind}, kind 0 lit brass, 1 shaded brass, 2 bronze outline, 3 rune. */
    private static final int[][] PIXELS = build();

    private static int[][] build() {
        List<int[]> pixels = new ArrayList<>();
        for (int y = -8; y < 34; y++) for (int x = -8; x < 34; x++) {
            float dx = x - CENTRE, dy = y - CENTRE, off = Math.abs(Mth.sqrt(dx * dx + dy * dy) - RADIUS);
            if (off < 0.55F) pixels.add(new int[] {x, y, dx + dy < 0 ? 0 : 1});
            else if (off < 1.25F) pixels.add(new int[] {x, y, 2});
        }
        for (int[] at : new int[][] {{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
            int x = Math.round(CENTRE + at[0] * RADIUS), y = Math.round(CENTRE + at[1] * RADIUS);
            int alongX = at[0] == 0 ? 1 : 0, alongY = 1 - alongX;
            for (int step = -2; step <= 2; step += 2) pixels.add(new int[] {x + alongX * step, y + alongY * step, 3});
        }
        return pixels.toArray(int[][]::new);
    }

    /**
     * @param animate the glint travels; false keeps the ring still
     * @param dull    the crystal is cracked or drained: the brass darkens toward bronze
     */
    static void draw(GuiGraphics graphics, int plateX, int plateY, boolean animate, boolean dull) {
        // Clockwise from the top, as the screen's y runs down.
        double head = animate ? (Util.getMillis() % TURN_MILLIS) / (double) TURN_MILLIS * Math.PI * 2 - Math.PI / 2 : Double.NaN;
        for (int[] at : PIXELS) {
            int colour = switch (at[2]) {
                case 0 -> BRASS;
                case 1 -> BRASS_DIM;
                case 2 -> BRONZE;
                default -> LIT;
            };
            if (at[2] != 2) {
                if (dull) colour = mix(colour, BRONZE, 0.35F);
                if (at[2] != 3 && !Double.isNaN(head)) {
                    double behind = head - Math.atan2(at[1] - CENTRE, at[0] - CENTRE);
                    behind = ((behind % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
                    if (behind < TAIL) colour = mix(colour, SHINE, (float) (1 - behind / TAIL) * GLINT);
                }
            }
            graphics.fill(plateX + at[0], plateY + at[1], plateX + at[0] + 1, plateY + at[1] + 1, colour);
        }
    }

    private static int mix(int from, int to, float t) {
        int r = Math.round(Mth.lerp(t, from >> 16 & 255, to >> 16 & 255));
        int g = Math.round(Mth.lerp(t, from >> 8 & 255, to >> 8 & 255));
        int b = Math.round(Mth.lerp(t, from & 255, to & 255));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private ManaRing() {}
}
