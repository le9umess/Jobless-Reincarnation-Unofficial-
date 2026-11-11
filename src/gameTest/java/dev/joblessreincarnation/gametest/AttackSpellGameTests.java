package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AttackSpellGameTests {
    private static final Vec3 ORIGIN = new Vec3(5.5,2,1);
    private static Actor caster(Fixture f) {
        Actor p=f.actor();
        p.setPos(f.helper.absoluteVec(ORIGIN));p.setYRot(0);p.setXRot(0);
        p.setNoGravity(true);
        p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.setData(ModAttachments.MANA,ManaData.full(100));
        return p;
    }
    private static Pig pig(Fixture f,double x,double z) {
        Pig pig=EntityType.PIG.create(f.helper.getLevel());
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        pig.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.2);
        pig.setHealth(100);pig.setNoAi(true);
        return f.admit(pig,new Vec3(x,2,z));
    }
    private static Projectile shot(Fixture f,Actor p,SpellCatalog spell,int stage,Vec3 from,Vec3 motion) {
        Projectile b=switch(spell){
            case WATER_BOLT -> new WaterBolt(f.helper.getLevel(),p);
            case FIRE_BALL -> new FireBallProjectile(f.helper.getLevel(),p);
            case STONE_CANNON -> new StoneCannonProjectile(f.helper.getLevel(),p);
            default -> throw new IllegalArgumentException("Not an attack spell");
        };
        if(b instanceof WaterBolt water)water.setFormStage(stage);
        else if(b instanceof FireBallProjectile fire)fire.setFormStage(stage);
        else ((StoneCannonProjectile)b).setFormStage(stage);
        b.setDeltaMovement(motion);
        return f.admit(b,from);
    }
    private static Projectile hit(Fixture f,Actor p,SpellCatalog spell,int stage) {
        var b=shot(f,p,spell,stage,new Vec3(5.5,2.7,3.1),new Vec3(0,0,3));
        b.tick();return b;
    }
    private static void ticks(LivingEntity entity,int count) {
        for(int i=0;i<count;i++)((net.minecraft.server.level.ServerLevel)entity.level()).tickNonPassenger(entity);
    }
    private static void close(GameTestHelper h,float actual,float expected,String message) {
        h.assertTrue(Math.abs(actual-expected)<.005F,message+" actual="+actual+" expected="+expected);
    }
    private static float baseDamage(SpellCatalog spell) {
        return switch(spell){
            case WATER_BOLT -> WaterControl.damage(0);
            case FIRE_BALL -> MagicConfig.FIRE_BALL_DAMAGE.get().floatValue();
            default -> MagicConfig.STONE_CANNON_DAMAGE.get().floatValue();
        };
    }

    @GameTest(template="empty")
    public static void formDamageUsesExistingProjectileHurtPaths(GameTestHelper h) {
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL,SpellCatalog.STONE_CANNON)) {
            for(int stage=0;stage<3;stage++)try(var f=new Fixture(h)){
                Actor p=caster(f);var target=pig(f,5.5,4.4);
                hit(f,p,spell,stage);
                close(h,target.getHealth(),100-baseDamage(spell)*AttackSpellRules.damageMultiplier(stage),
                        "Direct victim is damaged once without its own splash "+spell+" stage "+stage);
                h.assertTrue(target.hasEffect(ModContent.SLUGGISH)==(spell==SpellCatalog.WATER_BOLT&&stage==2),"Water slow matches the actual tier");
                h.assertTrue(target.hasEffect(ModContent.SEARING)==(spell==SpellCatalog.FIRE_BALL&&stage==2),"Custom burning matches the actual tier");
                if(spell==SpellCatalog.FIRE_BALL&&stage==2)h.assertFalse(target.isOnFire(),"An adept fire hit uses its custom burn");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void blastDamagesExposedNeighborsButStopsAtTheWallAndRadius(GameTestHelper h) {
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL,SpellCatalog.STONE_CANNON))try(var f=new Fixture(h)){
            Actor p=caster(f);
            var direct=pig(f,5.5,4.4);var adjacent=pig(f,6.5,4.1);
            var behind=pig(f,5.5,6.0);var far=pig(f,10.0,4.4);
            for(int x=4;x<=6;x++)for(int y=2;y<=4;y++)f.block(new BlockPos(x,y,5),Blocks.STONE.defaultBlockState());
            hit(f,p,spell,2);
            h.assertTrue(direct.getHealth()<100&&adjacent.getHealth()<100,"Direct and exposed targets lose health "+spell);
            close(h,behind.getHealth(),100,"The wall shields the nearby target");
            close(h,far.getHealth(),100,"Targets outside the radius stay safe");
            h.assertTrue(adjacent.getHealth()>100-baseDamage(spell)*AttackSpellRules.damageMultiplier(2),
                    "Splash damage is lower than the direct hit");
            close(h,p.getHealth(),20,"Caster does not splash himself");
            h.assertTrue(h.getBlockState(new BlockPos(5,2,5)).is(Blocks.STONE),"The area hit keeps terrain intact");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void lowerWaterAndFireRemainSingleTargetWhilePracticedStoneSplashes(GameTestHelper h) {
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL,SpellCatalog.STONE_CANNON))try(var f=new Fixture(h)){
            Actor p=caster(f);pig(f,5.5,4.4);var neighbor=pig(f,6.5,4.1);
            hit(f,p,spell,1);
            h.assertTrue((neighbor.getHealth()<100)==(spell==SpellCatalog.STONE_CANNON),"Area mechanism appears at the intended tier "+spell);
            h.assertFalse(neighbor.hasEffect(ModContent.SLUGGISH)||neighbor.hasEffect(ModContent.SEARING),"Intermediate splash adds no advanced debuff");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void cancelledDamageAndImpactNeverAttachTheNewDebuffs(GameTestHelper h) {
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL)) {
            try(var f=new Fixture(h)){
                Actor p=caster(f);var direct=pig(f,5.5,4.4);var neighbor=pig(f,6.5,4.1);
                Consumer<LivingIncomingDamageEvent> cancel=e->{if(e.getEntity()==direct||e.getEntity()==neighbor)e.setCanceled(true);};
                NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,cancel);
                try{hit(f,p,spell,2);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
                for(var victim:List.of(direct,neighbor)){
                    close(h,victim.getHealth(),100,"Cancelled damage stays cancelled");
                    h.assertFalse(victim.hasEffect(ModContent.SLUGGISH)||victim.hasEffect(ModContent.SEARING),"No effect follows a cancelled wound");
                }
            }
            try(var f=new Fixture(h)){
                Actor p=caster(f);var direct=pig(f,5.5,4.4);var neighbor=pig(f,6.5,4.1);
                Consumer<ProjectileImpactEvent> cancel=e->{if(e.getProjectile().getOwner()==p)e.setCanceled(true);};
                NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class,cancel);
                try{hit(f,p,spell,2);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
                close(h,direct.getHealth(),100,"Cancelled impact has no direct harm");
                close(h,neighbor.getHealth(),100,"Cancelled impact has no hidden blast");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void waterSlowRefreshesWithoutStacksAndCuresCleanly(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var target=pig(f,5.5,4.4);
            hit(f,p,SpellCatalog.WATER_BOLT,2);
            close(h,(float)target.getAttributeValue(Attributes.MOVEMENT_SPEED),.15F,"One sluggish effect reduces movement by a quarter");
            ticks(target,25);target.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));target.setDeltaMovement(Vec3.ZERO);
            hit(f,p,SpellCatalog.WATER_BOLT,2);
            close(h,(float)target.getAttributeValue(Attributes.MOVEMENT_SPEED),.15F,"Repeated hits refresh rather than stack");
            h.assertTrue(target.getEffect(ModContent.SLUGGISH).getDuration()>=59,"Refreshed lifetime");
            target.removeEffectsCuredBy(EffectCures.MILK);
            close(h,(float)target.getAttributeValue(Attributes.MOVEMENT_SPEED),.2F,"Milk removes only the added slowdown");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void searingTicksWithAttributionAndRestoresItsRemainingCadence(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var target=pig(f,5.5,4.4);
            hit(f,p,SpellCatalog.FIRE_BALL,2);
            float initial=target.getHealth();
            ticks(target,7);
            CompoundTag tag=target.saveWithoutId(new CompoundTag());
            tag.remove("UUID");target.discard();
            var loaded=EntityType.PIG.create(h.getLevel());loaded.load(tag);f.admit(loaded,new Vec3(5.5,2,4.4));
            h.assertTrue(loaded.hasEffect(ModContent.SEARING)&&loaded.hasData(ModAttachments.SEARING),"Effect and source survive native save/load");
            h.assertValueEqual(loaded.getData(ModAttachments.SEARING).caster(),p.getUUID(),"Burn ownership survived");
            ticks(loaded,12);close(h,loaded.getHealth(),initial,"First pulse is not advanced by reload");
            ticks(loaded,1);close(h,loaded.getHealth(),initial-1,"First pulse occurs at the original one-second mark");
            ticks(loaded,60);close(h,loaded.getHealth(),initial-4,"Four finite pulses in total");
            h.assertFalse(loaded.hasEffect(ModContent.SEARING),"Burn duration completes");
            h.assertFalse(loaded.hasData(ModAttachments.SEARING),"Expired source state is cleaned");
            h.assertTrue(loaded.getLastHurtByMob()==p,"Damage remains attributed to its caster");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void refreshingBurnDoesNotStarveTicksAndWaterExtinguishesOnlyIt(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var target=pig(f,5.5,4.4);
            hit(f,p,SpellCatalog.FIRE_BALL,2);ticks(target,10);
            target.invulnerableTime=0;target.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));target.setDeltaMovement(Vec3.ZERO);
            hit(f,p,SpellCatalog.FIRE_BALL,2);float afterRefresh=target.getHealth();
            ticks(target,10);close(h,target.getHealth(),afterRefresh-1,"Refreshing after half a second keeps the original next pulse");
            target.addEffect(new MobEffectInstance(MobEffects.REGENERATION,200,0));
            f.block(new BlockPos(5,2,4),Blocks.WATER.defaultBlockState());target.setPos(h.absoluteVec(new Vec3(5.5,2,4.5)));
            ticks(target,3);
            h.assertFalse(target.hasEffect(ModContent.SEARING),"Actual water contact puts out the custom burn");
            h.assertTrue(target.hasEffect(MobEffects.REGENERATION),"Unrelated effects survive extinguishing");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void resistanceAndEffectVetoDoNotInventBurn(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var target=pig(f,5.5,4.4);
            target.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,200,0));
            hit(f,p,SpellCatalog.FIRE_BALL,2);
            close(h,target.getHealth(),100,"Native fire resistance rejects fire damage");
            h.assertFalse(target.hasEffect(ModContent.SEARING),"Fire resistance also rejects the custom burn");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void adeptDrillStrikesThreeDistinctBodiesAndEmitsEachImpact(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);
            var one=pig(f,5.5,4);var two=pig(f,5.5,6);var three=pig(f,5.5,8);var four=pig(f,5.5,10);
            var flank1=pig(f,6.4,4);var flank2=pig(f,6.4,6);var flank3=pig(f,6.4,8);
            var drill=(StoneCannonProjectile)shot(f,p,SpellCatalog.STONE_CANNON,2,new Vec3(5.5,2.7,3),new Vec3(0,0,3.6));
            Consumer<LivingIncomingDamageEvent> reenter=e->{if(e.getSource().getDirectEntity()==drill)drill.tick();};
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,reenter);
            try{for(int i=0;i<5&&!drill.isRemoved();i++)drill.tick();}finally{NeoForge.EVENT_BUS.unregister(reenter);}
            for(var direct:List.of(one,two,three))close(h,direct.getHealth(),88,"Each direct target is struck once");
            close(h,four.getHealth(),100,"The fourth target is beyond the spent third contact and its blast");
            for(var side:List.of(flank1,flank2,flank3))h.assertTrue(side.getHealth()<100,"Every distinct contact creates a local pulse");
            h.assertTrue(drill.isRemoved(),"Drill stops after three bodies");
            var cues=p.spellBursts.stream().filter(x->x.kind()==SpellBurstOptions.STONE_IMPACT&&x.caster()==p.getId()).toList();
            h.assertValueEqual(cues.size(),3,"One actual impact message at each contact");
            for(int i=0;i<3;i++){
                h.assertValueEqual(cues.get(i).impactIndex(),i,"Impact index follows collision order");
                close(h,cues.get(i).radius(),1.5F,"Published radius agrees with the area");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void aWallCreatedDuringTheFirstDrillHitStopsLaterContacts(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var first=pig(f,5.5,4);var behind=pig(f,5.5,6.5);
            var drill=(StoneCannonProjectile)shot(f,p,SpellCatalog.STONE_CANNON,2,new Vec3(5.5,2.7,3),new Vec3(0,0,3.8));
            Consumer<LivingIncomingDamageEvent> wall=e->{
                if(e.getEntity()==first)for(int x=4;x<=6;x++)for(int y=2;y<=4;y++)f.block(new BlockPos(x,y,5),Blocks.STONE.defaultBlockState());
            };
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,wall);
            try{drill.tick();}finally{NeoForge.EVENT_BUS.unregister(wall);}
            close(h,first.getHealth(),88,"First contact still resolves");
            close(h,behind.getHealth(),100,"Re-trace honors a wall created by an earlier impact hook");
            h.assertTrue(drill.isRemoved(),"Solid terrain consumes the remaining drill");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void splashRespectsTeammatesPetsAndProtectedResidents(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);var primary=pig(f,5.5,4.4);
            var wolf=EntityType.WOLF.create(h.getLevel());wolf.setTame(true,false);wolf.setOwnerUUID(p.getUUID());wolf.setNoAi(true);
            f.admit(wolf,new Vec3(6.4,2,4.3));float petHealth=wolf.getHealth();
            var resident=ModContent.ROXY_TUTOR.get().create(h.getLevel());resident.setNoAi(true);
            f.admit(resident,new Vec3(4.6,2,4.3));float npcHealth=resident.getHealth();
            Actor friend=f.actor();friend.setPos(h.absoluteVec(new Vec3(5.5,2,5.3)));
            var board=h.getLevel().getScoreboard();var team=board.addPlayerTeam("sf"+UUID.randomUUID().toString().substring(0,8));
            team.setAllowFriendlyFire(false);board.addPlayerToTeam(p.getScoreboardName(),team);board.addPlayerToTeam(friend.getScoreboardName(),team);
            try{
                hit(f,p,SpellCatalog.WATER_BOLT,2);
                h.assertTrue(primary.getHealth()<100,"Hostile-directed hit still works");
                close(h,friend.getHealth(),20,"Teammate is protected");
                close(h,wolf.getHealth(),petHealth,"Tamed companion is protected");
                close(h,resident.getHealth(),npcHealth,"Family NPC is protected");
                for(var protectedTarget:List.of(friend,wolf,resident))h.assertFalse(protectedTarget.hasEffect(ModContent.SLUGGISH),"No debuff bypasses protection");
            }finally{board.removePlayerTeam(team);}
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void raisedShieldsRejectDebuffsAndStopTheAdeptDrill(GameTestHelper h) {
        class ShieldPig extends Pig {
            ShieldPig(){super(EntityType.PIG,h.getLevel());}
            void ready(){
                setItemInHand(InteractionHand.MAIN_HAND,net.minecraft.world.item.Items.SHIELD.getDefaultInstance());
                startUsingItem(InteractionHand.MAIN_HAND);
                useItemRemaining=getUseItem().getUseDuration(this)-6;
            }
        }
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL,SpellCatalog.STONE_CANNON))try(var f=new Fixture(h)){
            Actor p=caster(f);var guard=new ShieldPig();guard.setNoAi(true);guard.setYRot(180);guard.setYHeadRot(180);
            f.admit(guard,new Vec3(5.5,2,4.4));guard.ready();
            var behind=pig(f,5.5,7);
            h.assertTrue(guard.isBlocking(),"Native shield has completed its raise");
            var projectile=hit(f,p,spell,2);
            close(h,guard.getHealth(),10,"Shield rejects the incoming direct wound");
            h.assertFalse(guard.hasEffect(ModContent.SLUGGISH)||guard.hasEffect(ModContent.SEARING),"A blocked spell cannot attach its debuff");
            if(spell==SpellCatalog.STONE_CANNON){
                h.assertTrue(projectile.isRemoved(),"A blocked drill has no penetration receipt");
                close(h,behind.getHealth(),100,"The shield ends the drill before the next body");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void originalDirectTargetRulesStaySeparateFromProtectedSplash(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);
            var direct=EntityType.WOLF.create(h.getLevel());direct.setTame(true,false);
            direct.setOwnerUUID(UUID.randomUUID());direct.setNoAi(true);f.admit(direct,new Vec3(5.5,2,4.4));
            var neighbor=EntityType.WOLF.create(h.getLevel());neighbor.setTame(true,false);
            neighbor.setOwnerUUID(UUID.randomUUID());neighbor.setNoAi(true);f.admit(neighbor,new Vec3(6.4,2,4.3));
            float first=direct.getHealth(),second=neighbor.getHealth();
            hit(f,p,SpellCatalog.WATER_BOLT,2);
            h.assertTrue(direct.getHealth()<first,"The original direct shot can still hit a deliberately targeted non-allied animal");
            close(h,neighbor.getHealth(),second,"The new area pulse protects a nearby tamed animal");
            h.assertFalse(neighbor.hasEffect(ModContent.SLUGGISH),"Protected collateral also avoids the debuff");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void overlappingDrillPulsesDeduplicateCollateralWithoutWeakeningDirectHits(GameTestHelper h) {
        try(var f=new Fixture(h)){
            Actor p=caster(f);
            var one=pig(f,5.5,4);var two=pig(f,5.5,5);var three=pig(f,5.5,6);
            var side=pig(f,6.4,4.8);
            var count=new java.util.concurrent.atomic.AtomicInteger();
            var drill=(StoneCannonProjectile)shot(f,p,SpellCatalog.STONE_CANNON,2,new Vec3(5.5,2.7,3),new Vec3(0,0,3.8));
            Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> reset=e->{
                if(e.getSource().getDirectEntity()==drill && e.getEntity()!=side)side.invulnerableTime=0;
            };
            Consumer<LivingIncomingDamageEvent> watch=e->{
                if(e.getSource().getDirectEntity()==drill && e.getEntity()==side)count.incrementAndGet();
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post.class,reset);
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,watch);
            try{drill.tick();}finally{NeoForge.EVENT_BUS.unregister(reset);NeoForge.EVENT_BUS.unregister(watch);}
            h.assertValueEqual(count.get(),1,"One projectile cannot splash the same bystander at all three contact points");
            for(var direct:List.of(one,two,three))close(h,direct.getHealth(),88,"A later direct hit retains native stronger-hit semantics");
            h.assertTrue(side.getHealth()<100 && side.getHealth()>=95.19F,"Collateral takes only one bounded splash");
        }
        h.succeed();
    }

}

