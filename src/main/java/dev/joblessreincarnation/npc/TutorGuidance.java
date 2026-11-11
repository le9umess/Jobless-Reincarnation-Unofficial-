package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Read-only guidance over existing milestones. Dialogue is original adaptation text, not a canon quotation. */
public final class TutorGuidance {
    public enum Stage { LEARN, CAST, DISTANCE, SHAPING, CLAIM_AT_BOARD, COMPLETE }

    /** Detached values; neither a mutable attachment nor a reference to a player leaves this service. */
    public record Snapshot(Stage stage, boolean learned, int casts, int hits, boolean charged, boolean claimed) {}

    private static final String PREFIX = "message.jobless_reincarnation.tutor.";

    /** Calling this for a new player must not create default attachments or grant anything. */
    public static Snapshot snapshot(Player player) {
        MagicKnowledge knowledge = player.hasData(ModAttachments.KNOWLEDGE)
                ? player.getData(ModAttachments.KNOWLEDGE) : null;
        TrainingProgress progress = player.hasData(ModAttachments.TRAINING)
                ? player.getData(ModAttachments.TRAINING) : null;
        boolean learned = knowledge != null && knowledge.knows(WaterMagic.SPELL);
        int casts = knowledge == null ? 0 : knowledge.practiceCount(WaterMagic.SPELL);
        int hits = progress == null ? 0 : progress.targetHits();
        boolean charged = progress != null && progress.chargedTargetHit();
        boolean claimed = progress != null && progress.claimed();
        Stage stage = claimed ? Stage.COMPLETE : !learned ? Stage.LEARN
                : casts < TrainingTrials.CAST_GOAL ? Stage.CAST
                : hits < TrainingTrials.HIT_GOAL ? Stage.DISTANCE
                : !charged ? Stage.SHAPING : Stage.CLAIM_AT_BOARD;
        return new Snapshot(stage, learned, casts, hits, charged, claimed);
    }

    /** One private system-chat message: character dialogue, then a separate line of game rules. */
    public static Component message(Snapshot snapshot) {
        String suffix = snapshot.stage().name().toLowerCase(Locale.ROOT);
        String ruleKey = PREFIX + "rule." + suffix;
        Component rule = switch (snapshot.stage()) {
            case CAST -> Component.translatable(ruleKey, snapshot.casts(), TrainingTrials.CAST_GOAL);
            case DISTANCE -> Component.translatable(ruleKey,
                    snapshot.hits(), TrainingTrials.HIT_GOAL, TrainingTrials.MIN_RANGE);
            case SHAPING -> Component.translatable(ruleKey, TrainingTrials.MIN_RANGE);
            default -> Component.translatable(ruleKey);
        };
        return Component.translatable(PREFIX + "speaker").withStyle(ChatFormatting.AQUA)
                .append(Component.translatable(PREFIX + "dialogue." + suffix).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n"))
                .append(rule.copy().withStyle(ChatFormatting.GRAY));
    }

    private TutorGuidance() {}
}
