package dev.joblessreincarnation.client.aura;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.Random;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Ash in your own eyes. The puff itself bursts inside the camera and cannot be seen from there, and blindness alone only
 * darkens the sky, so the one it hit gets a dirty film and grains sliding down the screen for as long as the hit blinds them.
 * Only the server knows whose eyes it reached; being near the puff, or already blind from something else, is not a hit.
 */
public final class AshOverlay {
    private static final int GRAINS = 70, FADE_TICKS = 5;
    private static long startedAt, endsAt;

    public static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "ash_overlay"), AshOverlay::render);
    }

    /** {@code ticks} is the design length of this kind of hit, not whatever blindness the player may also have from elsewhere. */
    public static void hit(LocalPlayer player, int ticks) {
        startedAt = player.level().getGameTime();
        endsAt = startedAt + ticks;
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !minecraft.options.getCameraType().isFirstPerson()) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float left = endsAt - player.level().getGameTime() - partial;
        if (left <= 0) return;
        float age = player.level().getGameTime() + partial - startedAt;
        float strength = Mth.clamp(left / FADE_TICKS, 0, 1);
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        graphics.fill(0, 0, width, height, Math.round(strength * 120) << 24 | 0x38322B);
        Random grains = new Random(startedAt);
        for (int i = 0; i < GRAINS; i++) {
            int x = grains.nextInt(width), y = grains.nextInt(height) + Math.round(age * (0.5F + grains.nextFloat() * 1.5F));
            int size = 1 + grains.nextInt(3), shade = 0x50 + grains.nextInt(0x60);
            graphics.fill(x, y, x + size, y + size, Math.round(strength * 220) << 24 | shade << 16 | shade << 8 | shade);
        }
    }

    private AshOverlay() {}
}
