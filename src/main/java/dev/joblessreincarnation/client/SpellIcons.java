package dev.joblessreincarnation.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.SpellCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Spell icons drawn from a texture rather than as an item: so far only Icicle Field, which Roxy teaches without a tome.
 * One 16×16 image serves everywhere: the journal draws all of it where a tome would be; the mana HUD and the recall bar
 * draw its 12×10 middle, the size of their other small glyphs.
 */
public final class SpellIcons {
    private static final ResourceLocation ICICLE_FIELD = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,
            "textures/gui/spells/icicle_field.png");

    public static boolean has(SpellCatalog spell) {
        return spell == SpellCatalog.ICICLE_FIELD;
    }

    /** The full 16×16 icon, in place of a tome item. */
    public static void drawLarge(GuiGraphics graphics, SpellCatalog spell, int x, int y) {
        graphics.blit(ICICLE_FIELD, x, y, 0, 0, 16, 16, 16, 16);
    }

    /** The 12×10 glyph, in the mana HUD's and recall bar's glyph box. */
    public static void drawSmall(GuiGraphics graphics, SpellCatalog spell, int x, int y) {
        graphics.blit(ICICLE_FIELD, x, y, 2, 3, 12, 10, 16, 16);
    }

    private SpellIcons() {}
}
