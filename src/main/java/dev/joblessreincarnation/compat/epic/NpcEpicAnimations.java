package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.npc.*;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationBuilder;
import yesman.epicfight.api.animation.property.AnimationProperty.StaticAnimationProperty;
import yesman.epicfight.api.animation.types.MovementAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.api.client.animation.property.ClientAnimationProperties;
import yesman.epicfight.api.client.animation.property.JointMaskEntry;
import yesman.epicfight.gameasset.Armatures;

public final class NpcEpicAnimations {
    public enum Role { ROXY,PAUL,ZENITH,LILIA,SYLPHIE }
    public record Clips(AnimationAccessor<StaticAnimation> idle,AnimationAccessor<MovementAnimation> walk,
            Map<NpcGestureVisual.Kind,AnimationAccessor<StaticAnimation>> gestures) {}
    private static final Map<Role,Clips> SETS=new EnumMap<>(Role.class);
    public static Role role(Entity e) {
        // EF creates its patch from the LivingEntity constructor, before FamilyResidentEntity.member is assigned.
        // Entity.type is already available there and is the stable identity used by registration.
        var type=e.getType();
        if(type==dev.joblessreincarnation.registry.ModContent.ROXY_TUTOR.get())return Role.ROXY;
        if(type==dev.joblessreincarnation.registry.ModContent.PAUL_GREYRAT.get())return Role.PAUL;
        if(type==dev.joblessreincarnation.registry.ModContent.ZENITH_GREYRAT.get())return Role.ZENITH;
        if(type==dev.joblessreincarnation.registry.ModContent.LILIA_GREYRAT.get())return Role.LILIA;
        if(type==dev.joblessreincarnation.registry.ModContent.SYLPHIETTE.get())return Role.SYLPHIE;
        return null;
    }
    public static Clips of(Entity e) { return SETS.get(role(e)); }
    public static void register(AnimationBuilder builder) {
        SETS.clear();
        PaulDuelCombat.register(builder);
        add(builder,Role.ROXY,"roxy",.744F,NpcGestureVisual.Kind.LECTURE,NpcGestureVisual.Kind.GREETING);
        add(builder,Role.PAUL,"paul",.891F,NpcGestureVisual.Kind.GREETING,NpcGestureVisual.Kind.SWORD_DEMO);
        add(builder,Role.ZENITH,"zenith",.749F,NpcGestureVisual.Kind.GREETING);
        add(builder,Role.LILIA,"lilia",.696F,NpcGestureVisual.Kind.GREETING);
        add(builder,Role.SYLPHIE,"sylphie",.443F,NpcGestureVisual.Kind.GREETING,NpcGestureVisual.Kind.ATTEMPT);
        // Existing unused Roxy assets are registered without adding any new gameplay trigger.
        builder.nextAccessor("v02/roxy/cast",key->new StaticAnimation(.2F,true,key,Armatures.BIPED));
        builder.nextAccessor("v02/roxy/sit",key->new StaticAnimation(.2F,true,key,Armatures.BIPED));
    }
    private static void add(AnimationBuilder builder,Role role,String name,float nominalSpeed,NpcGestureVisual.Kind... kinds) {
        AnimationAccessor<StaticAnimation> idle=builder.nextAccessor("v02/"+name+"/idle",key->new StaticAnimation(.15F,true,key,Armatures.BIPED));
        AnimationAccessor<MovementAnimation> walk=builder.nextAccessor("v02/"+name+"/walk",key->{
            MovementAnimation clip=new MovementAnimation(.15F,true,key,Armatures.BIPED);
            // Vanilla smooths displacement*4 in walkAnimation; convert back to blocks/second for this authored stride.
            clip.addProperty(StaticAnimationProperty.PLAY_SPEED_MODIFIER,(self,patch,speed,prev,now)->self.isLinkAnimation()?1.0F
                    :Mth.clamp(patch.getOriginal().walkAnimation.speed()*5.0F/nominalSpeed,.05F,4.0F));
            return clip;
        });
        Map<NpcGestureVisual.Kind,AnimationAccessor<StaticAnimation>> gestures=new EnumMap<>(NpcGestureVisual.Kind.class);
        for(var kind:kinds) {
            String clipName=switch(kind){case GREETING->"greeting";case LECTURE->"lecture";case SWORD_DEMO->"sword_demo";case ATTEMPT->"attempt";default->throw new IllegalArgumentException();};
            gestures.put(kind,builder.nextAccessor("v02/"+name+"/"+clipName,key->{
                StaticAnimation clip=new StaticAnimation(.2F,false,key,Armatures.BIPED);
                clip.addProperty(ClientAnimationProperties.LAYER_TYPE,Layer.LayerType.COMPOSITE_LAYER);
                clip.addProperty(ClientAnimationProperties.PRIORITY,Layer.Priority.HIGH);
                if(kind==NpcGestureVisual.Kind.LECTURE)clip.addProperty(ClientAnimationProperties.JOINT_MASK,JointMaskEntry.builder().defaultMask(JointMaskEntry.BIPED_UPPER_JOINTS_WITH_ROOT).create());
                if(kind==NpcGestureVisual.Kind.SWORD_DEMO)clip.addProperty(StaticAnimationProperty.FIXED_HEAD_ROTATION,true);
                return clip;
            }));
        }
        SETS.put(role,new Clips(idle,walk,gestures));
    }
    private NpcEpicAnimations() {}
}
