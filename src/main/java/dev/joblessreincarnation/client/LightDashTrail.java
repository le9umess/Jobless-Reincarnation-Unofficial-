package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.NoRenderParticle;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The Longsword of Light released: a streak of light that follows the swordsman along the dash, a crescent of light where
 * the blade comes round, sparks shed on the way, a hard sweep and a rush of air. Bigger and brighter with the tier (0 to 2).
 * It only spawns sprites while it lives, following its caster as the client sees them move; no hitbox, and the hit flash
 * stays the separate burst for an actual hit.
 */
final class LightDashTrail extends NoRenderParticle {
    private final int caster, tier;
    private Vec3 last;

    LightDashTrail(ClientLevel level, int caster, int tier, double x, double y, double z) {
        super(level, x, y, z);
        this.caster = caster;
        this.tier = Mth.clamp(tier, 0, 2);
        this.lifetime = 9;
        this.last = new Vec3(x, y, z);
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.GLOW, 1.2F + 0.4F * this.tier, 5), x, y, z, 0, 0, 0);
        level.playLocalSound(x, y, z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9F, 0.75F + 0.1F * this.tier, false);
        level.playLocalSound(x, y, z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.35F, 1.6F, false);
    }

    @Override public void tick() {
        super.tick();
        Entity who = level.getEntity(caster);
        if (who == null) return;
        Vec3 now = who.position().add(0, who.getBbHeight() * .55, 0);
        int steps = Math.max(1, (int) Math.ceil(now.distanceTo(last) / 0.22));
        float size = 0.22F + 0.06F * tier;
        for (int i = 1; i <= steps; i++) {
            Vec3 p = last.lerp(now, (double) i / steps);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, size, 4 + tier), p.x, p.y, p.z, 0, 0, 0);
            if (random.nextFloat() < 0.35F) {
                level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.05F + random.nextFloat() * 0.04F, 6 + random.nextInt(5)),
                        p.x, p.y, p.z, (random.nextDouble() - .5) * .08, .02, (random.nextDouble() - .5) * .08);
            }
        }
        last = now;
        if (age == 2) crescent(who, now);
    }

    /** A slanted arc of light in front of the swordsman, high on the left and low on the right, where the cut comes round. */
    private void crescent(Entity who, Vec3 at) {
        float yaw = who.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), rx = -fz, rz = fx;
        float radius = 1.2F + 0.25F * tier;
        for (int i = -6; i <= 6; i++) {
            double a = i / 6.0 * 1.2;
            double ox = (fx * Math.cos(a) + rx * Math.sin(a)) * radius, oz = (fz * Math.cos(a) + rz * Math.sin(a)) * radius;
            float s = (0.32F + 0.08F * tier) * (1 - Math.abs(i) / 8.0F);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, s, 5), at.x + ox, at.y - i * 0.05, at.z + oz, 0, 0, 0);
        }
    }
}
