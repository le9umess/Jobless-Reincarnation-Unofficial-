package dev.joblessreincarnation.training;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/** An ordinary wooden sword in combat; practice must never sweep nearby living entities. */
public final class PracticeWoodenSwordItem extends SwordItem {
    public PracticeWoodenSwordItem(Item.Properties properties) {
        super(Tiers.WOOD, properties.attributes(SwordItem.createAttributes(Tiers.WOOD, 3, -2.4F)));
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        return ability != ItemAbilities.SWORD_SWEEP && super.canPerformAction(stack, ability);
    }
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.practice_wooden_sword.lesson").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.practice_wooden_sword.form").withStyle(ChatFormatting.GRAY));
    }
}
