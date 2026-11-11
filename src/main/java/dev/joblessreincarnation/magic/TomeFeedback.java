package dev.joblessreincarnation.magic;

import com.google.common.collect.MapMaker;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Transient, per-player and per-spell feedback; it never blocks item use or changes saved progress. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class TomeFeedback {
    public static final int INTERVAL_TICKS = 40;
    // Weak identity keys keep client/server and respawn instances separate, even when entity IDs match.
    // Each value is used only on that player's logical thread and holds no reference back to its player.
    private static final Map<Player, EnumMap<SpellCatalog, Long>> LAST_FEEDBACK =
            new MapMaker().weakKeys().makeMap();

    public static boolean shouldNotify(Player player, SpellCatalog spell) {
        long now = now(player);
        EnumMap<SpellCatalog, Long> feedback = LAST_FEEDBACK.get(player);
        Long last = feedback == null ? null : feedback.get(spell);
        if (last != null && now >= last && now - last < INTERVAL_TICKS) return false;
        // Only accepted feedback advances the window; a clock rollback starts a fresh one.
        LAST_FEEDBACK.computeIfAbsent(player, ignored -> new EnumMap<>(SpellCatalog.class)).put(spell, now);
        return true;
    }

    /**
     * With the optional Punchy! client mod, a consumed use on the client starts its reading clip although nothing is studied.
     * The use packet is already on its way by then, so the server's "already learned" message is unaffected.
     */
    public static boolean suppressClientUse(Level level) {
        return level.isClientSide && ModList.get().isLoaded("punchy");
    }

    /** Call only after study succeeds; the success message itself must always be sent. */
    public static void markLearned(Player player, SpellCatalog spell) {
        LAST_FEEDBACK.computeIfAbsent(player, ignored -> new EnumMap<>(SpellCatalog.class)).put(spell, now(player));
        if (player instanceof ServerPlayer serverPlayer
                && serverPlayer.getData(ModAttachments.KNOWLEDGE).snapshot().size() == 2) {
            serverPlayer.sendSystemMessage(Component.translatable("message.jobless_reincarnation.recall_hint",
                    Component.keybind("key.jobless_reincarnation.cycle_spell")));
        }
    }

    private static long now(Player player) {
        return player instanceof ServerPlayer serverPlayer
                ? serverPlayer.server.overworld().getGameTime()
                : player.level().getGameTime();
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_FEEDBACK.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LAST_FEEDBACK.clear();
    }

    private TomeFeedback() {}
}
