package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class ManaEvents {
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getItem().is(ModContent.TRAINING_STAFF.get())) {
            if (event.getHand() != InteractionHand.MAIN_HAND || !SpellCasting.begin(player, event.getItem())) {
                event.setCanceled(true);
            }
        }
    }
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EarthWallDismantling.tick(player);
        if (SpellCatalog.active(player) != null && !SpellCasting.validActive(player)) SpellCasting.cancel(player);
        ManaGrowth.tick(player);
        if (!player.isAlive() || player.isSpectator()) return;
        ManaData mana = player.getData(ModAttachments.MANA);
        if (mana.tick(player.getData(ModAttachments.MANA_GROWTH).effectiveMaximum(ManaGrowthRules.current()), MagicConfig.REGEN_PER_SECOND.getAsInt(),
                ManaDepletion.isDepleted(player) ? MagicConfig.DEPLETION_REGEN.get() : 1.0)) {
            player.syncData(ModAttachments.MANA);
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) { sync(event); }
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) { sync(event); }
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { sync(event); }
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { SpellCasting.cancel(event.getEntity()); }
    public static void onClone(PlayerEvent.Clone event) {
        SpellCasting.cancel(event.getOriginal());
        SpellCasting.cancel(event.getEntity());
    }

    private static void sync(PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpellCasting.cancel(player);
            ManaData mana = ManaGrowth.prepare(player);
            player.syncData(ModAttachments.MANA);
            player.getData(ModAttachments.KNOWLEDGE);
            player.syncData(ModAttachments.KNOWLEDGE);
            SpellCatalog.normalizeSelection(player);
            player.syncData(ModAttachments.CASTING);
            if (mana.cooldown() > 0) {
                player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), mana.cooldown());
            }
        }
    }

    private ManaEvents() {}
}
