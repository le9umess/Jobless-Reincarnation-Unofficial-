package dev.joblessreincarnation.client.aura;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.joblessreincarnation.training.AuraVisual;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/** The aura carried onto the sword (the author: 通到剑上). The game draws the item into a collector here; the collected shape is pushed out and drawn from the inside, the same way as the body's shells. */
public final class AuraBlade {
    private static final float[] THICKNESS = {0.012F, 0.028F, 0.05F};
    private static final float[] WEIGHTS = {1.0F, 0.7F, 0.4F};
    private static final Method RENDER_ARM = renderArm();
    private static final Map<TextureAtlasSprite, byte[]> RINGS = new WeakHashMap<>();

    /** For each transparent pixel, how far it is from the sprite's opaque shape: 1 or 2; 0 for the shape itself and anything further out. */
    private static byte[] rings(TextureAtlasSprite sprite) {
        int width = sprite.contents().width(), height = sprite.contents().height();
        byte[] rings = new byte[width * height];
        boolean[] solid = new boolean[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) solid[y * width + x] = !sprite.contents().isTransparent(0, x, y);
        for (int ring = 1; ring <= 2; ring++) {
            byte[] previous = rings.clone();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (solid[y * width + x] || previous[y * width + x] != 0) continue;
                    boolean near = false;
                    for (int dy = -1; dy <= 1 && !near; dy++) {
                        for (int dx = -1; dx <= 1 && !near; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                            near = ring == 1 ? solid[ny * width + nx] : previous[ny * width + nx] == ring - 1;
                        }
                    }
                    if (near) rings[y * width + x] = (byte) ring;
                }
            }
        }
        return rings;
    }

    private static Method renderArm() {
        try {
            Method method = ItemInHandRenderer.class.getDeclaredMethod("renderArmWithItem", AbstractClientPlayer.class, float.class, float.class, InteractionHand.class, float.class, ItemStack.class, float.class, com.mojang.blaze3d.vertex.PoseStack.class, MultiBufferSource.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    /** How bright the sword is for this signal; zero when it carries nothing. */
    static float level(AuraVisual cue, long gameTime, float partialTick) {
        return switch (cue.phase()) {
            case READY -> 0.55F;
            case GUARD -> 0.55F + cue.flash(gameTime, partialTick) * 1.1F;
            case GATHER -> 1.2F;
            default -> 0.0F;
        };
    }

    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (RENDER_ARM == null || player == null || event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        AuraVisual cue = AuraVisual.of(player);
        float level = level(cue, player.level().getGameTime(), event.getPartialTick());
        if (cue.phase() == AuraVisual.Phase.GATHER) level += 0.2F * AuraCues.gatherTier(player) + 0.9F * AuraCues.tierFlash(player, event.getPartialTick());
        if (level <= 0.0F) return;
        var renderer = minecraft.getEntityRenderDispatcher().getItemInHandRenderer();
        var pose = event.getPoseStack();
        // Gathering lifts the sword overhead on the body, which this view never shows: lift the hand toward it and draw it here.
        // The outline stays off while it is lifted: drawn on the tilted model it spread into a sheet behind the blade. The screen glow
        // and the tier chimes still carry the gathering in this view.
        float raise = cue.phase() == AuraVisual.Phase.GATHER ? raise(player.level().getGameTime() - cue.startedAt() + event.getPartialTick()) : 0.0F;
        if (raise > 0.0F) {
            pose.pushPose();
            pose.translate(0.0F, 0.2F * raise, 0.04F * raise);
            pose.mulPose(Axis.XP.rotationDegrees(26.0F * raise));
            try {
                RENDER_ARM.invoke(renderer, player, event.getPartialTick(), event.getInterpolatedPitch(), event.getHand(), event.getSwingProgress(),
                        event.getItemStack(), event.getEquipProgress(), pose, event.getMultiBufferSource(), event.getPackedLight());
                event.setCanceled(true);
            } catch (ReflectiveOperationException failure) {
                // Leave the hand to the game if the lift cannot be drawn.
            }
            pose.popPose();
            return;
        }
        Shape shape = new Shape();
        try {
            RENDER_ARM.invoke(renderer, player, event.getPartialTick(), event.getInterpolatedPitch(), event.getHand(), event.getSwingProgress(),
                    event.getItemStack(), event.getEquipProgress(), pose, shape.collector(), event.getPackedLight());
        } catch (ReflectiveOperationException failure) {
            return;
        }
        shape.draw(event.getMultiBufferSource(), event.getItemStack(), player.tickCount + event.getPartialTick(), level, event.getPackedLight());
    }

    /** Eased over the first eight ticks of the gathering, about as long as the body takes to get the sword up. */
    private static float raise(float age) {
        float t = Mth.clamp(age / 8.0F, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** One item's surface as the renderer sent it, already in camera space. */
    static final class Shape implements VertexConsumer {
        private final List<float[]> vertices = new ArrayList<>();
        private float[] current = new float[8];
        private static final VertexConsumer NOTHING = new VertexConsumer() {
            @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
            @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
            @Override public VertexConsumer setUv(float u, float v) { return this; }
            @Override public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }
            @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        };

        /** The enchantment glint is the same surface again and would double every vertex. */
        MultiBufferSource collector() {
            return type -> type.toString().contains("glint") ? NOTHING : this;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new float[] {x, y, z, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
            vertices.add(current);
            return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            current[3] = u;
            current[4] = v;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            current[5] = x;
            current[6] = y;
            current[7] = z;
            return this;
        }

        void draw(MultiBufferSource buffers, ItemStack stack, float time, float level, int light) {
            if (vertices.isEmpty()) return;
            if (flat(buffers, stack, time, level, light)) return;
            // Faces meeting at a corner share one direction, so the pushed-out shape stays closed.
            Map<Long, float[]> sums = new HashMap<>();
            for (float[] vertex : vertices) {
                float[] sum = sums.computeIfAbsent(key(vertex), k -> new float[3]);
                sum[0] += vertex[5];
                sum[1] += vertex[6];
                sum[2] += vertex[7];
            }
            for (float[] sum : sums.values()) {
                float length = (float) Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
                if (length < 1.0E-4F) continue;
                sum[0] /= length;
                sum[1] /= length;
                sum[2] /= length;
            }
            for (int shell = 0; shell < THICKNESS.length; shell++) {
                RenderType type = AuraLayer.bladeType((time * 0.0013F + shell * 0.31F) % 1.0F, (time * (0.018F + 0.006F * shell) + shell * 0.17F) % 1.0F);
                VertexConsumer out = buffers.getBuffer(type);
                float strength = Math.min(1.0F, level * WEIGHTS[shell]);
                for (float[] vertex : vertices) {
                    float[] direction = sums.get(key(vertex));
                    float waver = 1.0F + 0.3F * (float) Math.sin(time * 0.37F + (vertex[0] + vertex[1] * 1.7F + vertex[2]) * 23.0F);
                    float push = THICKNESS[shell] * waver;
                    out.addVertex(vertex[0] + direction[0] * push, vertex[1] + direction[1] * push, vertex[2] + direction[2] * push)
                            .setColor(AuraLayer.RED * strength, AuraLayer.GREEN * strength, AuraLayer.BLUE * strength, 1.0F)
                            .setUv(vertex[3] * 16.0F, vertex[4] * 16.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
                }
            }
        }

        /** A generated item model is one see-through square each way, and the sword is only in its alpha. So the glow is the transparent pixels just around the opaque ones. */
        private boolean flat(MultiBufferSource buffers, ItemStack stack, float time, float level, int light) {
            float[][] face = largestFace();
            if (face == null) return false;
            TextureAtlasSprite sprite = Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0).getParticleIcon();
            byte[] rings = RINGS.computeIfAbsent(sprite, AuraBlade::rings);
            int width = sprite.contents().width(), height = sprite.contents().height();
            float[] origin = null, across = null, down = null;
            for (float[] corner : face) {
                float s = (corner[3] - sprite.getU0()) / (sprite.getU1() - sprite.getU0()), t = (corner[4] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
                if (s < 0.5F && t < 0.5F) origin = corner;
                else if (s >= 0.5F && t < 0.5F) across = corner;
                else if (s < 0.5F) down = corner;
            }
            if (origin == null || across == null || down == null) return false;
            VertexConsumer out = buffers.getBuffer(AuraLayer.ribbonType((time * 0.0013F) % 1.0F, (time * 0.02F) % 1.0F));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int ring = rings[y * width + x];
                    if (ring == 0) continue;
                    float flicker = 0.75F + 0.25F * (float) Math.sin(time * 0.43F + (x * 7 + y * 13));
                    float strength = Math.min(1.0F, level * flicker * (ring == 1 ? 0.8F : 0.35F));
                    pixel(out, origin, across, down, (float) x / width, (float) y / height, 1.0F / width, 1.0F / height, strength, light);
                }
            }
            return true;
        }

        private float[][] largestFace() {
            float largest = 0.0F, smallest = Float.MAX_VALUE;
            float[][] best = null;
            for (int i = 0; i + 3 < vertices.size(); i += 4) {
                float[] a = vertices.get(i), b = vertices.get(i + 1), d = vertices.get(i + 3);
                float one = distance(a, b), two = distance(a, d), size = Math.min(one, two);
                if (size > largest) {
                    largest = size;
                    best = new float[][] {a, b, vertices.get(i + 2), d};
                }
                if (size > 1.0E-5F) smallest = Math.min(smallest, size);
            }
            // Only a model with one huge face among thin side strips is a flat item.
            return best != null && largest > smallest * 6.0F ? best : null;
        }

        private static float distance(float[] a, float[] b) {
            float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        private static void pixel(VertexConsumer out, float[] origin, float[] across, float[] down, float s, float t, float ds, float dt, float strength, int light) {
            float red = AuraLayer.RED * strength, green = AuraLayer.GREEN * strength, blue = AuraLayer.BLUE * strength;
            for (int corner = 0; corner < 4; corner++) {
                float cs = s + (corner == 1 || corner == 2 ? ds : 0.0F), ct = t + (corner >= 2 ? dt : 0.0F);
                float x = origin[0] + (across[0] - origin[0]) * cs + (down[0] - origin[0]) * ct;
                float y = origin[1] + (across[1] - origin[1]) * cs + (down[1] - origin[1]) * ct;
                float z = origin[2] + (across[2] - origin[2]) * cs + (down[2] - origin[2]) * ct;
                out.addVertex(x, y, z).setColor(red, green, blue, 1.0F).setUv(cs, ct).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
            }
        }

        private static long key(float[] vertex) {
            long x = Math.round(vertex[0] * 4000.0F) & 0x1FFFFF;
            long y = Math.round(vertex[1] * 4000.0F) & 0x1FFFFF;
            long z = Math.round(vertex[2] * 4000.0F) & 0x1FFFFF;
            return x << 42 | y << 21 | z;
        }
    }

    private AuraBlade() {}
}
