package dev.joblessreincarnation.client.epic;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.npc.SylphietteEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;

/** Tiny cosmetic motion on separate parts; keeps the original twenty-joint rig unchanged. */
public final class SylphietteSecondaryMotion {
    private static final String[] PARTS={"rightEar","leftEar","ahoge"};
    public static void draw(SylphietteEntity child,SkinnedMesh mesh,Armature rig,PoseStack stack,
            MultiBufferSource buffers,RenderType type,int light,float partialTick) {
        for(String part:PARTS)mesh.getPart(part).setHidden(true);
        var vertexBuffer=buffers.getBuffer(EpicFightRenderTypes.getTriangulated(type));
        try {
            // Immediate posed draws isolate visibility and modified matrices even when the optional compute path is enabled.
            mesh.drawPosed(stack,vertexBuffer,Mesh.DrawingFunction.NEW_ENTITY,light,1,1,1,1,OverlayTexture.NO_OVERLAY,rig,rig.getPoseMatrices());
            mesh.getAllParts().forEach(part->part.setHidden(true));
            double time=child.tickCount+partialTick+(child.getId()%29);
            double moving=Math.min(1,child.walkAnimation.speed()/.18F);
            float ear=(float)((1.7+.8*moving)*Math.sin(time*Math.PI*2/30));
            part(mesh,rig,stack,vertexBuffer,light,"rightEar",6.56F,29.12F,.4F,ear);
            part(mesh,rig,stack,vertexBuffer,light,"leftEar",-6.56F,29.12F,.4F,-ear);
            float tuft=(float)((1.0+2.5*moving)*Math.sin(time*Math.PI*2/18));
            part(mesh,rig,stack,vertexBuffer,light,"ahoge",-.48F,36.48F,1.44F,tuft);
        } finally {mesh.initialize();}
    }
    private static void part(SkinnedMesh mesh,Armature rig,PoseStack stack,com.mojang.blaze3d.vertex.VertexConsumer buffer,
            int light,String name,float x,float y,float z,float degrees) {
        var head=rig.searchJointByName("Head");
        var poses=rig.getPoseMatrices().clone();
        OpenMatrix4f spin=new OpenMatrix4f().translate(x/16,y/16,z/16).rotateDeg(degrees,Vec3f.Z_AXIS).translate(-x/16,-y/16,-z/16);
        poses[head.getId()]=OpenMatrix4f.mul(poses[head.getId()],head.getToOrigin(),null).mulBack(spin);
        mesh.getPart(name).setHidden(false);
        // Only this Head-bound part is visible; its matrix already includes the inverse bind transform.
        mesh.drawPosed(stack,buffer,Mesh.DrawingFunction.NEW_ENTITY,light,1,1,1,1,OverlayTexture.NO_OVERLAY,null,poses);
        mesh.getPart(name).setHidden(true);
    }
    private SylphietteSecondaryMotion() {}
}
