package dev.joblessreincarnation.training;

import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Routed only after the existing family interaction has validated a real main-hand query. */
public final class SwordLessonGuidance {
    private static final String PREFIX = "message.jobless_reincarnation.sword_lesson.";

    public static boolean reply(ServerPlayer player, FamilyResidentEntity resident) {
        if (resident.member() != FamilyResidentEntity.Member.PAUL) return false;
        if (player.getMainHandItem().is(Items.PAPER) && !player.isFakePlayer() && player.hasData(ModAttachments.SWORD_TRAINING)
                && player.getData(ModAttachments.SWORD_TRAINING).recognized()) return offerFirstNote(player);
        if (!player.getMainHandItem().is(ModContent.PRACTICE_WOODEN_SWORD.get())) return false;
        if (player.isFakePlayer()) return true;
        SwordTrainingProgress progress = player.getData(ModAttachments.SWORD_TRAINING);
        String dialogue;
        String rule;
        Object[] arguments;
        switch (progress.phase()) {
            case NOT_STARTED -> {
                progress.start();
                dialogue = "start";
                rule = "start";
                arguments = new Object[] {SwordTrainingProgress.GOAL};
            }
            case PRACTICING -> {
                dialogue = "practicing";
                rule = "practicing";
                arguments = new Object[] {progress.hits(), SwordTrainingProgress.GOAL};
            }
            case READY -> {
                if (progress.recognize()) StoryAdvancements.onSwordLessonRecognized(player);
                dialogue = "ready";
                rule = "done";
                arguments = new Object[0];
            }
            case DONE -> {
                dialogue = "done";
                rule = "done";
                arguments = new Object[0];
            }
            default -> throw new IllegalStateException("Unknown sword lesson phase");
        }
        if (progress.recognized() && !progress.auraAwakened()) {
            rule = progress.auraDrill() ? "aura_combat" : "aura_start";
        }
        player.sendSystemMessage(Component.empty().append(Component.translatable(PREFIX + "speaker"))
                .append(Component.translatable(PREFIX + "dialogue." + dialogue)).withStyle(ChatFormatting.GOLD));
        var guidance = Component.translatable(PREFIX + "rule." + rule, arguments).withStyle(ChatFormatting.GRAY);
        if (progress.recognized() && !progress.firstNoteCopied()) guidance.append(Component.translatable("message.jobless_reincarnation.school.paper_hint"));
        player.sendSystemMessage(guidance);
        NpcAnimations.playSwordDemo(resident);
        return true;
    }

    private static boolean offerFirstNote(ServerPlayer player) {
        var progress = player.getData(ModAttachments.SWORD_TRAINING);
        if (progress.firstNoteCopied()) {
            player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.school.note_already"));
            return true;
        }
        var form = player.getData(ModAttachments.SCHOOL_COMBAT).offerNotebook(player.isShiftKeyDown());
        ItemStack note = new ItemStack(form.note());
        if (!player.isShiftKeyDown()) {
            player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.school.choose", note.getHoverName()));
            return true;
        }
        ItemStack paper = player.getMainHandItem();
        if (paper.getCount() == 1 && !player.getAbilities().instabuild) {
            player.setItemInHand(InteractionHand.MAIN_HAND, note);
        } else {
            if (player.getInventory().getFreeSlot() < 0 || !player.addItem(note)) {
                player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.school.need_space"));
                return true;
            }
            if (!player.getAbilities().instabuild) paper.shrink(1);
        }
        progress.copiedFirstNote();
        player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.school.copied", form.note().getDescription()));
        return true;
    }

    private SwordLessonGuidance() {}
}
