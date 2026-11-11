package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.training.SwordTrainingProgress.Phase;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** State and NBT contracts; real attacks, attachment cloning and tutor interaction have separate checks. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SwordTrainingProgressGameTests {
    @GameTest(template = "empty")
    public static void lessonRequiresOpeningTwentyHitsAndOneRecognition(GameTestHelper helper) {
        SwordTrainingProgress progress = new SwordTrainingProgress();
        helper.assertFalse(progress.recordHit(), "Hitting before the lesson cannot bank practice");
        helper.assertFalse(progress.recognize(), "An unopened lesson cannot be recognized");
        helper.assertValueEqual(progress.phase(), Phase.NOT_STARTED, "Rejected actions leave the lesson unopened");
        helper.assertTrue(progress.start(), "The first consultation opens the lesson");
        helper.assertFalse(progress.recognize(), "Opening alone cannot complete the lesson");
        for (int hit = 0; hit < 19; hit++)
            helper.assertTrue(progress.recordHit(), "Each eligible hit before the target advances practice");
        helper.assertValueEqual(progress.hits(), 19, "Nineteen hits remain one short of the fixed target");
        helper.assertValueEqual(progress.phase(), Phase.PRACTICING, "The nineteenth hit still requires practice");
        helper.assertFalse(progress.recognize(), "Nineteen hits cannot be recognized");
        helper.assertFalse(progress.start(), "Consulting again cannot restart the lesson");
        helper.assertValueEqual(progress.hits(), 19, "Repeated opening preserves completed practice");
        helper.assertTrue(progress.recordHit(), "The twentieth hit reaches the target");
        helper.assertValueEqual(progress.phase(), Phase.READY, "Finishing practice waits for Paul's recognition");
        helper.assertFalse(progress.recognized(), "Practice alone grants no recognition");
        helper.assertFalse(progress.recordHit(), "Practice cannot exceed the target while awaiting recognition");
        helper.assertTrue(progress.recognize(), "A ready lesson is recognized exactly once");
        helper.assertValueEqual(progress.phase(), Phase.DONE, "Recognition completes the lesson");
        helper.assertFalse(progress.start() || progress.recordHit() || progress.recognize(),
                "A completed lesson cannot reopen, gain hits or repeat recognition");
        helper.assertValueEqual(progress.hits(), 20, "The finished lesson retains exactly twenty hits");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nbtRoundTripPreservesEveryReachablePhase(GameTestHelper helper) {
        SwordTrainingProgress[] states = {
                new SwordTrainingProgress(), new SwordTrainingProgress(true, 7, false),
                new SwordTrainingProgress(true, 20, false), new SwordTrainingProgress(true, 20, true)
        };
        for (SwordTrainingProgress original : states) {
            SwordTrainingProgress restored = decode(SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow());
            helper.assertValueEqual(restored.phase(), original.phase(), "NBT preserves the lesson phase");
            helper.assertValueEqual(restored.hits(), original.hits(), "NBT preserves exact practice within the phase");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingFieldsDefaultWithoutInventingPracticeOrRecognition(GameTestHelper helper) {
        SwordTrainingProgress empty = decode(new CompoundTag());
        helper.assertValueEqual(empty.phase(), Phase.NOT_STARTED, "An absent legacy record has no lesson");
        helper.assertValueEqual(empty.hits(), 0, "An absent legacy record has no banked hits");
        helper.assertFalse(empty.recognized(), "An absent legacy record has no recognition");
        CompoundTag opening = new CompoundTag();
        opening.putBoolean("started", true);
        SwordTrainingProgress started = decode(opening);
        helper.assertValueEqual(started.phase(), Phase.PRACTICING, "Missing hits and recognition permit a newly opened lesson");
        helper.assertValueEqual(started.hits(), 0, "Missing hits default to zero");
        opening.putInt("hits", 20);
        helper.assertValueEqual(decode(opening).phase(), Phase.READY, "Missing recognition does not auto-complete finished practice");
        CompoundTag orphaned = new CompoundTag();
        orphaned.putInt("hits", 20);
        orphaned.putBoolean("recognized", true);
        SwordTrainingProgress unopened = decode(orphaned);
        helper.assertValueEqual(unopened.phase(), Phase.NOT_STARTED, "Missing started defaults to an unopened lesson");
        helper.assertValueEqual(unopened.hits(), 0, "A missing opening cannot retain orphaned practice");
        helper.assertFalse(unopened.recognized(), "A missing opening cannot retain orphaned recognition");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void corruptNbtCannotBypassOpeningOrThePracticeTarget(GameTestHelper helper) {
        SwordTrainingProgress unopened = decode(saved(false, Integer.MAX_VALUE, true));
        helper.assertValueEqual(unopened.phase(), Phase.NOT_STARTED, "Unopened corrupt data stays unopened");
        helper.assertValueEqual(unopened.hits(), 0, "Unopened corrupt data cannot bank hits");
        helper.assertFalse(unopened.recognized(), "Unopened corrupt data cannot retain recognition");
        SwordTrainingProgress negative = decode(saved(true, Integer.MIN_VALUE, true));
        helper.assertValueEqual(negative.hits(), 0, "Negative saved hits clamp without overflow");
        helper.assertValueEqual(negative.phase(), Phase.PRACTICING, "Negative practice cannot be recognized");
        helper.assertFalse(negative.recognized(), "An invalid recognition flag is cleared");
        SwordTrainingProgress premature = decode(saved(true, 19, true));
        helper.assertFalse(premature.recognized(), "A saved recognition one hit short is cleared");
        helper.assertTrue(premature.recordHit() && premature.recognize(), "Corrected data can still complete through the normal boundary");
        SwordTrainingProgress oversized = decode(saved(true, Integer.MAX_VALUE, false));
        helper.assertValueEqual(oversized.hits(), 20, "Oversized saved hits clamp to the fixed target");
        helper.assertValueEqual(oversized.phase(), Phase.READY, "Clamping cannot fabricate recognition");
        SwordTrainingProgress completed = decode(saved(true, Integer.MAX_VALUE, true));
        helper.assertValueEqual(completed.hits(), 20, "A recognized lesson also clamps excessive hits");
        helper.assertValueEqual(completed.phase(), Phase.DONE, "A full recognized lesson retains completion");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void decodingTheSameNbtCreatesIndependentMutableLessons(GameTestHelper helper) {
        SwordTrainingProgress original = new SwordTrainingProgress(true, 19, false);
        Tag saved = SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        SwordTrainingProgress first = decode(saved);
        SwordTrainingProgress second = decode(saved);
        helper.assertTrue(first.recordHit() && first.recognize(), "One restored lesson can finish independently");
        helper.assertValueEqual(original.hits(), 19, "Mutating restored progress does not change its source");
        helper.assertValueEqual(second.hits(), 19, "Another decode of the same tag keeps its own practice");
        helper.assertValueEqual(second.phase(), Phase.PRACTICING, "Recognition does not leak between decoded instances");
        helper.assertValueEqual(decode(saved).phase(), Phase.PRACTICING, "Mutating a decoded lesson does not mutate the saved tag");
        helper.assertTrue(second.recordHit(), "The other restored lesson can advance separately");
        helper.assertFalse(second.recognized(), "Reaching the target does not inherit another instance's recognition");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void waterAndFireSaveFieldsCannotConferASwordLesson(GameTestHelper helper) {
        CompoundTag legacyMagic = new CompoundTag();
        legacyMagic.putInt("target_hits", 20);
        legacyMagic.putBoolean("charged_target_hit", true);
        legacyMagic.putBoolean("claimed", true);
        legacyMagic.putInt("fire_target_hits", 20);
        SwordTrainingProgress sword = decode(legacyMagic);
        helper.assertValueEqual(sword.phase(), Phase.NOT_STARTED, "Water and fire facts do not open a sword lesson");
        helper.assertTrue(sword.start(), "Sword training still requires its own opening");
        helper.assertValueEqual(sword.hits(), 0, "Water and fire hits cannot become sword practice");
        helper.assertFalse(sword.recognize(), "A claimed magic reward cannot recognize a sword lesson");
        helper.succeed();
    }

    private static CompoundTag saved(boolean started, int hits, boolean recognized) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("started", started);
        tag.putInt("hits", hits);
        tag.putBoolean("recognized", recognized);
        return tag;
    }

    private static SwordTrainingProgress decode(Tag tag) {
        return SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private SwordTrainingProgressGameTests() {}
}
