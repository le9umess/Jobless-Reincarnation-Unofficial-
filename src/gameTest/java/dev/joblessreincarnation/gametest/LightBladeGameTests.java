package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.LightBladeSkill;
import dev.joblessreincarnation.compat.epic.SchoolAnimations;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightBladeGameTests {
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND;
    private static final Vec3 ORIGIN = new Vec3(5.5, 2, 2.5);
    private static ServerPlayerPatch patch(Actor p) { return EpicFightCapabilities.getServerPlayerPatch(p); }
    private static java.util.List<dev.joblessreincarnation.magic.SpellBurstOptions> cues(Actor p, int kind) {
        return p.spellBursts.stream().filter(cue -> cue.kind() == kind).toList();
    }
    private static SkillContainer slot(Actor p) { return patch(p).getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM); }
    private static CompoundTag ticket(long id) { var n=new CompoundTag(); n.putLong(LightBladeSkill.PRESS_ID,id); return n; }

    private static Actor student(Fixture f, boolean insight, boolean mastered) {
        Actor p = f.actor();
        for (int i=0;i<61;i++) p.serverLevel().tickNonPassenger(p);
        var n = new CompoundTag();
        n.putBoolean("started",true); n.putInt("hits",20); n.putBoolean("recognized",true);
        n.putBoolean("aura_drill",true); n.putBoolean("aura_awakened",true);
        n.putInt("school_practices",insight ? 9 : 1); n.putInt("school_forms",mastered ? 9 : 1);
        n.putBoolean("light_blade_insight",insight);
        p.setData(ModAttachments.SWORD_TRAINING,SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,n).getOrThrow());
        p.setData(ModAttachments.MANA,new ManaData(50,100,1000,0));p.setPos(f.helper.absoluteVec(ORIGIN));
        var patch=patch(p); patch.toEpicFightMode(false);
        patch.getPlayerSkills().addLearnedSkill(SchoolSkills.skill(SchoolForm.ARM_STRIKE));
        if(mastered)patch.getPlayerSkills().addLearnedSkill(SchoolSkills.skill(SchoolForm.LIGHT_BLADE));
        p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));p.recover();face(p);patch.setStamina(patch.getMaxStamina());
        return p;
    }

    private static void face(Actor p) {
        p.setYRot(0);p.yRotO=0;p.setYHeadRot(0);p.yHeadRotO=0;p.setYBodyRot(0);p.yBodyRotO=0;
        p.setXRot(0);p.xRotO=0;p.setOnGround(true);patch(p).setModelYRot(0,true);
    }

    private static Husk enemy(Fixture f,double distance) {
        Husk mob=EntityType.HUSK.create(f.helper.getLevel());mob.setNoAi(true);mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);mob.getAttribute(Attributes.ARMOR).setBaseValue(0);
        mob.setHealth(100);f.admit(mob,ORIGIN.add(0,0,distance));mob.setYRot(180);return mob;
    }

    private static void useNotes(Actor p,SchoolForm form,boolean sneak) {
        var book=new ItemStack(form.note());p.setItemInHand(MAIN,book);p.setShiftKeyDown(sneak);
        book.use(p.level(),p,MAIN);p.setShiftKeyDown(false);
    }

    private static void prepareLight(Actor p,boolean training) {
        useNotes(p,SchoolForm.LIGHT_BLADE,true);
        p.setItemInHand(MAIN,training?ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance():new ItemStack(Items.IRON_SWORD));
        p.recover();face(p);
    }

    @GameTest(template="empty")
    public static void realWindupInterruptGivesInsightWhereBorrowedNotesAndOrdinaryDamageDoNot(GameTestHelper h) {
        try (var f=new Fixture(h)) {
            Actor p=student(f,false,false);
            useNotes(p,SchoolForm.LIGHT_BLADE,false);
            var profile=p.getData(ModAttachments.SWORD_TRAINING);
            h.assertFalse(profile.hasPractice(SchoolForm.LIGHT_BLADE),"A borrowed advanced page cannot authorize the trial");
            useNotes(p,SchoolForm.ARM_STRIKE,true);p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));p.recover();face(p);
            Husk opponent=enemy(f,1.4);
            var enemyPatch=EpicFightCapabilities.getEntityPatch(opponent,LivingEntityPatch.class);
            opponent.hurt(p.damageSources().playerAttack(p),2);opponent.invulnerableTime=0;
            h.assertFalse(profile.hasLightInsight(),"An ordinary melee hit does not count as mastery of the windup");
            h.assertTrue(cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.ARM_BREAK).isEmpty(),"An ordinary hit shows no interruption");
            enemyPatch.getAnimator().playAnimationInstantly(Animations.ZOMBIE_ATTACK1);
            enemyPatch.getAnimator().getPlayerFor(null).setElapsedTime(Animations.ZOMBIE_ATTACK1.get().getPhaseByTime(0).preDelay*.5F);
            enemyPatch.updateEntityState();
            h.assertTrue(slot(p).requestCasting(patch(p),new CompoundTag()),"Native arm strike accepted");
            for(int tick=0;tick<17;tick++)patch(p).getAnimator().tick();
            h.assertTrue(opponent.getHealth()<98 && enemyPatch.isStunned(),"Opponent really lost health and left an attacking windup");
            h.assertTrue(profile.hasLightInsight(),"A confirmed interrupted windup records the advanced insight");
            var breaks=cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.ARM_BREAK);
            h.assertTrue(breaks.size()==1 && breaks.get(0).caster()==p.getId() && breaks.get(0).stage()==0,"The interruption is shown once, named for the striker, as stopping a first strike");
            p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.PAPER));
            useNotes(p,SchoolForm.ARM_STRIKE,false);
            h.assertTrue(p.getOffhandItem().is(ModContent.LIGHT_BLADE_NOTE.get()),"The student's own notes plus paper make a study page");
            useNotes(p,SchoolForm.LIGHT_BLADE,false);
            h.assertTrue(profile.hasPractice(SchoolForm.LIGHT_BLADE) && !profile.mastered(SchoolForm.LIGHT_BLADE),"The page opens the trial but cannot grant the ultimate");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void trialNeedsFullHoldAndRealDummyContactAndCannotDamageAnotherEntity(GameTestHelper h) {
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,false);
            prepareLight(p,true);
            var dummy=f.dummy(p,ORIGIN.add(0,0,1.65));
            var bystander=enemy(f,1.25);
            p.setXRot(17);p.xRotO=17;
            h.assertTrue(LightBladeTraining.aimedDummy(p)==null,"A living body directly between the sword and dummy blocks this trial");
            bystander.setPos(p.position().add(.75,0,1.25));
            h.assertTrue(LightBladeTraining.aimedDummy(p)==dummy,"Dummy is actually in the crosshair");
            h.assertTrue(!patch(p).getPlayerSkills().hasLearned(SchoolSkills.skill(SchoolForm.LIGHT_BLADE)), "Practice does not count as knowing the ultimate");
            h.assertTrue(slot(p).requestHold(patch(p),ticket(1)), "Native held skill request starts the supervised trial");
            h.assertValueEqual(AuraVisual.of(p).phase(),AuraVisual.Phase.GATHER,"The server publishes the real starting tick");
            h.startSequence().thenExecuteAfter(9,()->f.checked(()->{
                ((LightBladeSkill)slot(p).getSkill()).holdTick(slot(p));
                h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()==SchoolAnimations.LIGHT_HOLD,"The gather has transitioned to the held pose");
                slot(p).requestCasting(patch(p),ticket(1));
                h.assertFalse(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.LIGHT_BLADE),"Releasing before full charge cannot pass the trial");
                h.assertFalse(patch(p).isHoldingAny(),"Rejected trial release returns control");
                h.assertTrue(slot(p).requestHold(patch(p),ticket(2)),"A fresh press can retry");
            })).thenExecuteAfter(30,()->f.checked(()->{
                float hp=bystander.getHealth();
                h.assertTrue(slot(p).requestCasting(patch(p),ticket(2)),"Full intended release is accepted");
                h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()==SchoolAnimations.LIGHT_CUT,"Only the release plays the cut");
                h.assertFalse(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.LIGHT_BLADE),"A full hold by itself is not the completed drill");
                for(int frame=0;frame<19;frame++)patch(p).getAnimator().tick();
                h.assertTrue(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.LIGHT_BLADE)
                        && patch(p).getPlayerSkills().hasLearned(SchoolSkills.skill(SchoolForm.LIGHT_BLADE)),"The local sword collider contacted the same supported dummy and taught the actual EF skill");
                h.assertValueEqual(bystander.getHealth(),hp,"The practice cut cannot damage a neighboring living entity");
                h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),50,"Practice does not use spell mana");
                f.close();
            })).thenSucceed();
        });
    }

    private static void tier(GameTestHelper h,int held,int wanted) {
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);Husk mob=enemy(f,1.55);
            h.assertTrue(slot(p).requestHold(patch(p),ticket(8)),"The native hold starts");
            h.startSequence().thenExecuteAfter(held,()->f.checked(()->{
                float stamina=patch(p).getStamina();
                var forged=ticket(8);forged.putInt("chargingTicks",100000);
                h.assertTrue(slot(p).requestCasting(patch(p),forged),"Meaningful release reaches the server");
                h.assertValueEqual(p.getData(ModAttachments.SCHOOL_COMBAT).lightTier(),wanted,"Only time on the actual server selects the tier");
                h.assertTrue(Math.abs(stamina-patch(p).getStamina()-LightBladeTiming.staminaCost(wanted))<.001F,"A successful cut pays the correct stamina once");
                h.assertFalse(patch(p).isHoldingAny(),"Release ends the gathering state");
                float before=mob.getHealth();int hits=0,first=-1;
                for(int tick=0;tick<20;tick++){
                    float last=mob.getHealth();patch(p).getAnimator().tick();if(mob.getHealth()<last){hits++;if(first<0)first=tick;}
                }
                float loss=before-mob.getHealth();
                LogUtils.getLogger().info("[LightBlade] held={} tier={} stamina={} damage={} first={} hits={}",held,wanted,stamina-patch(p).getStamina(),loss,first,hits);
                h.assertTrue(hits==1 && first<=2,"One early collision within the authored direct slash");
                h.assertTrue(Math.abs(loss - 6.0F*LightBladeTiming.damageMultiplier(wanted))<.05F,"A capped multiplier is applied to the real sword damage");
                h.assertValueEqual(p.lightCutCues,1,"One and only one world hit flash follows the real hit");
                var dash=cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH);
                h.assertTrue(dash.size()==1 && dash.get(0).caster()==p.getId() && dash.get(0).stage()==wanted-1,"One dash light for the release, at its tier");
                h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),50,"The magic reserve is not paid as a combat resource");
                f.close();
            })).thenSucceed();
        });
    }

    /** The cut far ahead with the server never moving the player, as online where the client's dash arrives late. */
    private static void dashReach(GameTestHelper h,boolean wall){
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);Husk far=enemy(f,4.6);
            if(wall)for(int x=4;x<=6;x++)for(int y=2;y<=4;y++)f.block(new net.minecraft.core.BlockPos(x,y,4),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            h.assertTrue(slot(p).requestHold(patch(p),ticket(8)),"The hold starts");
            h.startSequence().thenExecuteAfter(10,()->f.checked(()->{
                var start=p.position();
                h.assertTrue(slot(p).requestCasting(patch(p),ticket(8)),"Released");
                int first=-1;
                for(int tick=0;tick<12;tick++){float last=far.getHealth();patch(p).getAnimator().tick();if(first<0&&far.getHealth()<last)first=tick;}
                LogUtils.getLogger().info("[LightBlade] dash wall={} first={} reached={}",wall,first,p.getData(ModAttachments.SCHOOL_COMBAT).lightCutAt(.3F));
                h.assertTrue(p.position().distanceTo(start)<1.0E-6,"The server itself never moved the player");
                if(wall){
                    h.assertTrue(first<0&&far.getHealth()==100,"A wall stops the dash the server judges, and the cut behind it");
                    h.assertValueEqual(p.lightCutCues,0,"No hit flash through a wall");
                }else{
                    h.assertTrue(first>=0&&first<=4,"A target 4.6 blocks ahead is cut where the dash reaches it, on time");
                    h.assertValueEqual(p.lightCutCues,1,"One hit flash");
                }
                f.close();
            })).thenSucceed();
        });
    }
    @GameTest(template="empty",timeoutTicks=80) public static void theDashIsJudgedOnTheServersClock(GameTestHelper h){dashReach(h,false);}
    @GameTest(template="empty",timeoutTicks=80) public static void aWallStopsTheJudgedDash(GameTestHelper h){dashReach(h,true);}
    @GameTest(template="empty",timeoutTicks=80) public static void lightBladeFirstTier(GameTestHelper h){tier(h,10,1);}
    @GameTest(template="empty",timeoutTicks=80) public static void lightBladeSecondTier(GameTestHelper h){tier(h,20,2);}
    @GameTest(template="empty",timeoutTicks=80) public static void lightBladeThirdTierCapped(GameTestHelper h){tier(h,40,3);}
    @GameTest(template="empty",timeoutTicks=80)
    public static void tooTiredForTheFirstTierCannotChargeAndLowStaminaCutsLower(GameTestHelper h){
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);
            patch(p).setStamina(LightBladeTiming.staminaCost(1)-.5F);
            h.assertFalse(slot(p).requestHold(patch(p),ticket(1)),"Below the first tier cost a press starts no charge");
            h.assertFalse(patch(p).isHoldingAny(),"Nothing is held");
            patch(p).setStamina(patch(p).getMaxStamina());
            h.assertTrue(slot(p).requestHold(patch(p),ticket(2)),"With stamina the charge starts");
            h.startSequence().thenExecuteAfter(LightBladeTiming.FULL_TICKS+2,()->f.checked(()->{
                ((LightBladeSkill)slot(p).getSkill()).holdTick(slot(p));
                patch(p).setStamina(LightBladeTiming.staminaCost(2)-.5F);
                h.assertTrue(slot(p).requestCasting(patch(p),ticket(2)),"A full charge is released");
                h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()==SchoolAnimations.LIGHT_CUT,"It still cuts");
                h.assertValueEqual(p.getData(ModAttachments.SCHOOL_COMBAT).lightTier(),1,"At the highest tier the stamina can pay for");
                var dash=cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH);
                h.assertTrue(dash.size()==1 && dash.get(0).stage()==0,"Its light is the tier it actually cut at");
                h.assertTrue(Math.abs(LightBladeTiming.staminaCost(2)-.5F-patch(p).getStamina()-LightBladeTiming.staminaCost(1))<.001F,"And pays only for that tier");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template="empty",timeoutTicks=75)
    public static void shortSpoofedStaleAndSwappedHoldsCannotAttackOrSpend(GameTestHelper h){
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);
            float original=patch(p).getStamina();
            slot(p).requestCasting(patch(p),ticket(91));
            h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()!=SchoolAnimations.LIGHT_CUT,"No made-up cast without a hold");
            h.assertFalse(slot(p).requestHold(patch(p),new CompoundTag()),"A hold needs its fresh press ID");
            h.assertTrue(slot(p).requestHold(patch(p),ticket(1)),"First valid press");
            long start=AuraVisual.of(p).startedAt();
            h.assertFalse(slot(p).requestHold(patch(p),ticket(1)),"A duplicate press cannot restart the timer");
            h.assertValueEqual(AuraVisual.of(p).startedAt(),start,"Duplicate does not move the server start tick");
            h.startSequence().thenExecuteAfter(3,()->f.checked(()->{
                slot(p).requestCasting(patch(p),ticket(1));
                h.assertFalse(patch(p).isHoldingAny(),"Early release cancels instead of waiting for an automatic cut");
                h.assertTrue(slot(p).requestHold(patch(p),ticket(2)),"A second valid press starts");
                slot(p).requestCancel(patch(p),ticket(1));slot(p).requestCasting(patch(p),ticket(1));
                h.assertTrue(patch(p).isHoldingAny() && p.getData(ModAttachments.LIGHT_BLADE_SESSION).matches(2),"Stale cancel and release cannot affect a newer hold");
            })).thenExecuteAfter(12,()->f.checked(()->{
                p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));
                ((LightBladeSkill)slot(p).getSkill()).holdTick(slot(p));
                h.assertFalse(patch(p).isHoldingAny(),"Swapping to a different matching sword cancels its predecessor's charge");
                h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()!=SchoolAnimations.LIGHT_CUT,"Swap cancellation has no cut or hit flash");
                h.assertValueEqual(p.lightCutCues,0,"Cancelled and missed holds never look like landed hits");
                h.assertTrue(cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH).isEmpty(),"Rejected releases show no dash");
                h.assertTrue(patch(p).getStamina()>=original-.001F,"Rejected releases do not spend stamina");
                f.close();
            })).thenSucceed();
        });
    }
    @GameTest(template="empty",timeoutTicks=170)
    public static void cancellationBeforeStartTimeoutAndWhiffKeepTheWindowHonest(GameTestHelper h) {
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);
            slot(p).requestCancel(patch(p),ticket(3));
            h.assertFalse(slot(p).requestHold(patch(p),ticket(3)),"A late start cannot reopen a press already cancelled in transit");
            h.assertTrue(slot(p).requestHold(patch(p),ticket(4)),"The next fresh hold starts");
            h.startSequence().thenExecuteAfter(122,()->f.checked(()->{
                ((LightBladeSkill)slot(p).getSkill()).holdTick(slot(p));
                h.assertFalse(patch(p).isHoldingAny(),"A lost release eventually cancels instead of automatically attacking");
                h.assertValueEqual(p.lightCutCues,0,"The timeout creates no impact");
                h.assertTrue(cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH).isEmpty(),"Nor any dash");
                h.assertTrue(slot(p).requestHold(patch(p),ticket(5)),"Can start again after the timeout");
            })).thenExecuteAfter(10,()->f.checked(()->{
                float stamina=patch(p).getStamina();
                slot(p).requestCasting(patch(p),ticket(5));
                h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation()==SchoolAnimations.LIGHT_CUT,"An intentional whiff still plays the cut");
                for(int tick=0;tick<18;tick++)patch(p).getAnimator().tick();
                h.assertTrue(stamina>patch(p).getStamina(),"Air swings cost stamina");
                h.assertFalse(p.getData(ModAttachments.SCHOOL_COMBAT).recovered(p),"The miss retains actual recovery");
                h.assertFalse(slot(p).requestHold(patch(p),ticket(6)),"No immediate repeat during a whiff recovery");
                h.assertValueEqual(p.lightCutCues,0,"No target means no world hit flash");
                h.assertValueEqual(cues(p,dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH).size(),1,"A whiff still dashes, once");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void realDamageDuringGatherUsesFullHealthAndDepletionCancels(GameTestHelper h) {
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f,true,true);prepareLight(p,false);
            Actor plain=student(f,false,false);plain.setItemInHand(MAIN,new ItemStack(Items.STICK));
            var mob=enemy(f,2);var source=p.damageSources().mobAttack(mob);
            float ordinary=plain.getHealth();plain.invulnerableTime=0;plain.hurt(source,6);ordinary-=plain.getHealth();
            final float reference=ordinary;
            h.assertTrue(slot(p).requestHold(patch(p),ticket(1)),"Charge accepted before incoming physical attack");
            float before=p.getHealth();p.invulnerableTime=0;p.hurt(source,6);
            h.assertTrue(reference>0 && Math.abs((before-p.getHealth())-reference)<.001,"The body takes the unprotected control's actual damage");
            p.addEffect(new MobEffectInstance(ModContent.MANA_DEPLETION,60));
            ((LightBladeSkill)slot(p).getSkill()).holdTick(slot(p));
            h.assertFalse(patch(p).isHoldingAny(),"Depletion ends the real hold");
            h.assertTrue(AuraVisual.of(p).phase()!=AuraVisual.Phase.GATHER,"No stale visible gathering");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),50,"Neither holding nor this physical blow drains spell mana");
            h.assertValueEqual(p.lightCutCues,0,"The interruption produces no slash impact");
            f.close();h.succeed();
        });
    }

    @GameTest(template="empty")
    public static void nativeNullableRequestAndStampedWireRoundTripAreBothSafe(GameTestHelper h) {
        try (var f=new Fixture(h)) {
            Actor p=student(f,true,true);prepareLight(p,false);
            var original = new yesman.epicfight.network.client.CPSkillRequest(SchoolSkills.Slot.JOBLESS_PREPARED_FORM,
                    yesman.epicfight.network.client.CPSkillRequest.WorkType.HOLD_START);
            h.assertTrue(original.arguments() == null, "The pinned native two-argument constructor really has no NBT");
            h.assertFalse(slot(p).requestHold(patch(p), original.arguments()), "Unstamped null native arguments fail closed");
            slot(p).requestCancel(patch(p), null);slot(p).requestCasting(patch(p), null);
            var packet = LightBladeSkill.request(SchoolSkills.Slot.JOBLESS_PREPARED_FORM,
                    yesman.epicfight.network.client.CPSkillRequest.WorkType.HOLD_START, 123);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),p.registryAccess());
            try {
                yesman.epicfight.network.client.CPSkillRequest.STREAM_CODEC.encode(buffer, packet);
                var received = yesman.epicfight.network.client.CPSkillRequest.STREAM_CODEC.decode(buffer);
                h.assertValueEqual(LightBladeSkill.pressId(received.arguments()),123L,"The native wire codec preserves the genuine client press ticket");
                h.assertTrue(slot(p).requestHold(patch(p),received.arguments()),"A decoded genuine hold starts the native server skill");
            } finally { buffer.release(); }
            var cancel = LightBladeSkill.request(SchoolSkills.Slot.JOBLESS_PREPARED_FORM,
                    yesman.epicfight.network.client.CPSkillRequest.WorkType.CANCEL, 123);
            slot(p).requestCancel(patch(p),cancel.arguments());
            h.assertFalse(patch(p).isHoldingAny(),"Cancellation uses a real non-null tag with the matching ticket");
        }
        h.succeed();
    }
    private LightBladeGameTests() {}
}
