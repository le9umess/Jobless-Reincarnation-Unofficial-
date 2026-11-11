package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.network.CourseRecordsResponsePayload;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.CourseRecordSnapshot;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.training.TrainingProgress;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Read-only course-record snapshots never create, mutate, or mix a player's existing progress. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CourseRecordsGameTests {
    private static final class Reader extends FakePlayer {
        Reader(ServerLevel level, String name) { super(level, new GameProfile(UUID.randomUUID(), name)); }
    }

    @GameTest(template = "empty")
    public static void emptyReaderGetsExplicitZeroRecordsWithoutCreatingAttachments(GameTestHelper h) {
        Reader reader = new Reader(h.getLevel(), "EmptyCourseReader");
        h.assertFalse(reader.hasData(ModAttachments.KNOWLEDGE), "Empty reader starts without knowledge");
        h.assertFalse(reader.hasData(ModAttachments.TRAINING), "Empty reader starts without water/fire progress");
        h.assertFalse(reader.hasData(ModAttachments.SWORD_TRAINING), "Empty reader starts without sword progress");
        CourseRecordsResponsePayload result = CourseRecordsResponsePayload.fromPlayer(7, reader);
        h.assertValueEqual(result.nonce(), 7, "Nonce is echoed");
        h.assertValueEqual(result.water().primary(), 0, "Empty water casts are zero");
        h.assertValueEqual(result.water().secondary(), 0, "Empty water hits are zero");
        h.assertValueEqual(result.fire().primary(), 0, "Empty fire casts are zero");
        h.assertValueEqual(result.sword().stage(), SwordTrainingProgress.Phase.NOT_STARTED.ordinal(), "Empty sword is not started");
        h.assertFalse(result.water().completed() || result.fire().completed() || result.sword().completed(), "Empty records are incomplete");
        h.assertFalse(reader.hasData(ModAttachments.KNOWLEDGE), "Read does not create knowledge");
        h.assertFalse(reader.hasData(ModAttachments.TRAINING), "Read does not create training progress");
        h.assertFalse(reader.hasData(ModAttachments.SWORD_TRAINING), "Read does not create sword progress");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void allThreeRecordsExposeExistingFactsAndDerivedStages(GameTestHelper h) {
        Reader reader = new Reader(h.getLevel(), "CourseReader");
        MagicKnowledge knowledge = reader.getData(ModAttachments.KNOWLEDGE);
        knowledge.learn(WaterMagic.SPELL);
        knowledge.learn(FireMagic.SPELL);
        for (int i = 0; i < 5; i++) knowledge.practice(WaterMagic.SPELL);
        for (int i = 0; i < 5; i++) knowledge.practice(FireMagic.SPELL);
        reader.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, false, 3));
        reader.setData(ModAttachments.SWORD_TRAINING, new SwordTrainingProgress(true, SwordTrainingProgress.GOAL, false));

        CourseRecordsResponsePayload result = CourseRecordsResponsePayload.fromPlayer(11, reader);
        CourseRecordSnapshot water = result.water(), fire = result.fire(), sword = result.sword();
        h.assertValueEqual(water.primary(), 5, "Water casts are read from knowledge");
        h.assertValueEqual(water.secondary(), 3, "Water target hits are read from training");
        h.assertTrue(water.flag() && !water.completed(), "Water shaping and unclaimed card stay distinct");
        h.assertValueEqual(fire.primary(), 5, "Fire casts are read from knowledge");
        h.assertValueEqual(fire.secondary(), 3, "Fire target hits are read from training");
        h.assertTrue(fire.completed(), "Fire completion is derived from existing facts");
        h.assertValueEqual(sword.stage(), SwordTrainingProgress.Phase.READY.ordinal(), "Sword remains awaiting recognition");
        h.assertFalse(sword.completed(), "Snapshot does not recognize sword lesson");
        h.assertValueEqual(reader.getData(ModAttachments.SWORD_TRAINING).phase(), SwordTrainingProgress.Phase.READY,
                "Snapshot does not mutate sword phase");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void snapshotsArePrivateAndReadOnlyAcrossTwoPlayers(GameTestHelper h) {
        Reader a = new Reader(h.getLevel(), "CourseA"), b = new Reader(h.getLevel(), "CourseB");
        a.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        a.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        a.setData(ModAttachments.TRAINING, new TrainingProgress(1, false, false, 0));
        b.getData(ModAttachments.SWORD_TRAINING).start();
        Map<?, ?> beforeA = a.getData(ModAttachments.KNOWLEDGE).snapshot();
        Map<?, ?> beforeB = b.getData(ModAttachments.SWORD_TRAINING).phase() == SwordTrainingProgress.Phase.PRACTICING
                ? Map.of("phase", "practicing", "hits", b.getData(ModAttachments.SWORD_TRAINING).hits()) : Map.of();
        CourseRecordsResponsePayload ra = CourseRecordsResponsePayload.fromPlayer(1, a);
        CourseRecordsResponsePayload rb = CourseRecordsResponsePayload.fromPlayer(2, b);
        h.assertValueEqual(ra.water().primary(), 1, "A sees own water cast");
        h.assertValueEqual(ra.sword().stage(), SwordTrainingProgress.Phase.NOT_STARTED.ordinal(), "A cannot see B sword course");
        h.assertValueEqual(rb.water().primary(), 0, "B cannot see A water cast");
        h.assertValueEqual(rb.sword().stage(), SwordTrainingProgress.Phase.PRACTICING.ordinal(), "B sees own sword course");
        h.assertValueEqual(a.getData(ModAttachments.KNOWLEDGE).snapshot(), beforeA, "A data is unchanged");
        h.assertValueEqual(b.getData(ModAttachments.SWORD_TRAINING).phase(), SwordTrainingProgress.Phase.PRACTICING, "B data is unchanged");
        h.assertValueEqual(beforeB.get("phase"), "practicing", "B fixture retained its original state");
        h.succeed();
    }

    private CourseRecordsGameTests() {}
}
