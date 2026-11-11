package dev.joblessreincarnation.advancement;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Personal milestones use vanilla advancement storage and packets, independent of gameplay rewards. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class StoryAdvancements {
    public static final ResourceLocation ROOT = id("root");
    public static final ResourceLocation PAUL_DUEL = id("paul_duel");
    public static void onPaulDuelWon(ServerPlayer player) { award(player, PAUL_DUEL, "won"); }
    public static final ResourceLocation GREYRAT_HOME = id("greyrat_home");
    public static final ResourceLocation FIRST_SPELL = id("first_spell");
    public static final ResourceLocation MIRE_SLOW = id("mire_slow");
    public static final ResourceLocation WATER_SHIELD_BLOCK = id("water_shield_block");
    public static final ResourceLocation FIRST_SWORD_LESSON = id("first_sword_lesson");
    public static final ResourceLocation MEET_SYLPHIETTE = id("meet_sylphiette");
    public static final ResourceLocation SYLPHIETTE_FIRST_LESSON = id("sylphiette_first_lesson");

    /**
     * Player data is restored before this event, and vanilla's first advancement flush follows it.
     * Keep awards synchronous so existing knowledge joins the reset packet without a new toast.
     * Visits and conversations have no durable history to infer, so only known spells are backfilled.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        award(player, ROOT, "joined");
        if (knowsCurrentSpell(player)) award(player, FIRST_SPELL, "learned");
    }

    /** Called only from a tome's successful learn branch, after its existing learning feedback. */
    public static void onSpellLearned(ServerPlayer player) {
        if (player.isAlive() && !player.isSpectator() && knowsCurrentSpell(player)) {
            award(player, FIRST_SPELL, "learned");
        }
    }

    /** Called after a valid private reply, never merely after consuming an interaction. */
    public static void onSylphietteConversation(ServerPlayer player) {
        if (player.isAlive() && !player.isSpectator()) award(player, MEET_SYLPHIETTE, "talked");
    }

    public static void onSylphietteFirstLesson(ServerPlayer player) {
        if (player.isAlive() && !player.isRemoved() && !player.isSpectator()) award(player, SYLPHIETTE_FIRST_LESSON, "guided");
    }

    public static boolean hasCompleted(ServerPlayer player, ResourceLocation id) {
        var advancement = player.serverLevel().getServer().getAdvancements().get(id);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    /** Called by an active water screen only after it actually absorbs an eligible arrow. */
    public static void onWaterShieldBlocked(ServerPlayer player) {
        if (player.isAlive() && !player.isRemoved() && !player.isSpectator()) {
            award(player, WATER_SHIELD_BLOCK, "blocked");
        }
    }

    /** Called only when this player's valid field contributes to an actually slowed eligible target. */
    public static void onMireSlowedTarget(ServerPlayer player) {
        if (player.isAlive() && !player.isRemoved() && !player.isSpectator()) award(player, MIRE_SLOW, "slowed");
    }

    /** Recognition is a real tutor event, never inferred at login from practice totals. */
    public static void onSwordLessonRecognized(ServerPlayer player) {
        if (player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && player.hasData(ModAttachments.SWORD_TRAINING)
                && player.getData(ModAttachments.SWORD_TRAINING).recognized()) {
            award(player, FIRST_SWORD_LESSON, "recognized");
        }
    }

    private static boolean knowsCurrentSpell(ServerPlayer player) {
        if (!player.hasData(ModAttachments.KNOWLEDGE)) return false;
        var knowledge = player.getData(ModAttachments.KNOWLEDGE);
        for (SpellCatalog spell : SpellCatalog.values()) {
            if (knowledge.knows(spell.id())) return true;
        }
        return false;
    }

    private static void award(ServerPlayer player, ResourceLocation id, String criterion) {
        if (player.isFakePlayer() || !player.serverLevel().getServer().isSameThread()) return;
        // Reload may replace or remove data-pack entries; never retain an AdvancementHolder.
        var advancement = player.serverLevel().getServer().getAdvancements().get(id);
        if (advancement != null && advancement.value().criteria().containsKey(criterion)) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "story/" + path);
    }

    private StoryAdvancements() {}
}
