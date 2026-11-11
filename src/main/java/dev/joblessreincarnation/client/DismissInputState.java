package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.network.DismissPayload;
import dev.joblessreincarnation.network.DismissStatusPayload;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** H input has its own edge latch; it never borrows vanilla item-use or its charged spell. */
public final class DismissInputState {
    private enum Phase { IDLE, PREPARING, REQUESTED, ACTIVE, RELEASING, WAIT_RELEASE }
    private static Phase phase = Phase.IDLE;
    private static int nextNonce, nonce, slot, heldClientTicks, holdTicks = 20, pendingCost, elapsed;
    private static boolean wasDown, ready, requireFreshPress;
    private static ItemStack wand = ItemStack.EMPTY;
    private static ClientLevel level;
    private static List<BlockPos> positions = List.of();

    public static boolean blocksUse() { return phase != Phase.IDLE; }
    public static boolean holding() { return phase == Phase.PREPARING || phase == Phase.REQUESTED || phase == Phase.ACTIVE || phase == Phase.RELEASING; }
    public static boolean waitingForRelease() { return phase == Phase.WAIT_RELEASE; }
    public static boolean accepted() { return phase == Phase.ACTIVE; }
    public static boolean ready() { return ready; }
    public static int cost() { return pendingCost; }
    public static int currentNonce() { return nonce; }
    public static float progress() { return ready ? 1.0F : Math.min(0.95F, heldClientTicks / (float)Math.max(1, holdTicks)); }
    public static List<BlockPos> positions() { return phase == Phase.ACTIVE ? positions : List.of(); }

    private static boolean eligible(Minecraft minecraft) {
        var player = minecraft.player;
        return player != null && player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild
                && minecraft.screen == null && minecraft.getOverlay() == null && !minecraft.isPaused() && minecraft.isWindowActive()
                && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                && SpellCatalog.selected(player) == SpellCatalog.EARTH_WALL && SpellCatalog.EARTH_WALL.knows(player);
    }
    private static boolean sameContext(Minecraft minecraft) {
        return eligible(minecraft) && minecraft.player.getMainHandItem() == wand
                && minecraft.player.getInventory().selected == slot && !minecraft.player.isUsingItem()
                && !minecraft.options.keyShift.isDown() && !minecraft.player.isShiftKeyDown();
    }
    /** Runs before vanilla right-click processing; G has already been sent and wins all simultaneous edges. */
    public static void beforeTick(boolean cycleIntent) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean down = SpellKeys.DISMISS.isDown();
        while (SpellKeys.DISMISS.consumeClick()) {} // Keyboard autorepeat must not restart a hold.
        if (minecraft.player == null || minecraft.level != level) {
            reset(); level = minecraft.level; wasDown = down;
            if (down) phase = Phase.WAIT_RELEASE;
            return;
        }
        if (phase == Phase.WAIT_RELEASE && !down && !requireFreshPress && inputContext(minecraft)) finishLocal(false);
        if (cycleIntent) {
            if (holding()) cancel();
            if (down) phase = Phase.WAIT_RELEASE;
        } else if (down && !wasDown && phase == Phase.IDLE) {
            // Snapshot before vanilla can process a same-tick hotbar, inventory or drop key.
            if (eligible(minecraft) && !minecraft.player.isUsingItem() && !minecraft.options.keyShift.isDown()) {
                wand = minecraft.player.getMainHandItem(); slot = minecraft.player.getInventory().selected;
                nonce = ++nextNonce; phase = Phase.PREPARING; elapsed = heldClientTicks = 0; ready = false;
            } else phase = Phase.WAIT_RELEASE;
        }
        if (holding() && (!sameContext(minecraft) || ++elapsed >= 240)) cancel();
        if (phase == Phase.ACTIVE && down) heldClientTicks++;
        wasDown = down;
    }
    /** Vanilla has now handled slots/screens and sent this tick's movement and crouch updates. */
    public static void afterTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!holding()) return;
        if (!sameContext(minecraft)) { cancel(); return; }
        if (phase == Phase.PREPARING) {
            if (!SpellKeys.DISMISS.isDown()) { finishLocal(false); return; }
            phase = Phase.REQUESTED;
            PacketDistributor.sendToServer(new DismissPayload(nonce, DismissPayload.BEGIN));
        } else if (!SpellKeys.DISMISS.isDown() && phase != Phase.RELEASING) {
            phase = Phase.RELEASING;
            StaffInput.requireUseRelease();
            PacketDistributor.sendToServer(new DismissPayload(nonce, DismissPayload.RELEASE));
        }
    }
    public static void receive(DismissStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level != level || payload.nonce() != nonce || !holding()) return;
        if (!payload.active()) { finishLocal(requireFreshPress || SpellKeys.DISMISS.isDown()); return; }
        // A delayed BEGIN acknowledgement must not resurrect a released/cancelled session.
        if (phase == Phase.RELEASING) return;
        if (phase == Phase.REQUESTED) heldClientTicks = 0;
        phase = Phase.ACTIVE; holdTicks = payload.holdTicks(); pendingCost = payload.cost();
        positions = payload.positions(); ready = payload.ready();
    }
    /** Opening the journal cancels H before any same-tick release can settle it. */
    public static void cancelForJournal() {
        if (!holding() && phase != Phase.WAIT_RELEASE && !SpellKeys.DISMISS.isDown()) return;
        requireFreshPress = true;
        cancel();
    }
    private static void cancel() {
        requireFreshPress |= !inputContext(Minecraft.getInstance());
        if (phase == Phase.REQUESTED || phase == Phase.ACTIVE || phase == Phase.RELEASING)
            PacketDistributor.sendToServer(new DismissPayload(nonce, DismissPayload.CANCEL));
        finishLocal(requireFreshPress || SpellKeys.DISMISS.isDown());
    }
    private static void finishLocal(boolean waitForRelease) {
        StaffInput.requireUseRelease();
        phase = waitForRelease ? Phase.WAIT_RELEASE : Phase.IDLE;
        positions = List.of(); ready = false; heldClientTicks = pendingCost = elapsed = 0; wand = ItemStack.EMPTY;
    }
    private static void reset() {
        phase = Phase.IDLE; positions = List.of(); wand = ItemStack.EMPTY;
        wasDown = ready = requireFreshPress = false; heldClientTicks = pendingCost = elapsed = 0;
    }
    private static boolean inputContext(Minecraft minecraft) {
        return minecraft.screen == null && minecraft.getOverlay() == null && !minecraft.isPaused() && minecraft.isWindowActive();
    }
    /** A GUI or focus transition can synthesize KeyMapping releases. Only a fresh physical press unlocks it. */
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && SpellKeys.DISMISS.matches(event.getKey(), event.getScanCode())) unlockAfterContext();
    }
    public static void onMouseInput(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_PRESS && SpellKeys.DISMISS.matchesMouse(event.getButton())) unlockAfterContext();
    }
    private static void unlockAfterContext() {
        if (!requireFreshPress || !inputContext(Minecraft.getInstance()) || !JournalInput.acceptsFreshContextPress()) return;
        requireFreshPress = false;
        finishLocal(false);
        wasDown = false;
    }
    private DismissInputState() {}
}


