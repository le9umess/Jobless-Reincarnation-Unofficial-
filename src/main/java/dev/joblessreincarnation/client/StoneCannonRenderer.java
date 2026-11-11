package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.StoneCannonProjectile;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * The stone cannon in the form its caster has mastered: a cracked rock tumbling end over end, a dense round-nosed slug
 * holding its line, or a drill spinning hard on its own axis. Looks only; the hit box and sweep live in the projectile.
 */
public final class StoneCannonRenderer extends GeoEntityRenderer<StoneCannonProjectile>{
    private static final double NEAR_OWNER=2.6;
    /** One bone per form in stone_cannon.geo.json, in form-stage order. The nose of the slug and the drill point along -Z. */
    private static final String[] FORMS={"form_novice","form_practiced","form_adept"};
    /** Degrees per tick: the rock tumbles, the slug rolls slowly, the drill spins. */
    private static final float SLUG_ROLL=9,DRILL_SPIN=42;
    private static final Set<StoneCannonProjectile> WHIRRING=Collections.newSetFromMap(new WeakHashMap<>());
    private static final RenderType STREAK=RenderType.entityTranslucentEmissive(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"textures/entity/spell/streak.png"));
    public StoneCannonRenderer(EntityRendererProvider.Context context){super(context,new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"stone_cannon")));shadowRadius=0;}
    /** The speed is told by thin air lines trailing behind it: short and dusty off the loose rock, longest behind the drill. */
    @Override public void render(StoneCannonProjectile stone,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        int stage=stone.formStage();
        // The drill hums as it bores through the air, following it. Once per stone, the first time it is seen spinning.
        if(stage==2&&WHIRRING.add(stone))Minecraft.getInstance().getSoundManager().play(new EntityBoundSoundInstance(SoundEvents.TRIDENT_RIPTIDE_1.value(),SoundSource.PLAYERS,0.4F,1.5F,stone,stone.getId()));
        // The stone leaves from the caster's eye: in their own first-person view it would fill the screen for a frame. Everyone else sees it from the start.
        var camera=entityRenderDispatcher.camera;
        if(!camera.isDetached()&&stone.getOwner()==camera.getEntity()&&camera.getPosition().distanceTo(stone.getPosition(partial))<NEAR_OWNER)return;
        Vec3 motion=stone.getDeltaMovement();
        float speed=(float)motion.length();
        if(speed>1.0E-3F){
            Vec3 eye=entityRenderDispatcher.camera.getPosition();
            float near=Mth.clamp(((float)eye.distanceTo(stone.getPosition(partial))-1.2F)/2.0F,0,1);
            if(near>0){
                Vec3 back=motion.scale(-1.0F/speed);
                Vec3 toEye=eye.subtract(stone.getPosition(partial)).normalize();
                Vec3 side=back.cross(toEye);
                // Flying along the view the lines would fan across the whole screen; they only read when the stone crosses it.
                near*=Mth.clamp((0.9F-Math.abs((float)back.dot(toEye)))/0.3F,0,1);
                if(near>0&&side.lengthSqr()>1.0E-4){
                    side=side.normalize();
                    float length=Math.min(3.8F,speed*2.3F)*(stage==0?0.6F:stage==1?1.0F:1.25F);
                    int r=stage==0?214:232,g=stage==0?204:230,b=stage==0?184:222;
                    VertexConsumer lines=buffers.getBuffer(STREAK);
                    pose.pushPose();
                    pose.translate(0,stone.getBbHeight()*.5F,0);
                    if(stage==2){
                        // The drill wrings the air: three lines wind around the line of flight behind it, turning with the spin.
                        Vec3 up=back.cross(side).normalize();
                        float turn=(stone.tickCount+partial)*DRILL_SPIN*Mth.DEG_TO_RAD*0.25F;
                        for(int i=0;i<3;i++)helix(lines,pose.last(),side,up,back,0.2F,0.06F,length*(1-i*0.1F),turn+i*Mth.TWO_PI/3,(int)((200-i*20)*near),r,g,b);
                    }else{
                        streak(lines,pose.last(),side,side,back,0.17F,0.09F,length,(int)(205*near),r,g,b);
                        streak(lines,pose.last(),side,side,back,-0.17F,0.09F,length*.8F,(int)(165*near),r,g,b);
                        streak(lines,pose.last(),side,side,back,0.0F,0.06F,length*.6F,(int)(130*near),r,g,b);
                    }
                    pose.popPose();
                }
            }
        }
        super.render(stone,yaw,partial,pose,buffers,light);
    }
    @Override public void preRender(PoseStack pose,StoneCannonProjectile stone,BakedGeoModel model,@Nullable MultiBufferSource buffers,@Nullable VertexConsumer buffer,boolean reRender,float partial,int light,int overlay,int colour){
        // All three forms share one baked model, so each stone sets which one shows just before it is drawn.
        int stage=Mth.clamp(stone.formStage(),0,FORMS.length-1);
        for(int i=0;i<FORMS.length;i++){boolean hidden=i!=stage;model.getBone(FORMS[i]).ifPresent(bone->bone.setHidden(hidden));}
        super.preRender(pose,stone,model,buffers,buffer,reRender,partial,light,overlay,colour);
    }
    /** A flat line {@code offset} out along {@code out}, {@code width} wide across {@code across}, trailing {@code length} behind. */
    private static void streak(VertexConsumer lines,PoseStack.Pose pose,Vec3 out,Vec3 across,Vec3 back,float offset,float width,float length,int alpha,int r,int g,int b){
        float cx=(float)out.x*offset,cy=(float)out.y*offset,cz=(float)out.z*offset;
        float wx=(float)across.x*width,wy=(float)across.y*width,wz=(float)across.z*width;
        float lx=(float)back.x*length,ly=(float)back.y*length,lz=(float)back.z*length;
        corner(lines,pose,cx-wx,cy-wy,cz-wz,0,1,alpha,r,g,b);
        corner(lines,pose,cx+wx,cy+wy,cz+wz,1,1,alpha,r,g,b);
        corner(lines,pose,cx+wx+lx,cy+wy+ly,cz+wz+lz,1,0,alpha,r,g,b);
        corner(lines,pose,cx-wx+lx,cy-wy+ly,cz-wz+lz,0,0,alpha,r,g,b);
    }
    /** A line wound once around the flight behind the drill, as camera-facing pieces, widest and brightest at the stone. */
    private static void helix(VertexConsumer lines,PoseStack.Pose pose,Vec3 side,Vec3 up,Vec3 back,float radius,float width,float length,float phase,int alpha,int r,int g,int b){
        final int pieces=8;
        Vec3 prev=null;float prevV=1;
        for(int k=0;k<=pieces;k++){
            float along=k/(float)pieces,a=phase+along*Mth.TWO_PI,shrink=radius*(1-0.45F*along);
            Vec3 at=side.scale(Mth.cos(a)*shrink).add(up.scale(Mth.sin(a)*shrink)).add(back.scale(length*along));
            float v=1-along;
            if(prev!=null){
                float w0=width*(1-0.5F*(along-1F/pieces)),w1=width*(1-0.5F*along);
                corner(lines,pose,(float)(prev.x-side.x*w0),(float)(prev.y-side.y*w0),(float)(prev.z-side.z*w0),0,prevV,alpha,r,g,b);
                corner(lines,pose,(float)(prev.x+side.x*w0),(float)(prev.y+side.y*w0),(float)(prev.z+side.z*w0),1,prevV,alpha,r,g,b);
                corner(lines,pose,(float)(at.x+side.x*w1),(float)(at.y+side.y*w1),(float)(at.z+side.z*w1),1,v,alpha,r,g,b);
                corner(lines,pose,(float)(at.x-side.x*w1),(float)(at.y-side.y*w1),(float)(at.z-side.z*w1),0,v,alpha,r,g,b);
            }
            prev=at;prevV=v;
        }
    }
    private static void corner(VertexConsumer lines,PoseStack.Pose pose,float x,float y,float z,float u,float v,int alpha,int r,int g,int b){
        lines.addVertex(pose,x,y,z).setColor(r,g,b,alpha).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose,0,1,0);
    }
    @Override protected void applyRotations(StoneCannonProjectile stone,PoseStack pose,float age,float bodyYaw,float partial,float scale){
        // Projectile yaw increases toward +X; Gecko body yaw uses the opposite convention.
        super.applyRotations(stone,pose,age,-Mth.rotLerp(partial,stone.yRotO,stone.getYRot()),partial,scale);
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partial,stone.xRotO,stone.getXRot())));
        float t=stone.tickCount+partial,phase=(stone.getId()*37)%360;
        switch(stone.formStage()){
            case 0->{pose.mulPose(Axis.XP.rotationDegrees(phase+t*8));pose.mulPose(Axis.ZP.rotationDegrees(phase*.37F+t*3));}
            case 1->pose.mulPose(Axis.ZP.rotationDegrees(phase+t*SLUG_ROLL));
            default->pose.mulPose(Axis.ZP.rotationDegrees(phase+t*DRILL_SPIN));
        }
    }
}
