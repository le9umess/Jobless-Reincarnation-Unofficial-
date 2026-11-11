package dev.joblessreincarnation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The air pulse as crescents of thin wind strokes opening forward from the caster over the 60-degree cone the push really
 * uses, then gone. A wall stops the part of a stroke that reaches it, the way it stops the push. Purely visual: no hitbox or knockback.
 */
public final class AirWaveParticle extends Particle {
    private static final ResourceLocation ARC = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/spell/wind_arc.png");
    private static final ParticleRenderType LAYER = new ParticleRenderType() {
        @Override public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, ARC);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }
        @Override public String toString() { return "JOBLESS_AIR_WAVE"; }
    };
    private static final int SEGMENTS = 14, LIFE = 11, STROKES = 7;
    /** Half of the cone in AirBurstSpell. */
    private static final float HALF_ANGLE = 30.0F * Mth.DEG_TO_RAD;
    private static final float START_RADIUS = 1.3F;
    /** Three fronts following each other out: delay in ticks, and how strong each is. */
    private static final float[][] FRONTS = {{0.0F, 1.0F}, {1.6F, 0.7F}, {3.2F, 0.45F}};

    private final Vec3 origin, forward, side, up;
    private final float reach;
    private final float[] clear = new float[SEGMENTS + 1];
    /** One stroke of wind: height in the sheet (-1 to 1), slant across the arc, where it starts and ends along the arc (0 to 1), strength. */
    private final float[][] strokes = new float[STROKES][];

    AirWaveParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
        super(level, x, y, z);
        lifetime = LIFE;
        hasPhysics = false;
        setBoundingBox(getBoundingBox().inflate(8.0));
        origin = new Vec3(x, y, z);
        Vec3 aim = new Vec3(dx, dy, dz);
        float length = (float) aim.length();
        reach = Math.max(1.0F, length);
        forward = length > 1.0E-4F ? aim.scale(1.0F / length) : new Vec3(0, 0, 1);
        Vec3 sideways = forward.cross(new Vec3(0, 1, 0));
        side = sideways.lengthSqr() > 1.0E-4 ? sideways.normalize() : new Vec3(1, 0, 0);
        up = side.cross(forward).normalize();
        for (int i = 0; i <= SEGMENTS; i++) {
            Vec3 ray = direction(i);
            var hit = level.clip(new ClipContext(origin, origin.add(ray.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            clear[i] = hit.getType() == HitResult.Type.MISS ? reach : (float) hit.getLocation().distanceTo(origin);
        }
        for (int i = 0; i < STROKES; i++) {
            float from = random.nextFloat() * 0.3F, to = 0.7F + random.nextFloat() * 0.3F;
            strokes[i] = new float[] {i / (STROKES - 1.0F) * 2.0F - 1.0F + (random.nextFloat() - 0.5F) * 0.2F, (random.nextFloat() - 0.5F) * 0.5F, from, to, 0.55F + random.nextFloat() * 0.45F};
        }
        kickUp(level);
    }

    private Vec3 direction(int segment) {
        float angle = Mth.lerp(segment / (float) SEGMENTS, -HALF_ANGLE, HALF_ANGLE);
        return forward.scale(Mth.cos(angle)).add(side.scale(Mth.sin(angle)));
    }

    /** Flecks of wind along the cone and dust kicked off the ground under it. */
    private void kickUp(ClientLevel level) {
        for (int i = 0; i < 16; i++) {
            int segment = random.nextInt(SEGMENTS + 1);
            float at = 1.2F + random.nextFloat() * Math.max(0.5F, clear[segment] - 1.6F);
            Vec3 ray = direction(segment);
            Vec3 from = origin.add(ray.scale(at)).add(up.scale((random.nextFloat() - 0.5F) * (0.4F + at * 0.25F)));
            Vec3 drift = ray.scale(0.28 + random.nextFloat() * 0.22);
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLECK, 0.05F + random.nextFloat() * 0.05F, 6 + random.nextInt(6)), from.x, from.y, from.z, drift.x, drift.y, drift.z);
        }
        for (int i = 0; i < 9; i++) {
            int segment = random.nextInt(SEGMENTS + 1);
            float at = 2.0F + random.nextFloat() * Math.max(0.5F, clear[segment] - 2.2F);
            Vec3 ray = direction(segment), from = origin.add(ray.scale(at));
            int surface = Mth.floor(from.y);
            while (surface > from.y - 4 && level.getBlockState(net.minecraft.core.BlockPos.containing(from.x, surface, from.z)).isAir()) surface--;
            if (level.getBlockState(net.minecraft.core.BlockPos.containing(from.x, surface, from.z)).isAir()) continue;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.DUST, 0.24F + random.nextFloat() * 0.22F, 9 + random.nextInt(7)), from.x, surface + 1.1, from.z, ray.x * 0.14, 0.03 + random.nextFloat() * 0.04, ray.z * 0.14);
        }
    }

    @Override public ParticleRenderType getRenderType() { return LAYER; }
    @Override public int getLightColor(float partialTick) { return LightTexture.FULL_BRIGHT; }
    @Override public void tick() {
        xo = x; yo = y; zo = z;
        if (++age >= lifetime) remove();
    }

    @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        Vec3 eye = camera.getPosition();
        float now = age + partialTick;
        for (float[] front : FRONTS) {
            float t = (now - front[0]) / (LIFE - FRONTS[2][0]);
            if (t <= 0 || t >= 1) continue;
            float eased = 1 - (1 - t) * (1 - t);
            float radius = Mth.lerp(eased, START_RADIUS, reach);
            float alpha = front[1] * (t < 0.15F ? t / 0.15F : (1 - t) / 0.85F) * 0.95F;
            // The strokes spread apart and thicken a little as the crescent opens.
            float spread = 0.2F + radius * 0.17F, half = 0.022F + radius * 0.009F;
            for (float[] stroke : strokes) {
                for (int i = 0; i < SEGMENTS; i++) {
                    float s0 = i / (float) SEGMENTS, s1 = (i + 1) / (float) SEGMENTS;
                    if (s1 <= stroke[2] || s0 >= stroke[3] || radius > clear[i] || radius > clear[i + 1]) continue;
                    Vec3 a = strokePoint(stroke, i, radius, spread), b = strokePoint(stroke, i + 1, radius, spread);
                    float a0 = alpha * stroke[4] * taper(stroke, s0), a1 = alpha * stroke[4] * taper(stroke, s1);
                    // Both faces: the caster sees it from behind, someone in front sees the other side.
                    corner(buffer, a.add(up.scale(-half)), eye, s0, 1, a0);
                    corner(buffer, b.add(up.scale(-half)), eye, s1, 1, a1);
                    corner(buffer, b.add(up.scale(half)), eye, s1, 0, a1);
                    corner(buffer, a.add(up.scale(half)), eye, s0, 0, a0);
                    corner(buffer, a.add(up.scale(half)), eye, s0, 0, a0);
                    corner(buffer, b.add(up.scale(half)), eye, s1, 0, a1);
                    corner(buffer, b.add(up.scale(-half)), eye, s1, 1, a1);
                    corner(buffer, a.add(up.scale(-half)), eye, s0, 1, a0);
                }
            }
        }
    }

    private Vec3 strokePoint(float[] stroke, int segment, float radius, float spread) {
        float across = segment / (float) SEGMENTS * 2.0F - 1.0F;
        return origin.add(direction(segment).scale(radius)).add(up.scale(stroke[0] * spread + stroke[1] * across * spread));
    }

    /** Thin at both ends of a stroke, so it reads as a brush mark and not a cut-off bar. */
    private static float taper(float[] stroke, float along) {
        float s = (along - stroke[2]) / (stroke[3] - stroke[2]);
        return s <= 0 || s >= 1 ? 0 : Mth.sin(s * Mth.PI);
    }

    private static void corner(VertexConsumer buffer, Vec3 at, Vec3 eye, float u, float v, float alpha) {
        buffer.addVertex((float) (at.x - eye.x), (float) (at.y - eye.y), (float) (at.z - eye.z)).setUv(u, v).setColor(0.96F, 0.99F, 1.0F, alpha).setLight(LightTexture.FULL_BRIGHT);
    }
}
