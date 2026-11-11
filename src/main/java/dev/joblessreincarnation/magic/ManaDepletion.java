package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.EffectCures;

public final class ManaDepletion {
    public static boolean canOvercast(Player player, SpellCatalog spell) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().instabuild
                && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get()) && spell.knows(player)
                && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                && player.hasData(ModAttachments.MANA)
                && player.getData(ModAttachments.MANA).canPay(spell.manaCost(0), false, SpellPayment.OVERCAST);
    }

    public static int confirmTicks(Player player, SpellCatalog spell) {
        int chant = spell.chantTicks(player);
        return chant + Math.max(1, (int) Math.ceil(chant * MagicConfig.OVERCAST_CONFIRM_RATIO.get()));
    }

    public static boolean isDepleted(Player player) {
        return player.hasEffect(ModContent.MANA_DEPLETION);
    }

    static void apply(ServerPlayer player) {
        OvercastEffects.play(player);
        int duration = MagicConfig.DEPLETION_TICKS.getAsInt();
        player.addEffect(new MobEffectInstance(ModContent.MANA_DEPLETION, duration, 0, false, false, true));
        int blind = Math.min(duration, MagicConfig.DEPLETION_BLIND_TICKS.getAsInt());
        if (blind > 0) {
            MobEffectInstance onset = new MobEffectInstance(MobEffects.BLINDNESS, blind, 0, false, false, false);
            onset.getCures().remove(EffectCures.MILK);
            player.addEffect(onset);
        }
    }

    private ManaDepletion() {}
}
