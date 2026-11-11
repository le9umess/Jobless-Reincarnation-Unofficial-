package dev.joblessreincarnation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** The sprites spell effects are built from: sparks, smoke, steam, glow, small flames, drops, mist, dust and rings. No hitbox, damage or sound. */
public final class SpellSpriteParticle extends TextureSheetParticle {
    /** Glow adds light to what is behind it; it writes no depth, and the world still hides it. */
    private static final ParticleRenderType ADDITIVE = new ParticleRenderType() {
        @Override public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }
        @Override public String toString() { return "JOBLESS_SPELL_ADDITIVE"; }
    };
    // Positions in particles/spell_sprite.json.
    private static final int EMBER_FROM = 0, EMBER_FRAMES = 4, PUFF_FROM = 4, PUFF_FRAMES = 8, GLOW_AT = 12, FLAME_FROM = 13, FLAME_FRAMES = 8,
            DROPLET_FROM = 21, DROPLET_FRAMES = 2, RING_AT = 23, CHIP_AT = 24, LAST = 24;
    /** Sprites closer to the camera than this fade out, so a cast never fills the view or covers the crosshair. */
    private static final float NEAR_FADE_FROM = 0.6F, NEAR_FADE_TO = 1.6F;

    private final SpriteSet sprites;
    private final int style;
    private final float baseSize;
    private final int flameOffset;
    private Winding winding;

    private SpellSpriteParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites, SpellSpriteOptions options) {
        super(level, x, y, z);
        this.sprites = sprites;
        style = options.style();
        baseSize = options.size();
        lifetime = options.life();
        xd = dx;
        yd = dy;
        zd = dz;
        flameOffset = random.nextInt(FLAME_FRAMES);
        hasPhysics = false;
        gravity = 0.0F;
        friction = 0.9F;
        switch (style) {
            case SpellSpriteOptions.EMBER -> { gravity = 0.55F; friction = 0.95F; hasPhysics = true; }
            case SpellSpriteOptions.SMOKE -> { gravity = -0.015F; friction = 0.93F; }
            case SpellSpriteOptions.STEAM -> { gravity = -0.05F; friction = 0.92F; }
            case SpellSpriteOptions.FLAME -> gravity = -0.02F;
            case SpellSpriteOptions.DROPLET -> { gravity = 0.9F; friction = 0.97F; hasPhysics = true; }
            case SpellSpriteOptions.MIST -> friction = 0.88F;
            case SpellSpriteOptions.DUST -> { gravity = 0.005F; friction = 0.86F; }
            case SpellSpriteOptions.FLECK -> friction = 0.97F;
            case SpellSpriteOptions.FOCUS_SPARK, SpellSpriteOptions.FOCUS_DROP -> friction = 1.0F;
            // Chips that wind in follow their spiral; one dropped loose falls.
            case SpellSpriteOptions.FOCUS_CHIP -> { gravity = 0.4F; friction = 0.98F; }
            case SpellSpriteOptions.SEAR -> { gravity = -0.06F; friction = 0.92F; }
            case SpellSpriteOptions.FLAT_RING_WATER, SpellSpriteOptions.FLAT_RING_FIRE, SpellSpriteOptions.FLAT_RING_STONE -> friction = 0.0F;
            default -> {}
        }
        speedUpWhenYMotionIsBlocked = false;
        roll = random.nextFloat() * Mth.TWO_PI;
        oRoll = roll;
        look();
    }

    @Override public ParticleRenderType getRenderType() {
        return switch (style) {
            case SpellSpriteOptions.EMBER, SpellSpriteOptions.GLOW, SpellSpriteOptions.FOCUS_SPARK, SpellSpriteOptions.FOCUS_GLOW, SpellSpriteOptions.FLASH, SpellSpriteOptions.FOCUS_PALE, SpellSpriteOptions.SEAR,
                    SpellSpriteOptions.FLAT_RING_FIRE -> ADDITIVE;
            default -> ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        };
    }
    @Override public int getLightColor(float partialTick) {
        return switch (style) {
            case SpellSpriteOptions.EMBER, SpellSpriteOptions.GLOW, SpellSpriteOptions.FLAME, SpellSpriteOptions.FOCUS_FLAME, SpellSpriteOptions.FOCUS_SPARK, SpellSpriteOptions.FOCUS_GLOW, SpellSpriteOptions.FLASH, SpellSpriteOptions.FOCUS_PALE, SpellSpriteOptions.SEAR,
                    SpellSpriteOptions.FLAT_RING_FIRE -> LightTexture.FULL_BRIGHT;
            default -> lightHere();
        };
    }

    /** An impact on a wall starts its sprites right at the hit, inside the block, where it is dark: take the brightest side instead. */
    private int lightHere() {
        BlockPos at = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(at)) return 0;
        if (!level.getBlockState(at).isSolidRender(level, at)) return LevelRenderer.getLightColor(level, at);
        int sky = 0, block = 0;
        for (Direction side : Direction.values()) {
            BlockPos next = at.relative(side);
            if (!level.hasChunkAt(next)) continue;
            int packed = LevelRenderer.getLightColor(level, next);
            sky = Math.max(sky, LightTexture.sky(packed));
            block = Math.max(block, LightTexture.block(packed));
        }
        return LightTexture.pack(block, sky);
    }
    @Override public void tick() {
        if (winding != null) {
            xo = x;
            yo = y;
            zo = z;
            oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            float t = (float) age / lifetime;
            Vec3 at = winding.at(t * t * (3.0F - 2.0F * t));
            setPos(at.x, at.y, at.z);
            roll += 0.3F;
            look();
            return;
        }
        super.tick();
        if (!removed) look();
    }

    /** A sprite that winds in to {@code centre} along a narrowing spiral around {@code axis}, starting {@code behind} it and {@code radius} out. */
    static void spiral(SpellSpriteOptions options, Vec3 centre, Vec3 axis, float radius, float behind, float angle, float sweep) {
        Vec3 across = axis.cross(new Vec3(0, 1, 0));
        Vec3 side = across.lengthSqr() > 1.0E-4 ? across.normalize() : new Vec3(1, 0, 0);
        Winding winding = new Winding(centre, axis, side, axis.cross(side).normalize(), radius, behind, angle, sweep);
        Vec3 start = winding.at(0.0F);
        if (Minecraft.getInstance().particleEngine.createParticle(options, start.x, start.y, start.z, 0, 0, 0) instanceof SpellSpriteParticle sprite) sprite.winding = winding;
    }

    private record Winding(Vec3 centre, Vec3 axis, Vec3 side, Vec3 up, float radius, float behind, float angle, float sweep) {
        Vec3 at(float along) {
            float left = 1.0F - along, turn = angle + sweep * along;
            return centre.add(axis.scale(-behind * left))
                    .add(side.scale(Mth.cos(turn) * radius * left))
                    .add(up.scale(Mth.sin(turn) * radius * left));
        }
    }

    private void look() {
        float t = Mth.clamp((float) age / lifetime, 0.0F, 1.0F);
        switch (style) {
            case SpellSpriteOptions.EMBER, SpellSpriteOptions.FOCUS_SPARK -> {
                setSprite(sprites.get(EMBER_FROM + Math.min(EMBER_FRAMES - 1, (int) (t * EMBER_FRAMES)), LAST));
                quadSize = baseSize;
                // Pale yellow cooling through orange to a dull red.
                rCol = 1.0F;
                gCol = Mth.lerp(t, 0.86F, 0.22F);
                bCol = Mth.lerp(t, 0.45F, 0.04F);
                alpha = t < 0.7F ? 1.0F : (1.0F - t) / 0.3F;
            }
            case SpellSpriteOptions.SEAR -> {
                setSprite(sprites.get(EMBER_FROM + Math.min(EMBER_FRAMES - 1, (int) (t * EMBER_FRAMES)), LAST));
                quadSize = baseSize * (1.0F - 0.4F * t);
                // Orange cooling to a dull red, flickering as it rises off the burning creature.
                rCol = 1.0F;
                gCol = Mth.lerp(t, 0.62F, 0.18F);
                bCol = Mth.lerp(t, 0.2F, 0.03F);
                alpha = (t < 0.6F ? 1.0F : (1.0F - t) / 0.4F) * (0.65F + 0.35F * random.nextFloat());
            }
            case SpellSpriteOptions.SMOKE, SpellSpriteOptions.STEAM, SpellSpriteOptions.MIST, SpellSpriteOptions.DUST -> {
                setSprite(sprites.get(PUFF_FROM + Math.min(PUFF_FRAMES - 1, (int) (t * PUFF_FRAMES)), LAST));
                quadSize = baseSize * (0.55F + 0.75F * t);
                float top = switch (style) {
                    case SpellSpriteOptions.SMOKE -> 0.5F;
                    case SpellSpriteOptions.STEAM -> 0.62F;
                    case SpellSpriteOptions.MIST -> 0.42F;
                    default -> 0.62F;
                };
                switch (style) {
                    case SpellSpriteOptions.SMOKE -> setColor(Mth.lerp(t, 0.16F, 0.3F), Mth.lerp(t, 0.16F, 0.3F), Mth.lerp(t, 0.16F, 0.3F));
                    case SpellSpriteOptions.STEAM -> setColor(0.94F, 0.94F, 1.0F);
                    case SpellSpriteOptions.MIST -> setColor(0.8F, 0.92F, 1.0F);
                    default -> setColor(0.78F, 0.74F, 0.66F);
                }
                alpha = top * (t < 0.15F ? t / 0.15F : (1.0F - t) / 0.85F);
            }
            case SpellSpriteOptions.FLAME, SpellSpriteOptions.FOCUS_FLAME -> {
                setSprite(sprites.get(FLAME_FROM + (flameOffset + age) % FLAME_FRAMES, LAST));
                quadSize = style == SpellSpriteOptions.FLAME ? baseSize * (1.0F - 0.55F * t) : baseSize;
                alpha = t < 0.6F ? 0.95F : 0.95F * (1.0F - t) / 0.4F;
            }
            case SpellSpriteOptions.DROPLET -> {
                setSprite(sprites.get(DROPLET_FROM + Math.min(DROPLET_FRAMES - 1, (int) (t * DROPLET_FRAMES)), LAST));
                quadSize = baseSize;
                setColor(0.72F, 0.9F, 1.0F);
                alpha = t < 0.75F ? 0.95F : 0.95F * (1.0F - t) / 0.25F;
            }
            case SpellSpriteOptions.FOCUS_DROP -> {
                setSprite(sprites.get(DROPLET_FROM, LAST));
                quadSize = baseSize * (1.0F - 0.35F * t);
                setColor(0.62F, 0.84F, 1.0F);
                alpha = t < 0.85F ? 0.9F : 0.9F * (1.0F - t) / 0.15F;
            }
            case SpellSpriteOptions.FLAT_RING_WATER, SpellSpriteOptions.FLAT_RING_FIRE, SpellSpriteOptions.FLAT_RING_STONE -> {
                setSprite(sprites.get(RING_AT, LAST));
                // Out fast, then easing to the full reach; the sprite's outer edge sits just inside its square.
                float out = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
                quadSize = baseSize * 1.06F * (0.12F + 0.88F * out);
                switch (style) {
                    case SpellSpriteOptions.FLAT_RING_WATER -> setColor(0.75F, 0.92F, 1.0F);
                    case SpellSpriteOptions.FLAT_RING_FIRE -> { rCol = 1.0F; gCol = Mth.lerp(t, 0.75F, 0.3F); bCol = Mth.lerp(t, 0.35F, 0.06F); }
                    default -> setColor(0.7F, 0.66F, 0.58F);
                }
                alpha = (style == SpellSpriteOptions.FLAT_RING_FIRE ? 0.95F : 0.85F) * (t < 0.6F ? 1.0F : (1.0F - t) / 0.4F);
            }
            case SpellSpriteOptions.RING_WATER, SpellSpriteOptions.RING_STONE -> {
                setSprite(sprites.get(RING_AT, LAST));
                quadSize = baseSize * (0.35F + 0.65F * (1.0F - (1.0F - t) * (1.0F - t)));
                if (style == SpellSpriteOptions.RING_WATER) setColor(0.8F, 0.95F, 1.0F);
                else setColor(0.78F, 0.74F, 0.66F);
                alpha = 0.8F * (1.0F - t);
            }
            case SpellSpriteOptions.FOCUS_CHIP -> {
                setSprite(sprites.get(CHIP_AT, LAST));
                quadSize = baseSize;
                setColor(0.66F, 0.64F, 0.6F);
                alpha = t < 0.85F ? 1.0F : (1.0F - t) / 0.15F;
            }
            case SpellSpriteOptions.FOCUS_DUST -> {
                setSprite(sprites.get(PUFF_FROM + Math.min(PUFF_FRAMES - 1, (int) (t * PUFF_FRAMES)), LAST));
                quadSize = baseSize * (1.0F - 0.5F * t);
                setColor(0.8F, 0.76F, 0.66F);
                alpha = 0.5F * (t < 0.2F ? t / 0.2F : (1.0F - t) / 0.8F);
            }
            case SpellSpriteOptions.FOCUS_PALE -> {
                setSprite(sprites.get(GLOW_AT, LAST));
                quadSize = baseSize;
                setColor(1.0F, 0.95F, 0.68F);
                alpha = 0.55F * (1.0F - t * 0.6F);
            }
            case SpellSpriteOptions.FLASH -> {
                setSprite(sprites.get(GLOW_AT, LAST));
                quadSize = baseSize * (0.5F + 0.8F * t);
                setColor(1.0F, 0.97F, 0.88F);
                alpha = 0.8F * (1.0F - t);
            }
            case SpellSpriteOptions.FLECK -> {
                setSprite(sprites.get(EMBER_FROM + Math.min(EMBER_FRAMES - 1, (int) (t * EMBER_FRAMES)), LAST));
                quadSize = baseSize;
                setColor(0.95F, 1.0F, 0.98F);
                alpha = 0.75F * (1.0F - t);
            }
            default -> {
                setSprite(sprites.get(GLOW_AT, LAST));
                quadSize = baseSize * (0.6F + 0.6F * t);
                rCol = 1.0F;
                gCol = Mth.lerp(t, 0.82F, 0.4F);
                bCol = Mth.lerp(t, 0.5F, 0.1F);
                alpha = 0.7F * (1.0F - t);
            }
        }
    }

    @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        // The chant's own focus sits right in front of the caster on purpose.
        boolean atStaffTip = style == SpellSpriteOptions.FOCUS_FLAME || style == SpellSpriteOptions.FOCUS_SPARK || style == SpellSpriteOptions.FOCUS_GLOW
                || style == SpellSpriteOptions.FOCUS_CHIP || style == SpellSpriteOptions.FOCUS_DUST || style == SpellSpriteOptions.FOCUS_PALE || style == SpellSpriteOptions.FOCUS_DROP;
        if (atStaffTip) {
            super.render(buffer, camera, partialTick);
            return;
        }
        if (style == SpellSpriteOptions.FLAT_RING_WATER || style == SpellSpriteOptions.FLAT_RING_FIRE || style == SpellSpriteOptions.FLAT_RING_STONE) {
            // Lying on the ground, seen from above or below alike; it shows the reach, so it does not fade near the camera.
            renderRotatedQuad(buffer, camera, new Quaternionf().rotationX(-Mth.HALF_PI).rotateZ(roll), partialTick);
            renderRotatedQuad(buffer, camera, new Quaternionf().rotationX(Mth.HALF_PI).rotateZ(roll), partialTick);
            return;
        }
        Vec3 eye = camera.getPosition();
        float distance = (float) eye.distanceTo(new Vec3(Mth.lerp(partialTick, xo, x), Mth.lerp(partialTick, yo, y), Mth.lerp(partialTick, zo, z)));
        float near = Mth.clamp((distance - NEAR_FADE_FROM) / (NEAR_FADE_TO - NEAR_FADE_FROM), 0.0F, 1.0F);
        if (near <= 0.0F) return;
        float stored = alpha;
        alpha = stored * near;
        super.render(buffer, camera, partialTick);
        alpha = stored;
    }

    public static final class Provider implements ParticleProvider<SpellSpriteOptions> {
        private final SpriteSet sprites;
        public Provider(SpriteSet sprites) { this.sprites = sprites; }
        @Override public Particle createParticle(SpellSpriteOptions options, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
            if (dx == 1.0 && dy == 1.0 && dz == 1.0) {
                // A status effect's own particle: vanilla hands every one exactly (1, 1, 1), meant as colour, not motion.
                // Ours only drift off the body: a drop slips down, a spark lifts.
                double sideX = (level.random.nextDouble() - 0.5) * 0.01, sideZ = (level.random.nextDouble() - 0.5) * 0.01;
                return new SpellSpriteParticle(level, x, y, z, sideX, options.style() == SpellSpriteOptions.SEAR ? 0.02 : -0.01, sideZ, sprites, options);
            }
            return new SpellSpriteParticle(level, x, y, z, dx, dy, dz, sprites, options);
        }
    }
}
