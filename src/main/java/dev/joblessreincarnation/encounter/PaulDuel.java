package dev.joblessreincarnation.encounter;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.compat.epic.PaulDuelCombat;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.network.BossBarPayload;
import dev.joblessreincarnation.network.RemoveBossBarPayload;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.SchoolForm;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** A voluntary, bounded practice encounter. All scores, contacts and state transitions are server-owned. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID)
public final class PaulDuel {
    public static final int INVITATION_TICKS=200, INTRO_TICKS=60, INTERMISSION_TICKS=80, RESULT_TICKS=60;
    public static final float FIRST_BAR=60, SECOND_BAR=80, PLAYER_FLOOR=4;
    private static final String PREFIX="duel.jobless_reincarnation.paul.";
    /** Ticks a player may stay outside the ring before it counts as giving up; Paul waits meanwhile. */
    public static final int LEAVE_GRACE=100;
    public enum Stage { APPROACH, INTRO, ACTIVE, INTERMISSION, RESULT }
    public enum Move {
        READY("ready",1.2F), STEP("step_cut",1.6F), SWEEP("double_sweep",110/60F),
        SERIOUS("serious",2.2F), FAST("fast_cut",1.3F), CHARGE("charge",2),
        TO_WATER("to_water",20/60F), PARRY("parry_stance",1), COUNTER("parry_counter",.9F),
        FROM_WATER("from_water",20/60F), STOMP("stomp",.8F),
        STAGGER("stagger",.5F), STAGGER2("stagger2",.5F),
        LAUGH("laugh",2), LAUGH2("laugh2",2), HELP("help_up",1.6F), HELP2("help_up2",1.6F);
        public final String clip; public final float seconds;
        Move(String clip,float seconds){this.clip="duel_"+clip;this.seconds=seconds;}
        public boolean attack(){return this==STEP||this==SWEEP||this==FAST||this==CHARGE||this==COUNTER;}
    }
    private record Invite(ServerPlayer player,long created,long until) {}
    private static final Map<FamilyResidentEntity,Invite> INVITES=new IdentityHashMap<>();
    private static final Map<FamilyResidentEntity,Bout> BOUTS=new IdentityHashMap<>();
    private static final Map<UUID,Long> HINTS=new HashMap<>();
    private static final ResourceLocation EMBLEM=ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"hud/boss/emblem_paul");
    public static final class Bout {
        public final UUID id=UUID.randomUUID();
        public final FamilyResidentEntity paul;
        public final ServerPlayer player;
        public final ServerLevel level;
        public final PaulDuelArena arena;
        public Stage stage=Stage.APPROACH;
        public int phase,transition,lineId,pattern;
        public float remaining=FIRST_BAR;
        public long stageUntil,phaseStarted,moveStarted,nextMove,lastTime,lastMireEscape=-10000,lastParry=-10000;
        public @Nullable Move move;
        public @Nullable Move pendingMove;
        public Vec3 committedDirection=Vec3.ZERO;
        public @Nullable DamageSource incoming;
        public Component line;
        public long lineUntil;
        /** The tick the player stepped out of the ring, or -1 while inside. */
        public long outside=-1;
        /** The edge warning was given for this approach to the edge; cleared once the player is well inside again. */
        public boolean edgeWarned;
        public boolean dirty=true;
        private final Set<UUID> viewers=new HashSet<>();
        private final Set<Projectile> projectiles=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> reactions=new HashSet<>();
        private Bout(FamilyResidentEntity paul,ServerPlayer player,PaulDuelArena arena) {
            this.paul=paul;this.player=player;this.arena=arena;level=player.serverLevel();
            lastTime=level.getGameTime();stageUntil=lastTime+600;
        }
        public boolean active(){return stage==Stage.ACTIVE;}
        public float maximum(){return phase==0?FIRST_BAR:SECOND_BAR;}
    }
    public static @Nullable Bout bout(FamilyResidentEntity paul){return BOUTS.get(paul);}
    public static @Nullable Bout forPlayer(Entity player){
        for(Bout b:BOUTS.values())if(b.player==player)return b;
        return null;
    }
    public static boolean occupied(FamilyResidentEntity paul){return BOUTS.containsKey(paul);}
    public static boolean acceptsMelee(LivingEntity attacker,LivingEntity target){
        Bout b=forPlayer(attacker);
        return b==null || b.stage==Stage.RESULT || b.active()&&b.paul==target;
    }
    /** Only the enrolled player's spells can affect Paul, only in the second active phase. */
    public static boolean spellTarget(LivingEntity caster,LivingEntity target){
        if(!(target instanceof FamilyResidentEntity paul))return false;
        Bout b=bout(paul);
        return b!=null&&b.player==caster&&b.active()&&b.phase==1&&valid(b)
                &&b.arena.contains(caster.position())&&b.arena.contains(target.position());
    }
    public static boolean mayAffect(LivingEntity caster,LivingEntity target){
        Bout b=forPlayer(caster);
        return b==null||b.stage==Stage.RESULT||spellTarget(caster,target);
    }
    public static boolean canCast(ServerPlayer player){
        Bout b=forPlayer(player);
        if(b==null||b.stage==Stage.RESULT)return true;
        if(b.active()&&b.phase==1)return true;
        hint(player,b.phase==0?"swords_only":"wait");return false;
    }
    public static float score(LivingEntity target){return target instanceof FamilyResidentEntity p&&bout(p)!=null?bout(p).remaining:-1;}
    public static @Nullable ServerPlayer opponent(FamilyResidentEntity paul){Bout b=bout(paul);return b==null?null:b.player;}

    /** Real combat item use must reach the item/guard path, not Paul's ordinary conversation branch. */
    public static boolean combatUse(net.minecraft.world.entity.player.Player player, FamilyResidentEntity paul, net.minecraft.world.InteractionHand hand) {
        boolean owner=player.level().isClientSide ? paul.duelOwner(player) : bout(paul)!=null && bout(paul).player==player && bout(paul).active();
        if(!owner)return false;
        var stack=player.getItemInHand(hand);
        var use=stack.getUseAnimation();
        return SchoolForm.isSword(stack)||stack.is(ModContent.TRAINING_STAFF.get())||stack.is(net.minecraft.world.item.Items.SHIELD)
                ||use==net.minecraft.world.item.UseAnim.EAT||use==net.minecraft.world.item.UseAnim.DRINK;
    }
    /** Bound the NPC's physical drift at the training edge; never constrain the player or teleport either actor. */
    public static void constrainMovement(FamilyResidentEntity paul) {
        Bout b=bout(paul);if(b==null||b.stage==Stage.APPROACH||b.stage==Stage.RESULT||!b.arena.contains(paul.position()))return;
        Vec3 v=paul.getDeltaMovement();double radius=b.arena.radius()-.4;
        double dx=paul.getX()+v.x-b.arena.centre().x,dz=paul.getZ()+v.z-b.arena.centre().z;
        double length=Math.sqrt(dx*dx+dz*dz);
        if(Double.isFinite(length)&&length>radius&&length>0){
            double scale=radius/length;
            paul.setDeltaMovement(b.arena.centre().x+dx*scale-paul.getX(),v.y,b.arena.centre().z+dz*scale-paul.getZ());
            paul.hasImpulse=true;
        }
    }

    /** Invoked only after FamilyInteractions validated a real, in-range main-hand interaction. */
    public static boolean interact(ServerPlayer player,FamilyResidentEntity paul){
        if(paul.member()!=FamilyResidentEntity.Member.PAUL)return false;
        Bout current=bout(paul);
        if(current!=null){hint(player,current.player==player?"already":"busy");return true;}
        if(!player.isShiftKeyDown()||!player.getMainHandItem().is(ModContent.PRACTICE_WOODEN_SWORD.get()))return false;
        if(player.isFakePlayer())return true;
        if(forPlayer(player)!=null){hint(player,"busy");return true;}
        if(!player.hasData(ModAttachments.SWORD_TRAINING)||!player.getData(ModAttachments.SWORD_TRAINING).recognized()){
            sayPrivate(player,"not_ready");return true;
        }
        if(player.getHealth()<=PLAYER_FLOOR||player.isPassenger()||player.isSleeping()){
            hint(player,"rest");return true;
        }
        long now=player.level().getGameTime();Invite invitation=INVITES.get(paul);
        if(invitation!=null&&invitation.until>=now&&now>=invitation.created){
            if(invitation.player!=player){hint(player,"busy");return true;}
            if(now-invitation.created<5)return true; // A single input cannot confirm itself through two interaction hooks.
            INVITES.remove(paul);
            PaulDuelArena arena=PaulDuelArena.find(paul);
            if(arena==null){sayPrivate(player,"bad_ground");return true;}
            Bout b=new Bout(paul,player,arena);BOUTS.put(paul,b);
            SpellCasting.cancel(player);paul.prepareForDuel();paul.setDuelStance(1);paul.setDuelUser(player.getUUID(),false);
            clearProjectiles(b);clearFields(b);
            sayPrivate(player,"follow");
            paul.getNavigation().moveTo(arena.route(paul),1.0);
            return true;
        }
        INVITES.put(paul,new Invite(player,now,now+INVITATION_TICKS));
        sayPrivate(player,"invite");sayPrivate(player,"invite_hint");return true;
    }
    private static boolean valid(Bout b){
        return b.paul.isAlive()&&!b.paul.isRemoved()&&b.player.isAlive()&&!b.player.isRemoved()&&!b.player.isSpectator()
                &&b.paul.level()==b.level&&b.player.level()==b.level&&b.level.getEntity(b.paul.getUUID())==b.paul
                &&b.level.getEntity(b.player.getUUID())==b.player&&!b.player.isPassenger()&&!b.paul.isPassenger()
                &&!b.paul.isVehicle()&&!b.player.isSleeping()&&!b.paul.isInWaterOrBubble()&&!b.paul.isInLava();
    }
    public static void tick(FamilyResidentEntity paul){
        Bout b=bout(paul);if(b==null||paul.level().isClientSide)return;
        long now=b.level.getGameTime();
        if(!valid(b)||now<b.lastTime){clear(b);return;}b.lastTime=now;
        if(b.stage!=Stage.RESULT&&b.player.containerMenu!=b.player.inventoryMenu){clear(b);return;}
        if(b.stage==Stage.APPROACH){
            if(now>=b.stageUntil||!b.arena.near(b.player.position(),PaulDuelArena.APPROACH_RANGE,6)){clear(b);return;}
            // Ground navigation finishes within its waypoint tolerance, especially at large world coordinates.
            // The centre is a meeting area, not an exact pixel; both actors are still inside the checked clearing.
            if(paul.distanceToSqr(b.arena.centre())<4&&b.arena.contains(b.player.position())){
                paul.getNavigation().stop();face(b,b.player.position());
                b.stage=Stage.INTRO;b.stageUntil=now+INTRO_TICKS;play(b,Move.READY);line(b,"start",60);
            }else if(now%20==0)paul.getNavigation().moveTo(b.arena.route(paul),1.0);
            return;
        }
        boolean playerInside=b.arena.contains(b.player.position());
        if(playerInside&&b.outside>=0){b.outside=-1;b.dirty=true;}
        if(b.stage==Stage.RESULT){
            if(now>=b.stageUntil){clear(b);return;}
        }else if(!playerInside){
            // Out of the ring: a few seconds to come back before it counts as giving up. Paul starts nothing meanwhile.
            if(b.outside<0){b.outside=now;b.dirty=true;}
            long left=LEAVE_GRACE-(now-b.outside);
            if(left<=0)finish(b,"gave_up",false,false);
            else if((now-b.outside)%20==0)b.player.displayClientMessage(Component.translatable(PREFIX+"outside",(int)((left+19)/20)),true);
        }
        else if(!b.arena.contains(paul.position())){clear(b);return;}
        else if(b.player.getHealth()<=PLAYER_FLOOR){finish(b,"lose",false,true);}
        else if(b.stage==Stage.INTRO&&now>=b.stageUntil){
            b.stage=Stage.ACTIVE;b.phaseStarted=now;b.move=null;b.nextMove=now+10;PaulDuelCombat.stopAction(paul);
            line(b,"go",30);
        }else if(b.stage==Stage.INTERMISSION){
            if(now==b.stageUntil-60)line(b,"magic",60);
            if(now==b.stageUntil-20)line(b,"again",20);
            if(now>=b.stageUntil){
                b.stage=Stage.ACTIVE;b.phaseStarted=now;b.move=null;b.nextMove=now+8;paul.setDuelUser(b.player.getUUID(),true);PaulDuelCombat.stopAction(paul);b.dirty=true;
            }
        }else if(b.active()){
            combat(b,now);
            // Said once each time the player comes near the edge; the ring on the ground shows it all along.
            double fromCentre=b.arena.centre().subtract(b.player.position()).horizontalDistanceSqr();
            if(fromCentre>Math.pow(b.arena.radius()-1,2)){if(!b.edgeWarned)b.edgeWarned=hint(b.player,"edge");}
            else if(fromCentre<Math.pow(b.arena.radius()-2,2))b.edgeWarned=false;
        }
        if(b.move!=null&&b.move!=Move.PARRY&&now-b.moveStarted>(long)Math.ceil(b.move.seconds*20)+10)motionEnded(paul,b.move);
        if(b.dirty||now%5==0)views(b);
    }
    private static void combat(Bout b,long now){
        if(b.pendingMove!=null){Move next=b.pendingMove;b.pendingMove=null;play(b,next);return;}
        if(b.move!=null){
            b.paul.getNavigation().stop();
            float t=PaulDuelCombat.elapsed(b.paul,b.move);
            if(b.move==Move.STEP&&t>=.50F&&t<.67F)move(b,b.committedDirection,.08);
            if(b.move==Move.FAST&&t>=.30F&&t<.43F)move(b,b.committedDirection,.08);
            if(b.move==Move.CHARGE&&t>=.50F&&t<.90F)move(b,b.committedDirection,.32);
            if(b.move==Move.STOMP&&t>=.30F&&t<.60F)move(b,b.committedDirection,.50);
            if(b.move==Move.PARRY&&now>=b.stageUntil){play(b,Move.FROM_WATER);}
            return;
        }
        double distance=b.paul.distanceToSqr(b.player);
        face(b,b.player.position());
        var speed=b.paul.getAttribute(Attributes.MOVEMENT_SPEED);
        if(b.phase==1&&speed!=null&&speed.hasModifier(MireFields.SLOW_ID)&&now-b.lastMireEscape>=100){
            b.lastMireEscape=now;play(b,Move.STOMP);return;
        }
        if(now<b.nextMove)return;
        if(distance>2.89){
            if(b.phase==1&&distance>=9&&distance<=16&&b.paul.hasLineOfSight(b.player)){play(b,Move.CHARGE);return;}
            // Pathfinding stops just outside the player's own cell, about 1.76 blocks off, and Paul swings only inside 1.7:
            // a player standing still was never attacked (promo footage 11-08). The last 3 blocks are walked straight.
            if(distance<=9){approach(b,now);return;}
            if(now%10==0)b.paul.getNavigation().moveTo(b.player,1.05);return;
        }
        b.paul.getNavigation().stop();
        if(b.phase==0)play(b,(b.pattern++%2)==0?Move.STEP:Move.SWEEP);
        else if(now-b.lastParry>=80&&b.pattern++%3==2){b.lastParry=now;play(b,Move.TO_WATER);}
        else play(b,b.pattern++%3==0?Move.SWEEP:Move.FAST);
    }
    /** Blocks per tick of Paul's last steps toward the player at full speed: the pace of his own walk (speed 0.2 on foot). */
    public static final double APPROACH_STEP=.10;
    /** Paul's last-steps pace now: slowed in the same proportion as his movement speed, by a Mire for one. */
    public static double approachStep(LivingEntity paul){
        var speed=paul.getAttribute(Attributes.MOVEMENT_SPEED);
        if(speed==null||speed.getBaseValue()<=0)return APPROACH_STEP;
        return APPROACH_STEP*Math.clamp(speed.getValue()/speed.getBaseValue(),0,1);
    }
    private static void approach(Bout b,long now){
        Vec3 d=b.player.position().subtract(b.paul.position());Vec3 flat=new Vec3(d.x,0,d.z);
        if(flat.lengthSqr()<=1.0E-6||move(b,flat.normalize(),approachStep(b.paul))){b.paul.getNavigation().stop();return;}
        // Something in the straight way, a block the player put down say: pathfinding goes round it as before.
        if(now%10==0)b.paul.getNavigation().moveTo(b.player,1.05);
    }
    private static void face(Bout b,Vec3 target){
        Vec3 d=target.subtract(b.paul.position());float yaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z));
        b.paul.setYRot(yaw);b.paul.setYHeadRot(yaw);b.paul.setYBodyRot(yaw);
        PaulDuelCombat.face(b.paul,yaw);
    }
    /** One step of Paul's own, if the arena, the blocks and the floor allow it; says whether he moved. */
    private static boolean move(Bout b,Vec3 direction,double speed){
        Vec3 step=direction.scale(speed),next=b.paul.position().add(step);
        if(!b.arena.contains(next)||next.subtract(b.arena.centre()).horizontalDistanceSqr()>Math.pow(b.arena.radius()-.4,2)
                ||!b.level.noCollision(b.paul,b.paul.getBoundingBox().move(step)))return false;
        var below=BlockPos.containing(next.x,next.y-.1,next.z);
        if(!b.level.hasChunkAt(below)||!b.level.getBlockState(below).isFaceSturdy(b.level,below,net.minecraft.core.Direction.UP))return false;
        b.paul.move(net.minecraft.world.entity.MoverType.SELF,step);
        b.paul.setDeltaMovement(0,b.paul.getDeltaMovement().y,0);b.paul.hasImpulse=true;
        return true;
    }
    public static void play(Bout b,Move move){
        b.paul.getNavigation().stop();b.move=null;b.pendingMove=null;b.moveStarted=b.level.getGameTime();
        if(b.phase==1&&move!=Move.PARRY&&move!=Move.COUNTER&&move!=Move.FROM_WATER)b.paul.setDuelStance(2);
        Vec3 d=b.player.position().subtract(b.paul.position());b.committedDirection=new Vec3(d.x,0,d.z).normalize();
        if(move!=Move.STAGGER&&move!=Move.STAGGER2)face(b,b.player.position());
        if(move==Move.PARRY){b.paul.setDuelStance(3);b.stageUntil=b.moveStarted+22;PaulDuelCombat.stopAction(b.paul);}
        else PaulDuelCombat.play(b.paul,move);
        // Ending an interrupted copy of the same clip must not erase ownership of the replacement.
        b.move=move;
    }
    public static void motionEnded(FamilyResidentEntity paul,Move ended){
        Bout b=bout(paul);if(b==null||b.move!=ended)return;
        b.move=null;
        if(b.stage==Stage.RESULT){paul.setDuelStance(0);return;}
        if(!b.active())return;
        if(ended==Move.TO_WATER){b.pendingMove=Move.PARRY;return;}
        if(ended==Move.COUNTER){b.pendingMove=Move.FROM_WATER;return;}
        if(ended==Move.FROM_WATER)paul.setDuelStance(2);
        b.nextMove=b.level.getGameTime()+10;
    }
    public static boolean charging(FamilyResidentEntity paul){
        Bout b=bout(paul);if(b==null||!b.active()||b.move!=Move.CHARGE)return false;
        float t=PaulDuelCombat.elapsed(paul,Move.CHARGE);return t>=.50F&&t<.90F;
    }
    public static boolean parry(FamilyResidentEntity paul,DamageSource source){
        Bout b=bout(paul);if(b==null||!b.active()||b.move!=Move.PARRY||!meleeSource(b,source))return false;
        Vec3 d=b.player.position().subtract(paul.position()).normalize();
        if(paul.getLookAngle().dot(d)<.25)return false;
        react(b,"react_reckless");play(b,Move.COUNTER);return true;
    }
    public static boolean stagger(FamilyResidentEntity paul){
        Bout b=bout(paul);if(b==null||!b.active()||charging(paul)||!PaulDuelCombat.armStrike(b.incoming)||!PaulDuelCombat.windingUp(paul))return false;
        // Read before the stagger replaces the cut: was this the double sweep's second strike, after the first had its chance?
        boolean later=dev.joblessreincarnation.compat.epic.SchoolAnimations.followUp(paul);
        react(b,"react_arm");play(b,b.phase==0?Move.STAGGER:Move.STAGGER2);
        dev.joblessreincarnation.training.SchoolCues.send(b.level,new dev.joblessreincarnation.magic.SpellBurstOptions(
                dev.joblessreincarnation.magic.SpellBurstOptions.ARM_BREAK,b.player.getId(),later?1:0),b.player,
                dev.joblessreincarnation.training.LightBladeTraining.armContact(b.player,paul));
        return true;
    }
    public static boolean acceptsDamage(FamilyResidentEntity paul,DamageSource source){
        Bout b=bout(paul);if(b==null||!b.active()||source.getEntity()!=b.player||!valid(b))return false;
        if(meleeSource(b,source))return true;
        if(b.phase!=1)return false;
        if(source.getDirectEntity() instanceof Projectile projectile)return b.projectiles.contains(projectile)
                &&(projectile instanceof WaterBolt||projectile instanceof FireBallProjectile||projectile instanceof StoneCannonProjectile);
        return source.is(DamageTypes.ON_FIRE)&&paul.hasData(ModAttachments.SEARING)
                &&b.player.getUUID().equals(paul.getData(ModAttachments.SEARING).caster());
    }
    private static boolean meleeSource(Bout b,DamageSource source){
        return source.getDirectEntity()==b.player&&source.getEntity()==b.player&&source.is(DamageTypes.PLAYER_ATTACK)
                &&SchoolForm.isSword(b.player.getMainHandItem())&&dev.joblessreincarnation.compat.epic.SwordReach.reaches(b.player,b.paul);
    }
    /** The hurt wrapper supplies the actual source for EF's stun callback, not a guessed animation flag. */
    public static void incoming(FamilyResidentEntity paul,@Nullable DamageSource source){Bout b=bout(paul);if(b!=null)b.incoming=source;}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void incomingDamage(LivingIncomingDamageEvent event){
        DamageSource source=event.getSource();LivingEntity target=event.getEntity();
        if(source.getEntity() instanceof FamilyResidentEntity paul&&paul.member()==FamilyResidentEntity.Member.PAUL){
            Bout b=bout(paul);
            if(b==null||!b.active()||target!=b.player){event.setCanceled(true);return;}
        }
        if(source.getEntity() instanceof LivingEntity attacker){
            Bout b=forPlayer(attacker);
            if(b!=null&&b.stage!=Stage.RESULT&&(!b.active()||target!=b.paul)){event.setCanceled(true);return;}
        }
        Bout b=forPlayer(target);
        if(b!=null&&b.stage!=Stage.RESULT&&source.getEntity()!=b.paul){clear(b);}
    }
    /** Runs after armor and EF damage modifiers. Paul's real health never changes; only this encounter's score does. */
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void damagePre(LivingDamageEvent.Pre event){
        if(event.getEntity().level().isClientSide)return;
        if(event.getEntity() instanceof FamilyResidentEntity paul){
            Bout b=bout(paul);if(b==null||!acceptsDamage(paul,event.getSource()))return;
            float amount=event.getNewDamage();event.setNewDamage(0);
            if(!Float.isFinite(amount)||amount<=0)return;
            boolean magic=!meleeSource(b,event.getSource());
            if(PaulDuelCombat.flowCounter(event.getSource()))react(b,"react_flow");
            b.remaining=Math.max(0,b.remaining-amount);b.dirty=true;
            if(magic){react(b,"react_magic");if(b.move==Move.PARRY||b.move==Move.TO_WATER)play(b,Move.STAGGER2);}
            if(b.remaining<=0){
                if(b.phase==0)nextPhase(b);else finish(b,"win",true,false);
            }
        }else if(event.getEntity() instanceof ServerPlayer player&&event.getSource().getEntity() instanceof FamilyResidentEntity paul){
            Bout b=bout(paul);if(b==null||b.player!=player||!b.active()){event.setNewDamage(0);return;}
            float allowed=Math.max(0,player.getHealth()+player.getAbsorptionAmount()-PLAYER_FLOOR);
            if(event.getNewDamage()>=allowed){event.setNewDamage(allowed);finish(b,"lose",false,true);}
        }
    }
    private static void nextPhase(Bout b){
        clearProjectiles(b);clearFields(b);SpellCasting.cancel(b.player);
        b.phase=1;b.remaining=SECOND_BAR;b.transition++;b.stage=Stage.INTERMISSION;
        b.stageUntil=b.level.getGameTime()+INTERMISSION_TICKS;b.paul.setDuelStance(2);
        play(b,Move.SERIOUS);line(b,"praise",20);
    }
    private static void finish(Bout b,String reason,boolean win,boolean lost){
        if(b.stage==Stage.RESULT)return;
        b.stage=Stage.RESULT;b.stageUntil=b.level.getGameTime()+RESULT_TICKS;b.paul.setDuelUser(null,false);
        b.paul.getNavigation().stop();clearProjectiles(b);clearFields(b);SpellCasting.cancel(b.player);
        b.paul.removeEffect(ModContent.SLUGGISH);b.paul.removeEffect(ModContent.SEARING);
        if(win){
            b.player.setData(ModAttachments.PAUL_DUEL_PROGRESS,new PaulDuelProgress(true));
            StoryAdvancements.onPaulDuelWon(b.player);
            play(b,b.phase==0?Move.LAUGH:Move.LAUGH2);
        }else if(lost)play(b,b.phase==0?Move.HELP:Move.HELP2);
        else {b.move=null;PaulDuelCombat.stopAction(b.paul);}
        line(b,reason,RESULT_TICKS);
    }
    private static void clear(Bout b){
        if(BOUTS.remove(b.paul)!=b)return;
        clearProjectiles(b);clearFields(b);
        for(UUID id:b.viewers){ServerPlayer p=viewer(b,id);if(p!=null)PacketDistributor.sendToPlayer(p,new RemoveBossBarPayload(b.id));}
        b.viewers.clear();
        if(!b.paul.isRemoved()){
            b.paul.getNavigation().stop();b.paul.setDuelStance(0);b.paul.setDuelUser(null,false);b.paul.removeEffect(ModContent.SLUGGISH);b.paul.removeEffect(ModContent.SEARING);
            var speed=b.paul.getAttribute(Attributes.MOVEMENT_SPEED);if(speed!=null)speed.removeModifier(MireFields.SLOW_ID);
            PaulDuelCombat.stopAction(b.paul);b.paul.setTarget(null);
        }
    }
    private static void clearProjectiles(Bout b){
        for(Projectile p:new ArrayList<>(b.projectiles))if(!p.isRemoved())p.discard();b.projectiles.clear();
    }
    private static void clearFields(Bout b){
        var box=b.paul.getBoundingBox().inflate(32);
        for(MireEntity e:b.level.getEntitiesOfClass(MireEntity.class,box,e->b.player.getUUID().equals(e.ownerId())))e.discard();
        for(WaterShieldEntity e:b.level.getEntitiesOfClass(WaterShieldEntity.class,box,e->b.player.getUUID().equals(e.ownerId())))e.discard();
    }
    public static void react(Bout b,String key){if(b.reactions.add(key))line(b,key,50);}
    private static void line(Bout b,String key,int ticks){
        b.line=Component.translatable(PREFIX+key);b.lineId++;b.lineUntil=b.level.getGameTime()+ticks;b.dirty=true;sayPrivate(b.player,key);
    }
    private static void sayPrivate(ServerPlayer p,String key){p.sendSystemMessage(Component.translatable("message.jobless_reincarnation.family.line",Component.translatable(PREFIX+"name"),Component.translatable(PREFIX+key)));}
    /** Shows a short line over the hotbar unless one was shown this second; says whether it was shown. */
    private static boolean hint(ServerPlayer p,String key){
        long now=p.level().getGameTime();Long previous=HINTS.get(p.getUUID());if(previous!=null&&now>=previous&&now-previous<20)return false;
        HINTS.put(p.getUUID(),now);p.displayClientMessage(Component.translatable(PREFIX+key),true);return true;
    }
    private static @Nullable ServerPlayer viewer(Bout b,UUID id){
        Entity entity=b.level.getEntity(id);return entity instanceof ServerPlayer p?p:b.level.getServer().getPlayerList().getPlayer(id);
    }
    private static void views(Bout b){
        if(b.stage==Stage.APPROACH)return;
        long now=b.level.getGameTime();Set<UUID> here=new HashSet<>();
        for(ServerPlayer p:b.level.players()){
            if(p.isRemoved()||!b.arena.near(p.position(),16,8))continue;
            here.add(p.getUUID());if(b.dirty||!b.viewers.contains(p.getUUID())){
                int stage=switch(b.stage){case INTRO->0;case ACTIVE->1;case INTERMISSION->2;case RESULT->3;default->throw new IllegalStateException();};
                int ticks=b.stage==Stage.INTRO||b.stage==Stage.INTERMISSION?(int)Math.max(0,b.stageUntil-now):0;
                int lineTicks=(int)Math.max(0,b.lineUntil-now);
                PacketDistributor.sendToPlayer(p,new BossBarPayload(b.id,b.level.dimension().location(),b.arena.centre(),p==b.player,
                        Component.translatable(PREFIX+"name"),Component.translatable(PREFIX+"title"),EMBLEM,2,b.phase,
                        Math.clamp(b.remaining/b.maximum(),0F,1F),new int[]{0x7F9AB8,0xB8323A},true,b.phase==0,
                        stage,ticks,b.transition,lineTicks>0?b.line:null,b.lineId,lineTicks,b.arena.radius()));
            }
        }
        for(UUID id:b.viewers)if(!here.contains(id)){ServerPlayer p=viewer(b,id);if(p!=null)PacketDistributor.sendToPlayer(p,new RemoveBossBarPayload(b.id));}
        b.viewers.clear();b.viewers.addAll(here);b.dirty=false;
    }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event){
        if(event.getLevel().isClientSide||!(event.getEntity() instanceof Projectile p)||!(p.getOwner() instanceof ServerPlayer owner))return;
        Bout b=forPlayer(owner);if(b!=null&&b.stage!=Stage.RESULT)b.projectiles.add(p);
    }
    @SubscribeEvent public static void left(EntityLeaveLevelEvent event){
        if(event.getLevel().isClientSide)return;
        if(event.getEntity() instanceof FamilyResidentEntity paul){INVITES.remove(paul);Bout b=bout(paul);if(b!=null)clear(b);}
        if(event.getEntity() instanceof ServerPlayer player){Bout b=forPlayer(player);if(b!=null)clear(b);INVITES.values().removeIf(i->i.player==player);}
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        Bout b=forPlayer(event.getEntity());if(b!=null)clear(b);HINTS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event){
        INVITES.entrySet().removeIf(e->{Invite i=e.getValue();long now=i.player.level().getGameTime();
            boolean expired=now>i.until||now<i.created||!i.player.isAlive()||i.player.isRemoved()||e.getKey().isRemoved()
                    ||i.player.level()!=e.getKey().level()||!i.player.getMainHandItem().is(ModContent.PRACTICE_WOODEN_SWORD.get())
                    ||!i.player.canInteractWithEntity(e.getKey(),1);
            if(expired&&i.player.isAlive()&&!i.player.isRemoved())sayPrivate(i.player,"later");return expired;});
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){BOUTS.clear();INVITES.clear();HINTS.clear();}
    private PaulDuel() {}
}
