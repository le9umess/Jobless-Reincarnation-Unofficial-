package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaEvents;
import dev.joblessreincarnation.magic.SpellTraining;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.WaterControl;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only integration tests; this source set is excluded from release JARs. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MagicGameTests {
    private static final class ChantingPlayer extends FakePlayer {
        private ChantingPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "MagicTest"));
        }

        // FakePlayer.tick() is intentionally empty; exercise vanilla's actual item-use lifecycle here.
        private void tickChant() {
            if (isUsingItem()) updateUsingItem(getUseItem());
        }
    }

    private static ChantingPlayer learner(GameTestHelper helper) {
        ChantingPlayer player = new ChantingPlayer(helper.getLevel());
        Vec3 position = helper.absoluteVec(new Vec3(4, 2, 4));
        player.setPos(position);
        player.setYRot(0);
        player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        return player;
    }

    private static ChantingPlayer caster(GameTestHelper helper) {
        ChantingPlayer player = learner(helper);
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        return player;
    }

    private static void holdStaff(ChantingPlayer player, int ticks) {
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < ticks; i++) player.tickChant();
    }

    private static WaterBolt onlyBolt(GameTestHelper helper) {
        var bolts = helper.findEntities(ModContent.WATER_BOLT.get(), new Vec3(8, 4, 8), 16);
        helper.assertValueEqual(bolts.size(), 1, "Exactly one projectile is present");
        return bolts.getFirst();
    }

    private static void assertNear(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) < 0.0001,
                message + ": expected " + expected + ", got " + actual);
    }

    @GameTest(template = "empty")
    public static void holdingStaffWaitsForReleaseAndCastsOnce(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        var staff = ModContent.TRAINING_STAFF.get();
        int before = player.getData(ModAttachments.MANA).mana();
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.isUsingItem(), "Right-click begins chanting");
        helper.assertValueEqual(staff.getUseDuration(player.getMainHandItem(), player), 72000, "Staff supports continued charge after chanting");
        for (int i = 0; i < WaterMagic.chantTicks(player) - 1; i++) player.tickChant();
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "No cost before chant finishes");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        player.tickChant();
        helper.assertTrue(player.isUsingItem(), "Completed chant remains held until release");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Ready spell has not spent mana");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        player.releaseUsingItem();
        player.releaseUsingItem();
        player.tickChant();
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - MagicConfig.WATER_BOLT_COST.getAsInt(), "Release spends base cost once");
        WaterBolt bolt = onlyBolt(helper);
        assertNear(helper, bolt.charge(), 0, "Releasing at chant completion is a basic cast");
        assertNear(helper, bolt.getDamage(), MagicConfig.WATER_BOLT_DAMAGE.get(), "Basic cast keeps base damage");
        assertNear(helper, bolt.getDeltaMovement().length(), 1.35, "Basic cast launch speed");
        helper.assertFalse(player.isUsingItem(), "Release stops use");
        helper.assertTrue(player.getMainHandItem().is(staff), "Staff is reusable");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "One practice point for one completed cast");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releasingEarlyCancelsWithoutCost(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        var staff = ModContent.TRAINING_STAFF.get();
        int before = player.getData(ModAttachments.MANA).mana();
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt() / 2; i++) player.tickChant();
        player.releaseUsingItem();
        player.tickChant();
        helper.assertFalse(player.isUsingItem(), "Early release cancels chanting");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Cancelled chant costs no mana");
        helper.assertFalse(player.getCooldowns().isOnCooldown(staff), "Cancelled chant has no cooldown");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Cancelled chant grants no practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullChargeWaitsAndRemainsCappedUntilRelease(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        int fullAt = WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt();
        holdStaff(player, fullAt + 200);
        helper.assertTrue(player.isUsingItem(), "Full charge does not auto-release even after a long hold");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Holding a full spell has no upfront cost");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Holding alone cannot farm practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        player.releaseUsingItem();
        WaterBolt bolt = onlyBolt(helper);
        assertNear(helper, bolt.charge(), 1, "Overcharging clamps to full charge");
        assertNear(helper, bolt.getDamage(), MagicConfig.WATER_BOLT_DAMAGE.get() + MagicConfig.WATER_BOLT_EXTRA_DAMAGE.get(), "Full damage is snapshotted onto projectile");
        assertNear(helper, bolt.getDeltaMovement().length(), 1.65, "Full charge uses the maximum launch speed");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - MagicConfig.WATER_BOLT_COST.getAsInt() - MagicConfig.WATER_BOLT_EXTRA_COST.getAsInt(), "Overcharging does not exceed the full cost");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Long charge gives only one practice point");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialChargeScalesTheReleasedProjectile(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int chargeTicks = MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt();
        int extraTicks = Math.max(1, chargeTicks / 2);
        float fraction = (float) extraTicks / chargeTicks;
        int before = player.getData(ModAttachments.MANA).mana();
        holdStaff(player, WaterMagic.chantTicks(player) + extraTicks);
        player.releaseUsingItem();
        WaterBolt bolt = onlyBolt(helper);
        assertNear(helper, bolt.charge(), fraction, "Partial hold retains its fractional charge");
        assertNear(helper, bolt.getDamage(), MagicConfig.WATER_BOLT_DAMAGE.get() + MagicConfig.WATER_BOLT_EXTRA_DAMAGE.get() * fraction, "Partial charge reaches the actual projectile damage");
        assertNear(helper, bolt.getDeltaMovement().length(), 1.35 + 0.3 * fraction, "Partial charge changes actual launch speed");
        int expectedCost = MagicConfig.WATER_BOLT_COST.getAsInt() + (int) Math.ceil(MagicConfig.WATER_BOLT_EXTRA_COST.getAsInt() * fraction);
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - expectedCost, "Partial release spends its scaled cost");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Partial charge still counts as one successful practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chargedReleaseRechecksTheWholeManaCost(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int baseCost = MagicConfig.WATER_BOLT_COST.getAsInt();
        int fullCost = baseCost + MagicConfig.WATER_BOLT_EXTRA_COST.getAsInt();
        helper.assertTrue(fullCost > baseCost, "Fixture requires the configured charge surcharge");
        player.setData(ModAttachments.MANA, new ManaData(fullCost - 1, MagicConfig.MAX_MANA.getAsInt(), 0, 0));
        holdStaff(player, WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt());
        helper.assertTrue(player.isUsingItem(), "Enough mana for a basic spell allows starting the chant");
        player.releaseUsingItem();
        helper.assertFalse(player.isUsingItem(), "Rejected release ends the current use");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), fullCost - 1, "Unaffordable charged spell consumes nothing");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Unaffordable charge grants no practice");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Unaffordable release does not set item cooldown");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "Unaffordable release does not set mana cooldown");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sneakingBeforeReleaseCancelsAReadySpell(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        holdStaff(player, WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt());
        player.setShiftKeyDown(true);
        player.releaseUsingItem();
        helper.assertFalse(player.isUsingItem(), "Sneaking release ends the current use");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Cancelling a fully formed spell is free");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Sneak cancel adds no cooldown");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Sneak cancel cannot grant practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stoppingOrSwitchingItemsCancelsReadySpells(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        int readyAt = WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt();
        holdStaff(player, readyAt);
        player.stopUsingItem();
        player.releaseUsingItem();
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        holdStaff(player, readyAt);
        player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        // Vanilla's item-change path calls stopUsingItem(), not releaseUsingItem().
        player.stopUsingItem();
        player.releaseUsingItem();
        helper.assertFalse(player.isUsingItem(), "Changing the held item ends use");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Interrupted spells consume nothing");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Interrupted spells grant no practice");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Interrupted spells add no cooldown");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingStaffCannotReuseThePreviousCharge(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        holdStaff(player, WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt());
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        // A release arriving after a slot change must not cast using the old held stack.
        player.releaseUsingItem();
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Another staff cannot inherit the old charge");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Stale release cannot grant practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chargeUsesTheTrainedChantBoundary(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        while (WaterMagic.practiceCount(player) < SpellTraining.adeptAt()) {
            player.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        }
        int trainedChant = WaterMagic.chantTicks(player);
        assertNear(helper, WaterControl.charge(player, trainedChant - 1), 0, "An unfinished trained chant has no charge");
        assertNear(helper, WaterControl.charge(player, trainedChant), 0, "Charge starts at the trained chant boundary");
        holdStaff(player, trainedChant);
        player.releaseUsingItem();
        assertNear(helper, onlyBolt(helper).charge(), 0, "A trained player can release a basic spell at the shortened boundary");
        helper.assertValueEqual(WaterMagic.practiceCount(player), SpellTraining.adeptAt() + 1, "Trained basic release counts once");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidChargeInputsCannotCorruptCasting(GameTestHelper helper) {
        float[] inputs = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0F, 2.0F};
        for (float input : inputs) {
            ChantingPlayer player = caster(helper);
            int before = player.getData(ModAttachments.MANA).mana();
            float expectedCharge = input == 2.0F ? 1.0F : 0.0F;
            helper.assertTrue(WaterMagic.cast(player, input), "Normalized charge can still cast: " + input);
            WaterBolt bolt = onlyBolt(helper);
            assertNear(helper, bolt.charge(), expectedCharge, "Invalid charge is normalized on the spawned projectile");
            assertNear(helper, bolt.getDamage(), MagicConfig.WATER_BOLT_DAMAGE.get() + MagicConfig.WATER_BOLT_EXTRA_DAMAGE.get() * expectedCharge, "Normalized damage remains finite");
            int expectedCost = MagicConfig.WATER_BOLT_COST.getAsInt() + (expectedCharge == 1 ? MagicConfig.WATER_BOLT_EXTRA_COST.getAsInt() : 0);
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - expectedCost, "Invalid charge cannot bypass cost");
            bolt.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void survivalCastSpendsManaAndRejectsSpam(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        helper.assertTrue(WaterMagic.cast(player), "First valid cast should succeed");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - MagicConfig.WATER_BOLT_COST.getAsInt(), "mana after cast");
        helper.assertFalse(WaterMagic.cast(player), "A second cast in the same tick must be rejected");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - MagicConfig.WATER_BOLT_COST.getAsInt(), "mana after rejected cast");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Spam cannot grant extra practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientManaDoesNotSpawnOrConsume(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        player.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        helper.assertFalse(WaterMagic.cast(player), "Zero mana must reject casting");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 0, "remaining mana");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Failed cast grants no practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void castingRequiresHeldStaff(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_TOME.get().getDefaultInstance());
        helper.assertFalse(WaterMagic.cast(player), "A learned tome is not a casting item");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertFalse(WaterMagic.cast(player), "A player without the staff cannot cast");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recoveryAndCreativeModeRespectLimits(GameTestHelper helper) {
        ManaData data = ManaData.full(100);
        helper.assertTrue(data.trySpend(12, 40, 20, false), "Initial spell");
        for (int i = 0; i < 39; i++) data.tick(100, 2);
        helper.assertValueEqual(data.mana(), 88, "no recovery before the 40th tick");
        data.tick(100, 2);
        helper.assertValueEqual(data.mana(), 90, "first recovery");
        for (int i = 0; i < 19; i++) data.tick(100, 2);
        helper.assertValueEqual(data.mana(), 90, "no early second recovery");
        data.tick(100, 2);
        helper.assertValueEqual(data.mana(), 92, "second recovery");
        for (int i = 0; i < 200; i++) data.tick(100, 2);
        helper.assertValueEqual(data.mana(), 100, "mana cap");
        data.tick(50, 2);
        helper.assertValueEqual(data.mana(), 50, "clamp after config reduction");
        ManaData creative = new ManaData(0, 100, 0, 0);
        helper.assertTrue(creative.trySpend(12, 40, 20, true), "Creative bypasses mana cost");
        helper.assertValueEqual(creative.mana(), 0, "creative mana unchanged");
        helper.assertFalse(creative.trySpend(12, 40, 20, true), "Creative still has cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerSaveRestoresManaAndCooldown(GameTestHelper helper) {
        FakePlayer original = caster(helper);
        original.setData(ModAttachments.MANA, new ManaData(37, 100, 31, 11));
        for (int i = 0; i < 7; i++) original.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        FakePlayer restored = caster(helper);
        restored.load(saved);
        ManaData data = restored.getData(ModAttachments.MANA);
        helper.assertValueEqual(data.mana(), 37, "saved mana");
        helper.assertValueEqual(data.recoveryDelay(), 31, "saved recovery delay");
        helper.assertValueEqual(data.cooldown(), 11, "saved cooldown");
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(restored));
        helper.assertTrue(restored.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Relog restores staff cooldown");
        helper.assertTrue(WaterMagic.isLearned(restored), "Relog retains learned spell");
        helper.assertValueEqual(WaterMagic.practiceCount(restored), 7, "Relog retains practice count");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void studyingThenCastingUsesTheLearnedSpell(GameTestHelper helper) {
        ChantingPlayer player = learner(helper);
        var tome = ModContent.WATER_TOME.get();
        player.setItemInHand(InteractionHand.MAIN_HAND, tome.getDefaultInstance());
        int before = player.getData(ModAttachments.MANA).mana();
        tome.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.isUsingItem(), "Studying begins on use");
        for (int i = 0; i < MagicConfig.STUDY_TICKS.getAsInt() - 1; i++) player.tickChant();
        helper.assertFalse(WaterMagic.isLearned(player), "Spell not learned before studying finishes");
        player.tickChant();
        helper.assertTrue(WaterMagic.isLearned(player), "Completing study teaches Water Bolt");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Studying costs no mana");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Studying does not count as practice");
        helper.assertTrue(player.getMainHandItem().is(tome), "Book remains available for sharing");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        var staff = ModContent.TRAINING_STAFF.get();
        player.setItemInHand(InteractionHand.MAIN_HAND, staff.getDefaultInstance());
        staff.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        int duration = WaterMagic.chantTicks(player);
        for (int i = 0; i < duration; i++) player.tickChant();
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        player.releaseUsingItem();
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "First learned cast grants practice");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before - MagicConfig.WATER_BOLT_COST.getAsInt(), "Staff cast spends mana");
        helper.assertEntityPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void interruptedStudyAndRepeatedReadingDoNotGrantProgress(GameTestHelper helper) {
        ChantingPlayer player = learner(helper);
        var tome = ModContent.WATER_TOME.get();
        player.setItemInHand(InteractionHand.MAIN_HAND, tome.getDefaultInstance());
        tome.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < MagicConfig.STUDY_TICKS.getAsInt() / 2; i++) player.tickChant();
        player.releaseUsingItem();
        player.tickChant();
        helper.assertFalse(WaterMagic.isLearned(player), "Aborted studying cannot teach a spell");
        MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
        knowledge.learn(WaterMagic.SPELL);
        knowledge.practice(WaterMagic.SPELL);
        tome.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertFalse(player.isUsingItem(), "Reading a known spell reports progress instead of starting another study");
        helper.assertFalse(knowledge.learn(WaterMagic.SPELL), "Learning the same spell is idempotent");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Re-reading preserves existing practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unlearnedStaffCannotCastEvenInCreative(GameTestHelper helper) {
        ChantingPlayer player = learner(helper);
        int before = player.getData(ModAttachments.MANA).mana();
        ModContent.TRAINING_STAFF.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertFalse(player.isUsingItem(), "Unlearned spell cannot begin chanting");
        helper.assertFalse(WaterMagic.cast(player), "Server rejects unlearned cast");
        player.getAbilities().instabuild = true;
        helper.assertFalse(WaterMagic.cast(player), "Creative must still learn the spell");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), before, "Rejected casts spend nothing");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void practiceMilestonesReduceChantWithoutRemovingIt(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
        int base = MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt();
        helper.assertValueEqual(WaterMagic.chantTicks(player), base, "Novice uses full chant");
        for (int i = 0; i < SpellTraining.practicedAt() - 1; i++) knowledge.practice(WaterMagic.SPELL);
        helper.assertTrue(WaterMagic.cast(player), "Milestone cast succeeds");
        helper.assertValueEqual(SpellTraining.stage(WaterMagic.practiceCount(player)), SpellTraining.Stage.PRACTICED, "First milestone");
        helper.assertValueEqual(WaterMagic.chantTicks(player), (base * 4 + 4) / 5, "Practiced chant is 80%, rounded up");
        while (WaterMagic.practiceCount(player) < SpellTraining.adeptAt()) knowledge.practice(WaterMagic.SPELL);
        helper.assertValueEqual(SpellTraining.stage(WaterMagic.practiceCount(player)), SpellTraining.Stage.ADEPT, "Second milestone");
        helper.assertValueEqual(WaterMagic.chantTicks(player), (base * 3 + 4) / 5, "Adept chant is 60%, rounded up");
        helper.assertValueEqual(SpellTraining.chantTicks(1, SpellTraining.adeptAt()), 1, "Even a one-tick base chant remains positive");
        helper.assertValueEqual(SpellTraining.nextMilestone(WaterMagic.practiceCount(player)), 0, "Final stage has no unreachable next milestone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCastingDoesNotFarmPractice(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        player.getAbilities().instabuild = true;
        player.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        helper.assertTrue(WaterMagic.cast(player), "Known creative spell casts with zero mana");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 0, "Creative mana remains unchanged");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Free creative casts grant no practice");
        helper.assertFalse(WaterMagic.cast(player), "Creative still respects cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void knowledgeSurvivesDeathAndClonesWithoutSharingState(GameTestHelper helper) {
        ChantingPlayer original = caster(helper);
        original.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        original.setData(ModAttachments.MANA, new ManaData(37, 100, 31, 11));
        ChantingPlayer respawned = learner(helper);
        respawned.copyAttachmentsFrom(original, true);
        ManaEvents.onRespawn(new PlayerEvent.PlayerRespawnEvent(respawned, false));
        helper.assertTrue(WaterMagic.isLearned(respawned), "Death retains learned spells");
        helper.assertValueEqual(WaterMagic.practiceCount(respawned), 1, "Death retains practice");
        helper.assertValueEqual(respawned.getData(ModAttachments.MANA).mana(), MagicConfig.MAX_MANA.getAsInt(), "Death refills mana");
        respawned.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        helper.assertValueEqual(WaterMagic.practiceCount(original), 1, "Clone owns an independent copy");
        ChantingPlayer returnedFromEnd = learner(helper);
        returnedFromEnd.copyAttachmentsFrom(original, false);
        helper.assertValueEqual(returnedFromEnd.getData(ModAttachments.MANA).mana(), 37, "Non-death clone preserves mana");
        helper.assertValueEqual(returnedFromEnd.getData(ModAttachments.MANA).cooldown(), 11, "Non-death clone preserves cooldown");
        helper.assertValueEqual(WaterMagic.practiceCount(returnedFromEnd), 1, "Non-death clone preserves practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyManaOnlySaveLoadsWithUnknownSpells(GameTestHelper helper) {
        ChantingPlayer legacy = learner(helper);
        legacy.setData(ModAttachments.MANA, new ManaData(37, 100, 31, 11));
        helper.assertFalse(legacy.hasData(ModAttachments.KNOWLEDGE), "Fixture has only the 0.2 mana attachment");
        CompoundTag saved = legacy.saveWithoutId(new CompoundTag());
        ChantingPlayer restored = learner(helper);
        restored.load(saved);
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(restored));
        helper.assertValueEqual(restored.getData(ModAttachments.MANA).mana(), 37, "Upgrade preserves old mana");
        helper.assertValueEqual(restored.getData(ModAttachments.MANA).cooldown(), 11, "Upgrade preserves old cooldown");
        helper.assertFalse(WaterMagic.isLearned(restored), "New progression starts unlearned on an old save");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void knowledgeSaveAndNetworkRoundTripsKeepSpellIdentity(GameTestHelper helper) {
        MagicKnowledge original = new MagicKnowledge();
        var otherSpell = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "future_spell_fixture");
        helper.assertFalse(original.practice(WaterMagic.SPELL), "Unknown spell cannot gain practice");
        original.learn(WaterMagic.SPELL);
        original.learn(otherSpell);
        original.practice(WaterMagic.SPELL);
        var saved = MagicKnowledge.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        MagicKnowledge restored = MagicKnowledge.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        helper.assertValueEqual(restored.snapshot(), original.snapshot(), "NBT retains separate spells and learned zero-cast spell");
        var buffer = Unpooled.buffer();
        try {
            MagicKnowledge.STREAM_CODEC.encode(buffer, restored);
            MagicKnowledge client = MagicKnowledge.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(client.snapshot(), original.snapshot(), "Owner sync retains spell identities and counts");
            client.practice(WaterMagic.SPELL);
            helper.assertValueEqual(original.practiceCount(WaterMagic.SPELL), 1, "Decoded data is independent");
        } finally {
            buffer.release();
        }
        helper.assertValueEqual(MagicKnowledge.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().snapshot().size(), 0, "Absent knowledge fields decode as empty");
        helper.assertFalse(WaterMagic.isLearned(learner(helper)), "Another player has independent unlearned knowledge");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void survivalStaffRecipeWorks(GameTestHelper helper) {
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.IRON_NUGGET), new ItemStack(Items.REDSTONE), new ItemStack(Items.IRON_NUGGET),
                ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY,
                ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY));
        var holder = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
        helper.assertValueEqual(holder.id(), ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "training_staff"), "Staff recipe id");
        helper.assertTrue(holder.value().assemble(input, helper.getLevel().registryAccess()).is(ModContent.TRAINING_STAFF.get()), "Red stone, metal fittings, and wooden shaft craft the staff");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void projectileNbtAndSyncedDataRetainCharge(GameTestHelper helper) {
        ChantingPlayer player = caster(helper);
        helper.assertTrue(WaterMagic.cast(player, 0.75F), "Charged cast succeeds");
        WaterBolt original = onlyBolt(helper);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        assertNear(helper, saved.getFloat("Charge"), 0.75, "NBT stores charge");
        assertNear(helper, saved.getFloat("Damage"), original.getDamage(), "NBT stores the shot's damage snapshot");
        WaterBolt restored = new WaterBolt(ModContent.WATER_BOLT.get(), helper.getLevel());
        restored.load(saved);
        assertNear(helper, restored.charge(), original.charge(), "NBT round-trip retains charge");
        assertNear(helper, restored.getDamage(), original.getDamage(), "NBT round-trip retains damage");
        assertNear(helper, restored.getDeltaMovement().length(), original.getDeltaMovement().length(), "NBT round-trip retains launch velocity");
        var data = original.getEntityData().getNonDefaultValues();
        helper.assertTrue(data != null && !data.isEmpty(), "A charged projectile has synchronized entity data");
        WaterBolt synced = new WaterBolt(ModContent.WATER_BOLT.get(), helper.getLevel());
        synced.getEntityData().assignValues(data);
        assertNear(helper, synced.charge(), 0.75, "Entity tracking synchronizes the visual charge");
        // Represents a shot created under different damage configuration; loading must not recalculate it.
        saved.putFloat("Damage", 3.25F);
        restored.load(saved);
        assertNear(helper, restored.getDamage(), 3.25, "Stored shot damage is authoritative independently of today's config");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyProjectileNbtLoadsWithBasicSpellDefaults(GameTestHelper helper) {
        WaterBolt original = new WaterBolt(helper.getLevel(), caster(helper));
        CompoundTag legacy = original.saveWithoutId(new CompoundTag());
        legacy.remove("Charge");
        legacy.remove("Damage");
        legacy.remove("Age");
        WaterBolt restored = new WaterBolt(ModContent.WATER_BOLT.get(), helper.getLevel());
        restored.load(legacy);
        assertNear(helper, restored.charge(), 0, "Old serialized projectile defaults to zero charge");
        assertNear(helper, restored.getDamage(), MagicConfig.WATER_BOLT_DAMAGE.get(), "Old serialized projectile defaults to base damage");
        helper.assertValueEqual(restored.saveWithoutId(new CompoundTag()).getInt("Age"), 0, "Old projectile starts at age zero");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void serializedProjectileKeepsItsRemainingLifetime(GameTestHelper helper) {
        WaterBolt original = new WaterBolt(helper.getLevel(), caster(helper));
        original.setPos(helper.absoluteVec(new Vec3(8, 4, 8)));
        helper.getLevel().addFreshEntity(original);
        helper.runAfterDelay(20, () -> {
            helper.assertFalse(original.isRemoved(), "Fixture is still within its initial lifetime");
            CompoundTag saved = original.saveWithoutId(new CompoundTag());
            int elapsed = saved.getInt("Age");
            helper.assertValueEqual(elapsed, original.tickCount, "Serialization retains elapsed projectile age");
            helper.assertTrue(elapsed > 0 && elapsed < WaterBolt.LIFETIME_TICKS, "Fixture has a partially used lifetime");
            original.discard();
            saved.putUUID("UUID", UUID.randomUUID());
            WaterBolt restored = new WaterBolt(ModContent.WATER_BOLT.get(), helper.getLevel());
            restored.load(saved);
            helper.getLevel().addFreshEntity(restored);
            helper.runAfterDelay(WaterBolt.LIFETIME_TICKS - elapsed + 3, () -> {
                helper.assertTrue(restored.isRemoved(), "Serializing a projectile cannot restart its lifetime");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void fullyChargedReleaseDealsItsDamageOnCollision(GameTestHelper helper) {
        var cow = helper.spawn(EntityType.COW, new BlockPos(8, 2, 8));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        float before = cow.getHealth();
        ChantingPlayer player = caster(helper);
        player.setPos(cow.getX(), cow.getY() + 0.6 - player.getEyeHeight(), cow.getZ() - 3);
        holdStaff(player, WaterMagic.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt());
        player.releaseUsingItem();
        WaterBolt bolt = onlyBolt(helper);
        float damage = (float) (MagicConfig.WATER_BOLT_DAMAGE.get() + MagicConfig.WATER_BOLT_EXTRA_DAMAGE.get());
        assertNear(helper, bolt.getDamage(), damage, "Released charged projectile contains full damage");
        helper.runAfterDelay(8, () -> {
            assertNear(helper, cow.getHealth(), Math.max(0, before - damage), "Charged collision actually applies the snapshotted damage");
            helper.assertTrue(bolt.isRemoved(), "Charged projectile is consumed on its first hit");
            helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "The later hit does not grant another practice point");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void waterBoltDamagesOneTargetAndExpiresOnImpact(GameTestHelper helper) {
        var sheep = helper.spawn(EntityType.SHEEP, new BlockPos(8, 2, 8));
        sheep.setNoAi(true);
        sheep.setNoGravity(true);
        float previousHealth = sheep.getHealth();
        WaterBolt bolt = new WaterBolt(helper.getLevel(), caster(helper));
        bolt.setPos(sheep.getX(), sheep.getY() + 0.6, sheep.getZ() - 3);
        bolt.shoot(0, 0, 1, 1.35F, 0);
        helper.getLevel().addFreshEntity(bolt);
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(sheep.getHealth(), previousHealth - MagicConfig.WATER_BOLT_DAMAGE.get().floatValue(), "water damage");
            helper.assertTrue(bolt.isRemoved(), "Projectile must disappear after hitting");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void waterBoltStopsAtSolidBlockWithoutBreakingIt(GameTestHelper helper) {
        BlockPos wall = new BlockPos(8, 2, 8);
        helper.setBlock(wall, Blocks.STONE);
        WaterBolt bolt = new WaterBolt(helper.getLevel(), caster(helper));
        bolt.setPos(helper.absoluteVec(new Vec3(8.5, 2.5, 5.5)));
        bolt.shoot(0, 0, 1, 1.35F, 0);
        helper.getLevel().addFreshEntity(bolt);
        helper.runAfterDelay(8, () -> {
            helper.assertBlockPresent(Blocks.STONE, wall);
            helper.assertTrue(bolt.isRemoved(), "Wall must stop the projectile");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void missedProjectileHasBoundedLifetime(GameTestHelper helper) {
        WaterBolt bolt = new WaterBolt(helper.getLevel(), caster(helper));
        bolt.setPos(helper.absoluteVec(new Vec3(8, 4, 8)));
        helper.getLevel().addFreshEntity(bolt);
        helper.runAfterDelay(WaterBolt.LIFETIME_TICKS + 3, () -> {
            helper.assertTrue(bolt.isRemoved(), "Missed projectile should not remain forever");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void survivalRecipeWorksAndReturnsBucket(GameTestHelper helper) {
        CraftingInput input = CraftingInput.of(3, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.WATER_BUCKET)));
        var holder = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
        helper.assertValueEqual(holder.id(), ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "water_tome"), "recipe id");
        helper.assertTrue(holder.value().assemble(input, helper.getLevel().registryAccess()).is(ModContent.WATER_TOME.get()), "Recipe yields water tome");
        helper.assertTrue(holder.value().getRemainingItems(input).get(2).is(Items.BUCKET), "Water bucket leaves empty bucket");
        helper.succeed();
    }
}
