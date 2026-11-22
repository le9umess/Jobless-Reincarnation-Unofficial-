package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.encounter.PaulDuel;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import dev.joblessreincarnation.npc.IcicleLesson;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import java.util.*;

/** A bounded server field. Only this mod's transient movement modifier is removed, never another potion. */
public final class IcicleFields {
    public static final ResourceLocation SLOW_ID=ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"icicle_cold");
    public static final int NOVA_TICKS=60;
    private static final Map<ServerLevel,State> LEVELS=new IdentityHashMap<>();
    private record Nova(long until,ServerPlayer owner){}
    private static final class State {
        final Set<IcicleFieldEntity> fields=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<LivingEntity> affected=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<LivingEntity,Nova> novas=new IdentityHashMap<>();
        final Map<LivingEntity,Long> cues=new IdentityHashMap<>();
    }
    public static void register(IEventBus bus){bus.addListener(IcicleFields::tick);bus.addListener(IcicleFields::leave);bus.addListener(IcicleFields::unload);bus.addListener(IcicleFields::stopped);}
    public static void activate(IcicleFieldEntity f){
        if(!(f.level() instanceof ServerLevel level)||f.owner()==null||f.isRemoved()||f.demonstration())throw new IllegalArgumentException("Unpaid/unowned ice field");
        // Publish only after admission and payment. Old fields fade after a successful replacement, never a failed cast.
        for(var entry:LEVELS.entrySet())for(var old:new ArrayList<>(entry.getValue().fields))if(entry.getKey().getServer()==level.getServer()&&old.owner()!=null&&old.owner().getUUID().equals(f.owner().getUUID()))old.fade();
        LEVELS.computeIfAbsent(level,l->new State()).fields.add(f);
    }
    public static boolean registered(IcicleFieldEntity f){var s=LEVELS.get(f.level());return s!=null&&s.fields.contains(f);}
    public static boolean eligible(ServerPlayer owner,LivingEntity target){
        if(!owner.isAlive()||owner.isRemoved()||owner.isSpectator()||target.level()!=owner.level()||!target.isAlive()||target.isRemoved()||target.isSpectator())return false;
        if(owner==target)return true;
        if(target instanceof Player&&PaulDuel.forPlayer(target)!=null)return false; // No outsider control during a private practice bout.
        if(owner.isAlliedTo(target)||target.isAlliedTo(owner))return false;
        return AttackSpellImpact.canAffect(owner,target);
    }
    public static boolean touches(IcicleFieldEntity f,Entity target){
        double dx=target.getX()-f.getX(),dz=target.getZ()-f.getZ();
        return target.level()==f.level()&&dx*dx+dz*dz<=IcicleFieldEntity.RADIUS*IcicleFieldEntity.RADIUS
            &&Math.abs(target.getY()-f.getY())<=1.5
            &&IcicleGround.visible((ServerLevel)f.level(),f.position().add(0,.6,0),target.position().add(0,Math.min(.6,target.getBbHeight()/2),0),f);
    }
    private static boolean valid(IcicleFieldEntity f,ServerLevel level){var p=f.owner();return !f.isRemoved()&&level.getEntity(f.getUUID())==f&&p!=null&&p.isAlive()&&!p.isRemoved()&&!p.isSpectator()&&p.level()==level&&IcicleGround.valid(level,f.position());}
    private static void slow(LivingEntity e,double amount){var a=e.getAttribute(Attributes.MOVEMENT_SPEED);if(a==null)return;var current=a.getModifier(SLOW_ID);if(current==null||current.amount()!=amount){a.removeModifier(SLOW_ID);a.addTransientModifier(new AttributeModifier(SLOW_ID,amount,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));}}
    private static void clear(LivingEntity e){var a=e.getAttribute(Attributes.MOVEMENT_SPEED);if(a!=null)a.removeModifier(SLOW_ID);}
    public static void tick(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level))return;var s=LEVELS.get(level);if(s==null)return;long now=level.getGameTime();
        var next=Collections.newSetFromMap(new IdentityHashMap<LivingEntity,Boolean>());
        s.novas.entrySet().removeIf(e->now>=e.getValue().until||e.getValue().until-now>NOVA_TICKS||!eligible(e.getValue().owner,e.getKey())||e.getKey().isOnFire()||e.getKey().isInLava());
        for(var f:new ArrayList<>(s.fields)){
            if(!valid(f,level)){s.fields.remove(f);f.discard();continue;}
            if(!f.activeAt(now))continue;
            if(!f.terrainApplied){f.terrainApplied=true;int count=freezeWater(f);IcicleLesson.frozen(f.owner(),count);}
            if(nearHeat(f)){heat(f);continue;}
            for(var e:level.getEntitiesOfClass(LivingEntity.class,new AABB(f.position(),f.position()).inflate(3,2,3),e->eligible(f.owner(),e)&&touches(f,e))){
                if(!valid(f,level)||!f.activeAt(now)||!eligible(f.owner(),e)||!touches(f,e))continue;
                e.clearFire();next.add(e);
                // Never write vanilla frozen ticks: even a partial value adds powder-snow movement penalties.
                // The local frost rim follows our synced modifier, independent of real powder snow.
                if(!s.cues.containsKey(e)||now-s.cues.get(e)>=20){s.cues.put(e,now);IcicleVisuals.affect(level,e);}
                if(IcicleWet.wet(e)&&f.triggered.add(e.getUUID())&&!s.novas.containsKey(e)){
                    s.novas.put(e,new Nova(now+NOVA_TICKS,f.owner()));
                    if(!(e instanceof Player p&&p.getAbilities().instabuild)) e.hurt(level.damageSources().source(net.minecraft.world.damagesource.DamageTypes.FREEZE,f,f.owner()),2F);
                    if(e.isAlive()&&!e.isRemoved()&&eligible(f.owner(),e)){IcicleVisuals.nova(level,e);f.owner().displayClientMessage(net.minecraft.network.chat.Component.translatable("message.jobless_reincarnation.icicle.nova"),true);}
                }
            }
            // A wooden practice prop has no health and is deliberately not a LivingEntity.
            for(var d:level.getEntitiesOfClass(PracticeDummyEntity.class,new AABB(f.position(),f.position()).inflate(3,2,3),d->f.owner().getUUID().equals(d.ownerId())&&d.hasSupport()&&touches(f,d))){
                if(IcicleWet.contact(d,f.owner().getUUID())&&f.triggered.add(d.getUUID())){IcicleVisuals.nova(level,d);IcicleLesson.nova(f.owner(),d);}
            }
        }
        for(var entry:s.novas.entrySet())next.add(entry.getKey());
        for(var old:new ArrayList<>(s.affected))if(!next.contains(old))clear(old);
        for(var e:next)slow(e,s.novas.containsKey(e)?-.75:-.30);
        s.affected.clear();s.affected.addAll(next);s.cues.entrySet().removeIf(e->e.getKey().isRemoved()||e.getKey().level()!=level||now-e.getValue()>40);
        if(s.fields.isEmpty()&&s.novas.isEmpty()&&s.affected.isEmpty())LEVELS.remove(level);
    }
    private static boolean allowed(ServerPlayer p,BlockPos pos){return p.mayBuild()&&p.level().mayInteract(p,pos)&&p.mayUseItemAt(pos,Direction.UP,p.getMainHandItem());}
    /** Protected/flowing/waterlogged cells are simply skipped; they never get replaced by an ice-shaped solid. */
    static int freezeWater(IcicleFieldEntity f){
        var level=(ServerLevel)f.level();var owner=f.owner();if(owner==null||level.dimensionType().ultraWarm()||level.captureBlockSnapshots||level.restoringBlockSnapshots)return 0;
        int count=0;BlockPos centre=f.blockPosition();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x*x+z*z<=9){
            for(int y=0;y>=-3;y--){BlockPos pos=centre.offset(x,y,z);var before=level.getBlockState(pos);
                if(!before.is(Blocks.WATER)||!before.getFluidState().isSource()||!level.getBlockState(pos.above()).isAir()||!allowed(owner,pos)
                    ||!IcicleGround.visible(level,f.position().add(0,.2,0),Vec3.atCenterOf(pos).add(0,.6,0),f))continue;
                var frozen=Blocks.FROSTED_ICE.defaultBlockState();
                if(!level.isUnobstructed(frozen,pos,net.minecraft.world.phys.shapes.CollisionContext.empty()))continue;
                var snapshot=BlockSnapshot.create(level.dimension(),level,pos,Block.UPDATE_ALL);boolean committed=false;int size=level.capturedBlockSnapshots.size();
                try{level.captureBlockSnapshots=true;if(!level.setBlock(pos,frozen,Block.UPDATE_ALL))continue;
                    level.captureBlockSnapshots=false;
                    if(EventHooks.onBlockPlace(owner,snapshot,Direction.UP)||!valid(f,level)||!f.activeAt(level.getGameTime())||!allowed(owner,pos)||level.getBlockState(pos)!=frozen||!level.isUnobstructed(frozen,pos,net.minecraft.world.phys.shapes.CollisionContext.empty()))continue;
                    committed=true;f.frozen.add(pos.immutable());count++;
                }finally{level.captureBlockSnapshots=false;
                    // Remove only this write's capture entries. Never swallow another mod's unrelated snapshots.
                    for(int i=level.capturedBlockSnapshots.size()-1;i>=size;i--)if(level.capturedBlockSnapshots.get(i).getPos().equals(pos))level.capturedBlockSnapshots.remove(i);
                    if(!committed&&level.getBlockState(pos)==frozen){boolean restoring=level.restoringBlockSnapshots;level.restoringBlockSnapshots=true;try{snapshot.restore(Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);}finally{level.restoringBlockSnapshots=restoring;}}
                }
                if(committed){level.markAndNotifyBlock(pos,level.getChunkAt(pos),before,frozen,Block.UPDATE_ALL,512);level.scheduleTick(pos,Blocks.FROSTED_ICE,60+level.random.nextInt(60));}break;
            }
        }
        return count;
    }
    private static boolean nearHeat(IcicleFieldEntity f){
        var level=(ServerLevel)f.level();BlockPos c=f.blockPosition();boolean hot=false;
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x*x+z*z<=9)for(int y=-1;y<=1;y++){
            BlockPos p=c.offset(x,y,z);var b=level.getBlockState(p);
            if(!IcicleGround.visible(level,f.position().add(0,.6,0),Vec3.atCenterOf(p),f))continue;
            if(b.is(Blocks.LAVA))hot=true;
            if(b.is(Blocks.FIRE)||b.is(Blocks.SOUL_FIRE)){
                hot=true;var owner=f.owner();
                if(owner!=null&&allowed(owner,p)&&!net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level,p,b,owner)).isCanceled()
                        &&valid(f,level)&&level.getBlockState(p)==b&&allowed(owner,p))level.removeBlock(p,false);
            }
        }
        return hot;
    }
    public static void endOwner(ServerPlayer owner){for(var entry:LEVELS.entrySet()){
        if(entry.getKey().getServer()!=owner.getServer())continue;var s=entry.getValue();
        for(var f:new ArrayList<>(s.fields))if(f.owner()==owner)f.discard();
        s.novas.entrySet().removeIf(e->e.getValue().owner==owner);
    }}
    private static void heat(IcicleFieldEntity f){
        if(!(f.level() instanceof ServerLevel level)||!f.activeAt(level.getGameTime()))return;f.fade();
        for(var p:f.frozen)if(level.hasChunkAt(p)&&level.getBlockState(p).is(Blocks.FROSTED_ICE))level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());
        var s=LEVELS.get(level);if(s!=null)s.novas.entrySet().removeIf(e->touches(f,e.getKey()));
    }
    /** Called at a real, accepted fire impact, never just because a fire projectile existed nearby. */
    public static void heatAt(ServerLevel level,Vec3 point,Entity caster){var s=LEVELS.get(level);if(s==null||caster==null)return;for(var f:new ArrayList<>(s.fields))if(f.owner()!=null&&Math.abs(point.y-f.getY())<=2&&new Vec3(point.x,f.getY(),point.z).distanceToSqr(f.position())<=IcicleFieldEntity.RADIUS*IcicleFieldEntity.RADIUS&&IcicleGround.visible(level,point.add(0,.1,0),f.position().add(0,.3,0),f))heat(f);}
    private static void leave(EntityLeaveLevelEvent event){if(!(event.getLevel() instanceof ServerLevel level))return;if(event.getEntity() instanceof ServerPlayer owner)endOwner(owner);var s=LEVELS.get(level);if(event.getEntity() instanceof LivingEntity e){clear(e);IcicleWet.dry(e);if(s!=null){s.affected.remove(e);s.novas.remove(e);s.cues.remove(e);}}if(s!=null&&event.getEntity() instanceof IcicleFieldEntity f)s.fields.remove(f);}
    private static void unload(LevelEvent.Unload e){if(e.getLevel() instanceof ServerLevel l)removeLevel(l);}
    private static void stopped(ServerStoppedEvent e){for(var l:new ArrayList<>(LEVELS.keySet()))if(l.getServer()==e.getServer())removeLevel(l);}
    private static void removeLevel(ServerLevel l){var s=LEVELS.remove(l);if(s!=null){s.affected.forEach(IcicleFields::clear);for(var f:new ArrayList<>(s.fields))f.discard();}}
    private IcicleFields(){}
}
