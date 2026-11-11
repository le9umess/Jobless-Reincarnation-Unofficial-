package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellBurstOptions;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turns one server impact into a few short-lived sprites on this client. Apart from the air wave, it makes no particle of its own.
 * The three attack spells' impacts follow the caster's form (the burst's stage); where the server says a burst reached around
 * (its radius), a ring lies flat on the ground and spreads exactly that far. With no radius, no ring: nothing was reached.
 */
public final class SpellBurst implements ParticleProvider<SpellBurstOptions> {
    @Override public Particle createParticle(SpellBurstOptions options, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
        switch (options.kind()) {
            case SpellBurstOptions.FIRE_QUENCHED -> fireQuenched(level, x, y, z, options.stage());
            case SpellBurstOptions.WATER_IMPACT -> waterImpact(level, x, y, z, (float) dx, options.stage(), options.radius());
            // Each point a drill bores through comes as its own burst, numbered from 0 in the order it struck.
            case SpellBurstOptions.STONE_IMPACT -> stoneImpact(level, x, y, z, options.stage(), options.radius(), options.impactIndex());
            case SpellBurstOptions.AIR_WAVE -> {
                return new AirWaveParticle(level, x, y, z, dx, dy, dz);
            }
            case SpellBurstOptions.OVERCAST -> overcast(level, options.caster(), x, y, z);
            case SpellBurstOptions.TEAR_ASH, SpellBurstOptions.TEAR_ASH_GUARDED -> tearAsh(level, options, x, y, z);
            case SpellBurstOptions.FLOW_OPENING -> flowOpening(level, options.caster(), x, y, z);
            case SpellBurstOptions.LIGHT_CUT -> lightCut(level, x, y, z);
            case SpellBurstOptions.ARM_BREAK -> armBreak(level, options.caster(), options.stage(), x, y, z);
            case SpellBurstOptions.LIGHT_DASH -> {
                return new LightDashTrail(level, options.caster(), options.stage(), x, y, z);
            }
            case SpellBurstOptions.STONE_FOCUS -> {
                return StoneFocus.gather(level, options.caster(), x, y, z, dx, dy, dz, options.stage());
            }
            case SpellBurstOptions.WATER_FOCUS -> {
                return WaterFocus.gather(level, options.caster(), x, y, z, dx, dy, dz, options.stage());
            }
            default -> fireImpact(level, x, y, z, options.stage(), options.radius());
        }
        return null;
    }

    /**
     * A brief flash, flames that lick outward and go out, embers thrown clear, then a little smoke. The novice's is smaller and
     * dirtier and sputters; the adept's flares white and throws a ring of fire out along the ground as far as it burned.
     */
    private static void fireImpact(ClientLevel level, double x, double y, double z, int stage, float radius) {
        RandomSource random = level.random;
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.GLOW, stage == 0 ? 1.4F : stage == 1 ? 2.0F : 2.8F, 7), x, y, z, 0, 0, 0);
        for (int i = 0, flames = stage == 0 ? 5 : 9; i < flames; i++) {
            double[] v = direction(random, 0.06 + random.nextDouble() * 0.08);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAME, (stage == 0 ? 0.45F : 0.6F) + random.nextFloat() * 0.45F, 8 + random.nextInt(6)), x, y, z, v[0], v[1], v[2]);
        }
        for (int i = 0, embers = stage == 0 ? 12 : stage == 1 ? 22 : 36; i < embers; i++) {
            double[] v = direction(random, 0.12 + random.nextDouble() * (stage == 2 ? 0.28 : 0.2));
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.07F + random.nextFloat() * 0.06F, 10 + random.nextInt(14)), x, y, z, v[0], v[1] + 0.06, v[2]);
        }
        for (int i = 0, smoke = stage == 0 ? 9 : stage == 1 ? 6 : 4; i < smoke; i++) {
            double[] v = direction(random, 0.025);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.SMOKE, 0.8F + random.nextFloat() * 0.5F, 22 + random.nextInt(14)), x, y + 0.1, z, v[0], v[1] + 0.025, v[2]);
        }
        if (stage == 0) sound(level, x, y, z, SoundEvents.FIRE_EXTINGUISH, 0.12F, 1.9F);
        if (radius > 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAT_RING_FIRE, radius, 10), true, x, ground(level, x, y, z), z, 0, 0, 0);
            // Flames running out along the ground, reaching the edge as they die. A flame keeps 0.9 of its speed each tick.
            for (int i = 0; i < 18; i++) {
                float angle = i * Mth.TWO_PI / 18 + random.nextFloat() * 0.2F;
                double speed = radius * 0.1 * (0.85 + random.nextDouble() * 0.2);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAME, 0.35F + random.nextFloat() * 0.25F, 10 + random.nextInt(4)), x, y + 0.1, z,
                        Mth.cos(angle) * speed, 0.01, Mth.sin(angle) * speed);
            }
            sound(level, x, y, z, SoundEvents.BLAZE_SHOOT, 0.55F, 0.72F);
        }
    }

    /** A black pouch bursting in the face (60 P394): a dirty puff of ash that hangs for a moment and sinks. No flash, no fire. */
    /** The server names whose eyes it reached (or -1); only that player gets the film, for as long as the hit actually blinds. */
    private static void tearAsh(ClientLevel level, SpellBurstOptions options, double x, double y, double z) {
        RandomSource random = level.random;
        level.playLocalSound(x, y, z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 0.8F, 1.3F, false);
        var self = Minecraft.getInstance().player;
        if (self != null && self.getId() == options.caster()) {
            dev.joblessreincarnation.client.aura.AshOverlay.hit(self, options.kind() == SpellBurstOptions.TEAR_ASH_GUARDED ? 6 : 16);
        }
        for (int i = 0; i < 16; i++) {
            double[] v = direction(random, 0.05 + random.nextDouble() * 0.09);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.28F + random.nextFloat() * 0.3F, 16 + random.nextInt(12)), x, y, z, v[0], v[1] * 0.6, v[2]);
        }
        for (int i = 0; i < 7; i++) {
            double[] v = direction(random, 0.03);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.SMOKE, 0.5F + random.nextFloat() * 0.35F, 14 + random.nextInt(10)), x, y, z, v[0], v[1], v[2]);
        }
    }

    /** A clean deflection has just opened the counter: a small ring and a few drops at the blade. Deliberately small. */
    private static void flowOpening(ClientLevel level, int caster, double x, double y, double z) {
        RandomSource random = level.random;
        var self = Minecraft.getInstance().player;
        if (self != null && self.getId() == caster) dev.joblessreincarnation.client.aura.FormHud.counterOpened(self);
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.RING_WATER, 0.55F, 6), x, y, z, 0, 0, 0);
        for (int i = 0; i < 8; i++) {
            double[] v = direction(random, 0.05 + random.nextDouble() * 0.05);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, 0.05F + random.nextFloat() * 0.04F, 8 + random.nextInt(5)), x, y, z, v[0], Math.abs(v[1]) * 0.7, v[2]);
        }
    }

    /**
     * Arm Strike landing inside the opponent's windup: a hard white-gold flash with a slanted streak where the blade met the
     * arm, sparks thrown out, a short metal clang over a critical-hit crack, and for the striker "Interrupted!" for a moment.
     * It is meant to be unmistakable, because the attack it stopped otherwise just never comes.
     */
    private static void armBreak(ClientLevel level, int caster, int stage, double x, double y, double z) {
        RandomSource random = level.random;
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.GLOW, 1.7F, 5), x, y, z, 0, 0, 0);
        for (int i = -4; i <= 4; i++) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 0.3F * (1 - Math.abs(i) / 5.5F), 4), x + i * 0.08, y - i * 0.06, z, 0, 0, 0);
        }
        for (int i = 0; i < 16; i++) {
            double[] v = direction(random, 0.16 + random.nextDouble() * 0.14);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.07F + random.nextFloat() * 0.05F, 7 + random.nextInt(6)), x, y, z, v[0], v[1] * 0.6 + 0.06, v[2]);
        }
        level.playLocalSound(x, y, z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.3F, 1.85F + random.nextFloat() * 0.1F, false);
        level.playLocalSound(x, y, z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.9F, 1.15F, false);
        var self = Minecraft.getInstance().player;
        // Stage 1: the opponent's earlier strike already had its chance; this caught the next one, it did not undo the first.
        if (self != null && self.getId() == caster) self.displayClientMessage(Component.translatable(
                stage == 1 ? "message.jobless_reincarnation.arm.interrupt_followup" : "message.jobless_reincarnation.arm.interrupt"), true);
    }

    /** Light Blade connecting: a thin upright line of light for an instant and a few sparks thrown down along the cut. Not a blast. */
    private static void lightCut(ClientLevel level, double x, double y, double z) {
        RandomSource random = level.random;
        for (int i = -5; i <= 5; i++) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 0.2F * (1 - Math.abs(i) / 6.5F), 3), x, y + i * 0.13, z, 0, 0, 0);
        }
        for (int i = 0; i < 10; i++) {
            double[] v = direction(random, 0.1 + random.nextDouble() * 0.12);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.06F + random.nextFloat() * 0.05F, 7 + random.nextInt(6)), x, y, z, v[0] * 0.4, v[1] - 0.1, v[2] * 0.4);
        }
    }

    /** Hitting water: no flash and no fire, only steam going up; more of it from a hotter fire. */
    private static void fireQuenched(ClientLevel level, double x, double y, double z, int stage) {
        RandomSource random = level.random;
        for (int i = 0, puffs = stage == 0 ? 6 : stage == 1 ? 8 : 12; i < puffs; i++) {
            double[] v = direction(random, 0.03);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.STEAM, 0.45F + random.nextFloat() * 0.4F, 14 + random.nextInt(10)), x, y, z, v[0], Math.abs(v[1]) + 0.03, v[2]);
        }
    }

    /**
     * A short crown: a ring of spray, drops thrown up and out, and mist. A fuller charge throws more. The novice's water just
     * slops apart; the adept's bursts and a sheet of water runs out flat as far as it reached.
     */
    private static void waterImpact(ClientLevel level, double x, double y, double z, float charge, int stage, float radius) {
        RandomSource random = level.random;
        if (stage == 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.RING_WATER, 0.7F + charge * 0.3F, 7), x, y, z, 0, 0, 0);
            for (int i = 0, drops = 12 + Math.round(charge * 8); i < drops; i++) {
                double[] v = direction(random, 0.08 + random.nextDouble() * 0.1);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, 0.06F + random.nextFloat() * 0.07F, 10 + random.nextInt(10)), x, y, z, v[0], Math.abs(v[1]) * 0.5 + 0.04, v[2]);
            }
            for (int i = 0; i < 3; i++) {
                double[] v = direction(random, 0.03);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.MIST, 0.5F + random.nextFloat() * 0.3F, 9 + random.nextInt(6)), x, y, z, v[0], Math.abs(v[1]), v[2]);
            }
            for (int i = 0; i < 4; i++) level.addParticle(ParticleTypes.SPLASH, x, y, z, 0.0, 0.08, 0.0);
            return;
        }
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.RING_WATER, 1.15F + charge * 0.6F, 8), x, y, z, 0, 0, 0);
        for (int i = 0, drops = 24 + Math.round(charge * 16); i < drops; i++) {
            double[] v = direction(random, 0.14 + random.nextDouble() * 0.2);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, 0.07F + random.nextFloat() * 0.09F, 12 + random.nextInt(12)), x, y, z, v[0], Math.abs(v[1]) + 0.1, v[2]);
        }
        for (int i = 0; i < 8; i++) {
            double[] v = direction(random, 0.05);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.MIST, 0.65F + random.nextFloat() * 0.45F + charge * 0.25F, 11 + random.nextInt(8)), x, y, z, v[0], Math.abs(v[1]), v[2]);
        }
        for (int i = 0; i < 8; i++) level.addParticle(ParticleTypes.SPLASH, x, y, z, 0.0, 0.1, 0.0);
        if (radius > 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAT_RING_WATER, radius, 11), true, x, ground(level, x, y, z), z, 0, 0, 0);
            // Drops skimming out low to the edge (they keep 0.97 of their speed a tick), and mist rolling after them (0.88).
            for (int i = 0; i < 28; i++) {
                float angle = i * Mth.TWO_PI / 28 + random.nextFloat() * 0.15F;
                double speed = radius * 0.11 * (0.85 + random.nextDouble() * 0.25);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, 0.08F + random.nextFloat() * 0.07F, 12 + random.nextInt(6)), x, y + 0.1, z,
                        Mth.cos(angle) * speed, 0.12 + random.nextDouble() * 0.06, Mth.sin(angle) * speed);
            }
            for (int i = 0; i < 14; i++) {
                float angle = i * Mth.TWO_PI / 14 + random.nextFloat() * 0.3F;
                double speed = radius * 0.1;
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.MIST, 0.55F + random.nextFloat() * 0.35F, 12 + random.nextInt(6)), x, y + 0.15, z,
                        Mth.cos(angle) * speed, 0.005, Mth.sin(angle) * speed);
            }
            sound(level, x, y, z, SoundEvents.GENERIC_SPLASH, 0.7F, 0.85F);
            sound(level, x, y, z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 0.45F, 1.15F);
        }
    }

    /**
     * Short and dry: a hard flash of contact, a dust ring, mixed stone chips thrown hard, and dust left hanging. The novice's rock
     * crumbles to gravel; the slug and the drill knock a ring of rubble out along the ground as far as the blow reached. Each
     * point the drill bores through throws sparks, and fewer chips the further it has gone.
     */
    private static void stoneImpact(ClientLevel level, double x, double y, double z, int stage, float radius, int impactIndex) {
        RandomSource random = level.random;
        BlockParticleOption chip = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
        BlockParticleOption dark = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState());
        if (stage == 0) {
            BlockParticleOption gravel = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GRAVEL.defaultBlockState());
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 0.7F, 3), x, y, z, 0, 0, 0);
            for (int i = 0; i < 28; i++) {
                double[] v = direction(random, 0.08 + random.nextDouble() * 0.14);
                level.addParticle(i % 2 == 0 ? gravel : chip, x, y, z, v[0], Math.abs(v[1]) * 0.6 + 0.04, v[2]);
            }
            for (int i = 0; i < 16; i++) {
                double[] v = direction(random, 0.05 + random.nextDouble() * 0.06);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.55F + random.nextFloat() * 0.5F, 18 + random.nextInt(12)), x, y, z, v[0], Math.abs(v[1]) * 0.5, v[2]);
            }
            sound(level, x, y, z, SoundEvents.GRAVEL_BREAK, 0.6F, 1.0F);
            return;
        }
        float spent = 1.0F - 0.25F * impactIndex;
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 1.1F, 3), x, y, z, 0, 0, 0);
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.RING_STONE, 1.7F, 7), x, y, z, 0, 0, 0);
        for (int i = 0, chips = Math.round(26 * spent); i < chips; i++) {
            double[] v = direction(random, 0.16 + random.nextDouble() * 0.26);
            level.addParticle(i % 3 == 0 ? dark : chip, x, y, z, v[0], Math.abs(v[1]) * 0.8 + 0.06, v[2]);
        }
        for (int i = 0, dust = Math.round(12 * spent); i < dust; i++) {
            double[] v = direction(random, 0.06 + random.nextDouble() * 0.07);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.6F + random.nextFloat() * 0.5F, 16 + random.nextInt(12)), x, y, z, v[0], Math.abs(v[1]) * 0.6, v[2]);
        }
        if (stage == 2) {
            // Boring through: hot sparks off the point.
            for (int i = 0; i < 12; i++) {
                double[] v = direction(random, 0.18 + random.nextDouble() * 0.2);
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.05F + random.nextFloat() * 0.04F, 6 + random.nextInt(6)), x, y, z, v[0], v[1] + 0.05, v[2]);
            }
            sound(level, x, y, z, SoundEvents.GRINDSTONE_USE, 0.4F, 1.7F);
        }
        if (radius > 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLAT_RING_STONE, radius, 9), true, x, ground(level, x, y, z), z, 0, 0, 0);
            // Rubble skidding out to the edge of the blow.
            for (int i = 0; i < 14; i++) {
                float angle = i * Mth.TWO_PI / 14 + random.nextFloat() * 0.25F;
                double speed = radius * 0.12 * (0.8 + random.nextDouble() * 0.3);
                level.addParticle(i % 3 == 0 ? dark : chip, x, y + 0.1, z, Mth.cos(angle) * speed, 0.08, Mth.sin(angle) * speed);
            }
            sound(level, x, y, z, SoundEvents.DEEPSLATE_BREAK, 0.6F, 0.85F);
        }
    }

    /** The colour going out of someone who has spent more than they had: a pale flash and wisps rising off the body. From inside that body it would only fill the screen. */
    private static void overcast(ClientLevel level, int caster, double x, double y, double z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.getId() == caster && !minecraft.gameRenderer.getMainCamera().isDetached()) return;
        RandomSource random = level.random;
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 1.5F, 5), true, x, y, z, 0, 0, 0);
        for (int i = 0; i < 16; i++) {
            double angle = random.nextDouble() * Math.PI * 2, reach = 0.25 + random.nextDouble() * 0.2;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_PALE, 0.45F + random.nextFloat() * 0.4F, 14 + random.nextInt(12)),
                    x + Math.cos(angle) * reach, y - 0.5 + random.nextDouble() * 1.1, z + Math.sin(angle) * reach,
                    Math.cos(angle) * 0.012, 0.035 + random.nextDouble() * 0.03, Math.sin(angle) * 0.012);
        }
    }

    /** Where a reach ring lies: on the ground under the hit if there is ground within a few blocks, else level with the hit. */
    private static double ground(ClientLevel level, double x, double y, double z) {
        for (int down = 0; down <= 3; down++) {
            BlockPos pos = BlockPos.containing(x, y - down, z);
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double top = pos.getY() + shape.max(Direction.Axis.Y);
            if (top <= y + 0.01) return top + 0.05;
        }
        return y + 0.05;
    }

    private static void sound(ClientLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, volume, pitch * (0.95F + level.random.nextFloat() * 0.1F), false);
    }

    private static double[] direction(RandomSource random, double speed) {
        double angle = random.nextDouble() * Math.PI * 2, rise = random.nextDouble() * 2 - 1, flat = Math.sqrt(1 - rise * rise);
        return new double[] {Math.cos(angle) * flat * speed, rise * speed, Math.sin(angle) * flat * speed};
    }

}
