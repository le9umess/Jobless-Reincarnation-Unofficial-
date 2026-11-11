package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.npc.TutorInteractions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Formal entity event and native packet checks. Physical client input is a separate acceptance check. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TutorInteractionGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.tutor.";

    private static final class Reader extends FakePlayer {
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        final List<ClientboundSystemChatPacket> otherSystemMessages = new ArrayList<>();
        boolean spectator;

        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "Tutor" + UUID.randomUUID().toString().substring(0, 8)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
        }

        private void capture(Packet<?> packet) {
            if (!(packet instanceof ClientboundSystemChatPacket message)) return;
            // ReportGameListener broadcasts all concurrent tests' pass/fail messages to every
            // registered level player. Count the actual tutor protocol, not that test-runner chatter.
            // A tutor reply leaked from any other actor or NPC still has this speaker and is counted.
            if (message.content().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals(PREFIX + "speaker")) messages.add(message);
            else otherSystemMessages.add(message);
        }

        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Reader actor;
        final Reader peer;
        final RoxyTutorEntity npc;
        final RoxyTutorEntity second;
        boolean closed;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            actor = new Reader(helper.getLevel());
            peer = new Reader(helper.getLevel());
            actor.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            peer.setPos(helper.absoluteVec(new Vec3(6.6, 2, 2.5)));
            // Entity authorization requires real registration, not an unregistered imitation of a player.
            helper.getLevel().addNewPlayer(actor);
            helper.getLevel().addNewPlayer(peer);
            npc = createTutor(helper.absoluteVec(new Vec3(5.5, 2, 4.5)));
            second = createTutor(helper.absoluteVec(new Vec3(7.0, 2, 4.5)));
            helper.assertTrue(helper.getLevel().getEntity(actor.getUUID()) == actor
                    && helper.getLevel().getEntity(peer.getUUID()) == peer, "Both readers are actual level entities");
        }

        private RoxyTutorEntity createTutor(Vec3 position) {
            RoxyTutorEntity tutor = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
            if (tutor == null) throw new IllegalStateException("Formal tutor type did not create an entity");
            tutor.moveTo(position.x, position.y, position.z, 180, 0);
            // This fixture tests interaction. Navigation and physical placement have their own tests.
            tutor.setNoAi(true);
            tutor.setNoGravity(true);
            if (!helper.getLevel().addFreshEntity(tutor)) throw new IllegalStateException("Formal tutor did not join the level");
            return tutor;
        }

        PlayerInteractEvent.EntityInteract click(Reader player, Entity target, InteractionHand hand) {
            return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, target));
        }

        PlayerInteractEvent.EntityInteract click(Reader player) { return click(player, npc, InteractionHand.MAIN_HAND); }

        void packet(Reader player, RoxyTutorEntity target, boolean specific, InteractionHand hand) {
            ServerboundInteractPacket packet = specific
                    ? ServerboundInteractPacket.createInteractionPacket(target, false, hand, new Vec3(0, 1, 0))
                    : ServerboundInteractPacket.createInteractionPacket(target, false, hand);
            player.connection.handleInteract(packet);
        }

        void packet(Reader player, RoxyTutorEntity target, boolean specific) {
            packet(player, target, specific, InteractionHand.MAIN_HAND);
        }

        void logout(Reader player) {
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            npc.discard();
            second.discard();
            logout(actor);
            logout(peer);
            actor.discard();
            peer.discard();
        }
    }

    private static void prepare(Reader player, int casts, int hits, boolean charged, boolean claimed) {
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        for (int i = 0; i < casts; i++) knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(hits, charged, claimed));
    }

    private static void noAttachments(GameTestHelper helper, Reader player) {
        helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE) || player.hasData(ModAttachments.TRAINING)
                || player.hasData(ModAttachments.MANA) || player.hasData(ModAttachments.CASTING),
                "Read-only guidance creates no knowledge, training, mana, or casting attachments");
    }

    private static String attachmentState(Reader player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow();
    }

    private static String inventoryState(Reader player) {
        return player.getInventory().save(new ListTag()).toString() + ":slot=" + player.getInventory().selected;
    }

    private static String ruleKey(Component message) {
        return ((TranslatableContents) message.getSiblings().getLast().getContents()).getKey();
    }

    private static void stage(GameTestHelper helper, Reader player, TutorGuidance.Stage expected) {
        helper.assertValueEqual(TutorGuidance.snapshot(player).stage(), expected, "Existing personal milestones choose the next rule");
    }

    @GameTest(template = "empty")
    public static void ordinaryRegisteredPlayersNeedNoPreviewFlagOrSceneBinding(GameTestHelper helper) {
        String property = RoxyTutorInteraction.PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, "false");
        try (Fixture f = new Fixture(helper)) {
            noAttachments(helper, f.actor);
            noAttachments(helper, f.peer);
            String inventory = inventoryState(f.actor);
            var specific = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(
                    f.actor, InteractionHand.MAIN_HAND, f.npc, new Vec3(0, 1, 0)));
            helper.assertTrue(specific.isCanceled() && specific.getCancellationResult().consumesAction(),
                    "A formal tutor consumes the specific interaction without any development binding");
            helper.assertValueEqual(f.actor.messages.size(), 1, "An ordinary registered actor receives one private reply");
            helper.assertTrue(f.peer.messages.isEmpty(), "Nearby players do not receive another actor's reply");
            helper.assertTrue(f.npc.conversationPlayer() == f.actor, "The accepted actor is the real conversation target");
            helper.assertValueEqual(ruleKey(f.actor.messages.getFirst().content()), PREFIX + "rule.learn", "A fresh actor is directed to study");
            helper.assertValueEqual(inventoryState(f.actor), inventory, "Consulting does not edit the inventory");
            noAttachments(helper, f.actor);
            noAttachments(helper, f.peer);
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sixReadOnlyStagesKeepDialogueSeparateFromAuthoritativeRules(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            stage(helper, f.actor, TutorGuidance.Stage.LEARN);
            prepare(f.actor, 0, 0, false, false);
            TutorGuidance.Snapshot frozen = TutorGuidance.snapshot(f.actor);
            stage(helper, f.actor, TutorGuidance.Stage.CAST);
            prepare(f.actor, TrainingTrials.CAST_GOAL - 1, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, f.actor, TutorGuidance.Stage.CAST);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL - 1, true, false);
            stage(helper, f.actor, TutorGuidance.Stage.DISTANCE);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, false, false);
            stage(helper, f.actor, TutorGuidance.Stage.SHAPING);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, f.actor, TutorGuidance.Stage.CLAIM_AT_BOARD);
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, true);
            stage(helper, f.actor, TutorGuidance.Stage.COMPLETE);
            helper.assertValueEqual(frozen.casts(), 0, "Snapshots retain detached cast counts");
            helper.assertValueEqual(frozen.hits(), 0, "Snapshots retain detached target-hit counts");
            helper.assertTrue(frozen.learned() && !frozen.charged() && !frozen.claimed(), "Later changes do not mutate snapshots");
            for (TutorGuidance.Stage current : TutorGuidance.Stage.values()) {
                Component message = TutorGuidance.message(new TutorGuidance.Snapshot(current, true, 2, 1, false, false));
                helper.assertValueEqual(((TranslatableContents) message.getContents()).getKey(), PREFIX + "speaker", "Formal speaker key has no development namespace");
                helper.assertTrue(((TranslatableContents) message.getSiblings().getFirst().getContents()).getKey().startsWith(PREFIX + "dialogue."),
                        "The character's dialogue is separately localizable");
                helper.assertValueEqual(message.getSiblings().get(1).getString(), "\n", "Game rules begin on a separate line");
                TranslatableContents rule = (TranslatableContents) message.getSiblings().getLast().getContents();
                if (current == TutorGuidance.Stage.CAST)
                    helper.assertValueEqual(rule.getArgs()[1], TrainingTrials.CAST_GOAL, "Cast text uses the actual trial goal");
                if (current == TutorGuidance.Stage.DISTANCE) {
                    helper.assertValueEqual(rule.getArgs()[1], TrainingTrials.HIT_GOAL, "Hit text uses the actual trial goal");
                    helper.assertValueEqual(rule.getArgs()[2], TrainingTrials.MIN_RANGE, "Distance text uses the actual trial range");
                }
                if (current == TutorGuidance.Stage.SHAPING)
                    helper.assertValueEqual(rule.getArgs()[0], TrainingTrials.MIN_RANGE, "Shaping text uses the actual trial range");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualEntityPacketsDeliverIndependentPrivateComponents(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            prepare(f.peer, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            f.packet(f.actor, f.npc, false);
            f.packet(f.peer, f.npc, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "General C2S entity interaction replies only to its actor");
            helper.assertValueEqual(f.peer.messages.size(), 1, "Specific C2S entity interaction replies only to its actor");
            helper.assertValueEqual(ruleKey(f.actor.messages.getFirst().content()), PREFIX + "rule.learn", "Unknown actor has personal learning guidance");
            helper.assertValueEqual(ruleKey(f.peer.messages.getFirst().content()), PREFIX + "rule.claim_at_board", "Ready peer has personal board guidance");
            helper.assertFalse(f.peer.getData(ModAttachments.TRAINING).claimed(), "Dialogue never claims a finished trial");
            helper.assertFalse(f.peer.getInventory().contains(new ItemStack(ModContent.TRAINING_RECORD.get())), "Dialogue grants no record");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ClientboundSystemChatPacket original = f.peer.messages.getFirst();
                ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, original);
                ClientboundSystemChatPacket decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
                helper.assertValueEqual(decoded.content(), original.content(), "Native packet codec preserves both translatable lines");
                helper.assertFalse(decoded.overlay(), "Teaching uses private chat, not the action bar");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void repeatedPacketsRespectTwentyRealTicksPerPlayerAndPerTutor(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        try {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            f.packet(f.actor, f.second, false);
            helper.assertValueEqual(f.actor.messages.size(), 2, "The second tutor has an independent reply budget");
            helper.assertValueEqual(f.peer.messages.size(), 1, "A second player has an independent reply budget");
            helper.assertTrue(f.npc.conversationPlayer() == f.peer && f.second.conversationPlayer() == f.actor,
                    "Each tutor retains its own last accepted conversation actor");
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        long firstTick = helper.getLevel().getGameTime();
        for (int delay = 1; delay < TutorInteractions.REPLY_INTERVAL_TICKS; delay++) {
            helper.runAfterDelay(delay, () -> {
                try {
                    f.packet(f.actor, f.npc, true);
                    f.packet(f.actor, f.npc, false);
                    f.packet(f.actor, f.second, true);
                    helper.assertValueEqual(f.actor.messages.size(), 2, "Specific, general, and held-repeat packets share the cooldown");
                    helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Rejected repeats cannot steal another actor's conversation");
                } catch (RuntimeException | Error failure) { f.close(); throw failure; }
            });
        }
        helper.runAfterDelay(TutorInteractions.REPLY_INTERVAL_TICKS, () -> {
            try (f) {
                helper.assertValueEqual(helper.getLevel().getGameTime() - firstTick, (long) TutorInteractions.REPLY_INTERVAL_TICKS,
                        "This check crosses exactly twenty real server ticks");
                f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.npc, true);
                f.packet(f.peer, f.npc, true);
                f.packet(f.actor, f.second, true);
                helper.assertValueEqual(f.actor.messages.size(), 4, "Both tutor budgets reopen exactly at the boundary");
                helper.assertValueEqual(f.peer.messages.size(), 2, "The peer's budget independently reopens at the boundary");
                helper.assertTrue(f.npc.conversationPlayer() == f.peer && f.second.conversationPlayer() == f.actor,
                        "New accepted replies keep conversation targets isolated");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void logoutAndEntityLeaveClearOnlyTheirOwnTransientReplyBudgets(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.packet(f.actor, f.npc, true);
            f.packet(f.actor, f.second, true);
            f.packet(f.peer, f.npc, true);
            f.logout(f.actor);
            f.packet(f.actor, f.npc, false);
            f.packet(f.actor, f.second, false);
            f.packet(f.peer, f.npc, false);
            helper.assertValueEqual(f.actor.messages.size(), 4, "Logout clears this actor's budget on every tutor");
            helper.assertValueEqual(f.peer.messages.size(), 1, "Logout leaves the other actor's budget intact");
            // Exercise the actual cleanup subscription without removing the fixture we still need to query.
            NeoForge.EVENT_BUS.post(new EntityLeaveLevelEvent(f.npc, helper.getLevel()));
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, true);
            f.packet(f.actor, f.second, true);
            helper.assertValueEqual(f.actor.messages.size(), 5, "Entity-leave cleanup clears only that tutor's budget");
            helper.assertValueEqual(f.peer.messages.size(), 2, "Entity-leave cleanup clears all actors for that tutor");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidActorsAndNonLiveIdentityCannotReadProgressOrFallThrough(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            var offhand = f.click(f.actor, f.npc, InteractionHand.OFF_HAND);
            helper.assertTrue(offhand.isCanceled() && offhand.getCancellationResult().consumesAction(), "Offhand is consumed without replying");
            f.packet(f.actor, f.npc, true, InteractionHand.OFF_HAND);
            Reader remote = new Reader(helper.getLevel().getServer().getLevel(Level.NETHER));
            RoxyTutorEntity impostor = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
            if (impostor == null) throw new IllegalStateException("Impostor fixture did not create");
            try {
                remote.setPos(f.actor.position());
                f.click(remote);
                impostor.setUUID(f.npc.getUUID());
                impostor.setPos(f.npc.position());
                var sameUuid = f.click(f.actor, impostor, InteractionHand.MAIN_HAND);
                helper.assertTrue(sameUuid.isCanceled() && sameUuid.getCancellationResult().consumesAction(), "A same-type impostor still cannot trigger held-item fallback");
                f.actor.spectator = true;
                f.click(f.actor);
                f.actor.spectator = false;
                f.actor.setHealth(0);
                f.click(f.actor);
                f.actor.setHealth(f.actor.getMaxHealth());
                Vec3 original = f.actor.position();
                f.actor.setPos(original.add(0, 0, -20));
                f.click(f.actor);
                f.actor.setPos(original);
                BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
                var previous = helper.getLevel().getBlockState(wall);
                try {
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    helper.assertFalse(f.actor.hasLineOfSight(f.npc), "The fixture really blocks the eye ray");
                    f.click(f.actor);
                } finally { helper.getLevel().setBlockAndUpdate(wall, previous); }
                f.npc.setHealth(0);
                f.click(f.actor);
                f.npc.setHealth(f.npc.getMaxHealth());
                helper.assertTrue(f.actor.messages.isEmpty() && remote.messages.isEmpty(), "No invalid actor or target receives teaching data");
                f.packet(f.actor, f.npc, true);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Rejected attempts never consume the valid retry budget");
                f.npc.discard();
                f.click(f.actor);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Removed tutors cannot answer");
            } finally { remote.discard(); impostor.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void heldBooksWandsTagsEggsAndLeadsCannotModifyPlayersOrTheTutor(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            prepare(f.actor, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            f.actor.setData(ModAttachments.MANA, new ManaData(73, 100, 17, 9));
            f.actor.setData(ModAttachments.CASTING, new CastingData());
            String attachments = attachmentState(f.actor);
            Component originalName = f.npc.getCustomName().copy();
            ItemStack nameTag = Items.NAME_TAG.getDefaultInstance();
            nameTag.set(DataComponents.CUSTOM_NAME, Component.literal("Unexpected replacement name"));
            List<ItemStack> held = List.of(ModContent.WATER_TOME.get().getDefaultInstance(),
                    ModContent.TRAINING_STAFF.get().getDefaultInstance(), nameTag,
                    ModContent.ROXY_TUTOR_SPAWN_EGG.get().getDefaultInstance(), Items.LEAD.getDefaultInstance());
            int originalTutors = helper.getLevel().getEntitiesOfClass(RoxyTutorEntity.class, f.npc.getBoundingBox().inflate(4)).size();
            for (ItemStack item : held) {
                f.logout(f.actor);
                f.actor.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
                String inventory = inventoryState(f.actor);
                f.packet(f.actor, f.npc, true);
                f.packet(f.actor, f.npc, false);
                helper.assertTrue(f.actor.interactOn(f.npc, InteractionHand.MAIN_HAND).consumesAction(),
                        "The actual Player interaction hook stops before vanilla mob/item actions");
                helper.assertFalse(f.actor.isUsingItem(), "Conversation does not start textbook study or a wand chant");
                helper.assertValueEqual(attachmentState(f.actor), attachments, "Consultation leaves all personal attachments unchanged");
                helper.assertValueEqual(inventoryState(f.actor), inventory, "No held item, inventory stack, or slot changes");
                helper.assertValueEqual(f.npc.getCustomName(), originalName, "The named tag cannot replace the teacher's existing localized name");
                helper.assertFalse(f.npc.isLeashed(), "The lead cannot attach before dialogue");
                helper.assertValueEqual(helper.getLevel().getEntitiesOfClass(RoxyTutorEntity.class, f.npc.getBoundingBox().inflate(4)).size(),
                        originalTutors, "Clicking a teacher with her egg cannot create a second entity");
                helper.assertFalse(f.actor.getData(ModAttachments.TRAINING).claimed(), "NPC does not claim the existing board reward");
                helper.assertFalse(f.actor.getInventory().contains(new ItemStack(ModContent.TRAINING_RECORD.get())), "NPC grants no reward item");
                helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
                helper.assertEntityNotPresent(ModContent.FIRE_BALL.get());
            }
            helper.assertValueEqual(f.actor.messages.size(), held.size(), "One accepted consultation per item fixture despite duplicate routes");
            helper.assertTrue(f.peer.messages.isEmpty(), "The nearby peer sees none of these private replies");
        }
        helper.succeed();
    }

    private TutorInteractionGameTests() {}
}