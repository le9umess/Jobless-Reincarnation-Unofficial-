package dev.joblessreincarnation.client.aura;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.training.AuraVisual;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * What the wearer sees of their own aura in first person, where the body is out of view and, with some first-person mods,
 * so is the sword: a low glow along the bottom of the screen, and the edges lighting up when the aura takes a blow.
 */
public final class AuraOverlay {
    private static final int GOLD = 0xFFD27A;

    public static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "aura_overlay"), AuraOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !minecraft.options.getCameraType().isFirstPerson()) return;
        AuraVisual cue = AuraVisual.of(player);
        if (cue.phase() == AuraVisual.Phase.OFF) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float flash = cue.flash(player.level().getGameTime(), partial);
        float time = player.tickCount + partial;
        float breathe = 0.85F + 0.15F * (float) Math.sin(time * 0.09F);
        float bottom = switch (cue.phase()) {
            case GATHER -> 0.24F + 0.05F * AuraCues.gatherTier(player) + 0.22F * AuraCues.tierFlash(player, partial);
            case BREAK -> 0.0F;
            default -> 0.11F * breathe;
        };
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        band(graphics, width, height, height / 4, Math.max(bottom, flash * 0.42F), true);
        if (flash > 0.0F) {
            int reach = height / 5;
            graphics.fillGradient(0, 0, width, reach, tint(flash * 0.32F), tint(0.0F));
            sideways(graphics, width, height, reach, flash * 0.32F);
        }
    }

    private static void band(GuiGraphics graphics, int width, int height, int reach, float strength, boolean atBottom) {
        if (strength > 0.0F) graphics.fillGradient(0, height - reach, width, height, tint(0.0F), tint(strength));
    }

    /** {@code fillGradient} only runs top to bottom, so the sides are drawn as thin steps. */
    private static void sideways(GuiGraphics graphics, int width, int height, int reach, float strength) {
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            int color = tint(strength * (1.0F - (float) i / steps));
            int from = reach * i / steps, to = reach * (i + 1) / steps;
            graphics.fill(from, 0, to, height, color);
            graphics.fill(width - to, 0, width - from, height, color);
        }
    }

    private static int tint(float alpha) {
        return Math.round(Math.min(1.0F, alpha) * 255.0F) << 24 | GOLD;
    }

    private AuraOverlay() {}
}
