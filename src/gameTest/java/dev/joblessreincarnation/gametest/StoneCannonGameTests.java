package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoneCannonGameTests {
    private static class Caster extends FakePlayer {
        Caster(ServerLevel l){super(l,new GameProfile(UUID.randomUUID(),"ST"+UUID.randomUUID().toString().substring(0,8)));}
        void tickUse(){if(isUsingItem())updateUsingItem(getUseItem());}
    }
    private static Caster caster(GameTestHelper h){
        var p=new Caster(h.getLevel());p.setPos(h.absoluteVec(new Vec3(5.5,2,1)));p.setYRot(0);p.setXRot(0);
        p.getAbilities().instabuild=false;p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.STONE_CANNON.id());p.getData(ModAttachments.CASTING).select(SpellCatalog.STONE_CANNON.id());
        p.setData(ModAttachments.MANA,ManaData.full(100));return p;
    }
    private static Pig pig(GameTestHelper h,double z){var x=EntityType.PIG.create(h.getLevel());x.setPos(h.absoluteVec(new Vec3(5.5,2,z)));x.setNoAi(true);x.setNoGravity(true);h.getLevel().addFreshEntity(x);return x;}
    private static StoneCannonProjectile ball(GameTestHelper h,Caster p,Vec3 from,Vec3 motion){var b=new StoneCannonProjectile(h.getLevel(),p);b.setPos(h.absoluteVec(from));b.setDeltaMovement(motion);h.getLevel().addFreshEntity(b);return b;}
    private static StoneCannonProjectile hit(GameTestHelper h,Caster p){var b=ball(h,p,new Vec3(5.5,2.7,3.1),new Vec3(0,0,3));b.tick();h.assertTrue(b.isRemoved(),"First collision consumes the stone");return b;}
    private static void hold(Caster p,int n){ModContent.TRAINING_STAFF.get().use(p.level(),p,InteractionHand.MAIN_HAND);for(int i=0;i<n;i++)p.tickUse();}
    private static void free(GameTestHelper h,Caster p){h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),100,"No payment");h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(),0,"No cooldown");h.assertValueEqual(SpellCatalog.STONE_CANNON.practiceCount(p),0,"No practice");}
    @GameTest(template="empty") public static void fixedSingleReleaseHasOneReceiptAndSharedCooldown(GameTestHelper h){
        var p=caster(h);p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());hold(p,100);free(h,p);p.releaseUsingItem();
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),80,"Long holding still costs20");h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(),30,"Shared release cooldown30");
        h.assertValueEqual(SpellCatalog.STONE_CANNON.practiceCount(p),1,"One actual successful release");h.assertFalse(StoneCannonSpell.cast(p),"Duplicate refused");h.assertFalse(WaterMagic.cast(p),"Water shares the cooldown");
        h.assertValueEqual(h.getLevel().getEntitiesOfClass(StoneCannonProjectile.class,h.getBounds().inflate(2)).size(),1,"Only one projectile");h.succeed();
    }
    @GameTest(template="empty") public static void earlySneakSlotAndJournalCancellationStayFree(GameTestHelper h){
        var p=caster(h);hold(p,19);p.releaseUsingItem();free(h,p);hold(p,20);p.setShiftKeyDown(true);p.releaseUsingItem();p.setShiftKeyDown(false);free(h,p);
        hold(p,20);p.getInventory().selected=1;p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.releaseUsingItem();free(h,p);
        hold(p,20);SpellCasting.openJournal(p);p.releaseUsingItem();free(h,p);h.assertEntityNotPresent(ModContent.STONE_CANNON.get());h.succeed();
    }
    @GameTest(template="empty") public static void admissionVetoRemovalAndReentryCannotChargeTwice(GameTestHelper h){
        var p=caster(h);Consumer<EntityJoinLevelEvent> veto=e->{if(e.getEntity() instanceof StoneCannonProjectile b&&b.getOwner()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,veto);try{h.assertFalse(StoneCannonSpell.cast(p),"Veto rejected");}finally{NeoForge.EVENT_BUS.unregister(veto);}free(h,p);
        Consumer<EntityJoinLevelEvent> remove=e->{if(e.getEntity() instanceof StoneCannonProjectile b&&b.getOwner()==p)b.discard();};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,remove);try{h.assertFalse(StoneCannonSpell.cast(p),"Removed admission rejected");}finally{NeoForge.EVENT_BUS.unregister(remove);}free(h,p);
        AtomicInteger seen=new AtomicInteger();Consumer<EntityJoinLevelEvent> reentry=e->{if(e.getEntity() instanceof StoneCannonProjectile b&&b.getOwner()==p){seen.incrementAndGet();h.assertFalse(StoneCannonSpell.cast(p),"Same-caster recursive release refused");}};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,reentry);try{h.assertTrue(StoneCannonSpell.cast(p),"Outer release succeeds");}finally{NeoForge.EVENT_BUS.unregister(reentry);}
        h.assertValueEqual(seen.get(),1,"One admission");h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),80,"One fee");h.succeed();
    }
    @GameTest(template="empty") public static void changedOwnerDuringAdmissionLeavesNoPaidStone(GameTestHelper h){
        var p=caster(h);var peer=caster(h);Consumer<EntityJoinLevelEvent> change=e->{if(e.getEntity() instanceof StoneCannonProjectile b&&b.getOwner()==p)b.setOwner(peer);};
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class,change);try{h.assertFalse(StoneCannonSpell.cast(p),"Changed ownership rejected before settlement");}finally{NeoForge.EVENT_BUS.unregister(change);}
        free(h,p);free(h,peer);h.assertEntityNotPresent(ModContent.STONE_CANNON.get());h.succeed();
    }
    @GameTest(template="empty") public static void unlearnedInsufficientAndCreativeRemainDistinct(GameTestHelper h){
        var p=caster(h);p.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());h.assertFalse(StoneCannonSpell.cast(p),"Unknown spell refused");p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.STONE_CANNON.id());
        p.setData(ModAttachments.MANA,new ManaData(19,100,0,0));h.assertFalse(StoneCannonSpell.cast(p),"Insufficient mana refused");p.setData(ModAttachments.MANA,ManaData.full(100));p.getAbilities().instabuild=true;
        h.assertTrue(StoneCannonSpell.cast(p),"Creative release works");h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),100,"Creative free");h.assertValueEqual(SpellCatalog.STONE_CANNON.practiceCount(p),0,"Creative no practice");h.succeed();
    }
    @GameTest(template="empty") public static void sweepHitsOnlyTheFirstLivingTargetWithProjectileAttribution(GameTestHelper h){
        var p=caster(h);var first=pig(h,4.4);var second=pig(h,5.9);AtomicInteger hits=new AtomicInteger();
        Consumer<LivingIncomingDamageEvent> listener=e->{if(e.getEntity()==first){h.assertTrue(e.getSource().is(DamageTypes.THROWN),"Physical projectile source");h.assertTrue(e.getSource().getEntity()==p&&e.getSource().getDirectEntity() instanceof StoneCannonProjectile,"Caster attribution");hits.incrementAndGet();}};
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,listener);StoneCannonProjectile b;try{b=hit(h,p);}finally{NeoForge.EVENT_BUS.unregister(listener);}
        b.tick();h.assertValueEqual(first.getHealth(),2F,"Base eight damage");h.assertValueEqual(second.getHealth(),10F,"No piercing");h.assertValueEqual(hits.get(),1,"No second event");
        h.assertTrue(first.getDeltaMovement().z>.54&&first.getDeltaMovement().z<.56,"Vanilla0.4 then extra0.35 gives about0.55 at zero initial speed/resistance");h.succeed();
    }
    @GameTest(template="empty") public static void solidsAndOwnWallStopWithoutBlockCallbacks(GameTestHelper h){
        var p=caster(h);var target=pig(h,6.7);for(Block block:List.of(Blocks.STONE,Blocks.TARGET,Blocks.TNT,ModContent.EARTH_WALL.get())){
            h.setBlock(new BlockPos(5,2,4),block);var b=ball(h,p,new Vec3(5.5,2.4,3.1),new Vec3(0,0,4));b.tick();
            h.assertTrue(b.isRemoved(),"Solid sweep stops");h.assertTrue(h.getBlockState(new BlockPos(5,2,4)).is(block),"Block unchanged");
            if(block==Blocks.TARGET)h.assertValueEqual(h.getBlockState(new BlockPos(5,2,4)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER),0,"No target callback");
        }h.assertValueEqual(target.getHealth(),10F,"No target damage behind wall");h.assertEntityNotPresent(EntityType.TNT);h.succeed();
    }
    @GameTest(template="empty") public static void waterAndLavaConsumeBeforeTargetWithoutTerrainChanges(GameTestHelper h){
        var p=caster(h);var target=pig(h,5.7);for(Block fluid:List.of(Blocks.WATER,Blocks.LAVA)){h.setBlock(new BlockPos(5,2,4),fluid);hit(h,p);h.assertValueEqual(target.getHealth(),10F,"Fluid before target consumes");h.assertTrue(h.getBlockState(new BlockPos(5,2,4)).is(fluid),"Fluid unchanged");}h.succeed();
    }
    @GameTest(template="empty") public static void shallowFluidUsesItsActualSurface(GameTestHelper h){
        var p=caster(h);h.setBlock(new BlockPos(5,2,4),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,7));
        var high=ball(h,p,new Vec3(5.5,2.8,3.1),new Vec3(0,0,2));high.tick();h.assertFalse(high.isRemoved(),"Ray above low water survives");high.discard();
        var low=ball(h,p,new Vec3(5.5,2.02,3.1),new Vec3(0,0,2));low.tick();h.assertTrue(low.isRemoved(),"Low ray enters water");h.succeed();
    }
    @GameTest(template="empty") public static void vetoedDamageOrImpactNeverAddsExtraImpulse(GameTestHelper h){
        var p=caster(h);var target=pig(h,4.4);Consumer<LivingIncomingDamageEvent> cancel=e->{if(e.getEntity()==target)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class,cancel);try{hit(h,p);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertValueEqual(target.getHealth(),10F,"Canceled damage");h.assertTrue(target.getDeltaMovement().lengthSqr()<1e-10,"No canceled-hit impulse");
        Consumer<ProjectileImpactEvent> veto=e->{if(e.getProjectile() instanceof StoneCannonProjectile b&&b.getOwner()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class,veto);try{hit(h,p);}finally{NeoForge.EVENT_BUS.unregister(veto);}h.assertValueEqual(target.getHealth(),10F,"Canceled impact");h.succeed();
    }
    @GameTest(template="empty") public static void absorptionLossQualifiesButKnockbackResistanceAndEventsStillWin(GameTestHelper h){
        var p=caster(h);var target=pig(h,4.4);target.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(12);target.setAbsorptionAmount(12);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);hit(h,p);
        h.assertValueEqual(target.getHealth(),10F,"Absorption takes damage");h.assertValueEqual(target.getAbsorptionAmount(),4F,"Actual eight absorbed");h.assertTrue(target.getDeltaMovement().lengthSqr()<1e-10,"Full resistance honored");target.discard();
        var second=pig(h,4.4);Consumer<LivingKnockBackEvent> cancel=e->{if(e.getEntity()==second)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(LivingKnockBackEvent.class,cancel);try{hit(h,p);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertValueEqual(second.getHealth(),2F,"Damage independent of impulse event");h.assertTrue(second.getDeltaMovement().lengthSqr()<1e-10,"Canceled knockback honored");h.succeed();
    }
    @GameTest(template="empty") public static void frontShieldBlocksAndStoneStillDisperses(GameTestHelper h){
        class ShieldPig extends Pig {ShieldPig(){super(EntityType.PIG,h.getLevel());}void shield(){setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SHIELD));startUsingItem(InteractionHand.MAIN_HAND);useItemRemaining=getUseItem().getUseDuration(this)-6;}}
        var p=caster(h);var target=new ShieldPig();target.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));target.setYRot(180);target.setYHeadRot(180);target.setNoAi(true);target.setNoGravity(true);h.getLevel().addFreshEntity(target);target.shield();
        h.assertTrue(target.isBlocking(),"Actual vanilla shield active");hit(h,p);h.assertValueEqual(target.getHealth(),10F,"Shield prevents health loss");h.assertTrue(target.getDeltaMovement().horizontalDistance()<=.401,"No extra impulse on blocked hit");h.succeed();
    }
    @GameTest(template="empty") public static void npcOwnerPetAndAlliedTargetsAreProtected(GameTestHelper h){
        var p=caster(h);var npc=ModContent.PAUL_GREYRAT.get().create(h.getLevel());npc.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));npc.setNoAi(true);h.getLevel().addFreshEntity(npc);float health=npc.getHealth();hit(h,p);h.assertValueEqual(npc.getHealth(),health,"NPC protected");npc.discard();
        Wolf wolf=EntityType.WOLF.create(h.getLevel());wolf.setOwnerUUID(p.getUUID());wolf.setTame(true,true);wolf.setNoAi(true);wolf.setNoGravity(true);wolf.setPos(h.absoluteVec(new Vec3(5.5,2,4.4)));h.getLevel().addFreshEntity(wolf);health=wolf.getHealth();hit(h,p);h.assertValueEqual(wolf.getHealth(),health,"Owner pet protected");wolf.discard();
        var ally=pig(h,4.4);var board=h.getLevel().getScoreboard();var team=board.addPlayerTeam("s"+UUID.randomUUID().toString().substring(0,10));team.setAllowFriendlyFire(false);board.addPlayerToTeam(p.getScoreboardName(),team);board.addPlayerToTeam(ally.getScoreboardName(),team);
        try{hit(h,p);h.assertValueEqual(ally.getHealth(),10F,"Ally health unchanged");h.assertTrue(ally.getDeltaMovement().lengthSqr()<1e-10,"No allied impulse");}finally{board.removePlayerTeam(team);}h.succeed();
    }
    @GameTest(template="empty") public static void waterShieldDoesNotInterceptStone(GameTestHelper h){
        var p=caster(h);p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_SHIELD.id());h.assertTrue(WaterShieldSpell.cast(p),"Real water shield spawned");var target=pig(h,5);
        var stone=ball(h,p,new Vec3(5.5,2.7,1.5),new Vec3(0,0,4));stone.tick();h.assertValueEqual(target.getHealth(),2F,"Stone passed arrow-only shield");
        h.assertTrue(!h.getLevel().getEntitiesOfClass(WaterShieldEntity.class,h.getBounds().inflate(2)).isEmpty(),"Water shield remains");h.succeed();
    }
    @GameTest(template="empty") public static void distanceAndLifetimeAreIndependentHardBounds(GameTestHelper h){
        var p=caster(h);var fast=ball(h,p,new Vec3(5.5,30,5.5),new Vec3(0,1.6,0));for(int i=0;i<30&&!fast.isRemoved();i++)fast.tick();
        h.assertTrue(fast.isRemoved(),"Distance cap removes flight");h.assertTrue(Math.abs(fast.travelled()-36)<1e-7,"Last step truncated at exactly36");
        var slow=ball(h,p,new Vec3(5.5,30,5.5),new Vec3(0,.2,0));
        // Entity age is advanced by ServerLevel, not by calling tick() directly. Use real world ticks for lifetime.
        h.runAfterDelay(22,()->h.assertFalse(slow.isRemoved(),"Slow stone remains before its lifetime"));
        h.runAfterDelay(25,()->{h.assertTrue(slow.isRemoved(),"Real server ticks expire the slow stone");h.assertTrue(slow.tickCount>=24&&slow.tickCount<=25,"Actual world age reached the hard cap");h.assertTrue(slow.travelled()<5,"Low speed never gets extra lifetime");h.succeed();});
    }
    @GameTest(template="empty") public static void reloadMalformedMotionAndUnloadedChunksNeverCreateLiveFlight(GameTestHelper h){
        var p=caster(h);var original=ball(h,p,new Vec3(5.5,10,5.5),new Vec3(0,1,0));CompoundTag nbt=new CompoundTag();original.saveWithoutId(nbt);original.discard();var restored=ModContent.STONE_CANNON.get().create(h.getLevel());restored.load(nbt);restored.tick();h.assertTrue(restored.isRemoved(),"Manual reload is expired; normal entity type is also noSave");
        var invalid=ball(h,p,new Vec3(5.5,10,5.5),new Vec3(Double.NaN,0,1));invalid.tick();h.assertTrue(invalid.isRemoved(),"Nonfinite flight rejected");
        var remote=new BlockPos(10000000,80,10000000);h.assertFalse(h.getLevel().hasChunkAt(remote),"Remote chunk absent");var b=ModContent.STONE_CANNON.get().create(h.getLevel());b.setPos(Vec3.atCenterOf(remote));b.setDeltaMovement(0,0,1);b.tick();h.assertTrue(b.isRemoved(),"Unloaded flight discarded");h.assertFalse(h.getLevel().hasChunkAt(remote),"No chunk forcing");h.succeed();
    }
    @GameTest(template="empty") public static void tomePrerequisiteIsRecheckedAndExistingPracticeSurvives(GameTestHelper h){
        var p=caster(h);p.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.STONE_CANNON_TOME.get().getDefaultInstance());
        var book=ModContent.STONE_CANNON_TOME.get();book.use(p.level(),p,InteractionHand.MAIN_HAND);h.assertFalse(p.isUsingItem(),"Unknown Earth Wall prevents study");
        p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());book.use(p.level(),p,InteractionHand.MAIN_HAND);h.assertTrue(p.isUsingItem(),"Known prerequisite permits study");
        p.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());for(int i=0;i<40;i++)p.tickUse();h.assertFalse(SpellCatalog.STONE_CANNON.knows(p),"Prerequisite removed during reading is respected");
        p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());book.use(p.level(),p,InteractionHand.MAIN_HAND);for(int i=0;i<40;i++)p.tickUse();h.assertTrue(SpellCatalog.STONE_CANNON.knows(p),"Actual reusable tome teaches");
        p.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.STONE_CANNON.id());book.use(p.level(),p,InteractionHand.MAIN_HAND);h.assertValueEqual(SpellCatalog.STONE_CANNON.practiceCount(p),1,"Review never resets practice");h.assertTrue(p.getMainHandItem().is(book),"Book retained");h.succeed();
    }
    @GameTest(template="empty") public static void threeStoneMaterialsCraftOnlyOneTome(GameTestHelper h){
        for(Item stone:List.of(Items.COBBLESTONE,Items.BLACKSTONE,Items.COBBLED_DEEPSLATE)){
            var input=CraftingInput.of(2,2,List.of(new ItemStack(Items.BOOK),new ItemStack(stone),new ItemStack(Items.FLINT),ItemStack.EMPTY));
            var match=h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,h.getLevel()).orElseThrow();
            h.assertTrue(match.id().equals(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"stone_cannon_tome")),"No recipe conflict");
            var result=match.value().assemble(input,h.getLevel().registryAccess());h.assertTrue(result.is(ModContent.STONE_CANNON_TOME.get())&&result.getCount()==1,"One crafted book");
        }h.succeed();
    }
    @GameTest(template="empty") public static void zeroFinalDamageNeverAddsTheStoneImpulse(GameTestHelper h){
        var p=caster(h);var target=pig(h,4.4);Consumer<LivingDamageEvent.Pre> zero=e->{if(e.getEntity()==target)e.setNewDamage(0);};
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Pre.class,zero);try{hit(h,p);}finally{NeoForge.EVENT_BUS.unregister(zero);}
        h.assertValueEqual(target.getHealth(),10F,"Zero final damage preserves health");h.assertTrue(target.getDeltaMovement().horizontalDistance()<=.401,"Only vanilla response, never extra stone knockback");h.succeed();
    }
    private StoneCannonGameTests(){}
}
