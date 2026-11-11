package dev.joblessreincarnation.client.epic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.npc.NpcHeldModels;
import dev.joblessreincarnation.compat.epic.RoxyEpicPatch;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes.MeshAccessor;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;

/** EF's Gecko compatibility event routes the original renderer here, without casting it to a vanilla renderer. */
public final class RoxyEpicRenderer extends PatchedEntityRenderer<RoxyTutorEntity, RoxyEpicPatch,
        EntityRenderer<RoxyTutorEntity>, SkinnedMesh> {
    private static final MeshAccessor<SkinnedMesh> MESH = MeshAccessor.create(JoblessReincarnation.MOD_ID,
            "entity/roxy_epic_v01", loader -> loader.loadSkinnedMesh(SkinnedMesh::new));
    @Override public AssetAccessor<SkinnedMesh> getDefaultMesh() { return MESH; }
    @Override public void render(RoxyTutorEntity entity, RoxyEpicPatch patch, EntityRenderer<RoxyTutorEntity> renderer,
            MultiBufferSource buffers, PoseStack poses, int light, float partialTick) {
        // GeoEntityRenderer still draws the name outside its cancellable body pass.
        if (entity.isInvisible()) return;
        poses.pushPose();
        Armature armature = patch.getArmature();
        mulPoseStack(poses, armature, entity, patch, partialTick);
        // No scale here: Epic Fight takes over at GeoRenderEvent.Entity.Pre, after GeckoLib applied RoxyRendering.SCALE to this pose.
        setArmaturePose(patch, armature, partialTick);
        SkinnedMesh mesh = MESH.get();
        mesh.initialize();
        // The skin with this moment's eyes: open, or part of a blink. Only the eye pixels differ between them.
        mesh.draw(poses, buffers, RenderType.entityCutoutNoCull(NpcEyes.ROXY.skin(entity)), light, 1, 1, 1, 1,
                OverlayTexture.NO_OVERLAY, armature, armature.getPoseMatrices());
        renderStaff(armature, poses, buffers, light);
        poses.popPose();
    }
    private static void renderStaff(Armature armature, PoseStack poses, MultiBufferSource buffers, int light) {
        poses.pushPose();
        if (!NpcHeldPropPose.apply(poses, armature, 4.6F, 13.45F, 0.0F)) { poses.popPose(); return; }
        poses.mulPose(Axis.XP.rotationDegrees(5.27F));
        poses.mulPose(Axis.YP.rotationDegrees(180.0F));
        poses.scale(0.96F, 0.96F, 0.96F);
        poses.translate(-0.5F, -14.0F / 16.0F, -0.5F);
        var minecraft = Minecraft.getInstance();
        minecraft.getItemRenderer().renderModelLists(minecraft.getModelManager().getModel(NpcHeldModels.ROXY_STAFF),
                ItemStack.EMPTY, light, OverlayTexture.NO_OVERLAY, poses, buffers.getBuffer(Sheets.cutoutBlockSheet()));
        poses.popPose();
    }
}
