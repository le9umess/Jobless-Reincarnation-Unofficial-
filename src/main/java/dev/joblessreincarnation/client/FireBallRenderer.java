package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.FireBallProjectile;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A hot core with a tongue of flame trailing behind it, drawn as camera-facing frames from an original sheet. Seen from
 * behind, it reads as a round fire instead. Purely visual: the hit box and the sweep live in the projectile.
 */
public final class FireBallRenderer extends EntityRenderer<FireBallProjectile> {
    private static final ResourceLocation SHEET = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/spell/fire_ball.png");
    private static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/spell/glow.png");
    private static final RenderType FLAME = RenderType.entityTranslucentEmissive(SHEET);
    private static final RenderType HALO = RenderType.eyes(GLOW);
    private static final int FRAMES = 8, TAIL_ROW = 0, ROUND_ROW = 1;
    /** The sheet's tail frames have the head this far below the frame's centre, in half-frames. */
    private static final float HEAD_OFFSET = 0.34F;
    /** A fresh shot starts at the caster's eye: keep it out of the view until it has cleared the head. */
    private static final float NEAR_FADE_FROM = 1.5F, NEAR_FADE_TO = 3.0F;

    public FireBallRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = 0; }
    @Override public ResourceLocation getTextureLocation(FireBallProjectile entity) { return SHEET; }

    @Override public void render(FireBallProjectile entity, float yaw, float partialTick, PoseStack poses,
            MultiBufferSource buffers, int packedLight) {
        Camera camera = entityRenderDispatcher.camera;
        Vec3 centre = entity.getPosition(partialTick).add(0, entity.getBbHeight() * 0.5F, 0);
        float near = Mth.clamp(((float) camera.getPosition().distanceTo(centre) - NEAR_FADE_FROM) / (NEAR_FADE_TO - NEAR_FADE_FROM), 0, 1);
        if (near > 0) {
            poses.pushPose();
            poses.translate(0, entity.getBbHeight() * 0.5F, 0);
            draw(entity, camera, partialTick, poses.last(), buffers, near);
            poses.popPose();
        }
        super.render(entity, yaw, partialTick, poses, buffers, LightTexture.FULL_BRIGHT);
    }

    private static void draw(FireBallProjectile entity, Camera camera, float partialTick, PoseStack.Pose pose, MultiBufferSource buffers, float near) {
        int stage = Mth.clamp(entity.formStage(), 0, 2);
        Vector3f up = camera.getUpVector(), left = camera.getLeftVector();
        Vec3 motion = entity.getDeltaMovement();
        // The flight as seen on screen: crossing is 0 when it flies straight along the view, 1 when it crosses it.
        float onUp = (float) (motion.x * up.x + motion.y * up.y + motion.z * up.z);
        float onLeft = (float) (motion.x * left.x + motion.y * left.y + motion.z * left.z);
        float screen = Mth.sqrt(onUp * onUp + onLeft * onLeft);
        float speed = (float) motion.length();
        float crossing = speed > 1.0E-4F ? Mth.clamp(screen / speed, 0, 1) : 0;
        float tail = Mth.clamp((crossing - 0.25F) / 0.45F, 0, 1);
        float age = entity.tickCount + partialTick;
        int frame = (int) (age / 1.5F) + entity.getId();
        // The novice's fire will not hold together: a few small flames tumbling round each other. The practised one is a round
        // ball. The adept's is packed tight: smaller and hotter than the practised ball, white at the heart, with a long tongue.
        float scale = stage == 0 ? 0.8F : stage == 1 ? 1.0F : 0.88F;
        float flicker = scale * (1 + Mth.sin(age * (stage == 0 ? 1.7F : 0.9F) + entity.getId()) * (stage == 0 ? 0.15F : stage == 1 ? 0.06F : 0.03F));
        // The novice's burns a duller orange.
        int green = stage == 0 ? 214 : 255, blue = stage == 0 ? 186 : 255;

        // A glow around it that shows at night. It only adds light, so it fades by itself against a bright sky. Close up it would
        // be a big stepped square over the whole view, so it waits until the ball has got away from the camera.
        float clear = Mth.clamp(((float) camera.getPosition().distanceTo(entity.getPosition(partialTick).add(0, entity.getBbHeight() * 0.5F, 0)) - 2.6F) / 2.4F, 0, 1);
        float light = (stage == 0 ? 0.24F : stage == 1 ? 0.34F : 0.46F) * near * clear;
        if (light > 0) quad(buffers.getBuffer(HALO), pose, left, up, 0, 1, 0, 0.74F * flicker, 0, 1, 0, 1, (int) (255 * light), (int) (120 * light), (int) (40 * light), 255);

        VertexConsumer flame = buffers.getBuffer(FLAME);
        int alpha = (int) (255 * near);
        float spin = age * 0.11F;
        if (stage == 0) {
            // Three small flames chasing round each other, none of them the middle, each jumping on its own.
            for (int k = 0; k < 3; k++) {
                float around = age * 0.55F + k * Mth.TWO_PI / 3 + entity.getId(), reach = 0.11F + Mth.sin(age * 1.9F + k * 2.1F) * 0.04F;
                float moveLeft = Mth.cos(around) * reach, moveUp = Mth.sin(around) * reach;
                float size = 0.27F * flicker * (1 + Mth.sin(age * 2.3F + k * 1.7F) * 0.12F);
                if (tail < 1) frame(flame, pose, left, up, moveLeft, moveUp, Mth.sin(spin + k), Mth.cos(spin + k), 0, size, frame + k * 3, ROUND_ROW, (int) (alpha * (1 - tail)), green, blue);
                if (tail > 0 && screen > 1.0E-4F) {
                    float backLeft = -onLeft / screen, backUp = -onUp / screen;
                    frame(flame, pose, left, up, moveLeft, moveUp, backLeft, backUp, size * 1.15F * HEAD_OFFSET, size * 1.15F, frame + k * 3, TAIL_ROW, (int) (alpha * tail), green, blue);
                }
            }
            return;
        }
        boolean adept = stage == 2;
        // Seen from behind: a round fire, two layers turning opposite ways so it never looks like a stamp.
        if (tail < 1) {
            int round = (int) (alpha * (1 - tail));
            frame(flame, pose, left, up, 0, 0, Mth.sin(spin), Mth.cos(spin), 0, 0.5F * flicker, frame, ROUND_ROW, round, green, blue);
            frame(flame, pose, left, up, 0, 0, Mth.sin(-spin * 1.4F), Mth.cos(-spin * 1.4F), 0, 0.34F * scale, frame + 3, ROUND_ROW, round, green, blue);
            if (adept) {
                // The adept's flame is a vortex: two more layers wound tight inside the outline and turning fast.
                frame(flame, pose, left, up, 0, 0, Mth.sin(spin * 3.1F), Mth.cos(spin * 3.1F), 0, 0.46F * flicker, frame + 5, ROUND_ROW, round * 3 / 4, 255, 255);
                frame(flame, pose, left, up, 0, 0, Mth.sin(-spin * 3.7F), Mth.cos(-spin * 3.7F), 0, 0.38F * flicker, frame + 6, ROUND_ROW, round * 2 / 3, 255, 255);
            }
        }
        // Crossing the view: the head stays on the projectile and the tongue trails opposite the flight. The adept's tongue is
        // drawn out long and thin behind its small head.
        if (tail > 0 && screen > 1.0E-4F) {
            int long_ = (int) (alpha * tail);
            float backLeft = -onLeft / screen, backUp = -onUp / screen;
            float big = 0.62F * flicker, small = 0.4F * scale;
            frame(flame, pose, left, up, 0, 0, backLeft, backUp, big * HEAD_OFFSET, big, frame, TAIL_ROW, long_, green, blue);
            frame(flame, pose, left, up, 0, 0, backLeft, backUp, small * HEAD_OFFSET, small, frame + 4, TAIL_ROW, long_, green, blue);
            if (adept) {
                float far = 0.95F * scale;
                frame(flame, pose, left, up, 0, 0, backLeft, backUp, far * 0.62F, far, frame + 2, TAIL_ROW, long_ * 3 / 5, 255, 255);
            }
        }
        // The adept's white-hot heart. It only adds light, so it whitens the middle of the flame without hiding it.
        if (adept) {
            float size = 0.25F + Mth.sin(age * 1.3F) * 0.015F;
            int core = (int) (255 * near);
            quad(buffers.getBuffer(HALO), pose, left, up, 0, 1, 0, size, 0, 1, 0, 1, core, core, (int) (core * 0.9F), 255);
        }
    }

    /** One frame of the sheet, its top along (topLeft, topUp) on screen, moved (moveLeft, moveUp) across the screen and tinted. */
    private static void frame(VertexConsumer vertices, PoseStack.Pose pose, Vector3f left, Vector3f up, float moveLeft, float moveUp, float topLeft, float topUp,
            float shift, float size, int frame, int row, int alpha, int green, int blue) {
        if (alpha <= 0) return;
        float u0 = Math.floorMod(frame, FRAMES) / (float) FRAMES, v0 = row * 0.5F;
        PoseStack.Pose at = pose;
        if (moveLeft != 0 || moveUp != 0) {
            at = pose.copy();
            at.pose().translate(left.x * moveLeft + up.x * moveUp, left.y * moveLeft + up.y * moveUp, left.z * moveLeft + up.z * moveUp);
        }
        quad(vertices, at, left, up, topLeft, topUp, shift, size, u0, u0 + 1.0F / FRAMES, v0, v0 + 0.5F, 255, green, blue, alpha);
    }

    /** A camera-facing square whose top points along (topLeft, topUp) on screen, moved {@code shift} toward its top. */
    private static void quad(VertexConsumer vertices, PoseStack.Pose pose, Vector3f left, Vector3f up, float topLeft, float topUp, float shift, float size,
            float u0, float u1, float v0, float v1, int red, int green, int blue, int alpha) {
        float tx = left.x * topLeft + up.x * topUp, ty = left.y * topLeft + up.y * topUp, tz = left.z * topLeft + up.z * topUp;
        // To the viewer's right of that top.
        float rx = up.x * topLeft - left.x * topUp, ry = up.y * topLeft - left.y * topUp, rz = up.z * topLeft - left.z * topUp;
        float cx = tx * shift, cy = ty * shift, cz = tz * shift;
        // Counter-clockwise as the viewer sees it: lower left, lower right, upper right, upper left.
        vertex(vertices, pose, cx + (-rx - tx) * size, cy + (-ry - ty) * size, cz + (-rz - tz) * size, u0, v1, red, green, blue, alpha);
        vertex(vertices, pose, cx + (rx - tx) * size, cy + (ry - ty) * size, cz + (rz - tz) * size, u1, v1, red, green, blue, alpha);
        vertex(vertices, pose, cx + (rx + tx) * size, cy + (ry + ty) * size, cz + (rz + tz) * size, u1, v0, red, green, blue, alpha);
        vertex(vertices, pose, cx + (-rx + tx) * size, cy + (-ry + ty) * size, cz + (-rz + tz) * size, u0, v0, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y, float z, float u, float v, int red, int green, int blue, int alpha) {
        vertices.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }
}
