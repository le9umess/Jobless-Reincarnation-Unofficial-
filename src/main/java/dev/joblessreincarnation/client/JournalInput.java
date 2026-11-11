package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.network.OpenJournalPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** The journal wins same-tick spell input and requires a real new use press after closing. */
public final class JournalInput {
    private static boolean freshUseRequired;
    private static long clientTicks, acceptPressAfterTick;
    public static boolean waitingForFreshUse() { return freshUseRequired; }
    public static boolean acceptsFreshContextPress() { return clientTicks > acceptPressAfterTick; }

    /** Called before StaffInput consumes G/H or vanilla can release an item. */
    public static boolean beforeTick() {
        Minecraft minecraft = Minecraft.getInstance();
        clientTicks++;
        boolean requested = false;
        while (SpellKeys.JOURNAL.consumeClick()) requested = true;
        if (minecraft.player == null || minecraft.level == null) {
            freshUseRequired = false;
            acceptPressAfterTick = clientTicks - 1;
            return false;
        }
        if (requested && open()) return true;
        return minecraft.screen instanceof MagicJournalScreen;
    }
    public static boolean open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()
                || minecraft.player.isSpectator() || minecraft.screen != null || minecraft.getOverlay() != null
                || minecraft.isPaused() || !minecraft.isWindowActive()) return false;
        while (SpellKeys.CYCLE.consumeClick()) {} // K has priority over simultaneous G.
        freshUseRequired = true;
        acceptPressAfterTick = Long.MAX_VALUE;
        DismissInputState.cancelForJournal();
        PacketDistributor.sendToServer(OpenJournalPayload.INSTANCE);
        SpellCasting.openJournal(minecraft.player); // stopUsingItem, never releaseUsingItem.
        minecraft.setScreen(new MagicJournalScreen());
        boolean opened = minecraft.screen instanceof MagicJournalScreen;
        // Another client mod may veto/rewrite ScreenEvent.Opening. Never leave a permanent input lock.
        if (!opened) closing();
        return opened;
    }
    /** Screen.removed covers Escape, buttons and replacement by another screen alike. */
    public static void closing() {
        freshUseRequired = true;
        // A close-button/ESC event reaches InputEvent.Post after setScreen(null). It is not a fresh cast.
        // Let one complete tick pass before accepting an actual press in the game context.
        acceptPressAfterTick = clientTicks + 1;
    }
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS
                && Minecraft.getInstance().options.keyUse.matches(event.getKey(), event.getScanCode())) freshPress();
    }
    public static void onMouseInput(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_PRESS
                && Minecraft.getInstance().options.keyUse.matchesMouse(event.getButton())) freshPress();
    }
    private static void freshPress() {
        Minecraft minecraft = Minecraft.getInstance();
        if (freshUseRequired && acceptsFreshContextPress() && minecraft.player != null
                && minecraft.level != null && minecraft.screen == null && minecraft.getOverlay() == null
                && !minecraft.isPaused() && minecraft.isWindowActive()) freshUseRequired = false;
    }
    private JournalInput() {}
}


