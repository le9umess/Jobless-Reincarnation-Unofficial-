package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaReaderItem;
import dev.joblessreincarnation.training.SwordLessonGuidance;
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
 * Talking to a family member: a short private line of original adaptation text, in turn per person. The checks and
 * the consumed clicks follow {@link TutorInteractions}. Normal chat stays ephemeral; Paul's held-sword
 * branch keeps a private first-lesson record and only replies to the person asking.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class FamilyInteractions {
    public static final int REPLY_INTERVAL_TICKS = 20;
    public static final int LINES_PER_MEMBER = 3;
    private static final String PREFIX = "message.jobless_reincarnation.family.";

    /** Last reply time and the next line, per resident and person. Server-thread only; never saved. */
    private static final class Exchange {
        long lastReply;
        int nextLine;
    }

    private static final Map<FamilyResidentEntity, Map<UUID, Exchange>> EXCHANGES = new WeakHashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof FamilyResidentEntity resident) || ManaReaderItem.inMainHand(event.getEntity())
                || dev.joblessreincarnation.encounter.PaulDuel.combatUse(event.getEntity(),resident,event.getHand())) return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), resident, event.getHand());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void general(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof FamilyResidentEntity resident) || ManaReaderItem.inMainHand(event.getEntity())
                || dev.joblessreincarnation.encounter.PaulDuel.combatUse(event.getEntity(),resident,event.getHand())) return;
        // Even offhand/invalid/cooldown clicks consume this target; PASS could start the held book or wand.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), resident, event.getHand());
    }

    /** The line as anyone called Rudy hears it. */
    public static Component line(FamilyResidentEntity.Member member, int index) {
        return line(member, index, null);
    }

    /**
     * The line a person hears: "<name>: <line>" in the speaker's colour. A line that names them calls them what they chose
     * (N01): the short form ("Rudy") is the first argument, the full form (Lilia's "Rudeus") the second.
     */
    public static Component line(FamilyResidentEntity.Member member, int index, @org.jetbrains.annotations.Nullable Player listener) {
        Component shortName = listener == null ? Component.translatable("screen.jobless_reincarnation.naming.rudy_short")
                : dev.joblessreincarnation.naming.Naming.shortName(listener);
        Component fullName = listener == null ? Component.translatable("screen.jobless_reincarnation.naming.rudy_full")
                : dev.joblessreincarnation.naming.Naming.fullName(listener);
        ChatFormatting colour = switch (member) {
            case PAUL -> ChatFormatting.GOLD;
            case ZENITH -> ChatFormatting.YELLOW;
            case LILIA -> ChatFormatting.LIGHT_PURPLE;
        };
        return Component.translatable(PREFIX + "line", Component.translatable("entity.jobless_reincarnation." + member.id()),
                Component.translatable(PREFIX + member.id() + "." + index, shortName, fullName)).withStyle(colour);
    }

    private static void reply(Player actor, FamilyResidentEntity resident, InteractionHand hand) {
        if (!(actor instanceof ServerPlayer player) || hand != InteractionHand.MAIN_HAND
                || !(resident.level() instanceof ServerLevel level) || player.level() != level
                || level.getEntity(resident.getUUID()) != resident || !resident.isAlive() || resident.isRemoved()
                || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || !player.canInteractWithEntity(resident, 0.0) || !player.hasLineOfSight(resident)) return;
        if (dev.joblessreincarnation.encounter.PaulDuel.interact(player, resident)) return;
        long now = level.getGameTime();
        Map<UUID, Exchange> exchanges = EXCHANGES.computeIfAbsent(resident, ignored -> new HashMap<>());
        Exchange exchange = exchanges.get(player.getUUID());
        if (exchange != null && now >= exchange.lastReply && now - exchange.lastReply < REPLY_INTERVAL_TICKS) return;
        // A rejected duplicate must not extend the pause or steal another person's gaze.
        if (!resident.beginConversation(player)) return;
        if (exchange == null) exchanges.put(player.getUUID(), exchange = new Exchange());
        if (!SwordLessonGuidance.reply(player, resident)) {
            player.sendSystemMessage(line(resident.member(), exchange.nextLine, player));
            exchange.nextLine = (exchange.nextLine + 1) % LINES_PER_MEMBER;
            resident.tryBeginGreeting(player);
        }
        exchange.lastReply = now;
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof FamilyResidentEntity resident) EXCHANGES.remove(resident);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().level().isClientSide) return;
        UUID actor = event.getEntity().getUUID();
        EXCHANGES.values().forEach(exchanges -> exchanges.remove(actor));
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { EXCHANGES.clear(); }

    private FamilyInteractions() {}
}
