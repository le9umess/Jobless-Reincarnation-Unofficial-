package dev.joblessreincarnation.magic;

import java.util.List;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public final class TrainingStaffItem extends Item {
    public TrainingStaffItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || !player.isAlive() || player.isSpectator()) return InteractionResultHolder.pass(stack);
        if(level.isClientSide){
            var locked=level.getEntitiesOfClass(dev.joblessreincarnation.npc.FamilyResidentEntity.class,player.getBoundingBox().inflate(32),n->n.blocksDuelMagic(player));
            if(!locked.isEmpty()){
                player.displayClientMessage(Component.translatable("duel.jobless_reincarnation.paul."+(locked.getFirst().duelStance()==1?"swords_only":"wait")),true);
                return InteractionResultHolder.fail(stack);
            }
        }
        SpellCatalog spell = SpellCatalog.selected(player);
        // A server rejection does not always clear a locally predicted use animation.
        if (level.isClientSide && (!spell.knows(player) || !player.hasData(ModAttachments.MANA)
                || (!player.getData(ModAttachments.MANA).canCast(spell.manaCost(0.0F), player.getAbilities().instabuild)
                        && !ManaDepletion.canOvercast(player, spell))
                || player.getCooldowns().isOnCooldown(this))) return InteractionResultHolder.fail(stack);
        if (player instanceof ServerPlayer serverPlayer && !SpellCasting.canBegin(serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        SpellCasting.predictBegin(player, stack);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return WaterControl.MAX_USE_TICKS; }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (entity instanceof ServerPlayer player) {
            SpellCasting.release(player, stack, getUseDuration(stack, entity) - timeLeft);
        }
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int remainingTicks) {
        if (entity instanceof Player player) SpellCasting.clearActive(player);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (!(level instanceof ServerLevel) || !entity.isAlive() || entity.isShiftKeyDown()) return;
        int usedTicks = getUseDuration(stack, entity) - remainingUseDuration;
        SpellCatalog spell = SpellCatalog.active(entity);
        if (spell == SpellCatalog.FIRE_BALL && entity instanceof ServerPlayer caster) {
            FireFocusEffects.onUseTick(caster, usedTicks);
            return;
        }
        if (spell == SpellCatalog.STONE_CANNON && entity instanceof ServerPlayer caster) {
            StoneFocusEffects.onUseTick(caster, usedTicks);
            return;
        }
        if (spell == SpellCatalog.WATER_BOLT && entity instanceof ServerPlayer caster) {
            WaterFocusEffects.onUseTick(caster, usedTicks);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_staff.description").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_staff.use").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_staff.control").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.training_staff.learning").withStyle(ChatFormatting.GRAY));
    }
}
