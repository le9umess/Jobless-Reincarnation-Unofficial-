package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.encounter.PaulDuel;
import dev.joblessreincarnation.encounter.PaulDuel.Move;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.EnumMap;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import yesman.epicfight.api.collider.OBBCollider;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import java.util.List;
import java.util.Map;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationBuilder;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.property.AnimationProperty.AttackAnimationProperty;
import yesman.epicfight.api.animation.types.*;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.utils.AttackResult;
import yesman.epicfight.api.utils.math.ValueModifier;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.gameasset.ColliderPreset;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import yesman.epicfight.world.damagesource.StunType;

/** Native EF phases own contacts and guard/parry. The encounter controller owns only choice, travel and scoring. */
public final class PaulDuelCombat {
    private static final Map<Move,AnimationAccessor<? extends StaticAnimation>> MOVES=new EnumMap<>(Move.class);
    private static AnimationAccessor<StaticAnimation> GUARD,GUARD2,WATER;
    public static void register(AnimationBuilder builder){
        MOVES.clear();
        GUARD=builder.nextAccessor("v02/paul/duel_guard",key->new StaticAnimation(.1F,true,key,Armatures.BIPED));
        GUARD2=builder.nextAccessor("v02/paul/duel_guard2",key->new StaticAnimation(.1F,true,key,Armatures.BIPED));
        WATER=builder.nextAccessor("v02/paul/duel_parry_stance",key->new StaticAnimation(.1F,true,key,Armatures.BIPED));
        for(Move move:Move.values()){
            if(move==Move.PARRY){MOVES.put(move,WATER);continue;}
            if(move.attack())MOVES.put(move,builder.<AttackAnimation>nextAccessor("v02/paul/"+move.clip,key->new Cut(key,move)));
            else if(move==Move.STAGGER||move==Move.STAGGER2)MOVES.put(move,builder.<HitAnimation>nextAccessor("v02/paul/"+move.clip,key->new Flinch(key,move)));
            else MOVES.put(move,builder.<ActionAnimation>nextAccessor("v02/paul/"+move.clip,key->new Gesture(key,move)));
        }
    }
    public static final class Patch extends FriendlyNpcEpicPatch<FamilyResidentEntity>{
        private int previousStance=-1;
        public Patch(FamilyResidentEntity paul){super(paul);}
        @Override public void updateMotion(boolean considerInaction){
            int stance=getOriginal().duelStance();
            if(stance!=previousStance){
                getAnimator().addLivingAnimation(LivingMotions.IDLE,stance==1?GUARD:stance==2?GUARD2:stance==3?WATER:NpcEpicAnimations.of(getOriginal()).idle());
                previousStance=stance;
            }
            super.updateMotion(considerInaction);
        }
        @Override public AttackResult tryHurt(DamageSource source,float amount){
            AttackResult result=super.tryHurt(source,amount);
            return result.resultType.dealtDamage()&&PaulDuel.parry(getOriginal(),source)
                    ?new AttackResult(AttackResult.ResultType.BLOCKED,0):result;
        }
        @Override public boolean applyStun(StunType type,float duration){
            return type!=StunType.NONE&&PaulDuel.stagger(getOriginal());
        }
        @Override public void knockBackEntity(Vec3 source,float power){
            var b=PaulDuel.bout(getOriginal());
            if(b!=null&&(!b.active()||PaulDuel.charging(getOriginal())))return;
            super.knockBackEntity(source,power);
        }
        @Override public boolean shouldCancelKnockback(){
            var b=PaulDuel.bout(getOriginal());
            return b!=null ? !b.active()||PaulDuel.charging(getOriginal()) : super.shouldCancelKnockback();
        }
    }
    public static Patch patch(FamilyResidentEntity paul){return EpicFightCapabilities.getEntityPatch(paul,Patch.class);}
    public static void play(FamilyResidentEntity paul,Move move){
        Patch p=patch(paul);var clip=MOVES.get(move);if(p!=null&&clip!=null)p.playAnimationSynchronized(clip,0);
    }
    public static void stopAction(FamilyResidentEntity paul){
        Patch p=patch(paul);
        if(p!=null){
            var stance=paul.duelStance()==1?GUARD:paul.duelStance()==2?GUARD2:paul.duelStance()==3?WATER:NpcEpicAnimations.of(paul).idle();
            // The base layer needs a real, non-zero clip. EMPTY has zero duration and can make a concurrent link's clock NaN.
            p.playAnimationSynchronized(stance,0);
        }
    }
    public static void face(FamilyResidentEntity paul,float yaw){paul.yBodyRot=yaw;paul.yBodyRotO=yaw;}
    public static float elapsed(FamilyResidentEntity paul,Move move){
        Patch p=patch(paul);var clip=MOVES.get(move);if(p==null||clip==null)return -1;
        var player=p.getAnimator().getPlayerFor(clip);
        return player==null?-1:player.getElapsedTime();
    }
    public static boolean windingUp(FamilyResidentEntity paul){return SchoolAnimations.windingUp(paul);}
    public static boolean armStrike(DamageSource source){
        return source instanceof EpicFightDamageSource damage&&damage.getAnimation()!=null
                &&damage.getAnimation().equals(SchoolAnimations.ARM_STRIKE);
    }
    public static boolean flowCounter(DamageSource source){
        return source instanceof EpicFightDamageSource damage && SchoolAnimations.FLOW_COUNTER.equals(damage.getAnimation());
    }
    private static void ended(LivingEntityPatch<?> patch,Move move){
        if(patch.getOriginal() instanceof FamilyResidentEntity paul&&!paul.level().isClientSide)PaulDuel.motionEnded(paul,move);
    }
    private static final class Gesture extends ActionAnimation{
        final Move move;
        Gesture(AnimationAccessor<ActionAnimation> key,Move move){super(.1F,key,Armatures.BIPED);this.move=move;}
        @Override protected Vec3 getCoordVector(LivingEntityPatch<?> patch,AssetAccessor<? extends DynamicAnimation> animation){return Vec3.ZERO;}
        @Override public float getPlaySpeed(LivingEntityPatch<?> patch,DynamicAnimation animation){return 1;}
        @Override public void end(LivingEntityPatch<?> patch,AssetAccessor<? extends DynamicAnimation> next,boolean natural){super.end(patch,next,natural);ended(patch,move);}
    }
    private static final class Flinch extends HitAnimation{
        final Move move;
        Flinch(AnimationAccessor<HitAnimation> key,Move move){super(.05F,key,Armatures.BIPED);this.move=move;}
        @Override public void end(LivingEntityPatch<?> patch,AssetAccessor<? extends DynamicAnimation> next,boolean natural){super.end(patch,next,natural);ended(patch,move);}
    }
    private static final class Cut extends AttackAnimation{
        final Move move;
        Cut(AnimationAccessor<AttackAnimation> key,Move move){
            super(.1F,key,Armatures.BIPED,phases(move));this.move=move;
            addProperty(AttackAnimationProperty.ATTACK_SPEED_FACTOR,0F);
        }
        @Override public float getPlaySpeed(LivingEntityPatch<?> patch,DynamicAnimation animation){return 1;}
        @Override protected Vec3 getCoordVector(LivingEntityPatch<?> patch,AssetAccessor<? extends DynamicAnimation> animation){return Vec3.ZERO;}
        @Override protected int getMaxStrikes(LivingEntityPatch<?> patch,Phase phase){return 1;}
        @Override public EpicFightDamageSource getEpicFightDamageSource(LivingEntityPatch<?> patch,Entity target,Phase phase){
            EpicFightDamageSource source=super.getEpicFightDamageSource(patch,target,phase);
            source.setUsedItem(new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()));
            if(patch.getOriginal() instanceof FamilyResidentEntity paul){var b=PaulDuel.bout(paul);if(b!=null&&b.phase==1)source.attachDamageModifier(ValueModifier.multiplier(1.25F));}
            return source.setStunType(StunType.SHORT);
        }
        @Override public void end(LivingEntityPatch<?> patch,AssetAccessor<? extends DynamicAnimation> next,boolean natural){super.end(patch,next,natural);ended(patch,move);}
    }
    private static AttackAnimation.Phase[] phases(Move m){
        return switch(m){
            case STEP->new AttackAnimation.Phase[]{new Contact(m,0,.58F,.73F,1.30F,1.60F)};
            case SWEEP->new AttackAnimation.Phase[]{new Contact(m,0,.43F,.57F,.68F,.68F),new Contact(m,.68F,.77F,.90F,1.43F,110/60F)};
            case FAST->new AttackAnimation.Phase[]{new Contact(m,0,.37F,.50F,.97F,1.30F)};
            case CHARGE->new AttackAnimation.Phase[]{new Contact(m,0,.93F,1.07F,1.67F,2.0F)};
            case COUNTER->new AttackAnimation.Phase[]{new Contact(m,0,.23F,.37F,.90F,.90F)};
            default->throw new IllegalArgumentException();
        };
    }
    private static final class Contact extends AttackAnimation.Phase{
        final Move move;
        final OBBCollider blade;
        final int samples;
        Contact(Move move,float start,float contactStart,float contactEnd,float recovery,float end){
            super(start,start,contactStart,contactEnd,recovery,end,InteractionHand.MAIN_HAND,Armatures.BIPED.get().toolR,ColliderPreset.SWORD);this.move=move;
            CompoundTag preset=ColliderPreset.SWORD.serialize(new CompoundTag());
            samples=preset.getInt("number");preset.putInt("number",1);
            blade=(OBBCollider)ColliderPreset.deserializeSimpleCollider(preset);
        }
        @Override public List<Entity> getCollidingEntities(LivingEntityPatch<?> patch,AttackAnimation clip,float before,float now,float speed){
            if(!(patch.getOriginal() instanceof FamilyResidentEntity paul)||paul.level().isClientSide)return List.of();
            var b=PaulDuel.bout(paul);
            if(b==null||!b.active()||b.move!=move||!b.player.isAlive()||b.player.isSpectator()||!paul.hasLineOfSight(b.player))return List.of();
            // As with the player's school cuts, subtract world coordinates in double precision before using EF's
            // float matrices. Anchor the collider at the same skinned palm as V02NpcRenderer, not the wider stock socket.
            Vec3 origin=paul.position();
            var hand=patch.getArmature().searchJointByName("Hand_R");
            Vec3f palmLocal=OpenMatrix4f.transform3v(hand.getToOrigin(),new Vec3f(4.8F/16F,13.15F/16F,0),null);
            OBBCollider victim=new OBBCollider(b.player.getBoundingBox().move(-origin.x,-origin.y,-origin.z));
            int count=Math.max(1,Math.max(samples,Math.round(samples*speed)));boolean contact=false;
            for(int i=0;i<count;i++){
                float fraction=count==1?1:(float)i/(count-1);
                var pose=clip.getPoseByTime(patch,Mth.lerp(fraction,before,now),1);
                var handMatrix=patch.getArmature().getBoundTransformFor(pose,hand);
                Vec3f palm=OpenMatrix4f.transform3v(handMatrix,palmLocal,null);
                var grip=OpenMatrix4f.removeTranslation(patch.getArmature().getBoundTransformFor(pose,Armatures.BIPED.get().toolR),null);
                grip.m30=palm.x*.891F;grip.m31=palm.y*.891F;grip.m32=palm.z*.891F;
                double remaining=1.0-fraction;
                var travel=OpenMatrix4f.createTranslation((float)((origin.x-patch.getXOld())*remaining),
                        (float)((patch.getYOld()-origin.y)*remaining),(float)((origin.z-patch.getZOld())*remaining));
                OBBCollider sample=blade.deepCopy();sample.transform(grip.mulFront(travel.mulBack(patch.getModelMatrix(fraction))));
                if(sample.isCollide(victim)){contact=true;break;}
            }
            if(!contact)return List.of();
            if(b.phase==1){
                Vec3 from=paul.getEyePosition(),to=b.player.getBoundingBox().getCenter();
                for(WaterShieldEntity shield:b.level.getEntitiesOfClass(WaterShieldEntity.class,new AABB(from,to).inflate(2),s->b.player.getUUID().equals(s.ownerId())))
                    if(shield.blockPracticeStrike(b.player,from,to)){PaulDuel.react(b,"react_water");return List.of();}
            }
            return List.of(b.player);
        }
    }
    private PaulDuelCombat(){}
}
