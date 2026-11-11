package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Server-only coordination of paid spells, durable growth and existing mana synchronization. */
public final class ManaGrowth {
    public static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().instabuild;
    }

    public static ManaData prepare(ServerPlayer player) {
        ManaGrowthRules rules = ManaGrowthRules.current();
        ManaGrowthData growth = player.getData(ModAttachments.MANA_GROWTH);
        ManaData mana = player.getData(ModAttachments.MANA);
        growth.refreshRules(rules);
        growth.bindMana(mana);
        if (!eligible(player)) growth.cancelRound();
        if (mana.reconcileMaximum(growth.effectiveMaximum(rules))) player.syncData(ModAttachments.MANA);
        return mana;
    }

    public static void tick(ServerPlayer player) {
        prepare(player);
        player.getData(ModAttachments.MANA_GROWTH).tick(ManaGrowthRules.current(), eligible(player));
    }

    /** Call once after payment/placement commits, before external effect callbacks. */
    public static int onSpellPaid(ServerPlayer player, ManaData.Payment payment) {
        if (payment == null) return 0;
        ManaData mana = prepare(player);
        if (!payment.consume(mana) || !eligible(player)) return 0;
        if (payment.overcast()) {
            ManaDepletion.apply(player);
            return 0;
        }
        ManaGrowthRules rules = ManaGrowthRules.current();
        ManaGrowthData growth = player.getData(ModAttachments.MANA_GROWTH);
        int gained = growth.recordPayment(rules, payment.before(), payment.after(), payment.paid());
        if (gained > 0) {
            mana.reconcileMaximum(growth.effectiveMaximum(rules));
            player.syncData(ModAttachments.MANA);
            int effectiveBonus = Math.min(growth.earnedBonus(), rules.bonusLimit());
            player.sendSystemMessage(effectiveBonus >= rules.bonusLimit()
                    ? Component.translatable("message.jobless_reincarnation.mana_growth.complete")
                    : Component.translatable("message.jobless_reincarnation.mana_growth.gained"));
            player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.0F);
        }
        return gained;
    }

    /** Registered after the framework normal-priority attachment clone handler. */
    public static void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ManaGrowthData growth = player.getData(ModAttachments.MANA_GROWTH);
        growth.refreshRules(ManaGrowthRules.current());
        if (event.isWasDeath()) {
            growth.cancelRound();
            player.setData(ModAttachments.MANA, ManaData.full(growth.effectiveMaximum(ManaGrowthRules.current())));
        }
        growth.bindMana(player.getData(ModAttachments.MANA));
    }

    /** Low-priority observation sees changes/cancellation applied by ordinary mode hooks. */
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        GameType mode = event.getNewGameMode();
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR)
            player.getData(ModAttachments.MANA_GROWTH).cancelRound();
    }

    private ManaGrowth() {}
}
