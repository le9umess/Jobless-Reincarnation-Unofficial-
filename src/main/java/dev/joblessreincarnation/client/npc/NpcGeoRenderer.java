package dev.joblessreincarnation.client.npc;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws a GeckoLib NPC from {@code assets/jobless_reincarnation/{geo,animations,textures}/entity/<model>.*}. The head
 * bone follows where the entity looks. Player models are drawn at 0.9375, so NPC models built on the same 32-unit
 * figure pass that scale to stand at a player's height.
 */
public class NpcGeoRenderer<T extends Entity & GeoAnimatable> extends GeoEntityRenderer<T> {
    /** Height of one line of name tag text: 9 font units at the 0.025 scale vanilla draws it with. */
    private static final float LABEL_HEIGHT = 9 * 0.025F;
    private float nameTagBottom;

    public NpcGeoRenderer(EntityRendererProvider.Context context, String model, float scale) {
        this(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, model), true), scale);
    }

    /** As above, with a model of the caller's own for an NPC that handles its head differently. */
    public NpcGeoRenderer(EntityRendererProvider.Context context, GeoModel<T> model, float scale) {
        super(context, model);
        withScale(scale);
        shadowRadius = 0.5F * scale;
    }

    /** Shadow size for a figure narrower than a player. */
    public NpcGeoRenderer<T> withShadowRadius(float radius) {
        shadowRadius = radius;
        return this;
    }

    /** Keeps the name tag's lower edge at least this many blocks above the feet, clear of a tall hat. */
    public NpcGeoRenderer<T> withNameTagClearance(float height) {
        nameTagBottom = height;
        return this;
    }

    /** Shows a side-loaded model ({@link NpcHeldModels}) in the hand of the given arm bone; see {@link NpcHeldModelLayer}. */
    public NpcGeoRenderer<T> withHeldModel(String bone, ModelResourceLocation model, Vector3fc grip, float gripHeight, float scale, float yaw,
                                           float upright) {
        addRenderLayer(new NpcHeldModelLayer<>(this, bone, model, grip, gripHeight, scale, yaw, upright));
        return this;
    }

    /** As above, with the model tipped by {@code pitch} degrees about the arm's x axis, the way a sword lies along the forearm. */
    public NpcGeoRenderer<T> withHeldModel(String bone, ModelResourceLocation model, Vector3fc grip, float gripHeight, float scale, float yaw,
                                           float upright, float pitch) {
        addRenderLayer(new NpcHeldModelLayer<>(this, bone, model, grip, gripHeight, scale, yaw, upright, pitch));
        return this;
    }

    @Override
    protected void renderNameTag(T entity, Component name, PoseStack poses, MultiBufferSource buffers, int packedLight, float partialTick) {
        Vec3 anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        // Vanilla draws the text downward from the attachment point plus half a block.
        float lift = anchor == null ? 0.0F : Math.max(0.0F, nameTagBottom + LABEL_HEIGHT - 0.5F - (float) anchor.y);
        poses.pushPose();
        poses.translate(0.0F, lift, 0.0F);
        super.renderNameTag(entity, name, poses, buffers, packedLight, partialTick);
        poses.popPose();
    }
}
