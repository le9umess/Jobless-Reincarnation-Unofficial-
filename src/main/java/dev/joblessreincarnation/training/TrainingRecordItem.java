package dev.joblessreincarnation.training;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A keepsake, with no rank, stat bonus, or claim authority attached to the item. */
public final class TrainingRecordItem extends Item {
    public TrainingRecordItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_record.memory").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_record.keepsake").withStyle(ChatFormatting.GRAY));
    }
}
