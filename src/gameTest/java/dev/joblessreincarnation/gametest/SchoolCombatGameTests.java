package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.SchoolAnimations;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.training.TearPouchProjectile;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.registry.entries.EpicFightSkillDataKeys;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSources;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SchoolCombatGameTests {
    private static final Vec3 ORIGIN = new Vec3(5.5, 2, 2.5);
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND;

    private static Actor student(Fixture f) {
        Actor p = f.actor();
        for (int i = 0; i < 61; i++) p.serverLevel().tickNonPassenger(p);
        p.setData(ModAttachments.SWORD_TRAINING, new SwordTrainingProgress(true, 20, true));
        p.setData(ModAttachments.MANA, new ManaData(50, 100, 1000, 0));
        p.setPos(f.helper.absoluteVec(ORIGIN));
        p.setItemInHand(MAIN, new ItemStack(Items.IRON_SWORD));
        var patch = patch(p);
        patch.toEpicFightMode(false);
        face(p, 0);
        patch.setStamina(patch.getMaxStamina());
        return p;
    }

    private static void face(Actor p, float yaw) {
        p.setYRot(yaw); p.yRotO = yaw; p.setYHeadRot(yaw); p.yHeadRotO = yaw; p.setYBodyRot(yaw); p.yBodyRotO = yaw;
        p.setXRot(0); p.xRotO = 0; p.setOnGround(true); patch(p).setModelYRot(yaw, true);
    }

    private static ServerPlayerPatch patch(Actor p) { return EpicFightCapabilities.getServerPlayerPatch(p); }
    private static SkillContainer slot(Actor p) { return patch(p).getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM); }

    private static void note(Actor p, SchoolForm form, boolean crouch) {
        var item = new ItemStack(form.note());
        p.setItemInHand(MAIN, item);
        p.setShiftKeyDown(crouch);
        item.use(p.level(), p, MAIN);
        p.setShiftKeyDown(false);
    }

    private static void prepareKnown(GameTestHelper h, Actor p, SchoolForm form) {
        note(p, form, false);
        SchoolSkills.completeDrill(p, form);
        note(p, form, true);
        p.setItemInHand(MAIN, new ItemStack(Items.IRON_SWORD));
        p.recover();
        face(p, 0);
        h.assertTrue(slot(p).getSkill() == SchoolSkills.skill(form), "Prepared the expected native school slot");
    }

    private static Husk enemy(Fixture f, Vec3 relative, float yaw) {
        Husk mob = EntityType.HUSK.create(f.helper.getLevel());
        mob.setNoAi(true); f.admit(mob, relative); mob.setYRot(yaw); mob.yRotO = yaw; mob.setYHeadRot(yaw); mob.setYBodyRot(yaw);
        return mob;
    }

    private static TearPouchProjectile flying(GameTestHelper h, Actor p) {
        var found = h.getLevel().getEntitiesOfClass(TearPouchProjectile.class, p.getBoundingBox().inflate(12), e -> e.getOwner() == p);
        h.assertValueEqual(found.size(), 1, "Exactly one physical pouch entered the world");
        return found.getFirst();
    }

    private static void finishFlight(GameTestHelper h, TearPouchProjectile pouch) {
        for (int i = 0; i < 40 && !pouch.isRemoved(); i++) h.getLevel().tickNonPassenger(pouch);
        h.assertTrue(pouch.isRemoved(), "Pouch resolves or expires exactly once");
    }

    @GameTest(template = "empty", timeoutTicks = 130)
    public static void paulOffersOneSelectableNotebookThroughNormalInteraction(GameTestHelper h) {
        var f = new Fixture(h);
        f.checked(() -> {
            Actor p = student(f);
            var paul = f.paul(new Vec3(5.5, 2, 4.5));
            p.setItemInHand(MAIN, new ItemStack(Items.PAPER, 3));
            p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(paul, false, MAIN));
            h.startSequence().thenExecuteAfter(21, () -> f.checked(() -> {
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(paul, false, MAIN));
                h.assertValueEqual(p.getMainHandItem().getCount(), 3, "Looking at schools is free");
            })).thenExecuteAfter(21, () -> f.checked(() -> {
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(paul, true, MAIN));
                h.assertTrue(p.getData(ModAttachments.SWORD_TRAINING).firstNoteCopied(), "Paul recorded a completed personal copy");
                h.assertValueEqual(p.getInventory().countItem(SchoolForm.FLOW_COUNTER.note()), 1, "The second offered school was chosen");
                h.assertValueEqual(p.getMainHandItem().getCount(), 2, "Only one sheet was used");
            })).thenExecuteAfter(21, () -> f.checked(() -> {
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(paul, true, MAIN));
                h.assertValueEqual(p.getInventory().countItem(SchoolForm.FLOW_COUNTER.note()), 1, "Repeated confirmation cannot mint more notes");
                h.assertValueEqual(p.getMainHandItem().getCount(), 2, "Duplicate grants cost no paper");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty")
    public static void armDrillAndNativeSlotPersistWithoutOverwritingWeaponInnate(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = student(f);
            var patch = patch(p);
            note(p, SchoolForm.ARM_STRIKE, false);
            h.assertFalse(patch.getPlayerSkills().hasLearned(SchoolSkills.skill(SchoolForm.ARM_STRIKE)), "Reading opens a drill, without granting the move");
            var dummy = f.dummy(p, new Vec3(5.5, 2, 4.5));
            p.sword(); p.recover(); p.attack(dummy);
            h.assertTrue(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.ARM_STRIKE)
                    && patch.getPlayerSkills().hasLearned(SchoolSkills.skill(SchoolForm.ARM_STRIKE)), "Native practice strike learned arm strike");
            var innate = patch.getSkill(SkillSlots.WEAPON_INNATE).getSkill();
            note(p, SchoolForm.ARM_STRIKE, true);
            p.sword();
            h.assertValueEqual(SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE), SchoolSkills.Slot.JOBLESS_PREPARED_FORM, "A prepared sword redirects only the innate key");
            h.assertValueEqual(SchoolSkills.inputSlot(patch, SkillSlots.COMBO_ATTACKS), SkillSlots.COMBO_ATTACKS, "Basic attack keeps its original container");
            h.assertTrue(patch.getSkill(SkillSlots.WEAPON_INNATE).getSkill() == innate, "Preparing a school form never rewrites the original innate skill");
            p.setItemInHand(MAIN, new ItemStack(Items.IRON_AXE));
            h.assertValueEqual(SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE), SkillSlots.WEAPON_INNATE, "Axe immediately uses its own special");
            p.sword(); patch.toVanillaMode(false);
            h.assertValueEqual(SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE), SkillSlots.WEAPON_INNATE, "No input override in mining mode");
            patch.toEpicFightMode(false);
            var profile = p.getData(ModAttachments.SWORD_TRAINING);
            var reloaded = SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE, SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, profile).getOrThrow()).getOrThrow();
            h.assertTrue(reloaded.mastered(SchoolForm.ARM_STRIKE), "Personal completed drill persists");
            var efNbt = patch.getPlayerSkills().write(new CompoundTag());
            Actor other = student(f);
            patch(other).getPlayerSkills().read(efNbt);
            h.assertTrue(patch(other).getPlayerSkills().hasLearned(SchoolSkills.skill(SchoolForm.ARM_STRIKE)) &&
                    slot(other).getSkill() == SchoolSkills.skill(SchoolForm.ARM_STRIKE), "EF native save restores knowledge and the selected school slot");
            h.assertFalse(slot(other).requestCasting(patch(other), new CompoundTag()), "Copying only an equipped EF slot cannot skip the server's mastery check");
            slot(p).setReplaceCooldown(0);
            note(p, SchoolForm.ARM_STRIKE, true); p.sword();
            h.assertValueEqual(SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE), SchoolSkills.Slot.JOBLESS_PREPARED_FORM,
                    "Clearing an EF menu timer does not bypass the notebook's independent settling time");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void armStrikeActuallyHitsOnceAndMissRetainsRecovery(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = student(f); prepareKnown(h,p,SchoolForm.ARM_STRIKE);
            Husk target = enemy(f, new Vec3(5.5, 2, 3.9), 180);
            float before = target.getHealth(), stamina = patch(p).getStamina();
            h.assertTrue(slot(p).requestCasting(patch(p),new CompoundTag()), "Native EF accepted the first sword technique");
            h.assertTrue(patch(p).getStamina() < stamina, "One active technique spends EF stamina");
            float paid = patch(p).getStamina();
            h.assertFalse(slot(p).requestCasting(patch(p),new CompoundTag()), "Cannot repeat the request during recovery");
            h.assertValueEqual(patch(p).getStamina(), paid, "Rejected repeat does not spend additional stamina");
            int impacts = 0;
            StringBuilder frames = new StringBuilder();
            for (int i=0; i<18; i++) {
                float prior = target.getHealth();
                patch(p).getAnimator().tick();
                var clip = patch(p).getAnimator().getPlayerFor(null);
                frames.append(i).append('/').append(clip.getElapsedTime()).append('/').append(target.getHealth()).append(' ');
                if (target.getHealth() < prior) impacts++;
            }
            LogUtils.getLogger().info("[SchoolCombat] arm actual origin={} player={} enemy={} frames={}", h.absolutePos(net.minecraft.core.BlockPos.ZERO), p.position(), target.position(), frames);
            h.assertTrue(target.getHealth() < before && impacts == 1, "M1 sword collider reaches the opponent once: " + frames);
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),50,"A sword technique does not charge magic");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeTimedParryOpensFlowOnlyOnThatOpponent(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = student(f);
            Actor watcher = student(f);
            watcher.setPos(h.absoluteVec(new Vec3(9.0, 2, 2.5)));
            var patch = patch(p);
            var guard = patch.getSkill(SkillSlots.GUARD);
            guard.setSkill(EpicFightSkills.PARRYING.get());
            note(p,SchoolForm.FLOW_COUNTER,false); p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD)); face(p,0);
            Husk attacker = enemy(f,new Vec3(5.5,2,4.0),180);
            Husk bystander = enemy(f,new Vec3(5.5,2,3.7),180);
            h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Native EF guard hold opens");
            h.assertTrue(guard.getDataManager().getDataValue(EpicFightSkillDataKeys.LAST_ACTIVE) > 0,"Timed parry window exists");
            p.hurt(EpicFightDamageSources.mobAttack(attacker),8);
            h.assertTrue(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.FLOW_COUNTER),"A real front melee parry completed the water drill");
            patch.resetHolding(); p.stopUsingItem();
            note(p,SchoolForm.FLOW_COUNTER,true); p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));face(p,0);
            h.assertFalse(slot(p).requestCasting(patch,new CompoundTag()),"Previous parry cannot be banked before equipping");
            h.assertValueEqual(p.flowOpeningCues, 0, "An unprepared parry cannot emit a counter prompt");
            for(int i=0;i<24;i++) {p.serverLevel().tickNonPassenger(p); patch.getAnimator().tick();}
            p.setPos(h.absoluteVec(ORIGIN));p.setDeltaMovement(Vec3.ZERO);face(p,0);
            patch.setStamina(patch.getMaxStamina());
            h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Ready for second real parry");
            p.invulnerableTime=0; p.hurt(EpicFightDamageSources.mobAttack(attacker),8);
            h.assertTrue(p.getData(ModAttachments.SCHOOL_COMBAT).counterTarget(p)==attacker,"Incoming melee attacker is the only authorized target");
            h.assertValueEqual(p.flowOpeningCues, 1, "Exactly one native particle packet announces the defender's opening");
            h.assertValueEqual(watcher.flowOpeningCues, 1, "A nearby observer receives the same single opening");
            h.assertTrue(p.forcedFlowCues == 1 && watcher.forcedFlowCues == 0,
                    "Only the owner must receive its input cue even with minimum particles");
            h.assertTrue(p.lastFlowCaster == p.getId() && watcher.lastFlowCaster == p.getId(),
                    "The cue carries the defender's public entity ID so only their HUD lights up");
            float allyHealth=bystander.getHealth(), enemyHealth=attacker.getHealth();
            h.assertTrue(slot(p).requestCasting(patch,new CompoundTag()),"Pressing native cast after parry starts counter");
            for(int i=0;i<18;i++)patch.getAnimator().tick();
            LogUtils.getLogger().info("[SchoolCombat] flow attacker={}=>{} bystander={}=>{}",enemyHealth,attacker.getHealth(),allyHealth,bystander.getHealth());
            h.assertTrue(attacker.getHealth()<enemyHealth,"Counter landed through the real M1 collider");
            h.assertValueEqual(bystander.getHealth(),allyHealth,"Closer bystander in the same arc is unharmed");
            h.assertTrue(p.getData(ModAttachments.SCHOOL_COMBAT).counterTarget(p)==null,"One opening spends once");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pouchGenuineDummyDrillConsumesOneThenSwordsmanThrowsWithoutDamage(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=student(f);note(p,SchoolForm.TEAR_POUCH,false);
            var dummy=f.dummy(p,new Vec3(5.5,2,5.4));
            var supply=new ItemStack(ModContent.TEAR_POUCH.get(),3);p.setItemInHand(MAIN,supply);face(p,0);
            supply.use(p.level(),p,MAIN);
            h.assertValueEqual(supply.getCount(),2,"One pouch left the hand during practice");
            finishFlight(h,flying(h,p));
            h.assertTrue(p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.TEAR_POUCH),"Physical throw at supported dummy taught the North form");
            dummy.discard();
            p.setItemInHand(InteractionHand.OFF_HAND,supply);
            note(p,SchoolForm.TEAR_POUCH,true);p.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));face(p,0);
            Husk target=enemy(f,new Vec3(5.5,2,5.4),180);
            p.getCooldowns().removeCooldown(ModContent.TEAR_POUCH.get());
            float hp=target.getHealth(),stamina=patch(p).getStamina();
            supply.use(p.level(),p,InteractionHand.OFF_HAND);
            h.assertValueEqual(supply.getCount(),2,"A right-click with a sword cannot bypass the prepared move using the offhand bag");
            h.assertTrue(slot(p).requestCasting(patch(p),new CompoundTag()),"A school throw works through native casting while holding the sword");
            h.assertValueEqual(supply.getCount(),1,"One and only one physical offhand pouch used");
            h.assertTrue(patch(p).getAnimator().getPlayerFor(null).getRealAnimation() == SchoolAnimations.TEAR_THROW,
                    "Only an accepted quick throw starts the original left-hand action");
            h.assertTrue(p.getMainHandItem().is(Items.IRON_SWORD) && patch(p).getStamina()<stamina,"Sword stays in hand; EF accounts for the move");
            h.assertFalse(slot(p).requestCasting(patch(p),new CompoundTag()),"Duplicate cast rejected");
            finishFlight(h,flying(h,p));
            h.assertTrue(target.hasEffect(MobEffects.BLINDNESS),"Facing opponent is briefly obscured");
            h.assertValueEqual(target.getHealth(),hp,"Dust causes no direct hit damage");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void armInterruptsActualWindupButDoesNotInterruptRecovery(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p=student(f); prepareKnown(h,p,SchoolForm.ARM_STRIKE);
            Husk enemy=enemy(f,new Vec3(5.5,2,3.9),180);
            var opponent=EpicFightCapabilities.getEntityPatch(enemy,yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch.class);
            var stock=yesman.epicfight.gameasset.Animations.ZOMBIE_ATTACK1;
            var cut=SchoolAnimations.ARM_STRIKE.get();
            var phase=cut.getPhaseByTime(0);
            opponent.getAnimator().playAnimationInstantly(stock);
            var clock=opponent.getAnimator().getPlayerFor(null);
            var incomingPhase=stock.get().getPhaseByTime(0);
            clock.setElapsedTime(incomingPhase.contact+.015F);
            opponent.updateEntityState();
            h.assertTrue(opponent.getEntityState().getState(EntityState.PHASE_LEVEL)!=1,"Opponent is past the hit window");
            h.assertValueEqual(cut.getEpicFightDamageSource(patch(p),enemy,phase).getStunType(),
                    yesman.epicfight.world.damagesource.StunType.NONE,"Ordinary contact in recovery does not gain interruption");
            clock.setElapsedTime(incomingPhase.preDelay*.5F);
            opponent.updateEntityState();
            h.assertValueEqual(opponent.getEntityState().getState(EntityState.PHASE_LEVEL),1,"A real stock mob attack is in startup");
            h.assertValueEqual(cut.getEpicFightDamageSource(patch(p),enemy,phase).getStunType(),
                    yesman.epicfight.world.damagesource.StunType.SHORT,"Only a real pre-contact windup receives the interruption");
            float hp=enemy.getHealth();
            slot(p).requestCasting(patch(p),new CompoundTag());
            for(int i=0;i<16 && enemy.getHealth()>=hp;i++)patch(p).getAnimator().tick();
            var after=opponent.getAnimator().getPlayerFor(null).getRealAnimation();
            LogUtils.getLogger().info("[SchoolCombat] arm interrupt hp={}=>{} next={} stunned={}",hp,enemy.getHealth(),after,opponent.isStunned());
            h.assertTrue(enemy.getHealth()<hp && (after!=stock || opponent.isStunned()),"The committed hit interrupted the pending attack");
        }
        h.succeed();
    }

    @GameTest(template = "empty",timeoutTicks=75)
    public static void flowRejectsArrowBackAndStaleOrOutOfRangeOpenings(GameTestHelper h) {
        var f=new Fixture(h);
        f.checked(()->{
            Actor p=student(f);prepareKnown(h,p,SchoolForm.FLOW_COUNTER);var patch=patch(p);
            Husk attacker=enemy(f,new Vec3(5.5,2,4.5),180);
            var guard=patch.getSkill(SkillSlots.GUARD);guard.setSkill(EpicFightSkills.PARRYING.get());
            var runtime=p.getData(ModAttachments.SCHOOL_COMBAT);
            h.assertTrue(guard.requestHold(patch,new CompoundTag()),"First guard begins at native input");
            var arrow=new Arrow(EntityType.ARROW,h.getLevel()); arrow.setPos(attacker.position().add(0,1.4,0));
            p.invulnerableTime=0;p.hurt(EpicFightDamageSources.arrow(arrow,attacker),4);
            h.assertTrue(runtime.counterTarget(p)==null,"Even a parried arrow does not create the melee follow-up");
            patch.resetHolding();p.stopUsingItem();
            h.startSequence().thenExecuteAfter(19,()->f.checked(()->{
                p.setPos(h.absoluteVec(ORIGIN));p.setDeltaMovement(Vec3.ZERO);face(p,0);patch.setStamina(patch.getMaxStamina());
                h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Native guard reopened");
                attacker.setPos(p.position().add(0,0,-2));p.invulnerableTime=0;p.hurt(EpicFightDamageSources.mobAttack(attacker),3);
                h.assertTrue(runtime.counterTarget(p)==null,"An attack from behind cannot open flow");
                patch.resetHolding();p.stopUsingItem();
            })).thenExecuteAfter(22,()->f.checked(()->{
                p.setPos(h.absoluteVec(ORIGIN));p.setDeltaMovement(Vec3.ZERO);face(p,0);patch.setStamina(patch.getMaxStamina());
                for(int i=0;i<20;i++)patch.getAnimator().tick();
                h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Actual front guard reopened after recovery");
                attacker.setPos(p.position().add(0,0,2));p.invulnerableTime=0;p.hurt(EpicFightDamageSources.mobAttack(attacker),3);
                h.assertTrue(runtime.counterTarget(p)==attacker,"Front contact authorizes exactly this enemy");
                attacker.setPos(p.position().add(0,0,10));
                h.assertTrue(runtime.counterTarget(p)==null && !slot(p).requestCasting(patch,new CompoundTag()),"No distant retaliation");
                attacker.setPos(p.position().add(0,0,2));
            })).thenExecuteAfter(13,()->f.checked(()->{
                h.assertTrue(runtime.counterTarget(p)==null && !slot(p).requestCasting(patch,new CompoundTag()),"Expired opening is not an attack");
                f.close();
            })).thenSucceed();
        });
    }

    private static void throwBag(GameTestHelper h, Actor p, float pitch) {
        p.getCooldowns().removeCooldown(ModContent.TEAR_POUCH.get());
        p.setXRot(pitch);p.xRotO=pitch;
        ItemStack supply=new ItemStack(ModContent.TEAR_POUCH.get(),2);p.setItemInHand(MAIN,supply);
        supply.use(p.level(),p,MAIN);finishFlight(h,flying(h,p));
        h.assertValueEqual(supply.getCount(),1,"The physical throw consumed one bag");
    }

    @GameTest(template = "empty")
    public static void pouchRespectsEyesShieldAndActualAuraAndNeverDamages(GameTestHelper h) {
        var server = h.getLevel().getServer();
        boolean previousPvp = server.isPvpAllowed();
        try (var f=new Fixture(h)) {
            Actor p=student(f);prepareKnown(h,p,SchoolForm.TEAR_POUCH);
            Actor target=student(f);
            target.setPos(p.position().add(0,0,3.2));face(target,0);
            float hp=target.getHealth();
            face(target,180); server.setPvpAllowed(false);
            throwBag(h,p,0);h.assertFalse(target.hasEffect(MobEffects.BLINDNESS),"Server PvP protection also applies to dust");
            server.setPvpAllowed(true);face(target,0);
            throwBag(h,p,0);h.assertFalse(target.hasEffect(MobEffects.BLINDNESS),"Turning away protects the eyes");
            face(target,180);target.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));
            target.startUsingItem(InteractionHand.OFF_HAND);for(int i=0;i<6;i++)target.doTick();
            h.assertTrue(target.isBlocking(),"The real shield is up");
            throwBag(h,p,0);h.assertFalse(target.hasEffect(MobEffects.BLINDNESS),"Raised shield blocks the frontal dust");
            target.stopUsingItem();throwBag(h,p,19);
            h.assertFalse(target.hasEffect(MobEffects.BLINDNESS),"A low hit at the legs cannot blind");
            throwBag(h,p,0);
            var ordinary=target.getEffect(MobEffects.BLINDNESS);
            h.assertTrue(ordinary!=null && ordinary.getDuration()==16,"Unprotected frontal hit creates a short 16-tick opening");
            target.removeEffect(MobEffects.BLINDNESS);var progress=target.getData(ModAttachments.SWORD_TRAINING);
            progress.completeAuraDrill();progress.awakenAura();target.setItemInHand(MAIN,new ItemStack(Items.IRON_SWORD));
            throwBag(h,p,0);var guarded=target.getEffect(MobEffects.BLINDNESS);
            h.assertTrue(guarded!=null && guarded.getDuration()==6,"Actual body aura shortens the disturbance");
            h.assertValueEqual(target.getHealth(),hp,"No direct health loss in any physical throw");
        } finally {
            server.setPvpAllowed(previousPvp);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyPouchOrRejectedSpawnCannotSpendStaminaOrAmmo(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = student(f);
            prepareKnown(h, p, SchoolForm.TEAR_POUCH);
            float before = patch(p).getStamina();
            h.assertFalse(slot(p).requestCasting(patch(p),new CompoundTag()), "The real native request rejects an empty inventory");
            h.assertValueEqual(patch(p).getStamina(), before, "No bag means no stamina cost");
            var bag = new ItemStack(ModContent.TEAR_POUCH.get(), 2);
            p.setItemInHand(InteractionHand.OFF_HAND, bag);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> reject = event -> {
                if (event.getEntity() instanceof TearPouchProjectile thrown && thrown.getOwner() == p) event.setCanceled(true);
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(reject);
            try {
                slot(p).requestCasting(patch(p), new CompoundTag());
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(reject);
            }
            h.assertValueEqual(bag.getCount(), 2, "Rejected entity admission leaves both bags");
            h.assertValueEqual(patch(p).getStamina(), before, "No native stamina spent on failed admission");
            h.assertFalse(p.getCooldowns().isOnCooldown(ModContent.TEAR_POUCH.get()), "Failed spawn leaves no item cooldown");
            h.assertTrue(h.getLevel().getEntitiesOfClass(TearPouchProjectile.class,p.getBoundingBox().inflate(10),e->e.getOwner()==p).isEmpty(), "No failed projectile remains");
            patch(p).setStamina(0);
            h.assertFalse(slot(p).requestCasting(patch(p),new CompoundTag()), "Insufficient native stamina rejects the move");
            h.assertValueEqual(bag.getCount(),2,"A stamina rejection cannot consume the physical bag");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pouchRecipeWorksInSurvivalAndHasARecipeBookUnlock(GameTestHelper h) {
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "tear_pouch");
        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, List.of(
                new ItemStack(Items.PAPER), new ItemStack(Items.STRING), new ItemStack(Items.CHARCOAL), ItemStack.EMPTY));
        var result = h.getLevel().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel()).orElseThrow();
        h.assertValueEqual(result.id(), id, "The real recipe manager finds the intended pouch recipe");
        ItemStack bag = result.value().assemble(input, h.getLevel().registryAccess());
        h.assertTrue(bag.is(ModContent.TEAR_POUCH.get()) && bag.getCount() == 1, "Paper, string and charcoal craft exactly one pouch");
        var advancement = h.getLevel().getServer().getAdvancements().get(id.withPrefix("recipes/"));
        h.assertTrue(advancement != null && advancement.value().rewards().recipes().contains(id), "The recipe book can unlock from its material or notebook");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void preparingSchoolFormsUsesABriefPrivateSlotCooldown(GameTestHelper h) {
        var f = new Fixture(h);
        f.checked(() -> {
            Actor p = student(f);
            prepareKnown(h, p, SchoolForm.ARM_STRIKE);
            var school = slot(p);
            var guard = patch(p).getSkill(SkillSlots.GUARD);
            int nativeLimit = yesman.epicfight.world.gamerule.EpicFightGameRules.SKILL_REPLACE_COOLDOWN.getRuleValue(p.level());
            school.setReplaceCooldown(nativeLimit);
            guard.setReplaceCooldown(nativeLimit);
            h.assertValueEqual(school.getReplaceCooldown(), nativeLimit, "EF editor on the school slot retains its own rule");
            h.assertValueEqual(guard.getReplaceCooldown(), nativeLimit, "The base guard slot retains its own rule");
            note(p, SchoolForm.ARM_STRIKE, true);
            h.assertTrue(school.getSkill() != null, "An immediate repeat cannot unequip the form");
            h.startSequence().thenExecuteAfter(20, () -> f.checked(() -> {
                note(p, SchoolForm.ARM_STRIKE, true);
                h.assertTrue(school.getSkill() == null, "After one second the notes return the original weapon action");
                h.assertTrue(guard.getReplaceCooldown() >= Math.max(0, nativeLimit-22), "Notebook did not reset a native skill timer");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty")
    public static void armStrikeKeepsItsAuthoredForwardRangeAndMissRecovery(GameTestHelper h) {
        for (double distance : new double[] {1.4, 2.0, 2.3, 2.6, 2.9}) {
            try (var f = new Fixture(h)) {
                Actor p = student(f);
                prepareKnown(h, p, SchoolForm.ARM_STRIKE);
                Husk target = enemy(f, ORIGIN.add(0, 0, distance), 180);
                float hp = target.getHealth();
                h.assertTrue(slot(p).requestCasting(patch(p), new CompoundTag()), "The native attack starts at every test distance");
                // EF applies players' root motion on their owning client. The headless player has no network
                // movement stream, so feed in the same authored displacement that a client reports.
                Vec3 origin = p.position();
                var animation = SchoolAnimations.ARM_STRIKE.get();
                double maximumStep = 0;
                for (int frame = 0; frame < 15; frame++) {
                    float phaseTime = Math.min(animation.getTotalTime(), (frame + 1) * .05F);
                    Vec3 offset = animation.getExpectedMovement(patch(p), phaseTime);
                    Vec3 prior = p.position();
                    p.setPos(origin.add(offset));
                    p.xOld = prior.x; p.yOld = prior.y; p.zOld = prior.z;
                    maximumStep = Math.max(maximumStep, offset.horizontalDistance());
                    patch(p).getAnimator().tick();
                }
                boolean hit = target.getHealth() < hp;
                LogUtils.getLogger().info("[SchoolRange] distance={} hit={} loss={} authoredStep={}", distance, hit, hp - target.getHealth(), maximumStep);
                h.assertTrue(hit == (distance <= 2.6), "The authored step should hit through 2.6 blocks, miss at 2.9, actual " + distance);
                h.assertFalse(p.getData(ModAttachments.SCHOOL_COMBAT).recovered(p), "A miss retains its action recovery just like a hit");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void preexistingBlindnessNeverAssignsLongDirtToABystanderOrBackHit(GameTestHelper h) {
        var server = h.getLevel().getServer();
        boolean priorPvp = server.isPvpAllowed();
        try (var f = new Fixture(h)) {
            server.setPvpAllowed(true);
            Actor thrower = student(f);
            prepareKnown(h, thrower, SchoolForm.TEAR_POUCH);
            Actor victim = student(f), observer = student(f);
            victim.setPos(thrower.position().add(0,0,3.2));
            observer.setPos(victim.position().add(.95,0,0));
            victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.BLINDNESS, 300));
            observer.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.BLINDNESS, 300));
            face(victim,0);
            throwBag(h,thrower,0);
            h.assertTrue(victim.dustCues.size() == 1 && victim.dustCues.getFirst().subject() == -1 && !victim.dustCues.getFirst().forced(),
                    "A back hit emits only anonymous dust, even if the character was already blind");
            face(victim,180);
            throwBag(h,thrower,0);
            var ordinary = victim.dustCues.getLast();
            var other = observer.dustCues.getLast();
            h.assertTrue(ordinary.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.TEAR_ASH
                    && ordinary.subject() == victim.getId() && ordinary.forced(), "Front hit identifies its own target and normal short duration");
            h.assertTrue(!other.forced() && other.subject() == victim.getId(), "An already-blind neighbor still only sees world dust");
            var training = victim.getData(ModAttachments.SWORD_TRAINING);
            training.completeAuraDrill(); training.awakenAura();
            throwBag(h,thrower,0);
            var guarded = victim.dustCues.getLast();
            h.assertTrue(guarded.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.TEAR_ASH_GUARDED
                    && guarded.subject() == victim.getId() && guarded.forced(), "Protected eyes request the six-tick overlay only on the real target");
            h.assertTrue(victim.getEffect(MobEffects.BLINDNESS).getDuration() > 16, "The victim's unrelated longer effect is preserved");
        } finally {
            server.setPvpAllowed(priorPvp);
        }
        h.succeed();
    }

    private SchoolCombatGameTests() {}
}
