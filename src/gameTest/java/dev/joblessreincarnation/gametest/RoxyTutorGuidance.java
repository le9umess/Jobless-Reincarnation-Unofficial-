package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Original development teaching text; the milestones are the existing Minecraft adaptation. */
public final class RoxyTutorGuidance {
    public enum Stage { LEARN, CAST, DISTANCE, SHAPING, CLAIM_AT_BOARD, COMPLETE }

    /** Values only: no mutable attachment escapes, and reading an unknown player creates nothing. */
    public record Snapshot(Stage stage, boolean learned, int casts, int hits, boolean charged, boolean claimed) {}

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

    public static Component message(Snapshot snapshot) {
        String key = "message.jobless_reincarnation_dev.roxy_tutor.";
        Component next = switch (snapshot.stage()) {
            case LEARN -> Component.translatable(key + "learn");
            case CAST -> Component.translatable(key + "cast", snapshot.casts(), TrainingTrials.CAST_GOAL);
            case DISTANCE -> Component.translatable(key + "distance",
                    snapshot.hits(), TrainingTrials.HIT_GOAL, TrainingTrials.MIN_RANGE);
            case SHAPING -> Component.translatable(key + "shaping", TrainingTrials.MIN_RANGE);
            case CLAIM_AT_BOARD -> Component.translatable(key + "claim_at_board");
            case COMPLETE -> Component.translatable(key + "complete");
        };
        return Component.translatable(key + "marker").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" ").withStyle(ChatFormatting.RESET))
                .append(next.copy().withStyle(ChatFormatting.WHITE));
    }

    private RoxyTutorGuidance() {}
}
