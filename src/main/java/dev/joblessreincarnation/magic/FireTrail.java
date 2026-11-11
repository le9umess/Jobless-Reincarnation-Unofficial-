package dev.joblessreincarnation.magic;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** What a flying fire ball sheds behind it, by form: the novice's smokes, the adept's throws more sparks and hardly smokes. Common code: it only calls Level#addParticle, on the logical client. */
final class FireTrail {
    /** A fresh shot is still at the caster's face. */
    static final int FIRST_TICK = 3;

    static void emit(Level level, Vec3 at, Vec3 motion, int tickCount) { emit(level, at, motion, tickCount, 1); }

    static void emit(Level level, Vec3 at, Vec3 motion, int tickCount, int stage) {
        RandomSource random = level.random;
        for (int i = 0, sparks = stage == 2 ? 2 : 1; i < sparks; i++) {
            // Spread along the step just travelled, so a fast shot leaves a line of sparks and not dots.
            double back = random.nextDouble();
            double x = at.x - motion.x * back, y = at.y - motion.y * back, z = at.z - motion.z * back;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.06F + random.nextFloat() * 0.06F, 8 + random.nextInt(10)),
                    x + (random.nextDouble() - 0.5) * 0.2, y + (random.nextDouble() - 0.5) * 0.2, z + (random.nextDouble() - 0.5) * 0.2,
                    -motion.x * 0.04 + (random.nextDouble() - 0.5) * 0.05, 0.02 + random.nextDouble() * 0.03, -motion.z * 0.04 + (random.nextDouble() - 0.5) * 0.05);
        }
        double back = random.nextDouble();
        double x = at.x - motion.x * back, y = at.y - motion.y * back, z = at.z - motion.z * back;
        if (stage == 2 || tickCount % 2 == 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAME, (stage == 0 ? 0.16F : 0.2F) + random.nextFloat() * 0.12F, 5 + random.nextInt(3)),
                    x, y, z, motion.x * 0.05, 0.01, motion.z * 0.05);
        }
        int smokeEvery = stage == 0 ? 2 : stage == 1 ? 4 : 8;
        if (tickCount % smokeEvery == 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.SMOKE, (stage == 0 ? 0.34F : 0.28F) + random.nextFloat() * 0.14F, 12 + random.nextInt(8)),
                    x, y, z, 0, 0.012, 0);
        }
    }

    private FireTrail() {}
}
