package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Places one validated entity directly; arbitrary spawn-egg EntityTag data is never applied. */
public final class PracticeDummyItem extends Item {
    public PracticeDummyItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND || context.getClickedFace() != Direction.UP) {
            if (context.getPlayer() instanceof ServerPlayer player) SwordPractice.feedback(player, "cannot_place");
            return InteractionResult.FAIL;
        }
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getPlayer() instanceof ServerPlayer player)
                || !SwordPractice.validActor(player, level)) return InteractionResult.FAIL;

        ItemStack stack = context.getItemInHand();
        BlockPos support = context.getClickedPos();
        BlockPos placement = support.above();
        if (!stack.is(this) || stack.isEmpty() || !PracticeDummyEntity.allowsBuilding(player)
                || !level.mayInteract(player, support) || !level.mayInteract(player, placement)
                || !player.mayUseItemAt(placement, Direction.UP, stack)
                || !player.canInteractWithBlock(support, 0.0)) {
            SwordPractice.feedback(player, "not_allowed");
            return InteractionResult.FAIL;
        }

        Vec3 base = Vec3.atBottomCenterOf(placement);
        AABB box = ModContent.PRACTICE_DUMMY.get().getDimensions().makeBoundingBox(base.x, base.y, base.z);
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box)
                || !PracticeDummyEntity.chunksReady(level, box)) {
            SwordPractice.feedback(player, "cannot_place");
            return InteractionResult.FAIL;
        }
        if (!PracticeDummyEntity.validSupport(level, support) || !PracticeDummyEntity.dryBox(level, box)) {
            SwordPractice.feedback(player, "no_support");
            return InteractionResult.FAIL;
        }
        if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
            SwordPractice.feedback(player, "cannot_place");
            return InteractionResult.FAIL;
        }

        PracticeDummyEntity dummy = ModContent.PRACTICE_DUMMY.get().create(level);
        if (dummy == null) {
            SwordPractice.feedback(player, "cannot_place");
            return InteractionResult.FAIL;
        }
        double dx = player.getX() - base.x;
        double dz = player.getZ() - base.z;
        float yaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        dummy.moveTo(base.x, base.y, base.z, Mth.wrapDegrees(yaw), 0.0F);
        dummy.setOwnerId(player.getUUID());
        boolean admitted;
        try {
            admitted = level.addFreshEntity(dummy);
        } catch (RuntimeException failure) {
            // An onAddedToLevel hook may fail after insertion: still pay for a successfully admitted entity.
            admitted = level.getEntity(dummy.getUUID()) == dummy && !dummy.isRemoved();
            JoblessReincarnation.LOGGER.warn("Could not finish placing a practice dummy at {}", base, failure);
        }
        if (!admitted || dummy.isRemoved() || level.getEntity(dummy.getUUID()) != dummy) {
            dummy.discard();
            SwordPractice.feedback(player, "cannot_place");
            return InteractionResult.FAIL;
        }

        if (!player.isCreative()) stack.shrink(1);
        player.getInventory().setChanged();
        level.playSound(null, base.x, base.y, base.z, SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.75F, 0.9F);
        dummy.gameEvent(GameEvent.ENTITY_PLACE, player);
        SwordPractice.feedback(player, "placed");
        return InteractionResult.CONSUME;
    }
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.practice_dummy.place").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.jobless_reincarnation.practice_dummy.take").withStyle(ChatFormatting.GRAY));
    }
}
