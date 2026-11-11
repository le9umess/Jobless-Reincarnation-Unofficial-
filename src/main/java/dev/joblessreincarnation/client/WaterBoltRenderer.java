package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.WaterControl;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * A small body of water in the shape of a drop, round in front and drawn to a point behind, in the form its caster has
 * mastered: a loose lump that will not hold still, a smooth tight drop, or a long hard slug with spiral bands wound
 * through it. This renderer never changes the projectile's hit box.
 */
public final class WaterBoltRenderer extends EntityRenderer<WaterBolt> {
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    private static final RenderType WATER = RenderType.entityTranslucentCull(WHITE);
    private static final int SEGMENTS = 12, BANDS = 8;
    private static final List<Facet> FACETS = makeFacets();

    public WaterBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(WaterBolt entity, float entityYaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight) {
        int stage = Mth.clamp(entity.formStage(), 0, 2);
        poses.pushPose();
        poses.translate(0.0F, entity.getBbHeight() * 0.5F, 0.0F);
        float diameter = WaterControl.visualSize(entity.charge());
        float age = entity.tickCount + partialTick;
        // The model's y axis along the flight: a body of water in a hurry, drawn out behind and wobbling as it goes.
        Vec3 motion = entity.getDeltaMovement();
        if (motion.lengthSqr() > 1.0E-6) {
            Vec3 along = motion.normalize();
            poses.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) along.x, (float) along.y, (float) along.z));
        }
        float wobble = Mth.sin(age * (stage == 0 ? 1.15F : 0.85F) + entity.getId()) * (stage == 0 ? 0.12F : stage == 1 ? 0.05F : 0.02F);
        // The slug is pressed long and thin along its flight; the others are round drops.
        float girth = stage == 2 ? 0.8F : 0.98F, length = stage == 2 ? 1.4F : 1.08F;
        poses.scale(diameter * (girth - wobble * 0.6F), diameter * (length + wobble), diameter * (girth - wobble * 0.6F));
        poses.mulPose(Axis.YP.rotationDegrees(entity.getId() * 37.0F + age * (stage == 2 ? 24.0F : 7.0F)));
        Look look = new Look(stage, age * 0.9F + entity.getId() * 2.3F);
        VertexConsumer vertices = buffers.getBuffer(WATER);
        // A paler heart seen through the skin, so the blob reads as a volume and not a flat disc. The slug's is brighter, under pressure.
        poses.pushPose();
        float heart = stage == 2 ? 0.5F : 0.55F;
        poses.scale(heart, heart, heart);
        drawFacets(vertices, poses.last(), packedLight, stage == 2 ? 120 : 90, 0.6F, look);
        poses.popPose();
        drawFacets(vertices, poses.last(), packedLight, 0, stage == 0 ? 0.85F : 1.0F, look);
        poses.popPose();
        super.render(entity, entityYaw, partialTick, poses, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(WaterBolt entity) {
        return WHITE;
    }

    /** How one form bends and colours the shared mesh at this moment. */
    private record Look(int stage, float time) {
        /** The loose lump bulges and sinks in slow waves; the other two keep their skin. */
        float swell(float latitude, float longitude) {
            if (stage != 0) return 1.0F;
            return 1.0F + 0.17F * Mth.sin(3.0F * longitude + 2.0F * latitude + time) * Mth.cos(2.0F * longitude - 3.0F * latitude + time * 0.75F);
        }
        /** The slug's bands wind around it like rifling; elsewhere none. */
        boolean band(Facet facet) {
            return stage == 2 && Mth.sin(3.0F * (facet.longitude() + facet.latitude() * 2.2F)) > 0.3F;
        }
    }

    private static void drawFacets(VertexConsumer vertices, PoseStack.Pose pose, int light, int lighten, float opacity, Look look) {
        for (Facet facet : FACETS) {
            boolean band = look.band(facet);
            int red = band ? 185 : look.stage() == 2 ? facet.red - 22 : facet.red;
            int green = band ? 232 : look.stage() == 2 ? facet.green - 45 : facet.green;
            int blue = band ? 255 : look.stage() == 2 ? facet.blue - 12 : facet.blue;
            int alpha = band ? 235 : look.stage() == 2 ? facet.alpha + 35 : facet.alpha;
            if (facet.highlight) {
                red = 196; green = 239; blue = 255; alpha = 220;
            }
            for (int corner = 0; corner < 4; corner++) {
                float[] point = facet.corner(corner, look);
                vertices.addVertex(pose, point[0], point[1], point[2])
                        .setColor(Mth.clamp(red + lighten, 0, 255), Mth.clamp(green + lighten, 0, 255), Mth.clamp(blue + lighten, 0, 255), Mth.clamp((int) (alpha * opacity), 0, 255))
                        .setUv(corner < 2 ? 0.0F : 1.0F, corner == 0 || corner == 3 ? 1.0F : 0.0F)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, facet.nx, facet.ny, facet.nz);
            }
        }
    }

    private static List<Facet> makeFacets() {
        List<Facet> facets = new ArrayList<>();
        for (int band = 0; band < BANDS; band++) {
            float lower = -Mth.HALF_PI + band * Mth.PI / BANDS;
            float upper = lower + Mth.PI / BANDS;
            for (int segment = 0; segment < SEGMENTS; segment++) {
                float left = segment * Mth.TWO_PI / SEGMENTS;
                float right = (segment + 1) * Mth.TWO_PI / SEGMENTS;
                float latitude = (lower + upper) * 0.5F;
                float longitude = (left + right) * 0.5F;
                float nx = Mth.cos(latitude) * Mth.cos(longitude);
                float ny = Mth.sin(latitude);
                float nz = Mth.cos(latitude) * Mth.sin(longitude);
                // Upward facets are lighter, keeping a watery body instead of a flat blue sprite.
                int brightness = Math.round((Mth.clamp(ny + nx * 0.5F, -1.0F, 1.0F) + 1.0F) * 0.5F * 44.0F);
                facets.add(new Facet(lower, upper, left, right, nx, ny, nz, 30 + brightness, 140 + brightness, 212 + brightness, 150, false));
                // A small bright patch inside two facets: the shine on the water.
                if (band == 5 && (segment == 1 || segment == 2)) {
                    facets.add(new Facet(Mth.lerp(0.40F, lower, upper), Mth.lerp(0.75F, lower, upper), Mth.lerp(0.20F, left, right), Mth.lerp(0.65F, left, right),
                            nx, ny, nz, 196, 239, 255, 220, true));
                }
            }
        }
        return List.copyOf(facets);
    }

    private static float[] point(float latitude, float longitude, float swell, float lift) {
        float radius = Mth.cos(latitude) * 0.5F * swell, along = Mth.sin(latitude) * 0.5F * swell;
        // Behind the widest point it narrows and stretches into the tail of the drop; the flight runs along +y.
        if (along < 0.0F) {
            float behind = Math.min(1.0F, -along / 0.5F);
            radius *= 1.0F - 0.55F * behind * behind;
            along *= 1.0F + 0.65F * behind;
        }
        float nx = Mth.cos(latitude) * Mth.cos(longitude), ny = Mth.sin(latitude), nz = Mth.cos(latitude) * Mth.sin(longitude);
        return new float[] {radius * Mth.cos(longitude) + nx * lift, along + ny * lift, radius * Mth.sin(longitude) + nz * lift};
    }

    /** One quad of the skin between two latitudes and two longitudes. A highlight sits a hair above the skin it shines on. */
    private record Facet(float lower, float upper, float left, float right, float nx, float ny, float nz, int red, int green, int blue, int alpha, boolean highlight) {
        float latitude() { return (lower + upper) * 0.5F; }
        float longitude() { return (left + right) * 0.5F; }
        float[] corner(int corner, Look look) {
            float latitude = corner == 0 || corner == 3 ? lower : upper;
            float longitude = corner < 2 ? left : right;
            return point(latitude, longitude, look.swell(latitude, longitude), highlight ? 0.003F : 0.0F);
        }
    }
}
