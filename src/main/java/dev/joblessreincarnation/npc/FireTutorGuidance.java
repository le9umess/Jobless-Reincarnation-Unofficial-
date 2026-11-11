package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.FireTrainingTrials;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Read-only fire lesson. These milestones are game adaptation, not a canon rank or a new reward track. */
public final class FireTutorGuidance {
    public enum Stage { LEARN, CAST, DISTANCE, COMPLETE }

    /** Detached counts, with no attachment or entity reference exposed to a caller. */
    public record Snapshot(Stage stage, boolean learned, int casts, int hits) {}

    private static final String PREFIX = "message.jobless_reincarnation.fire_tutor.";

    /** The book opens its lesson before learning; a wand follows selection without changing it. */
    public static boolean isFireLesson(Player player) {
        return player.getMainHandItem().is(ModContent.FIRE_TOME.get())
                || (player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                && player.hasData(ModAttachments.CASTING)
                && player.getData(ModAttachments.CASTING).selectedSpell().equals(FireMagic.SPELL));
    }

    /** Missing data is observed as zero, never initialized by a consultation. */
    public static Snapshot snapshot(Player player) {
        boolean learned = FireMagic.isLearned(player);
        int casts = FireMagic.practiceCount(player);
        int hits = player.hasData(ModAttachments.TRAINING)
                ? player.getData(ModAttachments.TRAINING).fireTargetHits() : 0;
        Stage stage = !learned ? Stage.LEARN : casts < FireTrainingTrials.CAST_GOAL ? Stage.CAST
                : hits < FireTrainingTrials.HIT_GOAL ? Stage.DISTANCE : Stage.COMPLETE;
        return new Snapshot(stage, learned, casts, hits);
    }

    /** One private native chat component: named lesson and original dialogue, then actual game rules. */
    public static Component message(Snapshot snapshot) {
        String suffix = snapshot.stage().name().toLowerCase(Locale.ROOT);
        String ruleKey = PREFIX + "rule." + suffix;
        Component rule = switch (snapshot.stage()) {
            case CAST -> Component.translatable(ruleKey, snapshot.casts(), FireTrainingTrials.CAST_GOAL);
            case DISTANCE -> Component.translatable(ruleKey,
                    snapshot.hits(), FireTrainingTrials.HIT_GOAL, FireTrainingTrials.MIN_RANGE);
            default -> Component.translatable(ruleKey);
        };
        return Component.translatable(PREFIX + "speaker").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable(PREFIX + "dialogue." + suffix).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"))
                .append(rule.copy().withStyle(ChatFormatting.GRAY));
    }

    private FireTutorGuidance() {}
}
