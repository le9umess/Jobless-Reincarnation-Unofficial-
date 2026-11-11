package dev.joblessreincarnation.training;

import dev.joblessreincarnation.magic.FireBallProjectile;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Minecraft teaching objectives; neither new canon ranks nor another reward claim. */
public final class FireTrainingTrials {
    public static final int CAST_GOAL = 5;
    public static final int HIT_GOAL = 3;
    public static final double MIN_RANGE = 4.0;

    /** Reading guidance must not create a progress attachment. */
    public static boolean completed(Player player) {
        return FireMagic.isLearned(player) && FireMagic.practiceCount(player) >= CAST_GOAL
                && player.hasData(ModAttachments.TRAINING)
                && player.getData(ModAttachments.TRAINING).fireTargetHits() >= HIT_GOAL;
    }

    /** Called only inside the projectile's accepted block-impact window; the receipt is consumed once. */
    public static void onTargetHit(FireBallProjectile ball, BlockHitResult hit) {
        Vec3 origin = ball.consumeTrainingOrigin(hit);
        if (origin == null || ball.level().isClientSide || !(ball.getOwner() instanceof ServerPlayer player)
                || !player.isAlive() || player.isRemoved() || player.isSpectator() || player.getAbilities().instabuild
                || player.level() != ball.level() || !FireMagic.isLearned(player)
                || !ball.level().getBlockState(hit.getBlockPos()).is(Blocks.TARGET)) return;
        double distanceSquared = origin.distanceToSqr(hit.getLocation());
        if (!Double.isFinite(distanceSquared) || distanceSquared < MIN_RANGE * MIN_RANGE) return;

        TrainingProgress progress = player.getData(ModAttachments.TRAINING);
        if (progress.recordFireTargetHit()) {
            player.displayClientMessage(Component.translatable(completed(player)
                    ? "message.jobless_reincarnation.fire_training_complete"
                    : "message.jobless_reincarnation.fire_training_target_hit", progress.fireTargetHits(), HIT_GOAL), true);
        }
    }

    private FireTrainingTrials() {}
}
