package dev.joblessreincarnation.client.epic;

import com.mojang.blaze3d.vertex.PoseStack;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

/** Position follows the actual skinned palm; the independent Tool joint controls only the prop's orientation. */
public final class NpcHeldPropPose {
    public static boolean apply(PoseStack poses,Armature armature,float xPixels,float yPixels,float zPixels) {
        var hand=armature.searchJointByName("Hand_R");var tool=armature.searchJointByName("Tool_R");
        if(hand==null||tool==null)return false;
        Vec3f local=OpenMatrix4f.transform3v(hand.getToOrigin(),new Vec3f(xPixels/16.0F,yPixels/16.0F,zPixels/16.0F),null);
        Vec3f position=OpenMatrix4f.transform3v(armature.getPoseMatrices()[hand.getId()],local,null);
        OpenMatrix4f grip=OpenMatrix4f.removeTranslation(armature.getPoseMatrices()[tool.getId()],null);
        grip.m30=position.x;grip.m31=position.y;grip.m32=position.z;
        MathUtils.mulStack(poses,grip);return true;
    }
    private NpcHeldPropPose() {}
}
