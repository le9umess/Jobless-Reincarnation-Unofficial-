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

/** Earth Wall knowledge are checked at both ends of the ordinary reusable-tome study. */
public final class StoneCannonTomeItem extends Item {
    public StoneCannonTomeItem(Properties properties) { super(properties); }

    public static boolean hasPrerequisites(LivingEntity entity) {
        return SpellCatalog.EARTH_WALL.knows(entity);
    }

    private static void explainPrerequisites(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable("message.jobless_reincarnation.stone_cannon.prerequisites",
                    SpellCatalog.EARTH_WALL.title()), true);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || !player.isAlive() || player.isSpectator())
            return InteractionResultHolder.pass(stack);
        // Already learned comes first: reviewing the book must preserve knowledge and practice.
        if (SpellCatalog.STONE_CANNON.knows(player)) {
            if (TomeFeedback.suppressClientUse(level)) return InteractionResultHolder.fail(stack);
            if (!TomeFeedback.shouldNotify(player, SpellCatalog.STONE_CANNON)) return InteractionResultHolder.consume(stack);
            if (player instanceof ServerPlayer serverPlayer)
                serverPlayer.displayClientMessage(Component.translatable("message.jobless_reincarnation.stone_cannon.already_learned",
                        SpellTraining.stage(SpellCatalog.STONE_CANNON.practiceCount(player)).title(),
                        SpellTraining.progressText(SpellCatalog.STONE_CANNON.practiceCount(player))), true);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!hasPrerequisites(player)) {
            explainPrerequisites(player);
            return InteractionResultHolder.fail(stack);
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
        if (!(entity instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()
                || player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || player.getMainHandItem() != stack || !stack.is(ModContent.STONE_CANNON_TOME.get())
                || SpellCatalog.STONE_CANNON.knows(player)) return stack;
        // Knowledge may have changed while the use animation was running; never trust the initial check.
        if (!hasPrerequisites(player)) {
            explainPrerequisites(player);
            return stack;
        }
        if (player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.STONE_CANNON.id())) {
            SpellCatalog.normalizeSelection(player);
            player.syncData(ModAttachments.KNOWLEDGE);
            TomeFeedback.markLearned(player, SpellCatalog.STONE_CANNON);
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.learned_stone_cannon"), true);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.35F, 1.3F);
            StoryAdvancements.onSpellLearned(player);
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.stone_cannon_tome.use").withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.stone_cannon_tome.effect").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.stone_cannon_tome.prerequisites",
                SpellCatalog.EARTH_WALL.title()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.water_tome.reusable").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.stone_cannon_tome.tutor").withStyle(ChatFormatting.GRAY));
    }
}
