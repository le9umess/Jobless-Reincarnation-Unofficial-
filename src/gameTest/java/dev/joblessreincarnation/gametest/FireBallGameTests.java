package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireBallGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "FireTest")); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Caster caster(GameTestHelper h) {
        Caster p = new Caster(h.getLevel()); p.setPos(h.absoluteVec(new Vec3(5.5, 2, 1)));
        p.setYRot(0); p.setXRot(0); p.getAbilities().instabuild = false;
        p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);
        p.getData(ModAttachments.CASTING).select(FireMagic.SPELL);
        p.setData(ModAttachments.MANA, ManaData.full(100)); return p;
    }
    private static Pig pig(GameTestHelper h, double z) {
        Pig pig = EntityType.PIG.create(h.getLevel()); pig.setPos(h.absoluteVec(new Vec3(5.5, 2, z)));
        pig.setNoAi(true); pig.setNoGravity(true); h.getLevel().addFreshEntity(pig); return pig;
    }
    private static FireBallProjectile ball(GameTestHelper h, Caster p, Vec3 from, Vec3 motion) {
        FireBallProjectile ball = new FireBallProjectile(h.getLevel(), p);
        ball.setPos(h.absoluteVec(from)); ball.setDeltaMovement(motion); ball.setPower(5, 3);
        h.getLevel().addFreshEntity(ball); return ball;
    }
    private static void hold(Caster p, int ticks) {
        ModContent.TRAINING_STAFF.get().use(p.level(), p, InteractionHand.MAIN_HAND);
        for (int t = 0; t < ticks; t++) p.tickUse();
    }
    private static void free(GameTestHelper h, Caster p) {
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "No fee for rejection or cancellation");
        h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(), 0, "No cooldown for rejection");
        h.assertValueEqual(FireMagic.practiceCount(p), 0, "No practice for rejection");
    }
    private static void hit(GameTestHelper h, Caster p, Pig pig) {
        FireBallProjectile b = ball(h, p, new Vec3(5.5, 2.7, 3.1), new Vec3(0, 0, 3));
        b.tick(); h.assertTrue(b.isRemoved(), "Impact consumes projectile");
    }
    @GameTest(template="empty")
    public static void fixedReleasePaysOnceAndSharesCooldownWithOtherSpells(GameTestHelper h) {
        Caster p=caster(h); for(var s:SpellCatalog.values())p.getData(ModAttachments.KNOWLEDGE).learn(s.id());
        hold(p,FireMagic.chantTicks(p)+100); free(h,p); p.releaseUsingItem();
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),84,"Long hold costs fixed16");
        h.assertValueEqual(FireMagic.practiceCount(p),1,"One release practice");
        h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(),30,"Shared fire cooldown");
        h.assertTrue(p.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()),"Item cooldown applied");
        h.assertFalse(FireMagic.cast(p),"Cannot duplicate release");
        h.assertFalse(WaterMagic.cast(p),"Water shares cooldown");
        h.assertValueEqual(WaterMagic.practiceCount(p),0,"No water practice");
        h.assertValueEqual(p.getData(ModAttachments.TRAINING).targetHits(),0,"No water board credit");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void earlySneakSlotAndJournalCancelDoNotRelease(GameTestHelper h) {
        Caster p=caster(h);hold(p,FireMagic.chantTicks(p)-1);p.releaseUsingItem();free(h,p);
        hold(p,FireMagic.chantTicks(p));p.setShiftKeyDown(true);p.releaseUsingItem();p.setShiftKeyDown(false);free(h,p);
        hold(p,FireMagic.chantTicks(p));p.getInventory().selected=1;p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.releaseUsingItem();free(h,p);
        hold(p,FireMagic.chantTicks(p));SpellCasting.openJournal(p);p.releaseUsingItem();free(h,p);
        h.assertEntityNotPresent(ModContent.FIRE_BALL.get());h.succeed();
    }
    @GameTest(template="empty")
    public static void unknownResourcesCreativeAndSpawnCancellationAreGuarded(GameTestHelper h) {
        Caster p=caster(h);p.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());h.assertFalse(FireMagic.cast(p),"Unknown rejected");
        p.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);p.setItemInHand(InteractionHand.MAIN_HAND,Items.STICK.getDefaultInstance());h.assertFalse(FireMagic.cast(p),"Ordinary stick rejected");
        p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
        Consumer<EntityJoinLevelEvent> cancel=e->{if(e.getEntity() instanceof FireBallProjectile b && b.getOwner()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,cancel);try{h.assertFalse(FireMagic.cast(p),"Spawn veto free");}finally{NeoForge.EVENT_BUS.unregister(cancel);}free(h,p);
        p.setData(ModAttachments.MANA,new ManaData(15,100,0,0));h.assertFalse(FireMagic.cast(p),"Insufficient mana rejected");
        p.setData(ModAttachments.MANA,ManaData.full(100));p.getAbilities().instabuild=true;h.assertTrue(FireMagic.cast(p),"Creative allowed");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),100,"Creative free");h.assertValueEqual(FireMagic.practiceCount(p),0,"Creative no practice");h.succeed();
    }
    @GameTest(template="empty")
    public static void spawnHookCannotReenterAndRemovedSpawnDoesNotCharge(GameTestHelper h) {
        Caster p=caster(h);AtomicInteger seen=new AtomicInteger();
        Consumer<EntityJoinLevelEvent> callback=e->{if(e.getEntity() instanceof FireBallProjectile b&&b.getOwner()==p){seen.incrementAndGet();h.assertFalse(FireMagic.cast(p),"Reentrant release blocked");}};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,callback);try{h.assertTrue(FireMagic.cast(p),"Outer release succeeds");}finally{NeoForge.EVENT_BUS.unregister(callback);}
        h.assertValueEqual(seen.get(),1,"One entity admission");h.assertValueEqual(FireMagic.practiceCount(p),1,"One settlement");
        Caster rejected=caster(h);
        Consumer<EntityJoinLevelEvent> removed=e->{if(e.getEntity() instanceof FireBallProjectile b&&b.getOwner()==rejected)b.discard();};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,removed);
        try{h.assertFalse(FireMagic.cast(rejected),"Removed admission does not settle");}finally{NeoForge.EVENT_BUS.unregister(removed);}
        free(h,rejected);h.succeed();
    }
    @GameTest(template="empty")
    public static void entityBeforeWaterUsesTrueIntersectionAndCorrectAttribution(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);h.setBlock(new BlockPos(5,2,5),Blocks.WATER);
        AtomicInteger source=new AtomicInteger();Consumer<LivingIncomingDamageEvent> hook=e->{if(e.getEntity()==victim){h.assertTrue(e.getSource().is(DamageTypes.FIREBALL),"Fire damage type");h.assertTrue(e.getSource().getEntity()==p&&e.getSource().getDirectEntity() instanceof FireBallProjectile,"Caster and direct projectile attribution");source.incrementAndGet();}};
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,hook);try{hit(h,p,victim);}finally{NeoForge.EVENT_BUS.unregister(hook);}
        h.assertValueEqual(victim.getHealth(),5F,"Entity before water takes direct5");h.assertTrue(victim.getRemainingFireTicks()>0,"Accepted dry hit burns");h.assertValueEqual(source.get(),1,"Single damage event");h.succeed();
    }
    @GameTest(template="empty")
    public static void waterBeforeEntityQuenchesWithoutDamage(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,5.7);h.setBlock(new BlockPos(5,2,4),Blocks.WATER);hit(h,p,victim);
        h.assertValueEqual(victim.getHealth(),10F,"Water shields target behind it");h.assertFalse(victim.isOnFire(),"No burn through water");h.succeed();
    }
    @GameTest(template="empty")
    public static void shallowFlowingWaterRespectsItsActualHeightAndOrigin(GameTestHelper h) {
        Caster p=caster(h);h.setBlock(new BlockPos(5,2,4),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,7));
        FireBallProjectile high=ball(h,p,new Vec3(5.5,2.8,3.1),new Vec3(0,0,2));high.tick();h.assertFalse(high.isRemoved(),"Ray above shallow flow survives");high.discard();
        FireBallProjectile low=ball(h,p,new Vec3(5.5,2.02,3.1),new Vec3(0,0,2));low.tick();h.assertTrue(low.isRemoved(),"Actual shallow water crossing quenches");
        FireBallProjectile inside=ball(h,p,new Vec3(5.5,2.02,4.5),new Vec3(0,0,2));inside.tick();h.assertTrue(inside.isRemoved(),"Starting in water quenches even when ending outside");h.succeed();
    }
    @GameTest(template="empty")
    public static void solidBlockBeforeWaterStopsProjectileWithoutCallbacks(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,6.7);h.setBlock(new BlockPos(5,2,5),Blocks.WATER);
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.TARGET,Blocks.TNT,Blocks.OAK_PLANKS,Blocks.CANDLE}){
            h.setBlock(new BlockPos(5,2,4),block);
            FireBallProjectile b=ball(h,p,new Vec3(5.5,2.3,3.1),new Vec3(0,0,4));b.tick();h.assertTrue(b.isRemoved(),"Solid collision consumed fire");
            h.assertTrue(h.getBlockState(new BlockPos(5,2,4)).is(block),"Block retained");
            if(block==Blocks.TARGET)h.assertValueEqual(h.getBlockState(new BlockPos(5,2,4)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER),0,"No target callback");
            if(block==Blocks.CANDLE)h.assertFalse(h.getBlockState(new BlockPos(5,2,4)).getValue(CandleBlock.LIT),"No candle ignition");
        }
        h.assertValueEqual(victim.getHealth(),10F,"No hit past obstacle");h.assertEntityNotPresent(EntityType.TNT);h.succeed();
    }
    @GameTest(template="empty")
    public static void fireResistanceAndFireImmuneMobPreventDamageAndIgnition(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,200));hit(h,p,victim);
        h.assertValueEqual(victim.getHealth(),10F,"Potion prevents fire damage");h.assertFalse(victim.isOnFire(),"No auxiliary ignition");victim.discard();
        var blaze=EntityType.BLAZE.create(h.getLevel());blaze.setNoAi(true);blaze.setNoGravity(true);blaze.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));h.getLevel().addFreshEntity(blaze);
        float health=blaze.getHealth();FireBallProjectile b=ball(h,p,new Vec3(5.5,2.7,3.1),new Vec3(0,0,3));b.tick();
        h.assertValueEqual(blaze.getHealth(),health,"Innate fire immunity retained");h.assertFalse(blaze.isOnFire(),"Immune mob not ignited");h.succeed();
    }
    @GameTest(template="empty")
    public static void canceledOrZeroDamageNeverAddsFire(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);
        Consumer<LivingIncomingDamageEvent> cancel=e->{if(e.getEntity()==victim)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,cancel);try{hit(h,p,victim);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertValueEqual(victim.getHealth(),10F,"Canceled hurt retained health");h.assertFalse(victim.isOnFire(),"Canceled hurt did not add fire");
        Consumer<LivingDamageEvent.Pre> zero=e->{if(e.getEntity()==victim)e.setNewDamage(0);};
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Pre.class,zero);try{hit(h,p,victim);}finally{NeoForge.EVENT_BUS.unregister(zero);}
        h.assertValueEqual(victim.getHealth(),10F,"Zero damage retained health");h.assertFalse(victim.isOnFire(),"Zero damage did not add fire");h.succeed();
    }
    @GameTest(template="empty")
    public static void canceledImpactAndOwnerDeathStopEffects(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);
        Consumer<ProjectileImpactEvent> cancel=e->{if(e.getProjectile() instanceof FireBallProjectile b&&b.getOwner()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class,cancel);try{hit(h,p,victim);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertValueEqual(victim.getHealth(),10F,"Impact cancellation stops damage");h.assertFalse(victim.isOnFire(),"No ignition");
        FireBallProjectile b=ball(h,p,new Vec3(5.5,2.7,3.1),new Vec3(0,0,3));p.setHealth(0);b.tick();
        h.assertValueEqual(victim.getHealth(),10F,"Dead owner shot no longer damages");h.succeed();
    }
    @GameTest(template="empty")
    public static void absorbedDamageIgnitesButNeverShortensExistingBurn(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);victim.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(10);victim.setAbsorptionAmount(8);victim.igniteForSeconds(10);hit(h,p,victim);
        h.assertValueEqual(victim.getHealth(),10F,"Absorption receives damage first");h.assertValueEqual(victim.getAbsorptionAmount(),3F,"Actual absorption loss is accepted damage");
        h.assertTrue(victim.getRemainingFireTicks()>=199,"Existing longer burn retained");h.succeed();
    }
    @GameTest(template="empty")
    public static void droppedItemsAndArmorStandAreNotDamaged(GameTestHelper h) {
        Caster p=caster(h);var stand=EntityType.ARMOR_STAND.create(h.getLevel());stand.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));h.getLevel().addFreshEntity(stand);
        ItemEntity item=new ItemEntity(h.getLevel(),stand.getX(),stand.getY(),stand.getZ(),new ItemStack(Items.PAPER));item.setNoGravity(true);h.getLevel().addFreshEntity(item);
        float health=stand.getHealth();FireBallProjectile b=ball(h,p,new Vec3(5.5,2.7,3.1),new Vec3(0,0,3));b.tick();
        h.assertValueEqual(stand.getHealth(),health,"Decorative stand unchanged");h.assertFalse(stand.isOnFire(),"No stand burn");h.assertFalse(item.isRemoved(),"Dropped paper retained");h.assertFalse(item.isOnFire(),"No item fire");h.succeed();
    }
    @GameTest(template="empty")
    public static void lifetimeMalformedNbtAndUnloadedFlightAreBounded(GameTestHelper h) {
        Caster p=caster(h);FireBallProjectile b=ball(h,p,new Vec3(5.5,3,3),Vec3.ZERO);b.tickCount=FireBallProjectile.LIFETIME_TICKS;b.tick();h.assertTrue(b.isRemoved(),"Lifetime expires");
        FireBallProjectile raw=new FireBallProjectile(ModContent.FIRE_BALL.get(),h.getLevel());CompoundTag nbt=new CompoundTag();nbt.putFloat("Damage",Float.NaN);nbt.putInt("BurnSeconds",999);nbt.putInt("Age",-4);raw.readAdditionalSaveData(nbt);
        h.assertValueEqual(raw.damage(),0F,"NaN damage neutralized");h.assertValueEqual(raw.burnSeconds(),30,"Burn bounded");h.assertValueEqual(raw.tickCount,0,"Age bounded");
        BlockPos remote=new BlockPos(10000000,80,10000000);h.assertFalse(h.getLevel().hasChunkAt(remote),"Remote chunk initially absent");raw.setPos(Vec3.atCenterOf(remote));raw.setDeltaMovement(0,0,1);raw.tick();
        h.assertTrue(raw.isRemoved(),"Unloaded flight rejected");h.assertFalse(h.getLevel().hasChunkAt(remote),"No forced load");
        FireBallProjectile fast=new FireBallProjectile(ModContent.FIRE_BALL.get(),h.getLevel());fast.setPos(p.position());fast.setDeltaMovement(0,0,100000);fast.tick();h.assertTrue(fast.isRemoved(),"Pathological step rejected");h.succeed();
    }

    @GameTest(template="empty")
    public static void newlyGrantedFireResistanceAndTotemNeverReignite(GameTestHelper h) {
        Caster p=caster(h);Pig victim=pig(h,4.4);
        Consumer<LivingDamageEvent.Post> protect=e->{if(e.getEntity()==victim)victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,100));};
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class,protect);
        try{hit(h,p,victim);}finally{NeoForge.EVENT_BUS.unregister(protect);}
        h.assertValueEqual(victim.getHealth(),5F,"Direct damage occurred before new resistance");
        h.assertFalse(victim.isOnFire(),"Post-hurt resistance prevents additional ignition");
        victim.discard();Pig totem=pig(h,4.4);totem.setHealth(4);totem.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));hit(h,p,totem);
        h.assertTrue(totem.isAlive()&&totem.getHealth()==1,"Totem saves lethal direct impact");
        h.assertTrue(totem.hasEffect(MobEffects.FIRE_RESISTANCE),"Vanilla totem resistance retained");
        h.assertFalse(totem.isOnFire(),"Totem cannot be immediately reignited");h.succeed();
    }
    @GameTest(template="empty")
    public static void shieldAndBurningTimeAttributeKeepVanillaSemantics(GameTestHelper h) {
        class ShieldPig extends Pig {
            ShieldPig(){super(EntityType.PIG,h.getLevel());}
            void readyShield(){setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SHIELD));startUsingItem(InteractionHand.MAIN_HAND);useItemRemaining=getUseItem().getUseDuration(this)-6;}
        }
        Caster p=caster(h);ShieldPig shield=new ShieldPig();shield.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));shield.setYRot(180);shield.setYHeadRot(180);shield.setNoAi(true);shield.setNoGravity(true);h.getLevel().addFreshEntity(shield);shield.readyShield();
        h.assertTrue(shield.isBlocking(),"Vanilla shield is raised");hit(h,p,shield);
        h.assertValueEqual(shield.getHealth(),10F,"Front-facing shield blocks direct damage");h.assertFalse(shield.isOnFire(),"Blocked hit adds no fire");shield.discard();
        Pig shortened=pig(h,4.4);shortened.getAttribute(Attributes.BURNING_TIME).setBaseValue(0.5);hit(h,p,shortened);
        h.assertValueEqual(shortened.getRemainingFireTicks(),30,"Vanilla burning-time multiplier applies to 60-tick default");h.succeed();
    }
}
