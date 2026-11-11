package dev.joblessreincarnation.npc;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * How Sylphiette's first-lesson attempt looks and sounds, in step with animation.sylphiette.attempt. Server side only:
 * the particles and sounds go to everyone nearby through ServerLevel, so a friend watching sees the same attempt.
 *
 * Ticks count from the tick the gesture is triggered. GeckoLib blends the gesture in over TRANSITION_TICKS (4), so clip time t plays
 * at tick 4 + 20t: her hands are up at chest height from tick 12, she chants until tick 30, lifts them by tick 33 and has
 * settled back by tick 44. While she chants, small blue specks of mana appear between her hands, drawing closer
 * together as the chant goes on; when she lifts her hands, only a little water comes of it: a small splash, a couple of
 * drops and bubbles, a soft drip. That is the lesson as the original tells it (011 P130: chanted basics first), not a
 * failure to be fixed later.
 */
public final class SylphietteLessonVisuals {
    /** GeckoLib's blend into the gesture, in ticks; the gesture controller on SylphietteEntity uses the same value. */
    public static final int TRANSITION_TICKS = 4;
    /** First and last tick of the chant, when the specks of mana gather. */
    public static final int GATHER_FROM = 12, GATHER_TO = 30;
    /** The tick her hands reach the top of the lift and the little water appears. */
    public static final int RESULT_TICK = 33;
    /** A good tick for her closing line: the splash has just happened and she is lowering her hands. */
    public static final int FINISH_TICK = 40;
    /** The gesture is over: she is back in her idle pose. */
    public static final int ATTEMPT_TICKS = 44;
    /**
     * Midpoint between her hands, in blocks from her feet along her facing and up, measured on the posed model by
     * check-sylphiette-attempt.mjs: while she chants, and at the top of the lift. The hands are about half a block apart.
     */
    private static final double RESULT_FORWARD = 0.351, RESULT_UP = 0.691;
    private static final double HAND_HALF_SPAN = 0.24;

    /** Server side, once for every tick of the attempt; tick 0 is the tick the gesture was triggered. */
    public static void attemptTick(ServerLevel level, SylphietteEntity sylphiette, int tick) {
        if (tick < GATHER_FROM || tick > RESULT_TICK) return;
        RandomSource random = level.random;
        Vec3 forward = Vec3.directionFromRotation(0.0F, sylphiette.yBodyRot);
        Vec3 side = new Vec3(-forward.z, 0.0, forward.x);
        if (tick <= GATHER_TO) {
            if (tick % 2 != 0) return;
            // The specks start out by her hands and appear ever nearer the middle; this particle hardly drifts, so the
            // gathering is drawn by where new ones appear, not by moving them.
            double progress = (tick - GATHER_FROM) / (double) (GATHER_TO - GATHER_FROM);
            double radius = Mth.lerp(progress, HAND_HALF_SPAN, 0.05);
            // Follow the measured midpoint of the new 0.53-scale rig through the gathering pose.
            double clipSeconds=(tick-TRANSITION_TICKS)/20.0;
            double t=clipSeconds<=1.0?Mth.clamp((clipSeconds-.4)/.6,0,1):Mth.clamp((clipSeconds-1.0)/.45,0,1);
            double ahead=clipSeconds<=1.0?Mth.lerp(t,.210,.323):Mth.lerp(t,.323,RESULT_FORWARD);
            double up=clipSeconds<=1.0?Mth.lerp(t,.710,.659):Mth.lerp(t,.659,RESULT_UP);
            Vec3 centre = between(sylphiette, forward, ahead, up);
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                Vec3 point = centre.add(side.scale(Math.cos(angle) * radius)).add(0.0, Math.sin(angle) * radius * 0.6, 0.0);
                level.sendParticles(ParticleTypes.DOLPHIN, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            return;
        }
        if (tick == RESULT_TICK) {
            Vec3 centre = between(sylphiette, forward, RESULT_FORWARD, RESULT_UP);
            // Most of the tiny first attempt drips from her hands; limit upward splash so it does not cover her eyes.
            level.sendParticles(ParticleTypes.SPLASH, centre.x, centre.y, centre.z, 1, 0.05, 0.02, 0.05, 0.0);
            level.sendParticles(ParticleTypes.BUBBLE_POP, centre.x, centre.y, centre.z, 2, 0.05, 0.03, 0.05, 0.01);
            level.sendParticles(ParticleTypes.FALLING_WATER, centre.x, centre.y - 0.05, centre.z, 4, 0.05, 0.02, 0.05, 0.0);
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, SoundSource.NEUTRAL, 0.8F, 1.0F);
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.PLAYER_SPLASH, SoundSource.NEUTRAL, 0.2F, 1.7F);
        }
    }

    private static Vec3 between(SylphietteEntity sylphiette, Vec3 forward, double ahead, double up) {
        return sylphiette.position().add(forward.scale(ahead)).add(0.0, up, 0.0);
    }

    private SylphietteLessonVisuals() {}
}
