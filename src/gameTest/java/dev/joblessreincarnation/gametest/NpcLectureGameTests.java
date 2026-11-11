package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.NpcAnimations;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The shared lecture gesture on a dedicated-server environment: one start per NPC per interval however often it is
 * asked for, each NPC timed on its own, and no client needed to call it.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcLectureGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lectureStartsAtMostOncePerIntervalForEachNpc(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoxyDisplayEntity first = NpcDisplayRegistry.spawnRoxy(level, helper.absoluteVec(new Vec3(1.5, 2.0, 1.5)), 0.0F);
        RoxyDisplayEntity second = NpcDisplayRegistry.spawnRoxy(level, helper.absoluteVec(new Vec3(3.5, 2.0, 1.5)), 0.0F);
        helper.assertTrue(first != null && second != null, "Both display NPCs spawn");
        long start = level.getGameTime();
        helper.assertTrue(NpcAnimations.playLecture(first), "The first request starts the gesture");
        helper.assertFalse(NpcAnimations.playLecture(first), "A second request in the same tick is ignored");
        helper.assertTrue(NpcAnimations.playLecture(second), "Another NPC has its own interval");
        int interval = NpcAnimations.LECTURE_INTERVAL_TICKS;
        helper.runAfterDelay(interval - 1, () -> {
            long elapsed = level.getGameTime() - start;
            helper.assertTrue(elapsed < interval, "Checked inside the interval (" + elapsed + " ticks)");
            helper.assertFalse(NpcAnimations.playLecture(first), "Still ignored " + elapsed + " ticks after the start");
        });
        helper.runAfterDelay(interval, () -> {
            long elapsed = level.getGameTime() - start;
            helper.assertTrue(elapsed >= interval, "Checked once the interval has passed (" + elapsed + " ticks)");
            helper.assertTrue(NpcAnimations.playLecture(first), "Starts again " + elapsed + " ticks later; ignored requests did not push it back");
            helper.assertFalse(NpcAnimations.playLecture(first), "The new start opens a new interval");
            first.discard();
            second.discard();
            helper.succeed();
        });
    }

    private NpcLectureGameTests() {}
}
