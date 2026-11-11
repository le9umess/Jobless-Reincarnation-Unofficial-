package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The spell key with the staff in hand. A bare tap goes back to the previous spell. While it is held, a hotbar key picks the
 * spell in that place at once, and after a moment the hotbar shows where each spell is; the wheel then moves a highlight that
 * only counts when the key comes up. Nothing here pauses the game or takes the mouse, and choosing never casts.
 */
public final class SpellRecall {
    private static final long OPEN_AFTER_MS = 200L;
    private static final int AMBIGUOUS = -2;

    private static boolean held, tapAllowed, mayOpen, open, spent, wasDown, ignoreUntilUp, acted, chanting, chantEnded, answerEndsChant;
    private static long pressedAt;
    private static int browsed = -1;
    @Nullable private static SpellCatalog chosenFrom;

    public static boolean open() { return open; }
    /** The place the highlight is on, or -1. */
    public static int browsed() { return browsed; }

    @Nullable
    static SpellCatalog spellAt(LocalPlayer player, int slot) {
        return player.hasData(ModAttachments.CASTING) ? player.getData(ModAttachments.CASTING).spellAt(player, slot) : null;
    }

    /** Runs before vanilla handles keys, so a chosen hotbar key never also changes the held item. @return the spell key acted this tick */
    static boolean beforeTick(Minecraft minecraft, LocalPlayer player, boolean eligible) {
        acted = false;
        boolean clicked = false;
        while (SpellKeys.CYCLE.consumeClick()) clicked = true;
        boolean down = SpellKeys.CYCLE.isDown();
        // Keyboard autorepeat clicks too, so a press is a click while the key was up.
        boolean pressed = clicked && !wasDown;
        wasDown = down;
        settleAnswer(player);
        if (!eligible) {
            forget();
            ignoreUntilUp = down;
            return false;
        }
        if (ignoreUntilUp) {
            if (down) return false;
            ignoreUntilUp = false;
        }
        if (!held) {
            if (!pressed) return false;
            startHold();
        }
        int slot = pollSlotKeys(minecraft);
        if (slot >= 0 && !spent) pick(player, slot);
        else if (slot == AMBIGUOUS) tapAllowed = false;
        if (!down) {
            if (open) {
                if (browsed >= 0) chosen(player, RecallSelectionRequests.selectSlot(player, browsed));
            } else if (tapAllowed) tap(player);
            forget();
            return pressed || acted;
        }
        // Letting go of a chant while looking settles that chant by the ordinary rules, and the bar goes with it.
        if (chantEnded) closeBar();
        chantEnded = false;
        if (mayOpen && !open && Util.getMillis() - pressedAt >= OPEN_AFTER_MS) showBar(player);
        return pressed || acted;
    }

    /** Vanilla has handled this tick's use key: a chant running while the bar was open may have just been released. */
    static void afterTick(LocalPlayer player) {
        boolean now = player.isUsingItem() && player.getUseItem().is(ModContent.TRAINING_STAFF.get());
        if (open && chanting && !now) chantEnded = true;
        chanting = now;
    }

    /** @return this use press must not start a chant. Only a press inside the open bar has to be released and pressed again. */
    static boolean holdsBackUse(LocalPlayer player) {
        if (open) {
            StaffInput.requireUseRelease();
            return true;
        }
        // Tap and cast rolled together: the press on the use key settles the tap, then the hold starts the new chant.
        if (held && tapAllowed) {
            tapAllowed = mayOpen = false;
            tap(player);
        }
        return RecallSelectionRequests.pending();
    }

    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !StaffInput.eligible(minecraft, player)) return;
        // The wheel arrives between ticks: a key that went down since the last one already counts, or this click would turn the hotbar.
        if (!held && !ignoreUntilUp && !wasDown && SpellKeys.CYCLE.isDown()) startHold();
        if (!held) return;
        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta == 0.0 || spent) return;
        tapAllowed = mayOpen = false;
        if (!open) showBar(player);
        step(player, delta > 0.0 ? -1 : 1); // The same way round as the vanilla hotbar.
    }

    /** The journal took the key or the world went away: this press is over and replays nothing. */
    static void drop() {
        while (SpellKeys.CYCLE.consumeClick()) {}
        boolean down = SpellKeys.CYCLE.isDown();
        forget();
        wasDown = ignoreUntilUp = down;
        answerEndsChant = false;
    }

    private static void startHold() {
        held = tapAllowed = mayOpen = true;
        pressedAt = Util.getMillis();
    }
    private static void forget() {
        held = tapAllowed = mayOpen = spent = chantEnded = false;
        closeBar();
    }
    private static void closeBar() {
        open = mayOpen = false;
        browsed = -1;
    }
    private static void showBar(LocalPlayer player) {
        open = true;
        tapAllowed = false;
        SpellCatalog current = SpellCatalog.selected(player);
        browsed = -1;
        for (int slot = 0; slot < CastingData.SLOT_COUNT && browsed < 0; slot++) if (spellAt(player, slot) == current) browsed = slot;
    }

    private static void pick(LocalPlayer player, int slot) {
        tapAllowed = false;
        if (spellAt(player, slot) == null) return; // An empty place chooses nothing and changes nothing.
        closeBar();
        spent = true; // One press of the key submits one choice: a slipped finger neither re-picks nor swaps the staff away.
        chosen(player, RecallSelectionRequests.selectSlot(player, slot));
    }
    private static void tap(LocalPlayer player) {
        acted = true;
        chosen(player, RecallSelectionRequests.previous(player));
    }
    /** A request for a different spell is on its way. A running chant goes on until the answer: a refused request interrupts nothing. */
    private static void chosen(LocalPlayer player, boolean sent) {
        if (!sent) return;
        acted = true;
        if (player.isUsingItem() && player.getUseItem().is(ModContent.TRAINING_STAFF.get())) {
            answerEndsChant = true;
            chosenFrom = SpellCatalog.selected(player);
        }
    }
    /** The choice stood, so that chant ended unpaid, and the use key has to come up before the next one. */
    private static void settleAnswer(LocalPlayer player) {
        if (!answerEndsChant || RecallSelectionRequests.pending()) return;
        answerEndsChant = false;
        if (SpellCatalog.selected(player) == chosenFrom) return;
        if (player.isUsingItem() && player.getUseItem().is(ModContent.TRAINING_STAFF.get())) player.stopUsingItem();
        StaffInput.requireUseRelease();
    }

    /** Consumes every hotbar click, so none reaches vanilla. Two places bound to one input name no place at all. */
    private static int pollSlotKeys(Minecraft minecraft) {
        KeyMapping[] keys = minecraft.options.keyHotbarSlots;
        int found = -1;
        boolean ambiguous = false;
        for (int i = 0; i < keys.length; i++) {
            boolean clicked = false;
            while (keys[i].consumeClick()) clicked = true;
            if (!clicked) continue;
            if (found < 0) found = i;
            else if (keys[found].same(keys[i])) ambiguous = true;
        }
        return ambiguous ? AMBIGUOUS : found;
    }
    private static void step(LocalPlayer player, int direction) {
        int from = browsed >= 0 ? browsed : direction > 0 ? -1 : CastingData.SLOT_COUNT;
        for (int offset = 1; offset <= CastingData.SLOT_COUNT; offset++) {
            int slot = Math.floorMod(from + offset * direction, CastingData.SLOT_COUNT);
            if (spellAt(player, slot) != null) {
                browsed = slot;
                return;
            }
        }
    }

    private SpellRecall() {}
}
