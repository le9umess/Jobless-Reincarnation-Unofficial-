package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public final class WaterShieldTomeItem extends Item {
    public WaterShieldTomeItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || !player.isAlive() || player.isSpectator())
            return InteractionResultHolder.pass(stack);
        if (WaterShieldSpell.isLearned(player)) {
            if (TomeFeedback.suppressClientUse(level)) return InteractionResultHolder.fail(stack);
            if (!TomeFeedback.shouldNotify(player, SpellCatalog.WATER_SHIELD)) return InteractionResultHolder.consume(stack);
            if (player instanceof ServerPlayer serverPlayer)
                serverPlayer.displayClientMessage(Component.translatable("message.jobless_reincarnation.water_shield.already_learned",
                        SpellTraining.stage(WaterShieldSpell.practiceCount(player)).title(),
                        SpellTraining.progressText(WaterShieldSpell.practiceCount(player))), true);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return MagicConfig.STUDY_TICKS.getAsInt(); }
    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player && player.isAlive() && !player.isSpectator()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(ModContent.WATER_SHIELD_TOME.get())
                && player.getData(ModAttachments.KNOWLEDGE).learn(WaterShieldSpell.SPELL)) {
            SpellCatalog.normalizeSelection(player);
            player.syncData(ModAttachments.KNOWLEDGE);
            TomeFeedback.markLearned(player, SpellCatalog.WATER_SHIELD);
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.learned_water_shield"), true);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.35F, 1.3F);
            StoryAdvancements.onSpellLearned(player);
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.water_shield_tome.use").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.water_shield_tome.shield").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.water_tome.reusable").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.water_shield_tome.tutor").withStyle(ChatFormatting.GRAY));
    }
}
