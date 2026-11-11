package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

/**
 * The water bolt's chant at the staff tip, in the caster's form: drops wind in and a ball of water grows there, then
 * swells while the caster keeps shaping it. The novice's will not hold together and spills; the practised one is a
 * smooth ball; the adept's is pressed tight and spun, bands of light turning in it. Looks only.
 */
final class WaterFocus {
    private static final int ARMS = 3;

    /**
     * {@code dx, dy, dz} are the staff's direction times the shaping: up to 1 while the chant runs, then 1 plus the charge
     * while it is held. Returns the particle for the engine to keep.
     */
    static Particle gather(ClientLevel level, int caster, double x, double y, double z, double dx, double dy, double dz, int stage) {
        Vec3 motion = new Vec3(dx, dy, dz);
        float shaping = Mth.clamp((float) motion.length(), 0.0F, 2.0F);
        Vec3 axis = shaping > 1.0E-4F ? motion.scale(1.0F / shaping) : new Vec3(0, 0, 1);
        float chant = Math.min(1.0F, shaping), charge = Math.max(0.0F, shaping - 1.0F);
        Vec3 centre = StaffTipView.focus(new Vec3(x, y, z), caster);
        float smooth = chant * chant * (3 - 2 * chant);
        // The caster's camera is about a block from the staff tip: everything here would look three times as big and fill the view.
        float near = Mth.clamp((float) Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(centre) / 2.2F, 0.45F, 1.0F);
        RandomSource random = level.random;
        if (chant < 1.0F && Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL) {
            float phase = level.getGameTime() * (stage == 2 ? 1.1F : 0.7F);
            for (int arm = 0; arm < ARMS; arm++) {
                float angle = phase + arm * Mth.TWO_PI / ARMS;
                float radius = (0.48F - 0.12F * smooth + random.nextFloat() * (stage == 0 ? 0.12F : 0.05F)) * near;
                SpellSpriteParticle.spiral(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_DROP, (0.035F + random.nextFloat() * 0.03F) * near, 8 + random.nextInt(3)),
                        centre, axis, radius, 0.14F + random.nextFloat() * 0.12F, angle, stage == 2 ? 8.4F : 6.0F);
            }
        }
        // The novice cannot keep it all: drops slip off and fall.
        if (Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL && stage == 0 && smooth > 0.3F && random.nextInt(2) == 0) {
            Minecraft.getInstance().particleEngine.createParticle(new SpellSpriteOptions(SpellSpriteOptions.DROPLET, (0.05F + random.nextFloat() * 0.04F) * near, 10 + random.nextInt(6)),
                    centre.x + (random.nextDouble() - 0.5) * 0.12 * near, centre.y - 0.04 * near, centre.z + (random.nextDouble() - 0.5) * 0.12 * near,
                    (random.nextDouble() - 0.5) * 0.03, 0.01, (random.nextDouble() - 0.5) * 0.03);
        }
        float size = (0.025F + 0.06F * smooth + 0.05F * charge) * Mth.sqrt(near);
        return new Core(level, centre.x, centre.y, centre.z, axis, size, stage);
    }

    /** The ball itself: a faceted sphere of water from the block atlas, tinted like a clear river. */
    private static final class Core extends Particle {
        private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("block/water_still");
        private static final int SEGMENTS = 10, BANDS = 6;
        private final Vec3 axis, side, up;
        private final float size;
        private final int stage;

        Core(ClientLevel level, double x, double y, double z, Vec3 axis, float size, int stage) {
            super(level, x, y, z);
            lifetime = 2;
            hasPhysics = false;
            this.axis = axis;
            Vec3 across = axis.cross(new Vec3(0, 1, 0));
            side = across.lengthSqr() > 1.0E-4 ? across.normalize() : new Vec3(1, 0, 0);
            up = axis.cross(side).normalize();
            this.size = size;
            this.stage = stage;
        }

        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.TERRAIN_SHEET; }
        @Override public void tick() {
            xo = x; yo = y; zo = z;
            if (age++ >= lifetime) remove();
        }

        @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {
            if (size <= 0.002F) return;
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER);
            Vec3 eye = camera.getPosition();
            Vec3 centre = new Vec3(x - eye.x, y - eye.y, z - eye.z);
            float time = level.getGameTime() + partialTick;
            float spin = time * (stage == 2 ? 0.9F : 0.25F);
            int light = getLightColor(partialTick);
            // The adept's is pressed long along the staff's line; the others are round.
            float along = stage == 2 ? 1.25F : 1.0F, girth = stage == 2 ? 0.88F : 1.0F;
            for (int band = 0; band < BANDS; band++) {
                float lower = -Mth.HALF_PI + band * Mth.PI / BANDS, upper = lower + Mth.PI / BANDS;
                for (int segment = 0; segment < SEGMENTS; segment++) {
                    float left = spin + segment * Mth.TWO_PI / SEGMENTS, right = left + Mth.TWO_PI / SEGMENTS;
                    Vec3 a = point(centre, lower, left, time, along, girth), b = point(centre, upper, left, time, along, girth);
                    Vec3 c = point(centre, upper, right, time, along, girth), d = point(centre, lower, right, time, along, girth);
                    float latitude = (lower + upper) * 0.5F, longitude = (left + right) * 0.5F - spin;
                    // Lighter toward the sky, with a shine where the skin faces both the sky and the viewer. The adept's ball is
                    // deeper blue under pressure, its light in narrow bands wound around it like rifling, as on the slug it becomes.
                    Vec3 normal = axis.scale(Mth.sin(latitude)).add(side.scale(Mth.cos(latitude) * Mth.cos(longitude + spin)))
                            .add(up.scale(Mth.cos(latitude) * Mth.sin(longitude + spin)));
                    float shade = 0.7F + 0.3F * Math.max(0.0F, (float) normal.y);
                    boolean bright = stage == 2 ? Mth.sin(3.0F * (longitude + latitude * 2.2F)) > 0.6F
                            : normal.y > 0.55 && -normal.dot(centre.normalize()) > 0.35;
                    float u0 = sprite.getU(segment / (float) SEGMENTS), u1 = sprite.getU((segment + 1) / (float) SEGMENTS);
                    float v0 = sprite.getV(band / (float) BANDS), v1 = sprite.getV((band + 1) / (float) BANDS);
                    float deep = stage == 2 ? 0.72F : 1.0F;
                    float r = bright ? 0.78F : 0.32F * shade * deep, g = bright ? 0.94F : 0.58F * shade * deep, bl = bright ? 1.0F : 1.0F * shade;
                    float alpha = bright ? 0.92F : stage == 0 ? 0.68F : stage == 1 ? 0.8F : 0.86F;
                    // Counter-clockwise seen from outside: (side, up, axis) turns the other way from the bolt's own mesh.
                    vertex(buffer, a, u0, v0, r, g, bl, alpha, light);
                    vertex(buffer, d, u1, v0, r, g, bl, alpha, light);
                    vertex(buffer, c, u1, v1, r, g, bl, alpha, light);
                    vertex(buffer, b, u0, v1, r, g, bl, alpha, light);
                }
            }
        }

        /** A point of the skin; the novice's ball bulges and sinks as it goes. */
        private Vec3 point(Vec3 centre, float latitude, float longitude, float time, float along, float girth) {
            float swell = stage == 0 ? 1.0F + 0.2F * Mth.sin(3.0F * longitude + 2.0F * latitude + time * 0.8F) * Mth.cos(2.0F * longitude - 3.0F * latitude + time * 0.6F) : 1.0F;
            float radius = size * swell;
            float onAxis = Mth.sin(latitude) * radius * along, across = Mth.cos(latitude) * radius * girth;
            return centre.add(axis.scale(onAxis)).add(side.scale(Mth.cos(longitude) * across)).add(up.scale(Mth.sin(longitude) * across));
        }

        private static void vertex(VertexConsumer buffer, Vec3 at, float u, float v, float r, float g, float b, float alpha, int light) {
            buffer.addVertex((float) at.x, (float) at.y, (float) at.z).setUv(u, v).setColor(r, g, b, alpha).setLight(light);
        }
    }

    private WaterFocus() {}
}
