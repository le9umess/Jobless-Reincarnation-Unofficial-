package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Server-only hit eligibility and private feedback. No attack event is canceled or credited. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class SwordPractice {
    public static final int HIT_INTERVAL_TICKS = 10;
    public static final int FEEDBACK_INTERVAL_TICKS = 40;
    private static final String PREFIX = "message.jobless_reincarnation.sword_practice.";
    private static final Map<UUID, RuntimeState> PLAYERS = new HashMap<>();

    private static final class RuntimeState {
        long lastCount = Long.MIN_VALUE;
        long lastFeedback = Long.MIN_VALUE;
    }

    /**
     * Returns whether the dummy accepted a fully recovered physical practice strike, whether or not
     * that strike can advance this player's lesson. Called only by the dummy's actual hurt path.
     */
    public static boolean tryHit(PracticeDummyEntity dummy, DamageSource source, float amount) {
        if (!(dummy.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !Float.isFinite(amount) || amount <= 0.0F
                || !source.is(DamageTypes.PLAYER_ATTACK)
                || !(source.getEntity() instanceof ServerPlayer player) || source.getDirectEntity() != player
                || !validActor(player, level) || dummy.isRemoved() || !dummy.isAlive()
                || level.getEntity(dummy.getUUID()) != dummy
                || !player.canInteractWithEntity(dummy, 0.0) || !player.hasLineOfSight(dummy)) return false;

        if (!dummy.hasSupport()) {
            feedback(player, "no_support");
            return false;
        }
        if (!player.getMainHandItem().is(ModContent.PRACTICE_WOODEN_SWORD.get())) {
            feedback(player, "wrong_item");
            return false;
        }

        // NeoForge 21.1.251 Player.attack calls target.hurt before resetAttackStrengthTicker.
        // Keep this query inside hurt; verify it with real player.attack when upgrading NeoForge.
        // AttackEntityEvent is earlier than actual damage and cancellation would skip that reset.
        if (!(player.getAttackStrengthScale(0.5F) >= 1.0F)) {
            feedback(player, "not_recovered");
            return false;
        }
        if (player.isCreative()) {
            feedback(player, "creative");
            return true;
        }
        if (!player.hasData(ModAttachments.SWORD_TRAINING)) {
            feedback(player, "not_started");
            return true;
        }
        SwordTrainingProgress progress = player.getData(ModAttachments.SWORD_TRAINING);
        switch (progress.phase()) {
            case NOT_STARTED -> {
                feedback(player, "not_started");
                return true;
            }
            case READY -> {
                feedback(player, "ready");
                return true;
            }
            case DONE -> {
                boolean learned = dev.joblessreincarnation.compat.epic.SchoolSkills.completeDrill(player, SchoolForm.ARM_STRIKE);
                if (progress.completeAuraDrill()) {
                    player.displayClientMessage(Component.translatable(PREFIX + "aura_drill"), true);
                } else if (!learned) {
                    feedback(player, progress.auraAwakened() ? "done" : "aura_drill");
                }
                return true;
            }
            case PRACTICING -> { }
        }

        long now = now(player);
        RuntimeState runtime = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new RuntimeState());
        if (withinInterval(now, runtime.lastCount, HIT_INTERVAL_TICKS)) {
            feedback(player, "too_fast");
            return true;
        }
        // Reserve the player's global interval before any feedback/animation can reenter an attack.
        runtime.lastCount = now;
        if (progress.recordHit()) {
            player.displayClientMessage(Component.translatable(PREFIX + "counted",
                    progress.hits(), SwordTrainingProgress.GOAL), true);
        }
        return true;
    }

    static boolean validActor(ServerPlayer player, ServerLevel level) {
        return level.getServer().isSameThread() && player.serverLevel() == level
                && !player.isFakePlayer() && player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && level.getEntity(player.getUUID()) == player;
    }

    /** One budget per player for all failure/course hints; successful hit counts stay immediate. */
    public static void feedback(ServerPlayer player, String key, Object... arguments) {
        if (!validActor(player, player.serverLevel())) return;
        long now = now(player);
        RuntimeState runtime = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new RuntimeState());
        if (withinInterval(now, runtime.lastFeedback, FEEDBACK_INTERVAL_TICKS)) return;
        runtime.lastFeedback = now;
        player.displayClientMessage(Component.translatable(PREFIX + key, arguments), true);
    }

    private static long now(ServerPlayer player) {
        // One clock across dimensions, matching the UUID-wide hit budget.
        return player.serverLevel().getServer().overworld().getGameTime();
    }

    static boolean withinInterval(long now, long before, int ticks) {
        return before != Long.MIN_VALUE && now >= before && now - before < ticks;
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().isClientSide) PLAYERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        PLAYERS.clear();
    }

    private SwordPractice() { }
}