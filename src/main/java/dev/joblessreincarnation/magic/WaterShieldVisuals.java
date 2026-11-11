package dev.joblessreincarnation.magic;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Shared timing for how a water shield looks, and its client-side particles. Common code: it only calls
 * {@link Level#addParticle}, so the entity can call it on the logical client without touching client classes.
 * The screen rises from the ground (the original describes water spraying up from the ground to form the barrier),
 * holds, then thins and sinks over its last second.
 */
public final class WaterShieldVisuals {
    public static final float RISE_TICKS = 6.0F;
    public static final float FADE_TICKS = 20.0F;
    private static final float SINK = 0.35F;

    /** 0 to 1 while the water climbs out of the ground. */
    public static float rise(float age) {
        float t = Mth.clamp(age / RISE_TICKS, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** 1 until the last second, then down to 0 as the screen thins away. */
    public static float fade(float age) {
        return Mth.clamp((WaterShieldEntity.LIFETIME_TICKS - age) / FADE_TICKS, 0.0F, 1.0F);
    }

    /** Height of the visible water above the base point. */
    public static float visibleHeight(float age) {
        return (float) WaterShieldEntity.HEIGHT * rise(age) - (1.0F - fade(age)) * SINK;
    }

    /** Spray along the foot while it rises, then the odd drop from the crest and splash at the foot. */
    static void clientTick(WaterShieldEntity shield) {
        Level level = shield.level();
        RandomSource random = level.random;
        float age = shield.visualAge(0.0F);
        if (age >= WaterShieldEntity.LIFETIME_TICKS) return;
        Vec3 base = shield.position();
        Vec3 across = shield.across();
        Vec3 facing = shield.facing();
        double halfWidth = WaterShieldEntity.WIDTH * 0.5;
        boolean fading = fade(age) < 1.0F;
        if (age < RISE_TICKS) {
            for (int i = 0; i < 5; i++) {
                Vec3 point = base.add(across.scale((random.nextDouble() * 2.0 - 1.0) * halfWidth));
                level.addParticle(ParticleTypes.SPLASH, point.x, point.y + 0.05, point.z, 0.0, 0.2 + random.nextDouble() * 0.15, 0.0);
            }
            return;
        }
        int every = fading ? 4 : 2;
        if (shield.tickCount % every == 0) {
            double height = visibleHeight(age);
            Vec3 crest = base.add(across.scale((random.nextDouble() * 2.0 - 1.0) * (halfWidth - 0.1)))
                    .add(facing.scale((random.nextDouble() * 2.0 - 1.0) * 0.08));
            level.addParticle(ParticleTypes.FALLING_WATER, crest.x, crest.y + Math.max(0.1, height - 0.05), crest.z, 0.0, 0.0, 0.0);
        }
        if (shield.tickCount % (every + 1) == 0) {
            Vec3 foot = base.add(across.scale((random.nextDouble() * 2.0 - 1.0) * halfWidth));
            level.addParticle(ParticleTypes.SPLASH, foot.x, foot.y + 0.05, foot.z, 0.0, 0.0, 0.0);
        }
    }

    private WaterShieldVisuals() {}
}
