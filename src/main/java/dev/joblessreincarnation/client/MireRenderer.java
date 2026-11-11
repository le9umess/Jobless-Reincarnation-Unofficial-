package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.MireVisuals;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws a mire as wet mud lying on exactly the 21 cells the server slows on: vanilla mud, darkest at the centre, inside
 * a band of wet earth (packed mud, darkened) along the outer edge, under a thin film of still water that moves and a
 * few small wet glints. When slowing stops, the film and glints go and the mud dries into pale packed mud as it fades.
 * Every layer uses the same rectangles one above the other, so the translucent sort, which orders quads by their
 * centres, keeps them in order; the glints, which are smaller, go in a batch of their own drawn after them. It never
 * changes blocks and adds no light source; the film and glints keep a little light of their own so the wet surface
 * still reads at night.
 */
public final class MireRenderer extends EntityRenderer<MireEntity> {
    private static final ResourceLocation MUD = ResourceLocation.withDefaultNamespace("block/mud");
    private static final ResourceLocation EARTH = ResourceLocation.withDefaultNamespace("block/packed_mud");
    private static final ResourceLocation FILM = ResourceLocation.withDefaultNamespace("block/water_still");
    /** Nearly uniform white, so a glint is as bright as its tint. */
    private static final ResourceLocation SHINE = ResourceLocation.withDefaultNamespace("block/snow");
    private static final RenderType LAYERS = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    /** A separate instance of the same render type: a later batch, so the glints draw over the finished surface. */
    private static final RenderType GLINTS = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS, false);
    /** Heights above the ground's top face, one per layer, far enough apart to stay in order at a distance. */
    private static final float MUD_Y = 0.015625F;
    private static final float DRY_Y = 0.022F;
    private static final float FILM_Y = 0.03F;
    private static final float GLINT_Y = 0.034F;
    /** Mud multipliers, warming its grey: deeper at the centre. */
    private static final float[] CENTRE = {0.78F, 0.66F, 0.56F};
    private static final float[] OUTER = {1.0F, 0.86F, 0.72F};
    /** Packed mud multipliers: wet earth at the edge, pale once dried. */
    private static final float[] BAND = {0.55F, 0.47F, 0.42F};
    private static final float[] DRIED = {0.95F, 0.90F, 0.86F};
    /** Cool, like water reflecting the sky over warm mud; a warm film turned pink under the night lightmap. */
    private static final int[] FILM_RGB = {150, 158, 165};
    private static final int[] GLINT_RGB = {235, 232, 220};
    private static final float MUD_ALPHA = 0.95F;
    private static final float BAND_ALPHA = 0.9F;
    private static final float FILM_ALPHA = 0.3F;
    private static final float GLINT_ALPHA = 0.6F;
    private static final float GLINT_SIZE = 1.0F / 16.0F;
    private static final int FILM_MIN_BLOCK_LIGHT = 6;
    private static final int GLINT_MIN_BLOCK_LIGHT = 9;
    private static final List<Patch> PATCHES = patches();

    /** One rectangle of a cell, in blocks from the field's centre; band patches run along the footprint's outline. */
    private record Patch(int cellX, int cellZ, float x0, float z0, float x1, float z1, boolean band) {}

    public MireRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(MireEntity field, float entityYaw, float partialTick, PoseStack poses, MultiBufferSource buffers,
                       int packedLight) {
        float age = field.ageTicks(partialTick);
        float fade = MireVisuals.fade(age);
        if (fade <= 0.0F) return;
        // The mud dries into packed mud over the first half of the fade, while the whole surface thins out.
        float dried = smooth(MireVisuals.dry(age) * 2.0F), rim = MireVisuals.rim(age), wet = MireVisuals.wet(age);
        var atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite mud = atlas.apply(MUD), earth = atlas.apply(EARTH), film = atlas.apply(FILM);
        BlockPos centre = field.blockPosition();
        int[] light = new int[25];
        for (BlockPos offset : MireEntity.CELL_OFFSETS) {
            light[index(offset.getX(), offset.getZ())] = LevelRenderer.getLightColor(field.level(), centre.offset(offset));
        }
        PoseStack.Pose pose = poses.last();
        VertexConsumer out = buffers.getBuffer(LAYERS);
        for (Patch patch : PATCHES) {
            long seed = cellSeed(centre.getX() + patch.cellX(), centre.getZ() + patch.cellZ());
            boolean flipU = (seed & 1L) != 0L, flipV = (seed & 2L) != 0L;
            int cellLight = light[index(patch.cellX(), patch.cellZ())];
            float[][] corners = corners(patch);
            if (patch.band()) {
                float[] tint = mix(BAND, DRIED, dried);
                for (float[] c : corners) vertex(pose, out, earth, patch, c[0], MUD_Y, c[1], flipU, flipV, tint, BAND_ALPHA * rim, cellLight);
            } else {
                for (float[] c : corners) {
                    float distance = Mth.sqrt(c[0] * c[0] + c[1] * c[1]);
                    float[] tint = mix(CENTRE, OUTER, Mth.clamp(distance / (float) MireEntity.RADIUS, 0.0F, 1.0F));
                    vertex(pose, out, mud, patch, c[0], MUD_Y, c[1], flipU, flipV, tint,
                            MUD_ALPHA * MireVisuals.fill(age, distance) * fade * (1.0F - dried), cellLight);
                }
                if (dried > 0.0F) {
                    for (float[] c : corners) {
                        float distance = Mth.sqrt(c[0] * c[0] + c[1] * c[1]);
                        vertex(pose, out, earth, patch, c[0], DRY_Y, c[1], flipU, flipV, DRIED,
                                MUD_ALPHA * MireVisuals.fill(age, distance) * fade * dried, cellLight);
                    }
                }
            }
            if (wet <= 0.0F) continue;
            int filmLight = brighten(cellLight, FILM_MIN_BLOCK_LIGHT);
            // Each cell shimmers a little out of step with its neighbours.
            float shimmer = 0.8F + 0.2F * Mth.sin(age * 0.15F + (seed >>> 8 & 63L) * 0.1F);
            for (float[] c : corners) {
                float fill = MireVisuals.fill(age, Mth.sqrt(c[0] * c[0] + c[1] * c[1]));
                vertex(pose, out, film, patch, c[0], FILM_Y, c[1], false, false, FILM_RGB, FILM_ALPHA * wet * shimmer * fill, filmLight);
            }
        }
        if (wet > 0.0F) glints(pose, buffers.getBuffer(GLINTS), atlas.apply(SHINE), centre, light, age, wet);
        super.render(field, entityYaw, partialTick, poses, buffers, packedLight);
    }

    /**
     * Two tiny highlights per cell at fixed spots inside the band, each flashing briefly now and then, out of step, so a
     * few are always catching the light somewhere on the surface.
     */
    private static void glints(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite shine, BlockPos centre,
                               int[] light, float age, float wet) {
        float inner = 0.5F - MireVisuals.RIM - GLINT_SIZE;
        for (BlockPos cell : MireEntity.CELL_OFFSETS) {
            long seed = cellSeed(centre.getX() + cell.getX(), centre.getZ() + cell.getZ());
            int glintLight = brighten(light[index(cell.getX(), cell.getZ())], GLINT_MIN_BLOCK_LIGHT);
            for (int n = 0; n < 2; n++) {
                long bits = seed >>> (16 + n * 20);
                float x = cell.getX() + ((bits & 255L) / 255.0F * 2.0F - 1.0F) * inner;
                float z = cell.getZ() + ((bits >>> 8 & 255L) / 255.0F * 2.0F - 1.0F) * inner;
                float flash = Mth.sin(age * 0.11F + (bits >>> 16 & 15L) * 0.4F);
                float alpha = GLINT_ALPHA * wet * (flash > 0.0F ? (float) Math.pow(flash, 6.0) : 0.0F);
                if (alpha < 0.02F) continue;
                Patch spot = new Patch(cell.getX(), cell.getZ(), x, z, x + GLINT_SIZE, z + GLINT_SIZE, false);
                for (float[] c : corners(spot)) vertex(pose, out, shine, spot, c[0], GLINT_Y, c[1], false, false, GLINT_RGB, alpha, glintLight);
            }
        }
    }

    private static float[][] corners(Patch patch) {
        return new float[][] {{patch.x0(), patch.z0()}, {patch.x0(), patch.z1()}, {patch.x1(), patch.z1()}, {patch.x1(), patch.z0()}};
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, Patch patch, float x, float y, float z,
                               boolean flipU, boolean flipV, float[] tint, float alpha, int light) {
        vertex(pose, out, sprite, patch, x, y, z, flipU, flipV, Math.round(255 * tint[0]), Math.round(255 * tint[1]), Math.round(255 * tint[2]),
                Math.round(255 * Mth.clamp(alpha, 0.0F, 1.0F)), light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, Patch patch, float x, float y, float z,
                               boolean flipU, boolean flipV, int[] rgb, float alpha, int light) {
        vertex(pose, out, sprite, patch, x, y, z, flipU, flipV, rgb[0], rgb[1], rgb[2], Math.round(255 * Mth.clamp(alpha, 0.0F, 1.0F)), light);
    }

    /** The texture is tiled once per cell, mirrored per cell from its world position so the tiling repeats less. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, Patch patch, float x, float y, float z,
                               boolean flipU, boolean flipV, int r, int g, int b, int a, int light) {
        float across = x - (patch.cellX() - 0.5F), along = z - (patch.cellZ() - 0.5F);
        float u = Mth.lerp(flipU ? 1.0F - across : across, sprite.getU0(), sprite.getU1());
        float v = Mth.lerp(flipV ? 1.0F - along : along, sprite.getV0(), sprite.getV1());
        out.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /**
     * Splits each cell into a 3 × 3 grid at the band width. A grid piece belongs to the band when it touches an outer
     * side of the footprint, or is the corner piece next to a missing diagonal neighbour, so the band also turns the
     * inner corners without a gap. Nothing is drawn outside the cells.
     */
    private static List<Patch> patches() {
        List<Patch> patches = new ArrayList<>(MireEntity.CELL_OFFSETS.size() * 9);
        for (BlockPos cell : MireEntity.CELL_OFFSETS) {
            int cx = cell.getX(), cz = cell.getZ();
            float rim = MireVisuals.RIM;
            float[] xs = {cx - 0.5F, cx - 0.5F + rim, cx + 0.5F - rim, cx + 0.5F};
            float[] zs = {cz - 0.5F, cz - 0.5F + rim, cz + 0.5F - rim, cz + 0.5F};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                boolean band = i == 0 && !has(cx - 1, cz) || i == 2 && !has(cx + 1, cz)
                        || j == 0 && !has(cx, cz - 1) || j == 2 && !has(cx, cz + 1)
                        || i != 1 && j != 1 && !has(cx + (i == 0 ? -1 : 1), cz + (j == 0 ? -1 : 1));
                patches.add(new Patch(cx, cz, xs[i], zs[j], xs[i + 1], zs[j + 1], band));
            }
        }
        return List.copyOf(patches);
    }

    /** Position hash in the style of block model randomness, so a cell keeps its mirroring and glints frame to frame. */
    private static long cellSeed(int x, int z) {
        long seed = x * 3129871L ^ z * 116129781L;
        return (seed * seed * 42317861L + seed * 11L) >> 16;
    }

    private static int brighten(int packedLight, int minimumBlockLight) {
        return LightTexture.pack(Math.max(LightTexture.block(packedLight), minimumBlockLight), LightTexture.sky(packedLight));
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static boolean has(int x, int z) {
        return MireEntity.CELL_OFFSETS.contains(new BlockPos(x, 0, z));
    }

    private static int index(int x, int z) {
        return (x + 2) * 5 + (z + 2);
    }

    private static float[] mix(float[] from, float[] to, float amount) {
        return new float[] {Mth.lerp(amount, from[0], to[0]), Mth.lerp(amount, from[1], to[1]), Mth.lerp(amount, from[2], to[2])};
    }

    @Override
    public ResourceLocation getTextureLocation(MireEntity field) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
