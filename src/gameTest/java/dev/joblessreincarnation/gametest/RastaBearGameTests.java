package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.RastaBearCombat;
import dev.joblessreincarnation.compat.epic.SchoolAnimations;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.encounter.RastaBearEntity;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.MireFields;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RastaBearGameTests {
    private static final Vec3 STAND = new Vec3(4.5, 2, 3.5);
    private static final Vec3 BEAR = STAND.add(0, 0, 2);

    private static Actor player(Fixture f, Vec3 relative) {
        Actor actor = f.actor();
        for (int i=0; i<61; i++) actor.serverLevel().tickNonPassenger(actor);
        actor.setPos(f.helper.absoluteVec(relative));
        actor.setOnGround(true);
        actor.setNoGravity(true);
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        actor.setYRot(0); actor.yRotO = 0; actor.setYHeadRot(0); actor.setYBodyRot(0); actor.yBodyRotO = 0;
        var patch = EpicFightCapabilities.getServerPlayerPatch(actor);
        patch.toEpicFightMode(false); patch.setModelYRot(0,true); patch.setStamina(patch.getMaxStamina());
        return actor;
    }

    private static RastaBearEntity bear(Fixture f, Actor target) {
        return bear(f,target,BEAR);
    }

    private static RastaBearEntity bear(Fixture f, Actor target, Vec3 at) {
        var b = Objects.requireNonNull(ModContent.RASTA_BEAR.get().create(f.helper.getLevel()));
        b.setNoAi(true);
        f.admit(b, at);
        b.setTarget(target);
        f.helper.assertTrue(RastaBearCombat.patch(b) != null, "A real bear has its own native Epic Fight patch");
        return b;
    }

    private static void advance(RastaBearEntity bear, int ticks) {
        var patch = RastaBearCombat.patch(bear);
        for (int i=0;i<ticks;i++) {
            patch.getAnimator().tick();
            patch.updateEntityState();
        }
    }

    private static void equip(Actor p, SchoolForm form) {
        var n = new CompoundTag();
        n.putBoolean("started", true); n.putInt("hits",20); n.putBoolean("recognized",true);
        n.putBoolean("aura_drill",true); n.putBoolean("aura_awakened",true);
        n.putInt("school_practices",7); n.putInt("school_forms",7);
        p.setData(ModAttachments.SWORD_TRAINING,SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,n).getOrThrow());
        var patch = EpicFightCapabilities.getServerPlayerPatch(p);
        patch.getPlayerSkills().addLearnedSkill(SchoolSkills.skill(form));
        if (!SchoolSkills.toggleFromNote(p,form)) throw new AssertionError("Failed to prepare "+form);
    }

    @GameTest(template="empty")
    public static void pawHasNativeWindupAndOnePaidHitAgainstOriginalTarget(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor target=player(f,STAND), bystander=player(f,STAND.add(.30,0,.03));
            var b=bear(f,target);float hp=target.getHealth(), other=bystander.getHealth();
            h.assertTrue(b.startPaw(target),"A facing target within reach starts the swipe");
            h.assertFalse(b.startPaw(target),"Same commitment cannot restart during windup");
            advance(b,6);
            var patch=RastaBearCombat.patch(b);
            h.assertTrue(patch.getAnimator().getPlayerFor(null).getRealAnimation()==RastaBearCombat.PAW
                    && patch.getEntityState().getState(EntityState.PHASE_LEVEL)==1,"Actual EF AttackAnimation reports the pre-contact state");
            h.assertValueEqual(target.getHealth(),hp,"The warning itself does no damage");
            advance(b,10);
            float struck=target.getHealth();
            h.assertTrue(struck<hp,"EF front-paw contact genuinely hurt its intended target; health="+struck);
            target.invulnerableTime=0; advance(b,24);
            h.assertValueEqual(target.getHealth(),struck,"Recovery cannot deal a second hit even after immunity is cleared");
            h.assertValueEqual(bystander.getHealth(),other,"The neighboring player never inherited the attack");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void pawWarningIsOneLowHostileSoundEvenWhenTheStartIsRetried(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = player(f, STAND);
            var b = bear(f, p);
            var heard = new ArrayList<PlayLevelSoundEvent.AtPosition>();
            Consumer<PlayLevelSoundEvent.AtPosition> listener = event -> {
                if (event.getLevel() == b.level() && event.getPosition().distanceToSqr(b.position()) < .0001
                        && event.getSound() != null && event.getSound().value() == SoundEvents.POLAR_BEAR_WARNING) {
                    heard.add(event);
                }
            };
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, listener);
            try {
                h.assertTrue(b.startPaw(p), "The real bear commits");
                h.assertFalse(b.startPaw(p), "A repeated input during the same windup is ignored");
                h.assertTrue(heard.size() == 1, "One audible cue per real attack");
                var cue = heard.getFirst();
                h.assertTrue(cue.getSource() == SoundSource.HOSTILE && cue.getNewPitch() == .70F
                        && cue.getNewVolume() == 1.10F, "Clear low warning on the hostile-creatures mixer");
                h.assertValueEqual(p.getHealth(), 20F, "The warning is still the safe reaction window");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void twoShortStepsUseLockedDirectionAndStopBeforePlayersCamera(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            Actor p=player(f,STAND);var b=bear(f,p,STAND.add(0,0,2.89));
            double start=b.getZ();float hp=p.getHealth();
            h.assertTrue(b.startPaw(p),"The bear can commit at 2.89 blocks");
            advance(b,10);
            h.assertTrue(Math.abs(start-b.getZ())<.001,"The bear stays put for the readable warning");
            int moves=0,firstMove=-1,contact=-1;
            for(int tick=10;tick<25;tick++){
                double old=b.getZ();advance(b,1);double step=old-b.getZ();
                if(step>.001){
                    h.assertTrue(step<.302,"Each true movement is at most 0.30 blocks");
                    if(firstMove<0)firstMove=tick;
                    moves++;
                }
                if(contact<0 && p.getHealth()<hp)contact=tick;
            }
            h.assertTrue(moves==2 && contact==firstMove+1,"Two separate movements; contact on the second (moves="+moves+", first="+firstMove+", hit="+contact+")");
            h.assertTrue(Math.abs(b.getZ()-p.getZ()-2.30)<.015,"The lunge ends with space for the victim's first-person camera");
            Actor close=player(f,STAND.add(5,0,0));var closeBear=bear(f,close,STAND.add(5,0,1.8));
            Vec3 initial=closeBear.position();h.assertTrue(closeBear.startPaw(close),"Close presentation control");advance(closeBear,22);
            h.assertTrue(initial.distanceToSqr(closeBear.position())<.0001,"Already close: never shove the bear further into the camera");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void sidestepClearsTheCommittedStrikeLine(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=player(f,STAND);var b=bear(f,p,STAND.add(0,0,2.89));float hp=p.getHealth();
            h.assertTrue(b.startPaw(p),"Paw began");
            advance(b,7);p.setPos(p.position().add(1.35,0,0));advance(b,14);
            p.setPos(h.absoluteVec(STAND));advance(b,20);
            h.assertValueEqual(p.getHealth(),hp,"Stepping sideways avoids impact; re-entering during recovery is safe");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void retreatBeyondTheForepawEscapesTheForwardEdge(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            Actor p=player(f,STAND);var b=bear(f,p,STAND.add(0,0,2.89));
            h.assertTrue(b.startPaw(p),"Bear committed before the player backed up");
            advance(b,7);
            p.setPos(p.position().add(0,0,-.78));
            advance(b,25);
            h.assertValueEqual(p.getHealth(),20F,"Retreat beyond the visible palm must not be hit by excess forward reach");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void rotatedPawKeepsItsReachWithoutHittingBackstepCorners(GameTestHelper h) {
        for(int yaw:new int[]{45,90,135,225}) {
            Vec3 forward=Vec3.directionFromRotation(0,yaw);
            for(boolean retreat:new boolean[]{false,true}){
                try(var f=new Fixture(h)){
                    Actor p=player(f,STAND);var b=bear(f,p,STAND.subtract(forward.scale(2.89)));
                    h.assertTrue(b.startPaw(p),"Bear can face a diagonal target");
                    advance(b,6);
                    if(retreat)p.setPos(p.position().add(forward.scale(.78)));
                    advance(b,22);
                    h.assertTrue((p.getHealth()<20)==!retreat,"Physical right-paw footprint at yaw "+yaw+" retreat="+retreat);
                }
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void wallAddedAndTargetSwappedCannotTransferQueuedDamage(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor a=player(f,STAND),other=player(f,STAND.add(.16,0,0));var b=bear(f,a);
            h.assertTrue(b.startPaw(a),"Real commitment");
            advance(b,6);b.setTarget(other);b.setTarget(a);advance(b,30);
            h.assertValueEqual(a.getHealth(),20F,"Even re-acquiring the original target cannot revive a lost contact");
            h.assertValueEqual(other.getHealth(),20F,"A new target inside the paw does not inherit damage");
            var next=bear(f,a);
            h.assertTrue(next.startPaw(a),"Another real commitment");
            advance(next,6);
            f.block(new BlockPos(4,2,4),Blocks.STONE.defaultBlockState());
            f.block(new BlockPos(4,3,4),Blocks.STONE.defaultBlockState());
            advance(next,34);
            h.assertValueEqual(a.getHealth(),20F,"A solid obstruction placed during the warning stops the paw");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void actualArmStrikeInterruptsBearBeforeContactAndRecordsInsight(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=player(f,STAND);equip(p,SchoolForm.ARM_STRIKE);var b=bear(f,p,STAND.add(0,0,2.89));float hp=p.getHealth(),bhp=b.getHealth();
            h.assertTrue(b.startPaw(p),"Bear committed");advance(b,4);
            var patch=EpicFightCapabilities.getServerPlayerPatch(p);
            h.assertTrue(patch.getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM).requestCasting(patch,new CompoundTag()),"Native arm strike starts");
            // EF player root motion normally arrives from the owning client. Supply the clip's own
            // forward displacement for this headless player, as in the established M3 range regression.
            Vec3 origin=p.position();
            for(int i=0;i<8;i++){
                Vec3 previous=p.position();
                Vec3 step=SchoolAnimations.ARM_STRIKE.get().getExpectedMovement(patch,(i+1)*.05F);
                p.setPos(origin.add(step));
                p.xOld=previous.x; p.yOld=previous.y; p.zOld=previous.z;
                patch.getAnimator().tick();
                advance(b,1);
            }
            h.assertTrue(b.getHealth()<bhp && p.getData(ModAttachments.SWORD_TRAINING).hasLightInsight(),
                    "A real hit met a native bear windup, produced stun and awarded the legitimate insight");
            advance(b,38);
            h.assertValueEqual(p.getHealth(),hp,"Interrupted paw never pays delayed damage");
            h.assertTrue(RastaBearCombat.patch(b).getAnimator().getPlayerFor(null).getRealAnimation()!=RastaBearCombat.PAW,"Interrupted native clip actually ended");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void nativeFrontParryOpensFlowOnlyAgainstAttackingBear(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=player(f,STAND);equip(p,SchoolForm.FLOW_COUNTER);var b=bear(f,p,STAND.add(0,0,2.89));
            var patch=EpicFightCapabilities.getServerPlayerPatch(p);
            var guard=patch.getSkill(SkillSlots.GUARD);guard.setSkill(EpicFightSkills.PARRYING.get());
            h.assertTrue(b.startPaw(p),"Bear committed");advance(b,10);
            h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Native timed front guard is held");
            float hp=p.getHealth(),bhp=b.getHealth();
            advance(b,8);
            h.assertValueEqual(p.getHealth(),hp,"Native parry prevents the health hit");
            h.assertTrue(p.getData(ModAttachments.SCHOOL_COMBAT).counterTarget(p)==b && p.flowOpeningCues==1,
                    "A single genuine parry opens the existing Flow window against this bear");
            h.assertTrue(patch.getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM).requestCasting(patch,new CompoundTag()),"The defender can actively cast the counter");
            for(int i=0;i<18;i++)patch.getAnimator().tick();
            h.assertTrue(b.getHealth()<bhp,"Counter actually intersects and damages the original attacker");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void bodyGuardMitigatesRealPawWithoutSpendingMana(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=player(f,STAND);equip(p,SchoolForm.ARM_STRIKE);
            p.setData(ModAttachments.MANA,new ManaData(47,100,10000,0));
            var b=bear(f,p);float hp=p.getHealth();
            h.assertTrue(b.startPaw(p),"Bear committed to guarded swordsman");advance(b,20);
            h.assertTrue(Math.abs(hp-p.getHealth()-4.0F)<.001F,"Real six-damage paw loses two to active body guard");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),47,"Body guard does not pay spell mana");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void interruptingPawCannotRegainBodyGuardForTheSameDamage(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            Actor p=player(f,STAND);var patch=EpicFightCapabilities.getServerPlayerPatch(p);
            var n=new CompoundTag();n.putBoolean("started",true);n.putInt("hits",20);n.putBoolean("recognized",true);
            n.putBoolean("aura_drill",true);n.putBoolean("aura_awakened",true);n.putBoolean("light_blade_insight",true);
            n.putInt("school_practices",15);n.putInt("school_forms",15);
            p.setData(ModAttachments.SWORD_TRAINING,SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,n).getOrThrow());
            p.setData(ModAttachments.MANA,new ManaData(47,100,10000,0));
            patch.getPlayerSkills().addLearnedSkill(SchoolSkills.skill(SchoolForm.LIGHT_BLADE));
            h.assertTrue(SchoolSkills.toggleFromNote(p,SchoolForm.LIGHT_BLADE),"Native light slot prepared");
            var ticket=new CompoundTag();ticket.putLong(dev.joblessreincarnation.compat.epic.LightBladeSkill.PRESS_ID,1);
            var skill=patch.getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM);
            h.assertTrue(skill.requestHold(patch,ticket) && !dev.joblessreincarnation.training.BattleAura.hasBodyGuard(p),
                    "The current legitimate sustained charge has given up body guard");
            var b=bear(f,p);float hp=p.getHealth();
            h.assertTrue(b.startPaw(p),"Front paw started against the occupied swordsman");
            advance(b,18);
            float harm=hp-p.getHealth();
            h.assertTrue(Math.abs(harm-6F)<.001F,"The interrupting six-damage paw is fully exposed; actual "+harm);
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),47,"No spell mana cost from interrupted charge");
            if(patch.isHoldingSkill(SchoolSkills.skill(SchoolForm.LIGHT_BLADE)))patch.resetHolding();
            p.invulnerableTime=0;
            float protectedHp=p.getHealth();
            p.hurt(yesman.epicfight.world.damagesource.EpicFightDamageSources.mobAttack(b),6);
            h.assertTrue(Math.abs(protectedHp-p.getHealth()-4)<.001,"The next independent physical hit is guarded again");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void realMireSlowsGroundedBearAndReleasesItOutsideFootprint(GameTestHelper h) {
        var f=new Fixture(h);
        f.checked(() -> {
            Actor owner=player(f,STAND);
            var b=bear(f,owner);
            b.setNoGravity(false);
            for(var offset:MireEntity.CELL_OFFSETS)f.block(BlockPos.containing(BEAR).offset(offset).below(),Blocks.STONE.defaultBlockState());
            var field=new MireEntity(h.getLevel(),owner,h.absoluteVec(BEAR));f.admit(field,BEAR);MireFields.activate(field);
            var attr=Objects.requireNonNull(b.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
            double speed=attr.getValue();
            h.assertTrue(dev.joblessreincarnation.magic.MireTargets.eligible(owner,b),"The ordinary registered hostile, grounded bear is on the spell target list");
            h.startSequence().thenExecuteAfter(13,()->f.checked(()->{
                b.setOnGround(true);
                MireFields.onLevelTick(new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(()->true,h.getLevel()));
                h.assertTrue(attr.getModifier(MireFields.SLOW_ID)!=null && Math.abs(attr.getValue()-speed*.4)<.00001,
                        "Active paid-field processing applies the same 60% slow to the grounded bear");
                b.setPos(h.absoluteVec(BEAR.add(5,0,0)));b.setOnGround(true);
                MireFields.onLevelTick(new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(()->true,h.getLevel()));
                h.assertTrue(attr.getModifier(MireFields.SLOW_ID)==null && Math.abs(attr.getValue()-speed)<.00001,"Leaving the patch restores normal movement");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template="empty")
    public static void savedBearNeverResumesUnspentContact(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=player(f,STAND);var b=bear(f,p);
            h.assertTrue(b.startPaw(p),"Bear committed before save");advance(b,5);
            CompoundTag save=b.saveWithoutId(new CompoundTag());b.discard();
            var loaded=Objects.requireNonNull(ModContent.RASTA_BEAR.get().create(h.getLevel()));
            loaded.load(save);f.admit(loaded,BEAR);
            h.assertTrue(loaded.visualPhase()==RastaBearEntity.Motion.IDLE && loaded.takePawContact()==null,"Loaded bear has no stale victim or queued paw");
            advance(loaded,40);h.assertValueEqual(p.getHealth(),20F,"A fresh load cannot replay the previous hit");
        }
        h.succeed();
    }
}
