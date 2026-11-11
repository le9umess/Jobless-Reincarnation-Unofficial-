package dev.joblessreincarnation.training;

import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The board is a teaching aid; these milestones are mod-specific, not canon magic ranks. */
public final class TrainingTrials {
    public static final int CAST_GOAL = 5;
    public static final int HIT_GOAL = 3;
    public static final double MIN_RANGE = 4.0;

    public static boolean completed(Player player) {
        TrainingProgress progress = player.getData(ModAttachments.TRAINING);
        return WaterMagic.isLearned(player) && WaterMagic.practiceCount(player) >= CAST_GOAL
                && progress.targetHits() >= HIT_GOAL && progress.chargedTargetHit();
    }

    /** Called once for a block impact of a marked, non-creative WaterMagic cast. */
    public static void onTargetHit(WaterBolt bolt, BlockHitResult hit, Vec3 origin) {
        if (bolt.level().isClientSide || !(bolt.getOwner() instanceof ServerPlayer player)
                || !player.isAlive() || player.isSpectator() || player.getAbilities().instabuild
                || player.level() != bolt.level() || !WaterMagic.isLearned(player)
                || !bolt.level().getBlockState(hit.getBlockPos()).is(Blocks.TARGET)) return;
        double distanceSquared = origin.distanceToSqr(hit.getLocation());
        if (!Double.isFinite(distanceSquared) || distanceSquared < MIN_RANGE * MIN_RANGE) return;

        TrainingProgress progress = player.getData(ModAttachments.TRAINING);
        boolean firstCharged = !progress.chargedTargetHit() && bolt.charge() >= 1.0F;
        if (progress.recordTargetHit(bolt.charge() >= 1.0F)) {
            player.displayClientMessage(Component.translatable(firstCharged
                    ? "message.jobless_reincarnation.training_charged_hit"
                    : "message.jobless_reincarnation.training_target_hit", progress.targetHits(), HIT_GOAL), true);
        }
    }

    /** The menu checks board ownership/range as well. Full inventory keeps the reward unclaimed. */
    public static boolean claim(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return false;
        TrainingProgress progress = player.getData(ModAttachments.TRAINING);
        if (progress.claimed() || !completed(player)) return false;
        if (player.getInventory().getFreeSlot() < 0) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.training_inventory_full"), true);
            return false;
        }
        ItemStack reward = ModContent.TRAINING_RECORD.get().getDefaultInstance();
        if (!player.getInventory().add(reward)) return false;
        progress.claim();
        player.getInventory().setChanged();
        // The board has no inventory slots. While it is open, vanilla ignores window-0
        // single-slot updates outside the hotbar; a full window-0 snapshot reaches every slot.
        player.inventoryMenu.broadcastFullState();
        player.displayClientMessage(Component.translatable("message.jobless_reincarnation.training_claimed"), false);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.45F, 1.2F);
        return true;
    }

    private TrainingTrials() {}
}
