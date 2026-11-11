package dev.joblessreincarnation.encounter;

import java.util.EnumSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

final class BearEncounterGoal extends Goal {
    private final RastaBearEntity bear;
    private Player opponent;
    private Vec3 origin;
    private boolean retreating;
    private long nextPathTick, nextScanTick, retreatUntil;
    private long nextGrowlTick;
    private int unseenTicks;

    BearEncounterGoal(RastaBearEntity bear) {
        this.bear = bear;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public boolean canUse() {
        long now = bear.level().getGameTime();
        if (now < nextScanTick || bear.hasEffect(MobEffects.BLINDNESS)) return false;
        nextScanTick = now + 5;
        double radius = bear.level().isNight() ? 5.0 : 8.0;
        opponent = bear.level().getNearestPlayer(TargetingConditions.forCombat().range(radius), bear);
        return opponent != null && bear.hasLineOfSight(opponent);
    }

    @Override public void start() {
        origin = bear.position();
        retreating = false;
        unseenTicks = 0;
        nextPathTick = 0;
        bear.setTarget(opponent);
    }

    @Override public boolean canContinueToUse() {
        return opponent != null && (!retreating
                || (bear.level().getGameTime() < retreatUntil && bear.position().distanceToSqr(origin) > 1.5));
    }

    @Override public void tick() {
        long now = bear.level().getGameTime();
        if (retreating) {
            if (bear.activePaw() || bear.visualPhase() == RastaBearEntity.Motion.HURT) return;
            if (now >= nextPathTick) {
                bear.getNavigation().moveTo(origin.x, origin.y, origin.z, .85);
                nextPathTick = now + 10;
            }
            return;
        }
        boolean visible = bear.hasLineOfSight(opponent);
        unseenTicks = visible ? 0 : unseenTicks + 1;
        if (!RastaBearEntity.eligible(opponent) || bear.getTarget() != opponent || bear.hasEffect(MobEffects.BLINDNESS)
                || bear.distanceToSqr(opponent) > 14 * 14 || opponent.position().distanceToSqr(origin) > 16 * 16 || unseenTicks >= 16) {
            retreating = true;
            retreatUntil = now + 100;
            nextPathTick = now;
            bear.setTarget(null);
            bear.getNavigation().stop();
            return;
        }
        if (bear.activePaw() || bear.visualPhase() == RastaBearEntity.Motion.HURT) {
            bear.getNavigation().stop();
            return;
        }
        bear.getLookControl().setLookAt(opponent, 24, 20);
        double rangeSqr = bear.distanceToSqr(opponent);
        // Leave the short approach to the paw warning. Reacquiring a target does not reset this timer.
        if (visible && rangeSqr > 4.5 * 4.5 && now >= nextGrowlTick) {
            bear.playSound(SoundEvents.POLAR_BEAR_AMBIENT, .62F, .64F);
            nextGrowlTick = now + 60 + bear.getRandom().nextInt(41);
        }
        if (visible && rangeSqr <= RastaBearEntity.START_RANGE_SQR) {
            bear.getNavigation().stop();
            if (bear.startPaw(opponent)) nextGrowlTick = Math.max(nextGrowlTick, now + 60);
        } else if (now >= nextPathTick) {
            bear.getNavigation().moveTo(opponent, bear.level().isNight() ? .85 : 1.0);
            nextPathTick = now + 5;
        }
    }

    @Override public void stop() {
        bear.setTarget(null);
        bear.getNavigation().stop();
        opponent = null;
        nextScanTick = bear.level().getGameTime() + 35;
    }
}
