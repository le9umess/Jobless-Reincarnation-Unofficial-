package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.npc.SylphietteFirstLesson;
import dev.joblessreincarnation.npc.SylphietteLessonVisuals;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The tree lesson is personal, transient until success, and uses the real advancement as its durable result. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SylphietteFirstLessonGameTests {
    private static final class Reader extends ServerPlayer {
        Reader(ServerLevel level, String name) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), name), net.minecraft.server.level.ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) {}
            };
            getAbilities().instabuild = false;
        }
    }

    private static SylphietteEntity sylph(GameTestHelper helper, double x, double z) {
        SylphietteEntity entity = ModContent.SYLPHIETTE.get().create(helper.getLevel());
        if (entity == null) throw new IllegalStateException("Sylphiette factory returned null");
        net.minecraft.world.phys.Vec3 position = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, 2, z));
        entity.moveTo(position.x, position.y, position.z, 180, 0);
        entity.setNoAi(true);
        entity.setNoGravity(true);
        if (!helper.getLevel().addFreshEntity(entity)) throw new IllegalStateException("Sylphiette did not join");
        return entity;
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void successfulWaterReleaseFinishesOnlyTheActivePlayersTreeLesson(GameTestHelper helper) {
        Reader reader = new Reader(helper.getLevel(), "LessonReader");
        reader.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(5.5, 2, 2.5)));
        helper.getLevel().addNewPlayer(reader);
        SylphietteEntity sylphiette = sylph(helper, 5.5, 4.5);
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        reader.setData(ModAttachments.KNOWLEDGE, knowledge);
        helper.assertTrue(SylphietteFirstLesson.beginObservation(reader, sylphiette), "Valid meeting starts the transient lesson");
        SylphietteFirstLesson.onWaterCast(reader);
        // Her attempt starts after the delay; its closing line and the advancement come at the attempt's FINISH_TICK.
        helper.runAfterDelay(SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + 3, () -> helper.assertFalse(
                StoryAdvancements.hasCompleted(reader, StoryAdvancements.SYLPHIETTE_FIRST_LESSON), "Nothing is awarded while she is still trying"));
        helper.runAfterDelay(SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.FINISH_TICK + 3, () -> {
            helper.assertTrue(StoryAdvancements.hasCompleted(reader, StoryAdvancements.SYLPHIETTE_FIRST_LESSON),
                    "A successful demonstration awards the personal advancement");
            sylphiette.discard(); reader.discard(); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aDemonstrationLateInTheInvitationStillGetsItsAttempt(GameTestHelper helper) {
        Reader reader = new Reader(helper.getLevel(), "LessonLate");
        reader.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(5.5, 2, 2.5)));
        helper.getLevel().addNewPlayer(reader);
        SylphietteEntity sylphiette = sylph(helper, 5.5, 4.5);
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        reader.setData(ModAttachments.KNOWLEDGE, knowledge);
        helper.assertTrue(SylphietteFirstLesson.beginObservation(reader, sylphiette), "Valid meeting starts the transient lesson");
        // Ten ticks before the invitation would run out: the attempt itself must not be cut off by the session's end.
        int late = SylphietteFirstLesson.SESSION_TICKS - 10;
        helper.runAfterDelay(late, () -> SylphietteFirstLesson.onWaterCast(reader));
        helper.runAfterDelay(late + SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.FINISH_TICK + 3, () -> {
            helper.assertTrue(StoryAdvancements.hasCompleted(reader, StoryAdvancements.SYLPHIETTE_FIRST_LESSON),
                    "A demonstration late in the invitation still ends in her attempt and the advancement");
            sylphiette.discard(); reader.discard(); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void expiredOrUnrelatedPlayersCannotFinishTheLesson(GameTestHelper helper) {
        Reader first = new Reader(helper.getLevel(), "LessonFirst"), second = new Reader(helper.getLevel(), "LessonSecond");
        first.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(5.5, 2, 2.5)));
        second.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(20.5, 2, 2.5)));
        helper.getLevel().addNewPlayer(first); helper.getLevel().addNewPlayer(second);
        SylphietteEntity sylphiette = sylph(helper, 5.5, 4.5);
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        first.setData(ModAttachments.KNOWLEDGE, knowledge);
        helper.assertTrue(SylphietteFirstLesson.beginObservation(first, sylphiette), "First player owns the session");
        SylphietteFirstLesson.onWaterCast(second);
        helper.runAfterDelay(SylphietteFirstLesson.SESSION_TICKS + 5, () -> {
            helper.assertFalse(StoryAdvancements.hasCompleted(first, StoryAdvancements.SYLPHIETTE_FIRST_LESSON),
                    "Timeout alone grants no completion");
            helper.assertFalse(StoryAdvancements.hasCompleted(second, StoryAdvancements.SYLPHIETTE_FIRST_LESSON),
                    "Another player cannot consume the first player's session");
            sylphiette.discard(); first.discard(); second.discard(); helper.succeed();
        });
    }

    private SylphietteFirstLessonGameTests() {}
}
