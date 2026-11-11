package dev.joblessreincarnation.client.boss;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The fighting ground of an encounter that has one ({@link BossBarView#arenaRadius()}), drawn on the ground while the fight
 * runs, so the edge is visible instead of only announced. A thin brass line over a dark rim, which reads on grass and on
 * snow, with four rune ticks and a slow glow. For the one fighting it warms to orange over the last two blocks and turns
 * red, pulsing fast, once they are outside, while the server counts down their way back. Display only: the server alone
 * decides who is in the ring.
 */
public final class ArenaRing {
    private static final int SEGMENTS = 128;

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.options.hideGui) return;
        var rings = ClientBossBars.visible(player, 2).stream().map(shown -> shown.view)
                .filter(view -> view.arenaRadius() > 0 && view.stage() != BossBarView.Stage.RESULT).toList();
        if (rings.isEmpty()) return;
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        float time = (Util.getMillis() % 1_000_000L) / 1000.0F;
        for (BossBarView view : rings) {
            Vec3 centre = view.centre();
            pose.pushPose();
            // Relative to the ring's own centre, so the floats stay precise far from the world origin.
            pose.translate(centre.x - camera.x, centre.y + 0.03 - camera.y, centre.z - camera.z);
            ring(pose.last().pose(), quads, view, player.position(), time);
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    private static void ring(Matrix4f m, VertexConsumer out, BossBarView view, Vec3 at, float time) {
        Vec3 centre = view.centre();
        float radius = view.arenaRadius();
        double distance = Math.sqrt(Mth.square(at.x - centre.x) + Mth.square(at.z - centre.z));
        boolean outside = view.participant() && distance > radius;
        float warm = view.participant() ? Mth.clamp((float) (distance - (radius - 2)) / 2.0F, 0.0F, 1.0F) : 0.0F;
        float pulse = 0.5F + 0.5F * Mth.sin(time * (outside ? 9.0F : 2.2F));
        int red = outside ? 236 : (int) Mth.lerp(warm, 220, 245);
        int green = outside ? 72 : (int) Mth.lerp(warm, 196, 150);
        int blue = outside ? 60 : (int) Mth.lerp(warm, 143, 70);
        int alpha = (int) (outside ? 150 + 90 * pulse : 140 + 50 * pulse);
        band(m, out, -0.002F, radius - 0.11F, radius + 0.11F, 44, 34, 24, 110);
        band(m, out, 0.0F, radius - 0.05F, radius + 0.05F, red, green, blue, alpha);
        for (int k = 0; k < 4; k++) tick(m, out, radius, Mth.HALF_PI * k + Mth.PI / 4, red, green, blue, alpha);
    } // TGU5dW1l

    private static void band(Matrix4f m, VertexConsumer out, float y, float inner, float outer, int r, int g, int b, int a) {
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = Mth.TWO_PI * i / SEGMENTS, a1 = Mth.TWO_PI * (i + 1) / SEGMENTS;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            vertex(m, out, c0 * inner, y, s0 * inner, r, g, b, a);
            vertex(m, out, c0 * outer, y, s0 * outer, r, g, b, a);
            vertex(m, out, c1 * outer, y, s1 * outer, r, g, b, a);
            vertex(m, out, c1 * inner, y, s1 * inner, r, g, b, a);
        }
    }

    /** A short bar across the line, a rune mark. */
    private static void tick(Matrix4f m, VertexConsumer out, float radius, float angle, int r, int g, int b, int a) {
        float x = Mth.cos(angle), z = Mth.sin(angle), px = -z * 0.045F, pz = x * 0.045F;
        float inner = radius - 0.35F, outer = radius + 0.35F, y = 0.001F;
        vertex(m, out, x * inner - px, y, z * inner - pz, r, g, b, a);
        vertex(m, out, x * outer - px, y, z * outer - pz, r, g, b, a);
        vertex(m, out, x * outer + px, y, z * outer + pz, r, g, b, a);
        vertex(m, out, x * inner + px, y, z * inner + pz, r, g, b, a);
    }

    private static void vertex(Matrix4f m, VertexConsumer out, float x, float y, float z, int r, int g, int b, int a) {
        out.addVertex(m, x, y, z).setColor(r, g, b, a);
    }

    private ArenaRing() {}
}
