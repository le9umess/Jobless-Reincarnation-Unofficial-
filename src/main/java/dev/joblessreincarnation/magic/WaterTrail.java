package dev.joblessreincarnation.magic;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** What a flying water ball sheds, by form: a loose lump spills drops everywhere, a tight drop a few, the slug a thin spun spray. Common code, on the logical client. */
final class WaterTrail {
    /** A fresh shot is still at the caster's face. */
    static final int FIRST_TICK = 3;

    static void emit(Level level, Vec3 at, Vec3 motion, float charge, int tickCount) { emit(level, at, motion, charge, tickCount, 1); }

    static void emit(Level level, Vec3 at, Vec3 motion, float charge, int tickCount, int stage) {
        RandomSource random = level.random;
        int drops = switch (stage) {
            case 0 -> 2 + (random.nextFloat() < 0.5F + charge * 0.5F ? 1 : 0);
            case 1 -> 1 + (random.nextFloat() < charge ? 1 : 0);
            default -> random.nextFloat() < 0.5F ? 1 : 0;
        };
        float spread = stage == 0 ? 0.32F : 0.2F;
        for (int i = 0; i < drops; i++) {
            double back = random.nextDouble();
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, 0.07F + random.nextFloat() * (stage == 0 ? 0.09F : 0.07F), 10 + random.nextInt(10)),
                    at.x - motion.x * back + (random.nextDouble() - 0.5) * spread, at.y - motion.y * back + (random.nextDouble() - 0.5) * spread, at.z - motion.z * back + (random.nextDouble() - 0.5) * spread,
                    motion.x * 0.12 + (random.nextDouble() - 0.5) * 0.05, motion.y * 0.12 + random.nextDouble() * 0.03, motion.z * 0.12 + (random.nextDouble() - 0.5) * 0.05);
        }
        if (stage == 0 && tickCount % 3 == 0) level.addParticle(ParticleTypes.SPLASH, at.x, at.y, at.z, 0, 0.05, 0);
        if (stage == 2) {
            // The slug's spin throws a fine spray off sideways in a turning screw.
            Vec3 along = motion.lengthSqr() > 1.0E-6 ? motion.normalize() : new Vec3(0, 0, 1);
            Vec3 side = along.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() > 1.0E-4 ? side.normalize() : new Vec3(1, 0, 0);
            Vec3 up = along.cross(side).normalize();
            for (int i = 0; i < 2; i++) {
                float angle = tickCount * 1.9F + i * Mth.PI;
                Vec3 out = side.scale(Mth.cos(angle)).add(up.scale(Mth.sin(angle)));
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.MIST, 0.16F + random.nextFloat() * 0.08F, 7 + random.nextInt(4)),
                        at.x - motion.x * 0.4 + out.x * 0.12, at.y - motion.y * 0.4 + out.y * 0.12, at.z - motion.z * 0.4 + out.z * 0.12,
                        out.x * 0.06 + motion.x * 0.05, out.y * 0.06, out.z * 0.06 + motion.z * 0.05);
            }
        } else if (tickCount % 2 == 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.MIST, 0.28F + random.nextFloat() * 0.16F, 8 + random.nextInt(6)), at.x - motion.x * 0.5, at.y - motion.y * 0.5, at.z - motion.z * 0.5, motion.x * 0.05, 0.0, motion.z * 0.05);
        }
    }

    private WaterTrail() {}
}
