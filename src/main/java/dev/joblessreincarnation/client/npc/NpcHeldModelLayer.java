package dev.joblessreincarnation.client.npc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * Draws a side-loaded block/item model in an NPC's hand. GeckoLib calls this inside the bone's own transform, so the
 * model follows the arm in every clip. Positions are in model pixels: {@code grip} is relative to the bone's pivot (in
 * GeckoLib's model space, where +x is the figure's right and -z its front), and the model is held at
 * {@code (8, gripHeight, 8)}, the middle of its grip, after turning it by {@code yaw} about the vertical and then by
 * {@code pitch} about the arm's x axis (negative tips the model's top toward the front).
 * {@code upright} is the share of the arm's forward and back swing the hand takes out of the model: 0 keeps it fixed
 * to the arm, 1 keeps it as upright as the body, the way a hand keeps a long staff standing while the arm moves.
 */
public class NpcHeldModelLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {
    private final String bone;
    private final ModelResourceLocation model;
    private final Vector3f grip;
    private final float gripHeight, scale, yaw, upright, pitch;

    public NpcHeldModelLayer(GeoRenderer<T> renderer, String bone, ModelResourceLocation model, Vector3fc grip, float gripHeight, float scale, float yaw,
                              float upright) {
        this(renderer, bone, model, grip, gripHeight, scale, yaw, upright, 0.0F);
    }

    public NpcHeldModelLayer(GeoRenderer<T> renderer, String bone, ModelResourceLocation model, Vector3fc grip, float gripHeight, float scale, float yaw,
                              float upright, float pitch) {
        super(renderer);
        this.bone = bone;
        this.model = model;
        this.grip = new Vector3f(grip).div(16.0F);
        this.gripHeight = gripHeight;
        this.scale = scale;
        this.yaw = yaw;
        this.upright = upright;
        this.pitch = pitch;
    }

    @Override
    public void renderForBone(PoseStack poses, T animatable, GeoBone bone, RenderType renderType, MultiBufferSource buffers, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay) {
        if (!bone.getName().equals(this.bone)) return;
        poses.pushPose();
        // The bone's rotation is already applied here; only move to its pivot, then to the hand.
        RenderUtil.translateToPivotPoint(poses, bone);
        poses.translate(grip.x, grip.y, grip.z);
        // GeckoLib turns the bone Z, then Y, then X, so X is innermost: undoing part of it here tilts only the held model.
        if (upright != 0.0F && bone.getRotX() != 0.0F) poses.mulPose(Axis.XP.rotation(-upright * bone.getRotX()));
        // A sword is held along the forearm rather than standing up: tip the model about the arm's own x axis.
        if (pitch != 0.0F) poses.mulPose(Axis.XP.rotationDegrees(pitch));
        poses.mulPose(Axis.YP.rotationDegrees(yaw));
        poses.scale(scale, scale, scale);
        poses.translate(-0.5F, -gripHeight / 16.0F, -0.5F);
        Minecraft.getInstance().getItemRenderer().renderModelLists(Minecraft.getInstance().getModelManager().getModel(model), ItemStack.EMPTY,
                packedLight, OverlayTexture.NO_OVERLAY, poses, buffers.getBuffer(Sheets.cutoutBlockSheet()));
        poses.popPose();
    }
}
