package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaReaderItem;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

/**
 * Talking to Sylphiette: a short private line of original adaptation text, in turn per person, as with the Greyrat
 * family ({@link FamilyInteractions}, left unchanged). A successful reply records the personal meeting milestone.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class SylphietteInteractions {
    public static final int REPLY_INTERVAL_TICKS = 20;
    public static final int LINES = 3;
    private static final String PREFIX = "message.jobless_reincarnation.sylphiette.";

    /** Last reply time and the next line, per Sylphiette and person. Server-thread only; never saved. */
    private static final class Exchange {
        long lastReply;
        int nextLine;
    }

    private static final Map<SylphietteEntity, Map<UUID, Exchange>> EXCHANGES = new WeakHashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof SylphietteEntity sylphiette) || ManaReaderItem.inMainHand(event.getEntity())) return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), sylphiette, event.getHand());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void general(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof SylphietteEntity sylphiette) || ManaReaderItem.inMainHand(event.getEntity())) return;
        // Even offhand/invalid/cooldown clicks consume this target; PASS could start the held book or wand.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), sylphiette, event.getHand());
    }

    /** The line a person hears: "<name>: <line>" in green. */
    public static Component line(int index) {
        return Component.translatable(PREFIX + "line", Component.translatable("entity.jobless_reincarnation." + SylphietteEntity.ID),
                Component.translatable(PREFIX + index)).withStyle(ChatFormatting.GREEN);
    }

    private static void reply(Player actor, SylphietteEntity sylphiette, InteractionHand hand) {
        if (!(actor instanceof ServerPlayer player) || hand != InteractionHand.MAIN_HAND
                || !(sylphiette.level() instanceof ServerLevel level) || player.level() != level
                || level.getEntity(sylphiette.getUUID()) != sylphiette || !sylphiette.isAlive() || sylphiette.isRemoved()
                || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || !player.canInteractWithEntity(sylphiette, 0.0) || !player.hasLineOfSight(sylphiette)) return;
        long now = level.getGameTime();
        Map<UUID, Exchange> exchanges = EXCHANGES.computeIfAbsent(sylphiette, ignored -> new HashMap<>());
        Exchange exchange = exchanges.get(player.getUUID());
        if (exchange != null && now >= exchange.lastReply && now - exchange.lastReply < REPLY_INTERVAL_TICKS) return;
        // A rejected duplicate must not extend the pause or steal another person's gaze.
        if (!sylphiette.beginConversation(player)) return;
        if (exchange == null) exchanges.put(player.getUUID(), exchange = new Exchange());
        player.sendSystemMessage(line(exchange.nextLine));
        StoryAdvancements.onSylphietteConversation(player);
        if (!SylphietteFirstLesson.beginObservation(player, sylphiette)) sylphiette.tryBeginGreeting(player);
        exchange.nextLine = (exchange.nextLine + 1) % LINES;
        exchange.lastReply = now;
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof SylphietteEntity sylphiette) EXCHANGES.remove(sylphiette);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().level().isClientSide) return;
        UUID actor = event.getEntity().getUUID();
        EXCHANGES.values().forEach(exchanges -> exchanges.remove(actor));
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { EXCHANGES.clear(); }

    private SylphietteInteractions() {}
}
