package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaReaderItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Ordinary entity interaction: no development binding, scene state, or client-authored progress. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class TutorInteractions {
    public static final int REPLY_INTERVAL_TICKS = 20;
    // Server-thread only. Removed entities and disconnected actors never retain cooldown entries.
    private static final Map<RoxyTutorEntity, Map<UUID, Long>> REPLIES = new WeakHashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof RoxyTutorEntity tutor) || ManaReaderItem.inMainHand(event.getEntity())) return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), tutor, event.getHand());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void general(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof RoxyTutorEntity tutor) || ManaReaderItem.inMainHand(event.getEntity())) return;
        // Even offhand/invalid/cooldown clicks consume this target; PASS could start the held book or wand.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), tutor, event.getHand());
    }

    private static void reply(Player actor, RoxyTutorEntity tutor, InteractionHand hand) {
        if (!(actor instanceof ServerPlayer player) || hand != InteractionHand.MAIN_HAND
                || !(tutor.level() instanceof ServerLevel level) || player.level() != level
                || level.getEntity(tutor.getUUID()) != tutor || !tutor.isAlive() || tutor.isRemoved()
                || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || !player.canInteractWithEntity(tutor, 0.0) || !player.hasLineOfSight(tutor)) return;
        long now = level.getGameTime();
        Map<UUID, Long> replies = REPLIES.get(tutor);
        Long last = replies == null ? null : replies.get(player.getUUID());
        if (last != null && now >= last && now - last < REPLY_INTERVAL_TICKS) return;
        // A rejected duplicate must not extend the conversation or steal another player's gaze.
        if (!tutor.beginConversation(player)) return;
        player.sendSystemMessage(TutorLessons.message(player));
        REPLIES.computeIfAbsent(tutor, ignored -> new HashMap<>()).put(player.getUUID(), now);
        // Teaching comes first: asking about a course ends a greeting under way. Anything else said while she greets gets
        // its private reply and lets her finish greeting the person she is facing.
        if (tutor.isGreeting() && !TutorLessons.asksAboutCourse(player)) return;
        if (NpcAnimations.playLecture(tutor)) tutor.cancelGreetingForLecture();
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof RoxyTutorEntity tutor) REPLIES.remove(tutor);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().level().isClientSide) return;
        UUID actor = event.getEntity().getUUID();
        REPLIES.values().forEach(replies -> replies.remove(actor));
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { REPLIES.clear(); }

    private TutorInteractions() {}
}