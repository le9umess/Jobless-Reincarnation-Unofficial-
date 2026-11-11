package dev.joblessreincarnation.client.epic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.npc.NpcHeldModels;
import dev.joblessreincarnation.compat.epic.FriendlyNpcEpicPatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes.MeshAccessor;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;

/** The original Gecko renderer retains name/shadow handling; the approved mesh owns the body pass. */
public final class V02NpcRenderer<T extends PathfinderMob> extends PatchedEntityRenderer<T, FriendlyNpcEpicPatch<T>,
        EntityRenderer<T>, SkinnedMesh> {
    private final MeshAccessor<SkinnedMesh> mesh;
    private final ResourceLocation texture;
    /** The eye shapes swapped in for blinks and expressions, or null to keep the painted eyes. */
    @org.jetbrains.annotations.Nullable private final NpcEyes eyes;
    private final float scale;
    private final boolean practiceSword;
    public V02NpcRenderer(String name, float scale, boolean practiceSword) {
        mesh = MeshAccessor.create(JoblessReincarnation.MOD_ID, "entity/" + name, loader -> loader.loadSkinnedMesh(SkinnedMesh::new));
        texture = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/" + name + ".png");
        eyes = NpcEyes.forSkin(name);
        this.scale = scale;
        this.practiceSword = practiceSword;
    }
    @Override public AssetAccessor<SkinnedMesh> getDefaultMesh() { return mesh; }
    @Override public void render(T entity, FriendlyNpcEpicPatch<T> patch, EntityRenderer<T> original,
            MultiBufferSource buffers, PoseStack poses, int light, float partialTick) {
        if (entity.isInvisible()) return;
        poses.pushPose();
        Armature armature = patch.getArmature();
        mulPoseStack(poses, armature, entity, patch, partialTick);
        poses.scale(scale, scale, scale);
        setArmaturePose(patch, armature, partialTick);
        SkinnedMesh model = mesh.get();
        model.initialize();
        // This moment's eyes: open, part of a blink, or an expression. Only the eye pixels differ between the skins.
        RenderType skin = RenderType.entityCutoutNoCull(eyes != null ? eyes.skin(entity) : texture);
        if (entity instanceof dev.joblessreincarnation.npc.SylphietteEntity child && model.hasPart("rightEar") && model.hasPart("leftEar") && model.hasPart("ahoge")) {
            SylphietteSecondaryMotion.draw(child, model, armature, poses, buffers, skin, light, partialTick);
        } else {
            model.draw(poses, buffers, skin, light, 1, 1, 1, 1,
                    OverlayTexture.NO_OVERLAY, armature, armature.getPoseMatrices());
        }
        if (practiceSword) renderPracticeSword(armature, poses, buffers, light);
        poses.popPose();
    }
    private void renderPracticeSword(Armature armature, PoseStack poses, MultiBufferSource buffers, int light) {
        poses.pushPose();
        // The authored palm centre is narrower than the standard EF Tool_R socket.
        if (!NpcHeldPropPose.apply(poses, armature, 4.8F, 13.15F, 0.0F)) { poses.popPose(); return; }
        // motion-v2 was calibrated against the blade axis Tool_R local -Z.
        poses.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poses.mulPose(Axis.YP.rotationDegrees(180.0F));
        // Keep the held training sword the same physical size as the player item despite Paul’s mesh scale.
        float propScale = 1.12F / scale;
        poses.scale(propScale, propScale, propScale);
        poses.translate(-0.5F, -3.05F / 16.0F, -0.5F);
        var client = Minecraft.getInstance();
        client.getItemRenderer().renderModelLists(client.getModelManager().getModel(NpcHeldModels.PAUL_PRACTICE_SWORD),
                ItemStack.EMPTY, light, OverlayTexture.NO_OVERLAY, poses, buffers.getBuffer(Sheets.cutoutBlockSheet()));
        poses.popPose();
    }
}
