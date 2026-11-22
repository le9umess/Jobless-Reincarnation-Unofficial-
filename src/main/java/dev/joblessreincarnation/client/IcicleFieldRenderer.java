package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.IcicleFieldEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Icicle Field on the ground (OFF7-03 visual study): pale frost stamps laid out by {@link IcicleFieldLook}. They spread
 * from the centre outward while the field forms, linger a moment once it starts to fade, then recede from the edge
 * inward. Each sits on the surface under it as it is now (water, then the frosted ice the server makes of it, then water
 * again) and only where the field can see it, never behind a wall. Lit like the ground beneath, never full-bright, so in
 * the dark the frost stays dark rather than glowing. Display only; the server alone decides what the cold touches.
 */
public final class IcicleFieldRenderer extends EntityRenderer<IcicleFieldEntity> {
    private static final ResourceLocation FROST = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,
            "textures/entity/icicle_field/frost.png");
    /** The frost lingers this long after the field starts fading, then recedes over the rest of the fade. */
    private static final float LINGER = 8;

    public IcicleFieldRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override public ResourceLocation getTextureLocation(IcicleFieldEntity field) { return FROST; }

    /** The field's own box is tiny; the frost covers the whole disc, so cull on that. */
    @Override public boolean shouldRender(IcicleFieldEntity field, Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(field.getBoundingBox().inflate(IcicleFieldEntity.RADIUS + 0.5, 1, IcicleFieldEntity.RADIUS + 0.5));
    }

    @Override public void render(IcicleFieldEntity field, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float age = field.ageTicks(partial);
        float fadeStart = field.fadeGameTime() - field.startGameTime();
        float grow = Mth.clamp(age / IcicleFieldEntity.FORM_TICKS, 0, 1);
        float recede = Mth.clamp((age - fadeStart - LINGER) / (IcicleFieldEntity.FADE_TICKS - LINGER), 0, 1);
        float reach = Math.min(grow, 1 - recede);
        if (reach <= 0) return;
        IcicleFieldLook.Layout layout = IcicleFieldLook.of(field);
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(FROST));
        for (int i = 0; i < layout.stamps.size(); i++) {
            IcicleFieldLook.Stamp stamp = layout.stamps.get(i);
            if (!layout.seen[i] || stamp.edge() > reach) continue;
            // Denser near the centre, thinner toward the edge; a soft front where the frost is spreading or receding.
            float alpha = (0.95F - 0.4F * stamp.edge()) * Mth.clamp((reach - stamp.edge()) * 5, 0, 1);
            if (alpha <= 0.01F) continue;
            double y = layout.ground[i];
            int lit = LevelRenderer.getLightColor(field.level(), BlockPos.containing(stamp.x(), y + 0.2, stamp.z()));
            pose.pushPose();
            pose.translate(stamp.x() - field.getX(), y - field.getY() + stamp.lift(), stamp.z() - field.getZ());
            quad(pose.last(), out, stamp.turn(), alpha, lit);
            pose.popPose();
        }
    }

    /** A flat stamp facing up, its texture turned by quarter turns. */
    private static void quad(PoseStack.Pose pose, VertexConsumer out, int turn, float alpha, int light) {
        float h = IcicleFieldLook.CARD / 2;
        float[][] corners = {{-h, -h}, {-h, h}, {h, h}, {h, -h}};
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        int a = Math.round(alpha * 255);
        for (int i = 0; i < 4; i++) {
            float[] c = corners[i], t = uv[(i + turn) % 4];
            out.addVertex(pose, c[0], 0, c[1]).setColor(255, 255, 255, a).setUv(t[0], t[1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
        }
    }
}
