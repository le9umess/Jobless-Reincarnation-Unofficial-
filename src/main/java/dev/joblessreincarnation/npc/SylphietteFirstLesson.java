package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.magic.WaterMagic;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A short, transient tree lesson; only its completed advancement persists. Once the invited player has shown her a
 * real Water Ball, Sylphiette waits a moment and then tries it herself: the attempt gesture, with the particles and
 * sounds of {@link SylphietteLessonVisuals}, and her closing line and the advancement at its FINISH_TICK. From the
 * demonstration to the end of her attempt she stands still and faces that player.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class SylphietteFirstLesson {
    public static final int SESSION_TICKS = 200;
    public static final int ATTEMPT_DELAY_TICKS = 30;
    private static final double CAST_DISTANCE = 8.0;
    private static final String LINES = "message.jobless_reincarnation.sylphiette.";

    private enum Phase { INVITED, DEMONSTRATED, ATTEMPTING }
    private static final class Session {
        final ServerPlayer player;
        final SylphietteEntity sylphiette;
        long expiresAt;
        Phase phase = Phase.INVITED;
        long attemptAt, attemptStart;
        Session(ServerPlayer player, SylphietteEntity sylphiette, long expiresAt) {
            this.player = player; this.sylphiette = sylphiette; this.expiresAt = expiresAt;
        }
    }
    private static final Map<SylphietteEntity, Session> BY_NPC = new WeakHashMap<>();
    private static final Map<UUID, Session> BY_PLAYER = new HashMap<>();

    /** Starts only after an already-valid private conversation; ordinary meeting text remains unchanged. */
    public static boolean beginObservation(ServerPlayer player, SylphietteEntity sylphiette) {
        if (!valid(player, sylphiette) || player.isFakePlayer() || !WaterMagic.isLearned(player)
                || StoryAdvancements.hasCompleted(player, StoryAdvancements.SYLPHIETTE_FIRST_LESSON)
                || BY_PLAYER.containsKey(player.getUUID()) || BY_NPC.containsKey(sylphiette)) return false;
        Session session = new Session(player, sylphiette,
                player.serverLevel().getGameTime() + SESSION_TICKS);
        BY_PLAYER.put(player.getUUID(), session); BY_NPC.put(sylphiette, session);
        sylphiette.beginLessonConversation(player);
        player.sendSystemMessage(line("observe"));
        return true;
    }

    /** Read-only reservation for ordinary presentation; does not extend or complete a lesson. */
    public static boolean hasActiveSession(SylphietteEntity sylphiette) { return observingPlayer(sylphiette) != null; }

    public static ServerPlayer observingPlayer(SylphietteEntity sylphiette) {
        Session session = BY_NPC.get(sylphiette);
        if (session == null || !valid(session.player, sylphiette)
                || session.player.distanceToSqr(sylphiette) > CAST_DISTANCE * CAST_DISTANCE
                || sylphiette.level().getGameTime() > session.expiresAt) return null;
        return session.player;
    }

    /** Called only after WaterMagic has admitted, paid, and spawned the player's normal water spell. */
    public static void onWaterCast(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (player.getAbilities().instabuild || player.isFakePlayer()
                || session == null || session.phase != Phase.INVITED || !valid(player, session.sylphiette)
                || player.distanceToSqr(session.sylphiette) > CAST_DISTANCE * CAST_DISTANCE) return;
        session.phase = Phase.DEMONSTRATED;
        session.attemptAt = player.serverLevel().getGameTime() + ATTEMPT_DELAY_TICKS;
        // A demonstration late in the invitation still gets its attempt: the session lasts until the attempt is over.
        session.expiresAt = Math.max(session.expiresAt, session.attemptAt + SylphietteLessonVisuals.ATTEMPT_TICKS);
        session.sylphiette.holdForLesson(player, session.expiresAt);
        player.sendSystemMessage(line("try"));
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (BY_PLAYER.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        for (Session session : BY_PLAYER.values().toArray(Session[]::new)) {
            ServerPlayer player = session.player;
            if (player == null || !valid(player, session.sylphiette)
                    || player.distanceToSqr(session.sylphiette) > CAST_DISTANCE * CAST_DISTANCE
                    || now > session.expiresAt) { remove(session); continue; }
            if (session.phase == Phase.DEMONSTRATED && now >= session.attemptAt) {
                session.phase = Phase.ATTEMPTING;
                session.attemptStart = now;
                session.sylphiette.triggerAnim(NpcAnimations.GESTURE_CONTROLLER, SylphietteEntity.ATTEMPT);
                dev.joblessreincarnation.compat.epic.NpcEpicGestures.attempt(session.sylphiette);
            }
            if (session.phase == Phase.ATTEMPTING && session.sylphiette.level() instanceof ServerLevel level) {
                int attemptTick = (int) (now - session.attemptStart);
                SylphietteLessonVisuals.attemptTick(level, session.sylphiette, attemptTick);
                if (attemptTick == SylphietteLessonVisuals.FINISH_TICK) finish(player);
                if (attemptTick >= SylphietteLessonVisuals.ATTEMPT_TICKS) remove(session);
            }
        }
    }

    private static void finish(ServerPlayer player) {
        player.sendSystemMessage(line("finish"));
        StoryAdvancements.onSylphietteFirstLesson(player);
    }

    /** "<name>: <line>" in green, like her other lines (SylphietteInteractions.line). */
    private static Component line(String key) {
        return Component.translatable(LINES + "line", Component.translatable("entity.jobless_reincarnation." + SylphietteEntity.ID),
                Component.translatable(LINES + "lesson." + key)).withStyle(ChatFormatting.GREEN);
    }

    private static boolean valid(ServerPlayer player, SylphietteEntity sylphiette) {
        return player != null && sylphiette != null && player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && sylphiette.isAlive() && !sylphiette.isRemoved() && player.level() == sylphiette.level()
                && sylphiette.level() instanceof ServerLevel level && level.getEntity(sylphiette.getUUID()) == sylphiette
                && level.getServer().isSameThread();
    }

    private static void remove(Session session) {
        BY_PLAYER.remove(session.player.getUUID(), session); BY_NPC.remove(session.sylphiette, session);
        session.sylphiette.releaseLesson(session.player);
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.stop(session.sylphiette,dev.joblessreincarnation.compat.epic.NpcGestureVisual.Kind.ATTEMPT);
    }

    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof SylphietteEntity sylphiette) {
            Session session = BY_NPC.get(sylphiette); if (session != null) remove(session);
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session session = BY_PLAYER.get(event.getEntity().getUUID()); if (session != null) remove(session);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { BY_PLAYER.clear(); BY_NPC.clear(); }

    private SylphietteFirstLesson() {}
}
