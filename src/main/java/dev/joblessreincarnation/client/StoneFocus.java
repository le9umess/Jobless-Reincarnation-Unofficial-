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
 * The stone cannon's chant at the staff tip: chips and dust wind in along a narrowing spiral, and a small stone grows and
 * spins at the centre, in the caster's form: a lumpy rock shedding grit, a tight cone with its point forward, or a twisted
 * drill point turning fast. Looks only. The shot is a separate entity.
 */
final class StoneFocus {
    private static final int ARMS = 3;

    /** {@code dx, dy, dz} are the staff's direction times the chant's progress. Returns the particle for the engine to keep. */
    static Particle gather(ClientLevel level, int caster, double x, double y, double z, double dx, double dy, double dz, int stage) {
        Vec3 motion = new Vec3(dx, dy, dz);
        float progress = Mth.clamp((float) motion.length(), 0.0F, 1.0F);
        Vec3 axis = progress > 1.0E-4F ? motion.scale(1.0F / progress) : new Vec3(0, 0, 1);
        Vec3 centre = StaffTipView.focus(new Vec3(x, y, z), caster);
        x = centre.x;
        y = centre.y;
        z = centre.z;
        float smooth = progress * progress * (3 - 2 * progress);
        // The caster's camera is about a block from the staff tip: everything here would look three times as big and fill the view.
        float near = Mth.clamp((float) Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(centre) / 2.2F, 0.45F, 1.0F);
        RandomSource random = level.random;
        if (Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL) {
            float phase = level.getGameTime() * 0.7F;
            for (int arm = 0; arm < ARMS; arm++) {
                float angle = phase + arm * Mth.TWO_PI / ARMS;
                float radius = (0.5F - 0.14F * smooth + random.nextFloat() * 0.06F) * near;
                float behind = 0.16F + random.nextFloat() * 0.14F;
                SpellSpriteParticle.spiral(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_CHIP, (0.028F + random.nextFloat() * 0.03F) * near, 8 + random.nextInt(3)),
                        centre, axis, radius, behind, angle, stage == 2 ? 9.6F : 7.2F);
                if (random.nextInt(3) == 0) {
                    SpellSpriteParticle.spiral(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_DUST, (0.09F + random.nextFloat() * 0.07F) * near, 9 + random.nextInt(3)),
                            centre, axis, radius + 0.08F * near, behind + 0.06F, angle + 0.5F, 6.4F);
                }
            }
        }
        // The novice's rock will not hold together: grit drops off it.
        if (Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL && stage == 0 && smooth > 0.25F && random.nextInt(3) == 0) {
            Minecraft.getInstance().particleEngine.createParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_CHIP, (0.018F + random.nextFloat() * 0.016F) * near, 10 + random.nextInt(4)),
                    x + (random.nextDouble() - 0.5) * 0.1 * near, y - 0.03 * near, z + (random.nextDouble() - 0.5) * 0.1 * near, 0, -0.01, 0);
        }
        if (Minecraft.getInstance().options.particles().get() != ParticleStatus.MINIMAL && progress > 0.45F) {
            Minecraft.getInstance().particleEngine.createParticle(new SpellSpriteOptions(SpellSpriteOptions.FOCUS_PALE, (0.12F + 0.2F * smooth) * near, 3), x, y, z, 0, 0, 0);
        }
        return new Core(level, x, y, z, axis, smooth * Mth.sqrt(near), stage);
    }

    /** The stone itself: six rough sides around the staff's line, pointed in front and blunt behind, drawn from the block atlas. */
    private static final class Core extends Particle {
        private static final ResourceLocation STONE = ResourceLocation.withDefaultNamespace("block/cobblestone");
        /** A rough outline, the same at every moment so the spin reads as the stone turning. The novice's is lumpier. */
        private static final float[] ROUGH = {1.0F, 0.84F, 1.1F, 0.9F, 1.06F, 0.82F}, LUMPY = {1.18F, 0.72F, 1.22F, 0.8F, 1.12F, 0.7F};
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
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(STONE);
            Vec3 eye = camera.getPosition();
            Vec3 centre = new Vec3(x - eye.x, y - eye.y, z - eye.z);
            float spin = (level.getGameTime() + partialTick) * (stage == 0 ? 0.35F : stage == 1 ? 0.55F : 1.3F);
            float radius = 0.03F + 0.085F * size;
            float front = stage == 0 ? 0.035F + 0.13F * size : stage == 1 ? 0.05F + 0.25F * size : 0.06F + 0.34F * size;
            float back = stage == 0 ? 0.03F + 0.1F * size : 0.025F + 0.07F * size;
            Vec3 tip = centre.add(axis.scale(front)), tail = centre.add(axis.scale(-back));
            int light = getLightColor(partialTick);
            if (stage == 0) {
                lump(buffer, sprite, centre, spin, radius * 1.1F, light);
                return;
            }
            Vec3[] ring = ring(centre, spin, radius, ROUGH);
            float vRing = sprite.getV(0.5F);
            for (int i = 0; i < ring.length; i++) {
                Vec3 a = ring[i], b = ring[(i + 1) % ring.length];
                float u0 = sprite.getU(i / (float) ring.length), u1 = sprite.getU((i + 1) / (float) ring.length);
                face(buffer, b, a, tail, u1, u0, vRing, sprite.getV(1.0F), 1.0F, light);
                if (stage != 2) face(buffer, a, b, tip, u0, u1, vRing, sprite.getV(0.0F), 1.0F, light);
            }
            if (stage == 2) {
                // The drill point: a narrower ring part way forward, turned on from the first, so the sides twist; every other face
                // darker, so the twist reads as cut grooves.
                Vec3[] waist = ring(centre.add(axis.scale(front * 0.42F)), spin + 0.6F, radius * 0.6F, ROUGH);
                float vWaist = sprite.getV(0.25F);
                for (int i = 0; i < ring.length; i++) {
                    int next = (i + 1) % ring.length;
                    float u0 = sprite.getU(i / (float) ring.length), u1 = sprite.getU((i + 1) / (float) ring.length);
                    float groove = i % 2 == 0 ? 1.0F : 0.62F;
                    face(buffer, ring[i], ring[next], waist[next], u0, u1, vRing, vWaist, groove, light);
                    face(buffer, ring[i], waist[next], waist[i], u0, u1, vRing, vWaist, groove, light);
                    face(buffer, waist[i], waist[next], tip, u0, u1, vWaist, sprite.getV(0.0F), groove, light);
                }
            }
        }

        /**
         * The novice's stone: no point at all, a lumpy ball. Three rings around the staff's line, the middle one widest and each
         * turned a little from the next, closed by a blunt cap at either end.
         */
        private void lump(VertexConsumer buffer, TextureAtlasSprite sprite, Vec3 centre, float spin, float radius, int light) {
            float[] along = {-0.55F, 0.0F, 0.55F}, width = {0.8F, 1.0F, 0.78F}, turn = {0.0F, 0.5F, 1.1F};
            Vec3[][] rings = new Vec3[along.length][];
            for (int k = 0; k < along.length; k++) rings[k] = ring(centre.add(axis.scale(along[k] * radius)), spin + turn[k], radius * width[k], LUMPY);
            Vec3 front = centre.add(axis.scale(radius * 0.92F)), back = centre.add(axis.scale(-radius * 0.95F));
            int n = LUMPY.length;
            for (int i = 0; i < n; i++) {
                int next = (i + 1) % n;
                float u0 = sprite.getU(i / (float) n), u1 = sprite.getU((i + 1) / (float) n);
                for (int k = 0; k + 1 < along.length; k++) {
                    float v0 = sprite.getV(0.2F + 0.3F * k), v1 = sprite.getV(0.5F + 0.3F * k);
                    face(buffer, rings[k][i], rings[k][next], rings[k + 1][next], u0, u1, v0, v1, 1.0F, light);
                    face(buffer, rings[k][i], rings[k + 1][next], rings[k + 1][i], u0, u1, v0, v1, 1.0F, light);
                }
                face(buffer, rings[along.length - 1][i], rings[along.length - 1][next], front, u0, u1, sprite.getV(0.8F), sprite.getV(1.0F), 1.0F, light);
                face(buffer, rings[0][next], rings[0][i], back, u1, u0, sprite.getV(0.2F), sprite.getV(0.0F), 1.0F, light);
            }
        }

        private Vec3[] ring(Vec3 centre, float spin, float radius, float[] outline) {
            Vec3[] ring = new Vec3[outline.length];
            for (int i = 0; i < ring.length; i++) {
                float angle = spin + i * Mth.TWO_PI / ring.length;
                ring[i] = centre.add(side.scale(Mth.cos(angle) * radius * outline[i])).add(up.scale(Mth.sin(angle) * radius * outline[i]));
            }
            return ring;
        }

        /** A triangle as a quad with one doubled corner, shaded by which way it faces, since the particle shader has no normals. */
        private static void face(VertexConsumer buffer, Vec3 a, Vec3 b, Vec3 apex, float ua, float ub, float vRing, float vApex, float dark, int light) {
            Vec3 normal = b.subtract(a).cross(apex.subtract(a));
            float length = (float) normal.length();
            float facing = length > 1.0E-6F ? (float) ((normal.y * 0.8 + normal.x * 0.35 + normal.z * 0.2) / length) : 0;
            float shade = (0.68F + 0.32F * Math.max(0.0F, facing)) * dark;
            vertex(buffer, a, ua, vRing, shade, light);
            vertex(buffer, b, ub, vRing, shade, light);
            vertex(buffer, apex, ub, vApex, shade, light);
            vertex(buffer, apex, ua, vApex, shade, light);
        }

        private static void vertex(VertexConsumer buffer, Vec3 at, float u, float v, float shade, int light) {
            buffer.addVertex((float) at.x, (float) at.y, (float) at.z).setUv(u, v).setColor(shade, shade, shade, 1.0F).setLight(light);
        }
    }

    private StoneFocus() {}
}
