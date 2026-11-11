package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/** Selection precedes vanilla release; H settles only after vanilla updates slots and movement. */
public final class StaffInput {
    private static boolean awaitUseRelease;
    public static boolean waitingForRelease() { return awaitUseRelease || JournalInput.waitingForFreshUse(); }
    public static void requireUseRelease() {
        if (Minecraft.getInstance().options.keyUse.isDown()) awaitUseRelease = true;
    }
    public static void beforeClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        boolean journal = JournalInput.beforeTick();
        if (journal) {
            SpellRecall.drop();
            DismissInputState.beforeTick(false);
            return;
        }
        if (player == null) {
            awaitUseRelease = false; SpellRecall.drop();
            DismissInputState.beforeTick(false); return;
        }
        if (!minecraft.options.keyUse.isDown()) awaitUseRelease = false;
        boolean eligible = eligible(minecraft, player);
        DismissInputState.beforeTick(SpellRecall.beforeTick(minecraft, player, eligible));
        if (!eligible || minecraft.options.keyUse.isDown() || !player.isUsingItem()
                || player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || !player.getUseItem().is(ModContent.TRAINING_STAFF.get())) return;
        boolean crouchingNow = minecraft.options.keyShift.isDown();
        if (crouchingNow == player.isShiftKeyDown()) return;
        var action = crouchingNow ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY;
        player.connection.send(new ServerboundPlayerCommandPacket(player, action));
    }
    /** With the staff in hand, in the game itself, with nothing in the way: the spell key and its hotbar keys are ours. */
    static boolean eligible(Minecraft minecraft, LocalPlayer player) {
        return player.isAlive() && !player.isSpectator() && !minecraft.isPaused() && minecraft.isWindowActive()
                && minecraft.screen == null && minecraft.getOverlay() == null
                && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
    }
    public static void afterClientTick(ClientTickEvent.Post event) {
        DismissInputState.afterTick();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        SpellRecall.afterTick(player);
        OvercastCue.afterTick(player);
        dev.joblessreincarnation.client.aura.AuraCues.afterTick(Minecraft.getInstance());
    }
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.isUseItem() && minecraft.player != null
                && ((JournalInput.waitingForFreshUse() && SpellCasting.isMagicUse(minecraft.player.getMainHandItem()))
                || ((awaitUseRelease || DismissInputState.blocksUse())
                && minecraft.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())))) {
            event.setCanceled(true);
            event.setSwingHand(false);
        } else if (event.isUseItem() && minecraft.player != null && minecraft.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                && SpellRecall.holdsBackUse(minecraft.player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
    private StaffInput() {}
}
