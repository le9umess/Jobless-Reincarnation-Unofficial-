package dev.joblessreincarnation.client.aura;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.ChargeBarSkill;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * The charge bar for sword skills, in the brass style of the rest of the HUD, just under the crosshair while a
 * {@link ChargeBarSkill} is held; Epic Fight's own charging bar is hidden meanwhile. The skill's name below; a slim framed
 * bar filling with gold; a gem at each tier that lights as the tier is reached, with a short flash of light. When the stamina
 * cannot pay for the higher tiers, the bar past the last tier it can pay for is tinted red, fills only dully there and its
 * gems stay dark red, so charging on is visibly pointless. Once full the bar glows. Any later sword skill that charges gets
 * the same bar by implementing {@link ChargeBarSkill}.
 */
public final class SwordChargeHud {
    private static final ResourceLocation EF_CHARGING = ResourceLocation.fromNamespaceAndPath("epicfight", "charging_bar");
    private static final int WIDTH = 72, HEIGHT = 5;
    private static final int BRONZE = 0xFF2C2218, DIM = 0xFF7C6341, LIT = 0xFFDCC48F, TROUGH = 0xE60B111D;
    private static final int GOLD_TOP = 0xFFFFF1C0, GOLD = 0xFFF2C03C, GOLD_LOW = 0xFFB07F12, WASTED = 0xFF8A4A2E;
    private static final int GEM_LIT = 0xFFFFE08A, GEM_DARK = 0xFF5A4730, GEM_BARRED = 0xFF7A2420, BARRED = 0x8C5A1A1A, SHINE = 0xFFF2C4;
    private static int lastReached;
    private static long reachedAt;

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "sword_charge"), SwordChargeHud::render);
    }

    public static void hideEpicFightBar(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(EF_CHARGING) && holding() != null) event.setCanceled(true);
    }

    private static @Nullable LocalPlayerPatch holding() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return null;
        LocalPlayerPatch patch = EpicFightCapabilities.getEntityPatch(minecraft.player, LocalPlayerPatch.class);
        return patch != null && patch.isHoldingAny() && patch.getHoldingSkill() instanceof ChargeBarSkill ? patch : null;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayerPatch patch = holding();
        if (patch == null || minecraft.options.hideGui || minecraft.player.isSpectator()) {
            lastReached = 0;
            return;
        }
        ChargeBarSkill bar = (ChargeBarSkill) patch.getHoldingSkill();
        int[] tiers = bar.chargeTiers();
        int full = tiers[tiers.length - 1];
        // The same count Epic Fight's own bar shows: ticks since the hold started on this client.
        float ticks = Math.max(0.0F, patch.getSkillChargingTicks(delta.getGameTimeDeltaPartialTick(false)));
        int reached = 0;
        for (int i = 0; i < tiers.length; i++) if (ticks >= tiers[i]) reached = i + 1;
        int affordable = bar.affordableTier(patch, tiers.length);
        long now = Util.getMillis();
        if (reached > lastReached) reachedAt = now;
        lastReached = reached;

        // Just clear of the crosshair (which reaches 7 px below the centre), with the skill's name under the bar.
        int x = g.guiWidth() / 2 - WIDTH / 2, y = g.guiHeight() / 2 + 13;
        // Frame: a bronze outline with a lit top and left edge and a dim bottom and right edge, over the dark trough.
        g.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, BRONZE);
        g.fill(x - 1, y - 1, x + WIDTH + 1, y, LIT);
        g.fill(x - 1, y, x, y + HEIGHT, LIT);
        g.fill(x - 1, y + HEIGHT, x + WIDTH + 1, y + HEIGHT + 1, DIM);
        g.fill(x + WIDTH, y, x + WIDTH + 1, y + HEIGHT, DIM);
        g.fill(x, y, x + WIDTH, y + HEIGHT, TROUGH);
        // Past the last tier the stamina pays for, charging on changes nothing.
        int useful = affordable >= tiers.length ? WIDTH : affordable == 0 ? 0 : px(tiers[affordable - 1], full);
        if (useful < WIDTH) g.fill(x + useful, y, x + WIDTH, y + HEIGHT, BARRED);
        int filled = Math.round(WIDTH * Math.min(1.0F, ticks / full));
        boolean done = reached == tiers.length;
        float glow = done && useful == WIDTH ? 0.8F + 0.2F * Mth.sin(now / 90.0F) : 1.0F;
        int gold = Math.min(filled, useful);
        g.fill(x, y, x + gold, y + 1, shade(GOLD_TOP, glow));
        g.fill(x, y + 1, x + gold, y + HEIGHT - 1, shade(GOLD, glow));
        g.fill(x, y + HEIGHT - 1, x + gold, y + HEIGHT, shade(GOLD_LOW, glow));
        if (filled > useful) g.fill(x + useful, y + 1, x + filled, y + HEIGHT - 1, WASTED);
        if (filled > 0 && !done) g.fill(x + filled - 1, y, x + filled, y + HEIGHT, filled > useful ? 0xFFC08060 : 0xFFFFFFFF);
        float flash = Math.max(0.0F, 1.0F - (now - reachedAt) / 320.0F);
        for (int i = 0; i < tiers.length; i++) {
            int gx = x + px(tiers[i], full) - (i == tiers.length - 1 ? 1 : 0);
            boolean lit = i < reached && i < affordable;
            int colour = lit ? GEM_LIT : i >= affordable ? GEM_BARRED : GEM_DARK;
            gem(g, gx, y + HEIGHT / 2, colour, lit && i == reached - 1 ? flash : 0.0F);
        }
        if (patch.getHoldingSkill() instanceof Skill skill) {
            Component name = Component.translatable(skill.getTranslationKey());
            g.drawString(minecraft.font, name, g.guiWidth() / 2 - minecraft.font.width(name) / 2, y + HEIGHT + 5, LIT, true);
        }
    }

    private static int px(int tick, int full) { return Math.round(WIDTH * (float) tick / full); }

    /** A small diamond gem with a bronze rim; a just-reached tier throws a short star of light. */
    private static void gem(GuiGraphics g, int cx, int cy, int colour, float flash) {
        for (int dy = -3; dy <= 3; dy++) {
            int w = 3 - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, BRONZE);
        }
        for (int dy = -2; dy <= 2; dy++) {
            int w = 2 - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, colour);
        }
        if (flash > 0.0F) {
            int a = Math.round(flash * 220) << 24 | SHINE;
            g.fill(cx - 5, cy, cx + 6, cy + 1, a);
            g.fill(cx, cy - 5, cx + 1, cy + 6, a);
        }
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, Math.round((argb >> 16 & 255) * factor)), gr = Math.min(255, Math.round((argb >> 8 & 255) * factor));
        int b = Math.min(255, Math.round((argb & 255) * factor));
        return argb & 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private SwordChargeHud() {}
}
