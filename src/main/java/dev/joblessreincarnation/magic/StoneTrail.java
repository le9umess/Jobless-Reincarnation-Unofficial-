package dev.joblessreincarnation.magic;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A line of dust hanging in the air behind a fast stone. The loose rock also drops grit; the drill spins its dust off in a screw. Common code, on the logical client. */
final class StoneTrail {
    /** A fresh shot is still at the caster's face. */
    static final int FIRST_TICK = 3;

    static void emit(Level level, Vec3 at, Vec3 motion) { emit(level, at, motion, 0, 1); }

    static void emit(Level level, Vec3 at, Vec3 motion, int tickCount, int stage) {
        RandomSource random = level.random;
        for (int i = 0; i < 2; i++) {
            double back = random.nextDouble();
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.22F + random.nextFloat() * 0.18F, 12 + random.nextInt(9)),
                    at.x - motion.x * back, at.y - motion.y * back, at.z - motion.z * back,
                    (random.nextDouble() - 0.5) * 0.02, 0.004, (random.nextDouble() - 0.5) * 0.02);
        }
        if (stage == 0 && tickCount % 2 == 0) {
            // Grit breaking off the cracked rock and falling away.
            double back = random.nextDouble();
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                    at.x - motion.x * back, at.y - motion.y * back, at.z - motion.z * back, motion.x * 0.05, 0.02, motion.z * 0.05);
        }
        if (stage == 2) {
            Vec3 along = motion.lengthSqr() > 1.0E-6 ? motion.normalize() : new Vec3(0, 0, 1);
            Vec3 side = along.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() > 1.0E-4 ? side.normalize() : new Vec3(1, 0, 0);
            Vec3 up = along.cross(side).normalize();
            for (int i = 0; i < 2; i++) {
                float angle = tickCount * 2.2F + i * Mth.PI;
                Vec3 out = side.scale(Mth.cos(angle)).add(up.scale(Mth.sin(angle)));
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.14F + random.nextFloat() * 0.08F, 9 + random.nextInt(5)),
                        at.x - motion.x * 0.3 + out.x * 0.14, at.y - motion.y * 0.3 + out.y * 0.14, at.z - motion.z * 0.3 + out.z * 0.14,
                        out.x * 0.07, out.y * 0.07, out.z * 0.07);
            }
        }
    }

    private StoneTrail() {}
}
