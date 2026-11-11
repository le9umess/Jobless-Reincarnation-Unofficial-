package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Server integration checks. Actual client input consumption is additionally exercised by the preview. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoxyTutorInteractionGameTests {
    private static final class Reader extends FakePlayer {
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        boolean spectator;

        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "Roxy" + UUID.randomUUID().toString().substring(0, 8)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
        }

        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket message) messages.add(message);
        }

        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static final class Fixture implements AutoCloseable {
        final String previousProperty = System.getProperty(RoxyTutorInteraction.PROPERTY);
        final GameTestHelper helper;
        final Reader actor;
        final Reader peer;
        final RoxyDisplayEntity npc;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            System.setProperty(RoxyTutorInteraction.PROPERTY, "true");
            actor = new Reader(helper.getLevel());
            peer = new Reader(helper.getLevel());
            actor.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            peer.setPos(helper.absoluteVec(new Vec3(6.0, 2, 2.5)));
            npc = NpcDisplayRegistry.spawnRoxy(helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 2, 4.5)), 180);
            if (npc == null) {
                restoreProperty();
                throw new IllegalStateException("Scene NPC did not spawn");
            }
            RoxyTutorInteraction.bind(helper.getLevel(), npc, Set.of(actor.getUUID(), peer.getUUID()));
        }

        PlayerInteractEvent.EntityInteract click(Reader player, Entity target, InteractionHand hand) {
            return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, target));
        }

        PlayerInteractEvent.EntityInteract click(Reader player) { return click(player, npc, InteractionHand.MAIN_HAND); }

        void packet(Reader player, boolean specific) {
            var packet = specific
                    ? ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0))
                    : ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND);
            player.connection.handleInteract(packet);
        }

        void resetBinding() {
            RoxyTutorInteraction.unbind(helper.getLevel(), npc);
            RoxyTutorInteraction.bind(helper.getLevel(), npc, Set.of(actor.getUUID(), peer.getUUID()));
        }

        private void restoreProperty() {
            if (previousProperty == null) System.clearProperty(RoxyTutorInteraction.PROPERTY);
            else System.setProperty(RoxyTutorInteraction.PROPERTY, previousProperty);
        }

        @Override public void close() {
            RoxyTutorInteraction.unbind(helper.getLevel(), npc);
            npc.discard();
            actor.discard();
            peer.discard();
            restoreProperty();
        }
    }

    private static void prepare(Reader player, int casts, int hits, boolean charged, boolean claimed) {
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        for (int i = 0; i < casts; i++) knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(hits, charged, claimed));
    }

    private static void stage(GameTestHelper helper, Reader player, RoxyTutorGuidance.Stage expected) {
        helper.assertValueEqual(RoxyTutorGuidance.snapshot(player).stage(), expected, "Next step follows existing personal milestones");
    }

    private static void untouchedEmpty(GameTestHelper helper, Reader player) {
        helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE) || player.hasData(ModAttachments.TRAINING)
                || player.hasData(ModAttachments.MANA) || player.hasData(ModAttachments.CASTING),
                "A read or conversation creates no knowledge, training, mana, or casting attachments");
    }

    private static String attachmentState(Reader player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow();
    }

    private static String messageKey(Component message) {
        return ((TranslatableContents) message.getSiblings().getLast().getContents()).getKey();
    }

    @GameTest(template = "empty")
    public static void freshReadsAndConversationsCreateNoAttachments(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            untouchedEmpty(helper, f.actor);
            for (int i = 0; i < 4; i++) {
                stage(helper, f.actor, RoxyTutorGuidance.Stage.LEARN);
                RoxyTutorGuidance.message(RoxyTutorGuidance.snapshot(f.actor));
            }
            untouchedEmpty(helper, f.actor);
            var event = f.click(f.actor);
            helper.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction(),
                    "The registered general interaction consumes the scene click");
            helper.assertValueEqual(f.actor.messages.size(), 1, "Normal server message delivery reached this actor");
            helper.assertTrue(f.peer.messages.isEmpty(), "The other player receives no tutorial message");
            untouchedEmpty(helper, f.actor);
            untouchedEmpty(helper, f.peer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void allSixStagesUseExistingGoalsAndSnapshotsStayImmutable(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            stage(helper, f.actor, RoxyTutorGuidance.Stage.LEARN);
            prepare(f.actor, 0, 0, false, false);
            var frozen = RoxyTutorGuidance.snapshot(f.actor);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.CAST);
            prepare(f.actor, TrainingTrials.CAST_GOAL - 1, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.CAST);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL - 1, true, false);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.DISTANCE);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, false, false);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.SHAPING);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.CLAIM_AT_BOARD);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, true);
            stage(helper, f.actor, RoxyTutorGuidance.Stage.COMPLETE);
            helper.assertValueEqual(frozen.casts(), 0, "Snapshot contains detached values");
            helper.assertValueEqual(frozen.hits(), 0, "Later training cannot mutate a previous snapshot");
            helper.assertFalse(frozen.charged() || frozen.claimed(), "Snapshot booleans stay detached");
            helper.assertTrue(frozen.learned(), "Learned zero-cast differs from unknown");

            for (var current : RoxyTutorGuidance.Stage.values()) {
                var snapshot = new RoxyTutorGuidance.Snapshot(current, true, 2, 1, false, false);
                Component message = RoxyTutorGuidance.message(snapshot);
                helper.assertValueEqual(((TranslatableContents) message.getContents()).getKey(),
                        "message.jobless_reincarnation_dev.roxy_tutor.marker", "Development marker has its own namespace");
                TranslatableContents goal = (TranslatableContents) message.getSiblings().getLast().getContents();
                helper.assertTrue(goal.getKey().startsWith("message.jobless_reincarnation_dev.roxy_tutor."),
                        "Every goal is localizable in the independent development namespace");
                if (current == RoxyTutorGuidance.Stage.CAST)
                    helper.assertValueEqual(goal.getArgs()[1], TrainingTrials.CAST_GOAL, "Cast text uses the authoritative goal");
                if (current == RoxyTutorGuidance.Stage.DISTANCE) {
                    helper.assertValueEqual(goal.getArgs()[1], TrainingTrials.HIT_GOAL, "Hit text uses the authoritative goal");
                    helper.assertValueEqual(goal.getArgs()[2], TrainingTrials.MIN_RANGE, "Distance text uses the authoritative range");
                }
                if (current == RoxyTutorGuidance.Stage.SHAPING)
                    helper.assertValueEqual(goal.getArgs()[0], TrainingTrials.MIN_RANGE, "Shaping text uses the authoritative range");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualEntityPacketsDeliverOnlyEachPlayersPrivateProgress(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            prepare(f.peer, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            f.packet(f.actor, false);
            f.packet(f.peer, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "General entity packet reaches only its actor");
            helper.assertValueEqual(f.peer.messages.size(), 1, "Specific entity packet independently reaches its actor");
            helper.assertValueEqual(messageKey(f.actor.messages.getFirst().content()),
                    "message.jobless_reincarnation_dev.roxy_tutor.learn", "Unknown actor gets the learning stage");
            helper.assertValueEqual(messageKey(f.peer.messages.getFirst().content()),
                    "message.jobless_reincarnation_dev.roxy_tutor.claim_at_board", "Ready peer gets board guidance");
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.actor).stage(), RoxyTutorGuidance.Stage.LEARN, "Actor receipt is private");
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.peer).stage(), RoxyTutorGuidance.Stage.CLAIM_AT_BOARD, "Peer receipt uses peer data");
            helper.assertFalse(f.peer.getData(ModAttachments.TRAINING).claimed(), "Dialogue never claims the completed trial");
            helper.assertFalse(f.peer.getInventory().contains(new ItemStack(ModContent.TRAINING_RECORD.get())), "No record was granted");

            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                var original = f.peer.messages.getFirst();
                ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, original);
                var decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
                helper.assertValueEqual(decoded.content(), original.content(), "Native clientbound packet retains localized content");
                helper.assertFalse(decoded.overlay(), "Private tutorial uses chat rather than replacing the action bar");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void validationRejectsOffhandOutsidersDeadSpectatorsRangeAndDimension(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.click(f.actor, f.npc, InteractionHand.OFF_HAND);
            helper.assertTrue(f.actor.messages.isEmpty(), "Offhand cannot request progress");

            Reader outsider = new Reader(helper.getLevel());
            Reader remote = new Reader(helper.getLevel().getServer().getLevel(Level.NETHER));
            try {
                outsider.setPos(f.actor.position());
                remote.setPos(f.actor.position());
                f.click(outsider);
                f.click(remote);
                helper.assertTrue(outsider.messages.isEmpty() && remote.messages.isEmpty(), "Wrong participant or dimension receives nothing");

                f.actor.spectator = true;
                f.click(f.actor);
                f.actor.spectator = false;
                f.actor.setHealth(0);
                f.click(f.actor);
                f.actor.setHealth(f.actor.getMaxHealth());
                Vec3 origin = f.actor.position();
                f.actor.setPos(origin.add(0, 0, -20));
                f.click(f.actor);
                f.actor.setPos(origin);
                helper.assertTrue(f.actor.messages.isEmpty(), "Dead, spectator, and distant actors are rejected");
                helper.assertTrue(RoxyTutorInteraction.receipt(f.actor) == null, "Rejected attempts do not consume the reply budget");
                f.click(f.actor);
                helper.assertValueEqual(f.actor.messages.size(), 1, "A valid participant can immediately retry");
            } finally {
                outsider.discard();
                remote.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exactIdentityLiveTargetAndLineOfSightAreRequired(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            RoxyDisplayEntity impostor = NpcDisplayRegistry.roxy().create(helper.getLevel());
            helper.assertTrue(impostor != null, "Identity fixture exists");
            impostor.setUUID(f.npc.getUUID());
            impostor.setPos(f.npc.position());
            try {
                helper.assertFalse(f.click(f.actor, impostor, InteractionHand.MAIN_HAND).isCanceled(),
                        "A same-type entity with even the same UUID is not the bound identity");
                RoxyTutorInteraction.unbind(helper.getLevel(), impostor);
                helper.assertTrue(f.click(f.actor, f.npc, InteractionHand.OFF_HAND).isCanceled(),
                        "Wrong-identity unbind cannot remove the real binding");

                BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
                var original = helper.getLevel().getBlockState(wall);
                try {
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    helper.assertFalse(f.actor.hasLineOfSight(f.npc), "Fixture really blocks the eye ray");
                    f.click(f.actor);
                    helper.assertTrue(f.actor.messages.isEmpty(), "A solid wall blocks dialogue");
                } finally { helper.getLevel().setBlockAndUpdate(wall, original); }
                f.npc.setHealth(0);
                f.click(f.actor);
                helper.assertTrue(f.actor.messages.isEmpty(), "Dead NPC cannot answer");
                f.npc.setHealth(f.npc.getMaxHealth());
                f.click(f.actor);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Restored live visible NPC answers");
                f.resetBinding();
                f.npc.discard();
                f.click(f.actor);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Removed NPC cannot answer");
            } finally { impostor.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicatesArePerPlayerAndLogoutUnbindAndRebindResetTransientReplies(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int i = 0; i < 30; i++) {
                f.click(f.actor);
                NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(
                        f.actor, InteractionHand.MAIN_HAND, f.npc, new Vec3(0, 1, 0)));
            }
            helper.assertValueEqual(f.actor.messages.size(), 1, "Held-click and both event variants are rate limited together");
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.actor).delivered(), 1, "Only sent replies advance the receipt");
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.actor).tick(), helper.getLevel().getGameTime(), "Receipt uses the authoritative server tick");
            f.click(f.peer);
            helper.assertValueEqual(f.peer.messages.size(), 1, "One player's reply cannot throttle the other");

            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(f.actor));
            helper.assertTrue(RoxyTutorInteraction.receipt(f.actor) == null, "Logout clears this actor's transient reply");
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.peer).delivered(), 1, "Logout does not clear a peer's state");
            f.click(f.actor);
            helper.assertValueEqual(f.actor.messages.size(), 2, "A new actor session can immediately talk");

            RoxyTutorInteraction.unbind(helper.getLevel(), f.npc);
            helper.assertTrue(RoxyTutorInteraction.receipt(f.actor) == null && RoxyTutorInteraction.receipt(f.peer) == null,
                    "Unbind clears the scene's transient receipts");
            helper.assertFalse(f.click(f.actor).isCanceled(), "Unbound display has no teaching interception");
            f.resetBinding();
            f.click(f.actor);
            helper.assertValueEqual(RoxyTutorInteraction.receipt(f.actor).delivered(), 1, "Rebind begins a fresh receipt sequence");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void devPropertyIsRequiredAndStaffOrBookClicksDoNotStartUse(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            f.actor.setData(ModAttachments.MANA, new ManaData(73, 100, 17, 9));
            f.actor.setData(ModAttachments.CASTING, new CastingData());
            String before = attachmentState(f.actor);
            for (var item : List.of(ModContent.TRAINING_STAFF.get(), ModContent.WATER_TOME.get())) {
                f.resetBinding();
                f.actor.setItemInHand(InteractionHand.MAIN_HAND, item.getDefaultInstance());
                ItemStack held = f.actor.getMainHandItem().copy();
                f.packet(f.actor, true);
                f.packet(f.actor, false);
                helper.assertFalse(f.actor.isUsingItem(), "Entity packets did not start a staff chant or textbook study");
                helper.assertTrue(ItemStack.matches(f.actor.getMainHandItem(), held), "Dialogue does not consume or replace the held item");
                helper.assertValueEqual(attachmentState(f.actor), before, "All personal attachments are byte-for-value unchanged");
                helper.assertTrue(f.actor.getData(ModAttachments.CASTING).activeSpell() == null, "No active spell is created");
                helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
            }
            System.setProperty(RoxyTutorInteraction.PROPERTY, "false");
            var general = f.click(f.actor);
            var specific = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(
                    f.actor, InteractionHand.MAIN_HAND, f.npc, new Vec3(0, 1, 0)));
            helper.assertFalse(general.isCanceled() || specific.isCanceled(), "Ordinary development runs do not intercept display clicks");
            helper.assertValueEqual(f.actor.messages.size(), 2, "Disabled preview sent no further reply");
        }
        helper.succeed();
    }

    private RoxyTutorInteractionGameTests() {}
}
