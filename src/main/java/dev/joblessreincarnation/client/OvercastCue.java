package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.ManaDepletion;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** A low chime for the holder only, the moment letting go would overdraw: nobody should have to watch the crystal to know. */
final class OvercastCue {
    private static boolean rung;

    /** @return this chant is confirmed: letting go now overdraws */
    static boolean confirmed(LocalPlayer player) {
        if (!player.isUsingItem() || !player.getUseItem().is(ModContent.TRAINING_STAFF.get()) || !player.hasData(ModAttachments.CASTING)
                || !player.getData(ModAttachments.CASTING).overcasting()) return false;
        SpellCatalog active = SpellCatalog.active(player);
        return active != null && player.getTicksUsingItem() >= ManaDepletion.confirmTicks(player, active);
    }

    static void afterTick(LocalPlayer player) {
        boolean now = confirmed(player);
        if (now && !rung) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.0F));
        rung = now;
    }

    private OvercastCue() {}
}
