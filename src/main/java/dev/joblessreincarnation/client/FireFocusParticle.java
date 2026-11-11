package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.joblessreincarnation.magic.FireFocusEffects;
import dev.joblessreincarnation.magic.FireFocusOptions;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The chant's gathering fire at the staff tip: a small flame that grows with the chant and sparks drawn in toward it.
 * This particle only starts those sprites and has no look of its own. No entity, hitbox, damage or tick sound.
 */
public final class FireFocusParticle extends Particle {
    /** By form: the novice's flame is small, ragged and smoking; the adept's draws sparks in fast and burns white at the heart. */
    private FireFocusParticle(ClientLevel level, double x, double y, double z, float progress, int caster, int stage) {
        super(level, x, y, z);
        lifetime = FireFocusEffects.LIFETIME_TICKS;
        hasPhysics = false;
        Vec3 tip = StaffTipView.focus(new Vec3(x, y, z), caster);
        x = tip.x;
        y = tip.y;
        z = tip.z;
        float smooth = progress * progress * (3 - 2 * progress);
        float flame = switch (stage) {
            case 0 -> (0.05F + 0.08F * smooth) * (0.85F + 0.3F * random.nextFloat());
            case 1 -> 0.06F + 0.1F * smooth;
            default -> 0.07F + 0.11F * smooth;
        };
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_FLAME, flame, lifetime), true, x, y, z, 0, 0.002, 0);
        boolean minimal = Minecraft.getInstance().options.particles().get() == ParticleStatus.MINIMAL;
        int sparks = minimal ? 0 : stage == 0 ? 1 : stage == 1 ? 1 + Math.round(smooth * 2) : 2 + Math.round(smooth * 3);
        for (int i = 0; i < sparks; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI, reach = 0.22F + random.nextFloat() * 0.14F, rise = (random.nextFloat() - 0.5F) * 0.3F;
            SpellSpriteParticle.spiral(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_SPARK, 0.03F + random.nextFloat() * 0.02F, stage == 2 ? 5 : 6), tip, new Vec3(0, 1, 0), reach, rise, angle, stage == 2 ? 6.6F : 4.4F);
        }
        if (!minimal && stage == 0 && random.nextInt(3) == 0) {
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.SMOKE, 0.12F + random.nextFloat() * 0.08F, 10 + random.nextInt(6)), x, y + 0.05, z, 0, 0.02, 0);
        }
        if (stage == 2 && progress > 0.5F) {
            // The white heart grows in as the chant closes.
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_PALE, 0.04F + 0.08F * (progress - 0.5F) * 2, lifetime), true, x, y, z, 0, 0, 0);
        }
        if (!minimal && progress >= 1) level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_GLOW, stage == 0 ? 0.2F : stage == 1 ? 0.26F : 0.32F, 5), x, y, z, 0, 0, 0);
    }
    @Override public ParticleRenderType getRenderType() { return ParticleRenderType.NO_RENDER; }
    @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {}

    public static final class Provider implements ParticleProvider<FireFocusOptions> {
        @Override public Particle createParticle(FireFocusOptions options, ClientLevel level, double x, double y, double z,
                double xSpeed, double ySpeed, double zSpeed) {
            return new FireFocusParticle(level, x, y, z, options.progress(), options.caster(), options.stage());
        }
    }
}
