package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.magic.WaterShieldVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the water shield as a sheet of animated water standing on the ground: the vanilla still-water texture in a
 * fixed water blue, a pale crest along the top and spray along the foot. It rises, holds, then thins and sinks, and
 * brightens briefly when it stops an arrow. Visible from both sides; it never changes the server's geometry.
 */
public final class WaterShieldRenderer extends EntityRenderer<WaterShieldEntity> {
    private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final RenderType SHEET = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    private static final int[] BODY = {60, 118, 228};
    private static final int[] FOAM = {205, 232, 255};
    private static final int[] FLASH = {170, 210, 255};
    private static final float CREST = 0.09F;
    private static final float SPRAY = 0.12F;
    private static final float HALF_WIDTH = (float) WaterShieldEntity.WIDTH * 0.5F;
    /** Narrow upright strips, so the wave along the top reads as one smooth water line rather than three steps. */
    private static final int SLICES_PER_BLOCK = 8;

    public WaterShieldRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(WaterShieldEntity shield, float entityYaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight) {
        float age = shield.visualAge(partialTick);
        float fade = WaterShieldVisuals.fade(age);
        float height = WaterShieldVisuals.visibleHeight(age);
        if (fade <= 0.0F || height <= 0.02F) return;
        float ripple = shield.ripple(partialTick);
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);
        // A little light of its own keeps the screen readable at night without making it glow.
        int light = LightTexture.pack(Math.max(LightTexture.block(packedLight), 7), LightTexture.sky(packedLight));
        poses.pushPose();
        // Local +Z becomes the caster's facing, so the sheet spans local X and Y.
        poses.mulPose(Axis.YP.rotationDegrees(-shield.shieldYaw()));
        VertexConsumer out = buffers.getBuffer(SHEET);
        int[] body = mix(BODY, FLASH, 0.8F * ripple);
        int bodyAlpha = Math.round(255 * Mth.clamp(0.58F * fade + 0.25F * ripple, 0.0F, 1.0F));
        int crestAlpha = Math.round(255 * Mth.clamp(0.75F * fade + 0.2F * ripple, 0.0F, 1.0F));
        int sprayAlpha = Math.round(0.6F * crestAlpha);
        // A stopped arrow brightens the sheet at once and stirs the top edge, which swells and settles again.
        float stir = 4.0F * ripple * (1.0F - ripple);
        float wave = (1.0F + 2.5F * stir) * WaterShieldVisuals.rise(age);
        int slices = (int) Math.round(WaterShieldEntity.WIDTH * SLICES_PER_BLOCK);
        for (int slice = 0; slice < slices; slice++) {
            // Strip edges in blocks, measured across the sheet from its local -X edge.
            float x0 = (float) slice / SLICES_PER_BLOCK, x1 = (float) (slice + 1) / SLICES_PER_BLOCK, middle = (x0 + x1) * 0.5F;
            // Two slow waves running along the top edge, so the crest looks like moving water.
            float top = Math.max(0.02F, height + wave * (0.045F * Mth.sin(middle * 2.4F + age * 0.30F)
                    + 0.02F * Mth.sin(middle * 5.3F - age * 0.45F)));
            float sprayTop = Math.min(SPRAY, top * 0.5F), crestBottom = Math.max(sprayTop, top - CREST);
            // Spray, body and crest never overlap, so no two coplanar quads fight over the same pixels. Cuts at every
            // whole block keep the texture tiled one per block and continuous across the bands.
            float bottom = 0.0F;
            while (bottom < top) {
                float next = Math.min(top, (float) Math.floor(bottom) + 1.0F);
                if (bottom < sprayTop) next = Math.min(next, sprayTop);
                else if (bottom < crestBottom) next = Math.min(next, crestBottom);
                if (next <= bottom) break;
                if (bottom < sprayTop) band(poses.last(), out, sprite, x0, bottom, x1, next, FOAM, sprayAlpha, light);
                else if (bottom < crestBottom) band(poses.last(), out, sprite, x0, bottom, x1, next, body, bodyAlpha, light);
                else band(poses.last(), out, sprite, x0, bottom, x1, next, FOAM, crestAlpha, light);
                bottom = next;
            }
        }
        poses.popPose();
        super.render(shield, entityYaw, partialTick, poses, buffers, packedLight);
    }

    /**
     * One quad of the sheet within a single block column and row, with x measured from the sheet's local -X edge; the
     * texture is tiled once per block.
     */
    private static void band(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite,
                             float x0, float y0, float x1, float y1, int[] rgb, int alpha, int light) {
        float column = (float) Math.floor(x0), row = (float) Math.floor(y0);
        float uSpan = sprite.getU1() - sprite.getU0(), vSpan = sprite.getV1() - sprite.getV0();
        float uLeft = sprite.getU0() + uSpan * (x0 - column), uRight = sprite.getU0() + uSpan * (x1 - column);
        float vLow = sprite.getV1() - vSpan * (y0 - row), vHigh = sprite.getV1() - vSpan * (y1 - row);
        float left = x0 - HALF_WIDTH, right = x1 - HALF_WIDTH;
        vertex(pose, out, left, y0, uLeft, vLow, rgb, alpha, light);
        vertex(pose, out, right, y0, uRight, vLow, rgb, alpha, light);
        vertex(pose, out, right, y1, uRight, vHigh, rgb, alpha, light);
        vertex(pose, out, left, y1, uLeft, vHigh, rgb, alpha, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, float x, float y, float u, float v,
                               int[] rgb, int alpha, int light) {
        out.addVertex(pose, x, y, 0.0F)
                .setColor(rgb[0], rgb[1], rgb[2], alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    private static int[] mix(int[] from, int[] to, float amount) {
        return new int[] {
                Math.round(Mth.lerp(amount, from[0], to[0])),
                Math.round(Mth.lerp(amount, from[1], to[1])),
                Math.round(Mth.lerp(amount, from[2], to[2]))
        };
    }

    @Override
    public ResourceLocation getTextureLocation(WaterShieldEntity shield) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
