package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.magic.ManaGrowthRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pure state contracts using independent rules, with no global configuration changes.
 * Payments here are numeric fixtures; spell authorization, receipt uniqueness, entity lifecycle,
 * real offline time and the private network path are verified by separate integration checks.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaGrowthStateGameTests {
    private static final ManaGrowthRules DEFAULT = new ManaGrowthRules(true, 100, 100, 2, 6000);

    @GameTest(template = "empty")
    public static void fourPaidCastsEarnTwoWithoutMutatingBoundMana(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        ManaData mana = new ManaData(4, 100, 80, 7);
        growth.bindMana(mana);
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 100, 76, 24), 0, "First paid cast arms the round");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 76, 52, 24), 0, "Second paid cast remains above the low line");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 52, 28, 24), 0, "Enough spending alone does not finish");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 28, 4, 24), 2, "Fourth paid cast crosses the low line");
        helper.assertValueEqual(growth.earnedBonus(), 2, "Exactly two capacity is earned");
        helper.assertValueEqual(growth.effectiveMaximum(DEFAULT), 102, "The effective maximum includes the award");
        helper.assertValueEqual(growth.cooldownRemaining(), 6000, "The complete configured cooldown starts");
        assertNoRound(helper, growth, "Completion");
        helper.assertValueEqual(mana.mana(), 4, "The state award does not replenish current mana");
        helper.assertValueEqual(mana.maximum(), 100, "Binding is identity tracking, not direct gauge mutation");
        helper.assertValueEqual(mana.recoveryDelay(), 80, "The award does not change recovery delay");
        helper.assertValueEqual(mana.cooldown(), 7, "The award does not change spell cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void startingBelowHighLineCannotArmButExactHighLineCan(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        growth.recordPayment(DEFAULT, 79, 55, 24);
        growth.recordPayment(DEFAULT, 55, 31, 24);
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 31, 7, 24), 0, "Low-start spending cannot earn a round");
        assertNoRound(helper, growth, "Below the 80-percent opening line");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 80, 56, 24), 0, "Exactly 80 can open a new round");
        helper.assertValueEqual(growth.roundMaximum(), 100, "The round keeps its starting capacity");
        growth.recordPayment(DEFAULT, 56, 32, 24);
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 32, 20, 12), 2, "Exactly 60 paid and exactly 20 remaining qualifies");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lowCrossWithoutEnoughPaidCannotUseAnUnpaidResourceDrop(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        growth.recordPayment(DEFAULT, 100, 76, 24);
        // The numeric gap models a resource change outside the authorized payment path.
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 21, 20, 1), 0, "An unpaid drop to the low line cannot replace real spending");
        helper.assertValueEqual(growth.paidInRound(), 25, "Only the two declared payments count");
        // A later recovery is allowed, but the actual paid total must still reach the requirement.
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 80, 46, 34), 0, "Fifty-nine paid is still insufficient");
        helper.assertValueEqual(growth.paidInRound(), 59, "Unpaid resource changes never enter the accumulator");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 21, 20, 1), 2, "A fresh low-line crossing with exactly sixty paid qualifies");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sufficientPaidStillRequiresACrossFromAboveTheLowLine(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 100, 40, 60), 0, "Sixty paid above the low line cannot settle");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 20, 10, 10), 0, "Starting on the low line is not a downward crossing");
        helper.assertValueEqual(growth.earnedBonus(), 0, "No award occurs merely for being below the line");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 21, 20, 1), 2, "Crossing from strictly above to exactly the low line settles");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedPaymentsNeitherArmNorAdvanceARound(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        int[][] invalid = {
                {100, 76, 23}, {100, 100, 0}, {100, 101, -1},
                {101, 77, 24}, {24, -1, 25}, {Integer.MAX_VALUE, 0, Integer.MAX_VALUE}
        };
        for (int[] payment : invalid) {
            helper.assertValueEqual(growth.recordPayment(DEFAULT, payment[0], payment[1], payment[2]), 0,
                    "An inconsistent, nonpositive or out-of-range payment cannot earn");
            assertNoRound(helper, growth, "Rejected initial payment");
        }
        growth.recordPayment(DEFAULT, 100, 76, 24);
        for (int[] payment : invalid) growth.recordPayment(DEFAULT, payment[0], payment[1], payment[2]);
        helper.assertValueEqual(growth.paidInRound(), 24, "Rejected payments do not advance the valid half-round");
        helper.assertValueEqual(growth.roundMaximum(), 100, "Rejected payments do not change the round capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recoveryWithinAnActiveRoundPreservesOnlyAccumulatedPaidMana(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData();
        growth.recordPayment(DEFAULT, 100, 76, 24);
        growth.recordPayment(DEFAULT, 90, 66, 24);
        helper.assertValueEqual(growth.paidInRound(), 48, "Recovery between casts neither pays nor resets a round");
        helper.assertValueEqual(growth.roundMaximum(), 100, "Recovery leaves the round capacity fixed");
        growth.recordPayment(DEFAULT, 66, 42, 24);
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 42, 18, 24), 2, "Real payments after recovery still complete normally");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fractionalThresholdsRoundInTheDocumentedDirections(GameTestHelper helper) {
        ManaGrowthRules rules = new ManaGrowthRules(true, 101, 100, 2, 6000);
        helper.assertValueEqual(ManaGrowthRules.highThreshold(101), 81, "The high threshold rounds upward");
        helper.assertValueEqual(ManaGrowthRules.lowThreshold(101), 20, "The low threshold rounds downward");
        helper.assertValueEqual(ManaGrowthRules.requiredSpend(101), 61, "Required spending rounds upward");
        ManaGrowthData growth = new ManaGrowthData();
        growth.recordPayment(rules, 80, 50, 30);
        assertNoRound(helper, growth, "Eighty is below the rounded high threshold");
        growth.recordPayment(rules, 81, 50, 31);
        growth.recordPayment(rules, 50, 21, 29);
        helper.assertValueEqual(growth.paidInRound(), 60, "Sixty paid remains below the rounded requirement");
        helper.assertValueEqual(growth.earnedBonus(), 0, "Rounded thresholds cannot award early");
        helper.assertValueEqual(growth.recordPayment(rules, 21, 20, 1), 2, "Exactly sixty-one paid at the rounded low line qualifies");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onePointBaseCapacityHasAReachableRound(GameTestHelper helper) {
        ManaGrowthRules rules = new ManaGrowthRules(true, 1, 100, 2, 6000);
        helper.assertValueEqual(ManaGrowthRules.highThreshold(1), 1, "The minimum capacity still requires a full opening gauge");
        helper.assertValueEqual(ManaGrowthRules.lowThreshold(1), 0, "The minimum low threshold is zero");
        helper.assertValueEqual(ManaGrowthRules.requiredSpend(1), 1, "The minimum round requires one real payment");
        ManaGrowthData growth = new ManaGrowthData();
        helper.assertValueEqual(growth.recordPayment(rules, 1, 0, 1), 2, "The minimum-size paid round can complete");
        helper.assertValueEqual(growth.effectiveMaximum(rules), 3, "The award is added without a zero or overflow maximum");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cooldownRejectsPreloadedRoundsAndExpiresOnTheFinalEligibleTick(GameTestHelper helper) {
        ManaGrowthData growth = completed();
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 102, 0, 102), 0, "Spending during cooldown cannot preload another round");
        assertNoRound(helper, growth, "Cooldown spending");
        for (int tick = 0; tick < 5999; tick++) growth.tick(DEFAULT, true);
        helper.assertValueEqual(growth.cooldownRemaining(), 1, "One eligible tick remains");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 102, 0, 102), 0, "The final cooldown tick still blocks a round");
        growth.tick(DEFAULT, true);
        helper.assertValueEqual(growth.cooldownRemaining(), 0, "Exactly six thousand eligible ticks expire cooldown");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 21, 0, 21), 0, "A low payment after expiry cannot use prior cooldown spending");
        helper.assertValueEqual(growth.earnedBonus(), 2, "Cooldown spending produced no hidden award");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 102, 0, 102), 2, "A fresh qualifying round is possible after expiry");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void serializationWithoutOnlineTicksCannotConsumeCooldown(GameTestHelper helper) {
        ManaGrowthData growth = completed();
        for (int save = 0; save < 20; save++) {
            growth = roundTrip(growth);
            growth.refreshRules(DEFAULT);
        }
        helper.assertValueEqual(growth.cooldownRemaining(), 6000, "Saving and loading without ticks does not simulate elapsed online time");
        for (int tick = 0; tick < 100; tick++) growth.tick(DEFAULT, true);
        helper.assertValueEqual(growth.cooldownRemaining(), 5900, "Only explicitly delivered eligible ticks reduce cooldown");
        helper.assertValueEqual(roundTrip(growth).cooldownRemaining(), 5900, "Remaining cooldown survives the next save");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ineligibleTicksClearTheHalfRoundAndFreezeCooldown(GameTestHelper helper) {
        ManaGrowthData partial = new ManaGrowthData();
        partial.recordPayment(DEFAULT, 100, 76, 24);
        partial.tick(DEFAULT, false);
        assertNoRound(helper, partial, "Creative or spectator eligibility loss");
        helper.assertValueEqual(partial.recordPayment(DEFAULT, 76, 0, 76), 0, "Returning with a low opening value cannot resume the cancelled round");
        ManaGrowthData cooling = completed();
        for (int tick = 0; tick < 100; tick++) cooling.tick(DEFAULT, false);
        helper.assertValueEqual(cooling.cooldownRemaining(), 6000, "Ineligible online ticks do not consume growth cooldown");
        helper.assertValueEqual(cooling.earnedBonus(), 2, "Eligibility loss preserves the earned award");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disabledGrowthPreservesCapacityAndCooldownButCancelsHalfRounds(GameTestHelper helper) {
        ManaGrowthRules disabled = new ManaGrowthRules(false, 100, 100, 2, 6000);
        ManaGrowthData partial = new ManaGrowthData();
        partial.recordPayment(DEFAULT, 100, 76, 24);
        partial.refreshRules(disabled);
        assertNoRound(helper, partial, "Disabling growth");
        ManaGrowthData cooling = completed();
        for (int tick = 0; tick < 100; tick++) cooling.tick(disabled, true);
        helper.assertValueEqual(cooling.effectiveMaximum(disabled), 102, "Disabling new growth does not remove existing capacity");
        helper.assertValueEqual(cooling.cooldownRemaining(), 6000, "Disabled growth freezes its cooldown");
        helper.assertValueEqual(cooling.recordPayment(disabled, 102, 0, 102), 0, "Disabled payments do not earn");
        cooling.refreshRules(DEFAULT);
        helper.assertValueEqual(cooling.earnedBonus(), 2, "Re-enabling does not produce a deferred award");
        helper.assertValueEqual(cooling.cooldownRemaining(), 6000, "Re-enabling keeps the remaining cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void finalAwardUsesOnlyRemainingCapacityAndCapNeverAwardsAgain(GameTestHelper helper) {
        ManaGrowthRules rules = new ManaGrowthRules(true, 100, 3, 2, 0);
        ManaGrowthData growth = new ManaGrowthData();
        helper.assertValueEqual(growth.recordPayment(rules, 100, 0, 100), 2, "The first round grants its full two");
        helper.assertValueEqual(growth.recordPayment(rules, 102, 0, 102), 1, "The second round grants only the final one");
        helper.assertValueEqual(growth.effectiveMaximum(rules), 103, "Effective capacity stops exactly at the configured cap");
        for (int repeat = 0; repeat < 10; repeat++)
            helper.assertValueEqual(growth.recordPayment(rules, 103, 0, 103), 0, "Further payments at the cap cannot award or notify");
        helper.assertValueEqual(growth.earnedBonus(), 3, "The earned value does not grow past this configured limit");
        assertNoRound(helper, growth, "The reached cap");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void temporaryCapReductionPreservesEarnedBonusForLaterRestoration(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData(50, 321, 0, 0, DEFAULT.signature());
        ManaData mana = new ManaData(47, 150, 20, 1);
        growth.bindMana(mana);
        ManaGrowthRules reduced = new ManaGrowthRules(true, 100, 10, 2, 6000);
        growth.refreshRules(reduced);
        helper.assertValueEqual(growth.earnedBonus(), 50, "A temporary low cap does not erase earned history");
        helper.assertValueEqual(growth.effectiveMaximum(reduced), 110, "Only the configured portion is effective");
        helper.assertValueEqual(roundTrip(growth).earnedBonus(), 50, "Saving under the reduced cap preserves all earned history");
        growth.refreshRules(DEFAULT);
        helper.assertValueEqual(growth.effectiveMaximum(DEFAULT), 150, "Raising the cap restores the saved earned capacity");
        helper.assertValueEqual(growth.cooldownRemaining(), 321, "Neither cap change resets cooldown");
        helper.assertValueEqual(mana.mana(), 47, "Changing the effective cap in state does not refill the bound gauge");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyTrainingRuleChangeCancelsTheRoundButKeepsRemainingCooldown(GameTestHelper helper) {
        ManaGrowthRules[] changed = {
                new ManaGrowthRules(false, 100, 100, 2, 6000),
                new ManaGrowthRules(true, 101, 100, 2, 6000),
                new ManaGrowthRules(true, 100, 99, 2, 6000),
                new ManaGrowthRules(true, 100, 100, 3, 6000),
                new ManaGrowthRules(true, 100, 100, 2, 200)
        };
        for (ManaGrowthRules rules : changed) {
            ManaGrowthData partial = new ManaGrowthData();
            partial.recordPayment(DEFAULT, 100, 76, 24);
            helper.assertFalse(rules.signature().equals(DEFAULT.signature()), "Each training rule participates in the saved signature");
            partial.refreshRules(rules);
            assertNoRound(helper, partial, "A changed training configuration");
            ManaGrowthData cooling = completed();
            cooling.refreshRules(rules);
            helper.assertValueEqual(cooling.cooldownRemaining(), 6000, "A rule edit cannot restart or shorten an existing cooldown");
            helper.assertValueEqual(cooling.earnedBonus(), 2, "A rule edit cannot erase already earned history");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedSignatureDetectsOfflineConfigurationChanges(GameTestHelper helper) {
        ManaGrowthData partial = new ManaGrowthData();
        partial.recordPayment(DEFAULT, 100, 76, 24);
        ManaGrowthData same = roundTrip(partial);
        same.refreshRules(new ManaGrowthRules(true, 100, 100, 2, 6000));
        helper.assertValueEqual(same.paidInRound(), 24, "An identical configuration preserves the loaded half-round");
        ManaGrowthData changed = roundTrip(partial);
        changed.refreshRules(new ManaGrowthRules(true, 100, 100, 2, 3000));
        assertNoRound(helper, changed, "A configuration change while unloaded");
        helper.assertValueEqual(changed.configSignature(), new ManaGrowthRules(true, 100, 100, 2, 3000).signature(),
                "The loaded state records the current full signature");
        ManaGrowthData mismatchedMaximum = new ManaGrowthData(0, 0, 101, 24, DEFAULT.signature());
        mismatchedMaximum.refreshRules(DEFAULT);
        assertNoRound(helper, mismatchedMaximum, "A saved round with an inconsistent effective capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nbtRoundTripPreservesAnUnfinishedPaidRoundAndItsFirstBinding(GameTestHelper helper) {
        ManaGrowthData original = new ManaGrowthData();
        original.bindMana(new Object());
        original.recordPayment(DEFAULT, 100, 76, 24);
        ManaGrowthData restored = roundTrip(original);
        restored.bindMana(new Object());
        restored.refreshRules(DEFAULT);
        helper.assertValueEqual(restored.roundMaximum(), 100, "The saved round maximum survives NBT");
        helper.assertValueEqual(restored.paidInRound(), 24, "The saved paid accumulator survives NBT and first runtime binding");
        helper.assertValueEqual(restored.configSignature(), DEFAULT.signature(), "The configuration signature survives NBT");
        restored.recordPayment(DEFAULT, 76, 52, 24);
        restored.recordPayment(DEFAULT, 52, 28, 24);
        helper.assertValueEqual(restored.recordPayment(DEFAULT, 28, 4, 24), 2, "The restored half-round can finish with remaining payments");
        helper.assertValueEqual(original.earnedBonus(), 0, "The decoded state is independent of its original object");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyAndCorruptNbtUseIndependentSafetyBoundsWithoutIntegerWrap(GameTestHelper helper) {
        ManaGrowthData legacy = decode(new CompoundTag());
        helper.assertValueEqual(legacy.earnedBonus(), 0, "An older save without growth data starts without a bonus");
        helper.assertValueEqual(legacy.cooldownRemaining(), 0, "An older save starts without a cooldown");
        helper.assertValueEqual(legacy.effectiveMaximum(DEFAULT), 100, "An older save uses the configured base capacity");
        assertNoRound(helper, legacy, "An empty legacy record");

        CompoundTag negative = new CompoundTag();
        negative.putLong("earned_bonus", -50L);
        negative.putLong("cooldown_remaining", -100L);
        negative.putLong("round_maximum", 100L);
        negative.putLong("paid_in_round", -24L);
        negative.putString("config_signature", DEFAULT.signature());
        ManaGrowthData clamped = decode(negative);
        helper.assertValueEqual(clamped.earnedBonus(), 0, "Negative earned history clamps to zero");
        helper.assertValueEqual(clamped.cooldownRemaining(), 0, "Negative cooldown clamps to zero");
        assertNoRound(helper, clamped, "Negative saved spending");

        CompoundTag oversized = new CompoundTag();
        oversized.putLong("earned_bonus", Long.MAX_VALUE);
        oversized.putLong("cooldown_remaining", Long.MAX_VALUE);
        oversized.putLong("round_maximum", Long.MAX_VALUE);
        oversized.putLong("paid_in_round", Long.MAX_VALUE);
        oversized.putString("config_signature", DEFAULT.signature());
        ManaGrowthData bounded = decode(oversized);
        helper.assertValueEqual(bounded.earnedBonus(), 100000, "Oversized long rewards reach the safety bound without int wrap");
        helper.assertValueEqual(bounded.cooldownRemaining(), 1200000, "Oversized long cooldown reaches its independent safety bound");
        assertNoRound(helper, bounded, "An oversized or cooling saved round");
        ManaGrowthRules maximum = new ManaGrowthRules(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        helper.assertValueEqual(bounded.effectiveMaximum(maximum), 200000, "The combined maximum remains bounded and positive");
        helper.assertValueEqual(ManaGrowthRules.highThreshold(200000), 160000, "The high threshold uses overflow-safe arithmetic");
        helper.assertValueEqual(ManaGrowthRules.requiredSpend(200000), 120000, "The spending threshold uses overflow-safe arithmetic");
        helper.assertValueEqual(ManaGrowthRules.lowThreshold(200000), 40000, "The low threshold uses overflow-safe arithmetic");
        assertNoRound(helper, new ManaGrowthData(0, 0, 100, 24, ""), "An unsigned saved half-round");
        assertNoRound(helper, new ManaGrowthData(0, 1, 100, 24, DEFAULT.signature()), "A half-round saved during cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyAReplacementManaIdentityCancelsAnExistingHalfRound(GameTestHelper helper) {
        ManaGrowthData growth = new ManaGrowthData(0, 0, 100, 24, DEFAULT.signature());
        Object identity = new Object();
        growth.bindMana(identity);
        helper.assertValueEqual(growth.paidInRound(), 24, "The first runtime binding preserves a valid loaded half-round");
        growth.bindMana(identity);
        helper.assertValueEqual(growth.paidInRound(), 24, "Binding the identical attachment again preserves progress");
        growth.bindMana(new Object());
        assertNoRound(helper, growth, "An externally replaced mana attachment");
        helper.assertValueEqual(growth.recordPayment(DEFAULT, 76, 0, 76), 0, "A replacement cannot finish the cancelled round from a low opening gauge");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellingARoundPreservesEarnedHistoryAndRemainingCooldown(GameTestHelper helper) {
        ManaGrowthData partial = new ManaGrowthData(2, 0, 102, 24, DEFAULT.signature());
        partial.cancelRound();
        assertNoRound(helper, partial, "Explicit half-round cancellation");
        helper.assertValueEqual(partial.earnedBonus(), 2, "Cancellation preserves permanent earned history");
        helper.assertValueEqual(partial.configSignature(), DEFAULT.signature(), "Cancellation preserves the configuration signature");
        ManaGrowthData cooling = new ManaGrowthData(2, 4321, 0, 0, DEFAULT.signature());
        cooling.cancelRound();
        helper.assertValueEqual(cooling.cooldownRemaining(), 4321, "Cancellation does not wipe remaining cooldown");
        helper.assertValueEqual(roundTrip(cooling).earnedBonus(), 2, "Retained history remains serializable");
        helper.succeed();
    }

    private static ManaGrowthData completed() {
        ManaGrowthData growth = new ManaGrowthData();
        growth.recordPayment(DEFAULT, 100, 76, 24);
        growth.recordPayment(DEFAULT, 76, 52, 24);
        growth.recordPayment(DEFAULT, 52, 28, 24);
        growth.recordPayment(DEFAULT, 28, 4, 24);
        return growth;
    }

    private static ManaGrowthData roundTrip(ManaGrowthData growth) {
        return ManaGrowthData.CODEC.parse(NbtOps.INSTANCE,
                ManaGrowthData.CODEC.encodeStart(NbtOps.INSTANCE, growth).getOrThrow()).getOrThrow();
    }

    private static ManaGrowthData decode(CompoundTag tag) {
        return ManaGrowthData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static void assertNoRound(GameTestHelper helper, ManaGrowthData growth, String cause) {
        helper.assertValueEqual(growth.roundMaximum(), 0, cause + " clears the round maximum");
        helper.assertValueEqual(growth.paidInRound(), 0, cause + " clears accumulated spending");
    }
}