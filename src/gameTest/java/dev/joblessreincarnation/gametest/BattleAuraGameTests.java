package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.StoneCannonProjectile;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.AuraVisual;
import dev.joblessreincarnation.training.BattleAura;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.registry.entries.EpicFightSkillDataKeys;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSources;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BattleAuraGameTests {
    private static final Vec3 IN_FRONT = new Vec3(5.5, 2, 4.5);

    private static Actor fighter(Fixture f, boolean awake) {
        Actor p = f.actor();
        // Expire native login invulnerability through the actual server player tick before sending damage.
        for (int i = 0; i < 61; i++) p.serverLevel().tickNonPassenger(p);
        p.sword();
        var progress = new SwordTrainingProgress(true, SwordTrainingProgress.GOAL, true);
        if (awake) { progress.completeAuraDrill(); progress.awakenAura(); }
        p.setData(ModAttachments.SWORD_TRAINING, progress);
        p.setData(ModAttachments.MANA, new ManaData(50, 100, 80, 0));
        return p;
    }

    private static Husk opponent(Fixture f) {
        Husk mob = EntityType.HUSK.create(f.helper.getLevel());
        mob.setNoAi(true);
        return f.admit(mob, IN_FRONT);
    }

    private static float hit(Actor p, DamageSource source, float amount) {
        p.invulnerableTime = 0;
        p.setHealth(p.getMaxHealth());
        float before = p.getHealth();
        p.hurt(source, amount);
        return before - p.getHealth();
    }

    private static float afterDefaultHardness(float exposed) {
        return Math.max(exposed - 2.0F, exposed * 0.5F);
    }

    private static void near(GameTestHelper h, float actual, float expected, String what) {
        h.assertTrue(Math.abs(expected - actual) < 0.001F, what + " expected " + expected + " got " + actual);
    }

    @GameTest(template = "empty")
    public static void auraAwakensOnlyAfterPaulsLessonDummyAndHostileSwordHit(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, false);
            var training = p.getData(ModAttachments.SWORD_TRAINING);
            var dummy = f.dummy(p, IN_FRONT);
            Husk mob = opponent(f);
            p.recover(); p.attack(mob);
            h.assertFalse(training.auraAwakened(), "Prior recognition or a hostile hit alone cannot awaken aura");
            p.recover(); p.attack(dummy);
            h.assertTrue(training.auraDrill() && !training.auraAwakened(), "A real fully recovered wooden sword hit completes the drill only");
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            p.recover(); mob.invulnerableTime = 0; p.attack(mob);
            h.assertFalse(training.auraAwakened(), "Punching the hostile does not complete sword practice");
            p.sword();
            mob.invulnerableTime = 0; mob.hurt(mob.damageSources().thrown(new StoneCannonProjectile(h.getLevel(), p), p), 2.0F);
            h.assertFalse(training.auraAwakened(), "A projectile cannot stand in for wielding a sword");
            p.recover(); mob.invulnerableTime = 0; p.attack(mob);
            h.assertTrue(training.auraAwakened(), "A real sword hit on the hostile awakens the recognized student");
            SwordTrainingProgress decoded = SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,
                    SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, training).getOrThrow()).getOrThrow();
            h.assertTrue(decoded.auraAwakened(), "Earned training survives a real NBT round trip");
            var legacy = new CompoundTag();
            legacy.putBoolean("started", true); legacy.putInt("hits", 20); legacy.putBoolean("recognized", true);
            h.assertFalse(SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow().auraAwakened(),
                    "An untouched 0.34 recognized character does not wake with free aura");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void weakAndHeavyEpicHitsMeetDifferentHardnessCapsAndNeverSpendMana(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor plain = fighter(f, false), ward = fighter(f, true);
            DamageSource attack = EpicFightDamageSources.mobAttack(opponent(f));
            var mana = new ManaData(50, 100, 1200, 13);
            ward.setData(ModAttachments.MANA, mana);
            for (float power : new float[] {2, 4, 10}) {
                float ordinary = hit(plain, attack, power);
                h.assertTrue(ordinary > 0, "Control must lose actual health");
                float guarded = hit(ward, attack, power);
                near(h, guarded, afterDefaultHardness(ordinary), "Hardness applies once to this physical hit");
                h.assertValueEqual(AuraVisual.of(ward).phase(), AuraVisual.Phase.GUARD, "Only genuinely prevented damage emits GUARD");
                h.assertValueEqual(mana.mana(), 50, "Repeated weak or heavy hits must never spend mana");
                com.mojang.logging.LogUtils.getLogger().info("[AuraHardness] power={} unguarded={} guarded={} mana={}",
                        power, ordinary, guarded, mana.mana());
            }
            h.assertValueEqual(mana.cooldown(), 13, "Sword guard does not touch the spell cooldown");
            h.assertValueEqual(mana.recoveryDelay(), 1200, "Sword guard does not restart regeneration");
            h.assertFalse(mana.overcastLocked() || ward.hasEffect(ModContent.MANA_DEPLETION), "Ordinary protection cannot cause overdraft");
            var previous = AuraVisual.of(ward);
            h.assertFalse(ward.hurt(attack, 10), "Native immunity rejects an immediate repeat");
            h.assertTrue(AuraVisual.of(ward) == previous, "A rejected hit does not start another pulse");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void shieldAndAbsorptionApplyBeforeAuraHardness(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, true), plain = fighter(f, false);
            Husk mob = opponent(f);
            DamageSource attack = mob.damageSources().mobAttack(mob);
            float full = hit(plain, attack, 8);
            h.assertTrue(full > 3, "Control hit is large enough for a partial absorption check");
            p.setYRot(0); p.setXRot(0);
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            p.startUsingItem(InteractionHand.OFF_HAND);
            for (int i = 0; i < 6; i++) p.doTick();
            h.assertTrue(p.isBlocking(), "Native offhand shield is up before the frontal hit");
            near(h, hit(p, attack, 8), 0, "A completely shielded hit loses no red hearts");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "Full shield block consumes no mana");
            h.assertFalse(AuraVisual.of(p).phase() == AuraVisual.Phase.GUARD, "Shield alone does not flash battle aura");
            p.stopUsingItem();
            p.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
            p.setAbsorptionAmount(15);
            near(h, hit(p, attack, 8), 0, "Yellow hearts take the entire hit");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "No aura payment for yellow hearts");
            p.setAbsorptionAmount(3);
            near(h, hit(p, attack, 8), afterDefaultHardness(full - 3), "Only the remainder past absorption is mitigated");
            near(h, p.getAbsorptionAmount(), 0, "Vanilla still consumes all three yellow hearts");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "Partially absorbing the hit does not spend mana");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void depletedStaffSpellAndFallNeverBorrowTheAuraPool(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, true), plain = fighter(f, false);
            Husk mob = opponent(f);
            DamageSource attack = mob.damageSources().mobAttack(mob);
            float full = hit(plain, attack, 8);
            p.addEffect(new MobEffectInstance(ModContent.MANA_DEPLETION, 120, 0));
            near(h, hit(p, attack, 8), full, "Depleted character receives ordinary damage");
            p.removeEffect(ModContent.MANA_DEPLETION);
            p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            near(h, hit(p, attack, 8), full, "A staff in the main hand never enables aura");
            p.sword();
            DamageSource spell = p.damageSources().thrown(new StoneCannonProjectile(h.getLevel(), mob), mob);
            near(h, hit(p, spell, 8), hit(plain, spell, 8), "Actual stone spell uses thrown damage and bypasses aura");
            near(h, hit(p, p.damageSources().fall(), 8), hit(plain, plain.damageSources().fall(), 8), "Falling does not light aura");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "Excluded sources and states spent nothing");
            h.assertFalse(AuraVisual.of(p).phase() == AuraVisual.Phase.GUARD, "Excluded events did not publish a guard");
            var arrow = new Arrow(EntityType.ARROW, h.getLevel());
            DamageSource shot = EpicFightDamageSources.arrow(arrow, mob);
            near(h, hit(p, shot, 8), afterDefaultHardness(hit(plain, shot, 8)), "An actual Epic Fight arrow is physical");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "Guarding the arrow preserves mana");
            h.assertValueEqual(AuraVisual.of(p).phase(), AuraVisual.Phase.GUARD, "Physical arrow caused a real guard");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void capacityRemainingManaAndLegacyBreakTimerDoNotControlAura(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, true), plain = fighter(f, false);
            DamageSource attack = EpicFightDamageSources.mobAttack(opponent(f));
            float ordinary = hit(plain, attack, 8);
            float guarded = afterDefaultHardness(ordinary);
            // A completed character from the withdrawn prototype must keep training but lose its payment lock.
            var encoded = (CompoundTag) SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE,
                    p.getData(ModAttachments.SWORD_TRAINING)).getOrThrow();
            encoded.putLong("aura_broken_until", h.getLevel().getGameTime() + 10_000);
            var loaded = SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
            p.setData(ModAttachments.SWORD_TRAINING, loaded);
            h.assertTrue(loaded.auraAwakened(), "Prior awakening survives upgrading from core2");
            var saved = (CompoundTag) SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
            h.assertFalse(saved.contains("aura_broken_until"), "The obsolete timer is dropped on next save");
            for (var pool : new ManaData[] {new ManaData(100, 100, 100, 0), new ManaData(12, 100, 100, 0),
                    new ManaData(12, 200, 100, 0), new ManaData(0, 200, 100, 0)}) {
                p.setData(ModAttachments.MANA, pool);
                int before = pool.mana();
                near(h, hit(p, attack, 8), guarded, "Reserve, capacity and old timer do not change guard strength");
                h.assertValueEqual(pool.mana(), before, "No spending in any pool");
                h.assertValueEqual(AuraVisual.of(p).phase(), AuraVisual.Phase.GUARD, "Low mana alone never emits BREAK");
            }
            var zeroHold = BattleAura.beginGathering(p, 20);
            h.assertTrue(zeroHold != null && BattleAura.endGathering(p, zeroHold), "Zero reserve alone permits concentration");
            p.addEffect(new MobEffectInstance(ModContent.MANA_DEPLETION, 60));
            BattleAura.tick(new PlayerTickEvent.Post(p));
            h.assertValueEqual(AuraVisual.of(p), AuraVisual.OFF, "The actual depletion effect suppresses aura without a break flash");
            h.assertTrue(BattleAura.beginGathering(p, 20) == null, "Cannot begin concentration while actually depleted");
            near(h, hit(p, attack, 8), ordinary, "An actual depletion effect suspends protection");
            p.removeEffect(ModContent.MANA_DEPLETION);
            BattleAura.tick(new PlayerTickEvent.Post(p));
            h.assertValueEqual(AuraVisual.of(p), AuraVisual.READY, "Effect removal at zero reserve restores ready immediately");
            near(h, hit(p, attack, 8), guarded, "First hit after depletion ends is protected without a refill gate");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void serverCanTuneOrDisableHardnessWithoutFalseImpacts(GameTestHelper h) {
        double originalHardness = MagicConfig.AURA_HARDNESS.get();
        double originalRatio = MagicConfig.AURA_MAX_REDUCTION_RATIO.get();
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, true), plain = fighter(f, false);
            DamageSource attack = EpicFightDamageSources.mobAttack(opponent(f));
            MagicConfig.AURA_HARDNESS.set(1.0);
            MagicConfig.AURA_MAX_REDUCTION_RATIO.set(0.25);
            for (float power : new float[] {2, 10}) {
                float full = hit(plain, attack, power);
                near(h, hit(p, attack, power), full - Math.min(1, full * 0.25F), "Server configuration changes actual damage");
            }
            for (boolean zeroHardness : new boolean[] {true, false}) {
                MagicConfig.AURA_HARDNESS.set(zeroHardness ? 0.0 : 1.0);
                MagicConfig.AURA_MAX_REDUCTION_RATIO.set(zeroHardness ? 0.25 : 0.0);
                p.setData(ModAttachments.BATTLE_AURA_VISUAL, AuraVisual.READY);
                near(h, hit(p, attack, 6), hit(plain, attack, 6), "Either zero setting leaves the hit untouched");
                h.assertValueEqual(AuraVisual.of(p), AuraVisual.READY, "Preventing nothing must not flash");
            }
        } finally {
            MagicConfig.AURA_HARDNESS.set(originalHardness);
            MagicConfig.AURA_MAX_REDUCTION_RATIO.set(originalRatio);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realEpicFightTimedParryDoesNotSpendAura(GameTestHelper h) {
        var f = new Fixture(h);
        f.checked(() -> {
            Actor p = fighter(f, true);
            Husk mob = opponent(f);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            ServerPlayerPatch patch = EpicFightCapabilities.getEntityPatch(p, ServerPlayerPatch.class);
            h.assertTrue(patch != null, "The real Epic Fight server patch is attached");
            patch.toEpicFightMode(false);
            var guard = patch.getSkill(SkillSlots.GUARD);
            guard.setSkill(EpicFightSkills.PARRYING.get());
            patch.setStamina(patch.getMaxStamina());
            h.startSequence().thenExecuteAfter(24, () -> f.checked(() -> {
                p.setOnGround(true);
                p.setYRot(0); p.yRotO = 0;
                mob.setPos(p.getX(), p.getY(), p.getZ() + 2);
                h.assertTrue(guard.requestHold(patch, new CompoundTag()), "EF accepts the normal native guard hold request");
                h.assertTrue(guard.isActivated() && patch.isHoldingSkill(EpicFightSkills.PARRYING.get()), "Parrying is really held");
                var data = guard.getDataManager();
                int beforeWindow = data.getDataValue(EpicFightSkillDataKeys.LAST_ACTIVE);
                h.assertTrue(beforeWindow > 0 && beforeWindow == p.tickCount, "EF opened its timed parry window (recorded " + beforeWindow + ", player tick " + p.tickCount + ")");
                var mana = p.getData(ModAttachments.MANA);
                int beforeMana = mana.mana();
                near(h, hit(p, EpicFightDamageSources.mobAttack(mob), 8), 0, "A front attack is stopped by the timed parry");
                h.assertValueEqual(data.getDataValue(EpicFightSkillDataKeys.LAST_ACTIVE), 0, "Only the perfect parry consumes EF's active window");
                h.assertValueEqual(mana.mana(), beforeMana, "A native parry leaves mana unchanged");
                h.assertFalse(AuraVisual.of(p).phase() == AuraVisual.Phase.GUARD, "No aura guard is published after parrying");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty")
    public static void gatheringSignalsStartAndEndAndSuspendsGuard(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = fighter(f, true), plain = fighter(f, false);
            Husk mob = opponent(f);
            var source = mob.damageSources().mobAttack(mob);
            float full = hit(plain, source, 8);
            h.assertTrue(BattleAura.beginGathering(plain, 40) == null, "An unawakened player cannot emit gather");
            var first = BattleAura.beginGathering(p, 40);
            h.assertTrue(first != null && BattleAura.beginGathering(p, 40) == null, "Duplicate begin cannot restart a confirmed hold");
            h.assertValueEqual(AuraVisual.of(p).phase(), AuraVisual.Phase.GATHER, "The accepted hold emits a public start");
            near(h, hit(p, source, 8), full, "Gathering moves the guard off the body");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 50, "A physical hit during concentration never charges guard");
            h.assertTrue(BattleAura.endGathering(p, first), "Valid release closes its own hold");
            h.assertValueEqual(AuraVisual.of(p), AuraVisual.READY, "The end is immediately visible without another hit");
            var second = BattleAura.beginGathering(p, 40);
            h.assertTrue(second != null && !BattleAura.endGathering(p, first), "An old release cannot clear the newer hold");
            h.assertValueEqual(AuraVisual.of(p).phase(), AuraVisual.Phase.GATHER, "Newer hold stays active");
            h.assertTrue(BattleAura.endGathering(p, second), "The current caller can cancel normally");
            near(h, hit(p, source, 8), afterDefaultHardness(full), "Guard resumes on the first hit after release");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void gatheringCannotSurviveWeaponSwapTimeoutDepletionOrDeath(GameTestHelper h) {
        var f = new Fixture(h);
        f.checked(() -> {
            Actor p = fighter(f, true);
            var first = BattleAura.beginGathering(p, 40);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            h.assertFalse(BattleAura.endGathering(p, first), "A different weapon cannot release a charge owned by the previous stack");
            h.assertValueEqual(AuraVisual.of(p), AuraVisual.READY, "The new sword is immediately eligible for guard");
            var timed = BattleAura.beginGathering(p, 3);
            h.assertTrue(timed != null, "New sword accepts a new hold");
            h.startSequence().thenExecuteAfter(4, () -> f.checked(() -> {
                BattleAura.tick(new PlayerTickEvent.Post(p));
                h.assertValueEqual(AuraVisual.of(p), AuraVisual.READY, "A missing release cannot orphan the gather phase");
                h.assertFalse(BattleAura.endGathering(p, timed), "Late release after timeout is rejected");
                var depleted = BattleAura.beginGathering(p, 40);
                h.assertTrue(depleted != null, "A new gather is possible after timeout");
                p.addEffect(new MobEffectInstance(ModContent.MANA_DEPLETION, 40));
                BattleAura.tick(new PlayerTickEvent.Post(p));
                h.assertValueEqual(AuraVisual.of(p), AuraVisual.OFF, "Depletion cancels all aura presentation");
                h.assertFalse(BattleAura.endGathering(p, depleted), "Cancelled spell state cannot be paid or released");
                p.removeEffect(ModContent.MANA_DEPLETION);
                var death = BattleAura.beginGathering(p, 40);
                h.assertTrue(death != null, "Prepare a separate genuine death during a hold");
                p.hurt(p.damageSources().genericKill(), Float.MAX_VALUE);
                h.assertTrue(p.isDeadOrDying(), "Actual server damage killed the test actor");
                h.assertFalse(p.hasData(ModAttachments.AURA_GATHERING), "No charge survives death on the original entity");
                h.assertValueEqual(AuraVisual.of(p), AuraVisual.OFF, "The corpse does not keep a charging halo");
                f.close();
            })).thenSucceed();
        });
    }

    private BattleAuraGameTests() {}
}
