package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.NpcGestureVisual;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.NpcGreetings;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Roxy's greeting (user 11-05: she needs a real hello). Someone arriving within reach and in sight is greeted once with
 * the public GREETING gesture; staying nearby does not repeat it. A right-click still lectures exactly as before, and a
 * lecture that starts ends the greeting. No greeting over a live lecture, and none for fake players.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoxyGreetingGameTests {
    private static final int SCAN = RoxyTutorEntity.GREETING_SCAN_TICKS + 2;

    private static final class Listener extends ServerPlayer {
        Listener(ServerLevel level) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), "Hello" + UUID.randomUUID().toString().substring(0, 8)), ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}
            };
            setGameMode(GameType.SURVIVAL);
            setNoGravity(true);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final RoxyTutorEntity roxy;
        Fixture(GameTestHelper h, boolean fake) {
            for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
            player = fake ? new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "HelloFake")) : new Listener(h.getLevel());
            player.setPos(h.absoluteVec(new Vec3(5.5, 1, 2.5)));
            player.setNoGravity(true);
            h.getLevel().addNewPlayer(player);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            roxy = ModContent.ROXY_TUTOR.get().create(h.getLevel());
            if (roxy == null) throw new IllegalStateException("Missing registered Roxy tutor");
            Vec3 at = h.absoluteVec(new Vec3(5.5, 1, 4.5));
            roxy.moveTo(at.x, at.y, at.z, 180, 0);
            roxy.setNoAi(true);
            roxy.setNoGravity(true);
            if (!h.getLevel().addFreshEntity(roxy)) throw new IllegalStateException("Tutor failed admission");
        }
        void talk() { player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(roxy, false, InteractionHand.MAIN_HAND)); }
        NpcGestureVisual gesture() { return roxy.getData(ModAttachments.NPC_GESTURE_VISUAL); }
        @Override public void close() {
            roxy.discard();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void arrivingPlayerIsGreetedOnceWhileTheyStay(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.runAfterDelay(SCAN, () -> {
            try {
                NpcGestureVisual gesture = f.gesture();
                h.assertTrue(gesture.kind() == NpcGestureVisual.Kind.GREETING && gesture.live(), "Someone arriving within reach is greeted");
                h.assertValueEqual(gesture.duration(), NpcGestureVisual.ENTRY_TICKS + NpcGreetings.ROXY_HOLD_TICKS - NpcGreetings.SETTLE_TICKS,
                        "Public greeting is the four entry ticks plus the fifty-two tick clip");
                h.assertTrue(f.roxy.isGreeting() && f.roxy.greetingPlayer() == f.player, "Server greeting state names the arrival");
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        h.runAfterDelay(SCAN + NpcGreetings.ROXY_HOLD_TICKS + 3 * RoxyTutorEntity.GREETING_SCAN_TICKS, () -> {
            try (f) {
                h.assertFalse(f.roxy.isGreeting(), "The greeting ended by itself");
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.NONE, "Staying nearby is not greeted again");
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void emptyHandedReplyLetsTheGreetingFinish(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.runAfterDelay(SCAN, () -> {
            try (f) {
                h.assertTrue(f.roxy.isGreeting(), "Greeting under way");
                long sequence = f.gesture().sequence();
                f.talk();
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.GREETING && f.gesture().sequence() == sequence,
                        "An empty-handed reply while she greets leaves the same public greeting");
                h.assertTrue(f.roxy.isGreeting(), "She finishes the greeting");
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void rightClickOutsideAGreetingStillLectures(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.assertTrue(NpcAnimations.playLecture(f.roxy), "A lecture keeps her from greeting the arrival first");
        h.runAfterDelay(NpcAnimations.LECTURE_INTERVAL_TICKS + 1, () -> {
            try (f) {
                h.assertFalse(f.roxy.isGreeting(), "No greeting is under way");
                f.talk();
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.LECTURE, "An ordinary right-click is the lecture, as before");
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void noGreetingOverALiveLecture(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.assertTrue(NpcAnimations.playLecture(f.roxy), "A lecture is playing before she looks round");
        h.runAfterDelay(SCAN, () -> {
            try (f) {
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.LECTURE, "The playing lecture keeps the public gesture");
                h.assertFalse(f.roxy.isGreeting(), "No greeting starts over a live lecture");
            }
            h.succeed();
        });
    }

    /** GPT's review of cand2: a course request in the middle of a greeting must get the lecture. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courseRequestDuringAGreetingTakesOver(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.runAfterDelay(SCAN, () -> {
            try (f) {
                h.assertTrue(f.roxy.isGreeting(), "Greeting under way");
                f.player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_TOME.get().getDefaultInstance());
                f.talk();
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.LECTURE, "The course request is taught");
                h.assertFalse(f.roxy.isGreeting(), "Teaching ended the greeting");
            }
            h.succeed();
        });
    }

    /** GPT's review of cand2: someone else speaking empty-handed while she greets must not turn her away or take the greeting. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void secondListenerCannotTurnHerAwayFromAGreeting(GameTestHelper h) {
        Fixture f = new Fixture(h, false);
        h.runAfterDelay(SCAN, () -> {
            Listener other = new Listener(h.getLevel());
            other.setPos(h.absoluteVec(new Vec3(7.3, 1, 3.6)));
            h.getLevel().addNewPlayer(other);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(other));
            try {
                h.assertTrue(f.roxy.isGreeting() && f.roxy.greetingPlayer() == f.player, "The first arrival is being greeted");
                float yaw = f.roxy.getYRot();
                long sequence = f.gesture().sequence();
                other.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(f.roxy, false, InteractionHand.MAIN_HAND));
                h.assertTrue(f.roxy.isGreeting() && f.roxy.greetingPlayer() == f.player, "The greeting still belongs to the first player");
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.GREETING && f.gesture().sequence() == sequence, "The public greeting is the same event");
                h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(f.roxy.getYRot() - yaw)) < 0.01, "She has not turned to the second listener");
            } finally {
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(other));
                other.discard();
                f.close();
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fakePlayersAreNotGreeted(GameTestHelper h) {
        Fixture f = new Fixture(h, true);
        h.runAfterDelay(SCAN + RoxyTutorEntity.GREETING_SCAN_TICKS, () -> {
            try (f) {
                h.assertFalse(f.roxy.isGreeting(), "A fake player never triggers the greeting");
                h.assertTrue(f.gesture().kind() == NpcGestureVisual.Kind.NONE, "No public gesture for a fake player");
            }
            h.succeed();
        });
    }

    private RoxyGreetingGameTests() {}
}
