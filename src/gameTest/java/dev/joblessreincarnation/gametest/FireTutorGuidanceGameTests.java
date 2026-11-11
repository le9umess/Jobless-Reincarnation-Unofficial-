package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.FireTutorGuidance;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.npc.TutorInteractions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.FireTrainingTrials;
import dev.joblessreincarnation.training.TrainingProgress;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** New fire routing on the ordinary tutor path. Native packet tests are distinct from client-input acceptance. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireTutorGuidanceGameTests {
    private static final String FIRE = "message.jobless_reincarnation.fire_tutor.";
    private static final String WATER = "message.jobless_reincarnation.tutor.";

    private static FakePlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "FireGuidance"));
    }

    private static void prepare(FakePlayer player, boolean learned, int casts, int fireHits) {
        MagicKnowledge knowledge = new MagicKnowledge();
        if (learned) knowledge.learn(FireMagic.SPELL);
        for (int i = 0; i < casts; i++) knowledge.practice(FireMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        // A finished water course must never substitute for the fire course's own goals.
        player.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true, fireHits));
    }

    private static void stage(GameTestHelper helper, FakePlayer player, FireTutorGuidance.Stage expected) {
        helper.assertValueEqual(FireTutorGuidance.snapshot(player).stage(), expected, "Fire guidance respects its own prerequisites");
    }

    @GameTest(template = "empty")
    public static void fireStagesIgnoreWaterClaimAndHaveNoShapingOrClaimStep(GameTestHelper helper) {
        FakePlayer player = player(helper);
        try {
            stage(helper, player, FireTutorGuidance.Stage.LEARN);
            prepare(player, false, 0, FireTrainingTrials.HIT_GOAL);
            stage(helper, player, FireTutorGuidance.Stage.LEARN);
            prepare(player, true, 0, FireTrainingTrials.HIT_GOAL);
            stage(helper, player, FireTutorGuidance.Stage.CAST);
            prepare(player, true, FireTrainingTrials.CAST_GOAL - 1, FireTrainingTrials.HIT_GOAL);
            stage(helper, player, FireTutorGuidance.Stage.CAST);
            prepare(player, true, FireTrainingTrials.CAST_GOAL, FireTrainingTrials.HIT_GOAL - 1);
            stage(helper, player, FireTutorGuidance.Stage.DISTANCE);
            prepare(player, true, FireTrainingTrials.CAST_GOAL, FireTrainingTrials.HIT_GOAL);
            stage(helper, player, FireTutorGuidance.Stage.COMPLETE);
            player.setData(ModAttachments.TRAINING, new TrainingProgress(0, false, false, FireTrainingTrials.HIT_GOAL));
            stage(helper, player, FireTutorGuidance.Stage.COMPLETE);
            helper.assertValueEqual(TutorGuidance.snapshot(player).stage(), TutorGuidance.Stage.LEARN,
                    "Completing fire does not teach water or claim the water reward");
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingAndPartialDataStayAbsentWhenRoutingAndReading(GameTestHelper helper) {
        FakePlayer empty = player(helper), knowledgeOnly = player(helper), progressOnly = player(helper);
        try {
            MagicKnowledge knowledge = new MagicKnowledge();
            knowledge.learn(FireMagic.SPELL);
            knowledgeOnly.setData(ModAttachments.KNOWLEDGE, knowledge);
            progressOnly.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true, 2));
            for (FakePlayer current : List.of(empty, knowledgeOnly, progressOnly)) {
                for (int repeat = 0; repeat < 3; repeat++) {
                    current.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
                    helper.assertTrue(FireTutorGuidance.isFireLesson(current), "Fire tome opens the lesson before learning");
                    FireTutorGuidance.message(FireTutorGuidance.snapshot(current));
                    current.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
                    helper.assertFalse(FireTutorGuidance.isFireLesson(current), "An absent selection stays the default water route");
                }
                helper.assertFalse(current.hasData(ModAttachments.CASTING) || current.hasData(ModAttachments.MANA),
                        "Neither routing nor reading creates casting or mana");
            }
            helper.assertFalse(empty.hasData(ModAttachments.KNOWLEDGE) || empty.hasData(ModAttachments.TRAINING),
                    "An unknown player gains no data from asking");
            helper.assertFalse(knowledgeOnly.hasData(ModAttachments.TRAINING), "Knowledge-only data remains partial");
            helper.assertFalse(progressOnly.hasData(ModAttachments.KNOWLEDGE), "Training-only data remains partial");
            helper.assertTrue(knowledgeOnly.getData(ModAttachments.KNOWLEDGE) == knowledge, "Existing attachment is not replaced");
            stage(helper, knowledgeOnly, FireTutorGuidance.Stage.CAST);
            stage(helper, progressOnly, FireTutorGuidance.Stage.LEARN);
        } finally { empty.discard(); knowledgeOnly.discard(); progressOnly.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lessonRouteUsesMainHandAndSelectionWithoutTeachingOrSelecting(GameTestHelper helper) {
        FakePlayer player = player(helper);
        try {
            CastingData casting = new CastingData();
            player.setData(ModAttachments.CASTING, casting);
            player.setItemInHand(InteractionHand.OFF_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            helper.assertFalse(FireTutorGuidance.isFireLesson(player), "An offhand fire tome never changes the course");
            player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            helper.assertTrue(FireTutorGuidance.isFireLesson(player), "Unknown fire book opens fire even with water selected");
            helper.assertValueEqual(casting.selectedSpell(), WaterMagic.SPELL, "Opening the fire lesson never switches the selected spell");
            player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            helper.assertFalse(FireTutorGuidance.isFireLesson(player), "Water-selected wand keeps the water lesson");
            casting.select(FireMagic.SPELL);
            helper.assertTrue(FireTutorGuidance.isFireLesson(player), "An idle fire-selected wand opens fire");
            helper.assertTrue(casting.activeSpell() == null, "Routing does not require or start active casting");
            for (ItemStack other : List.of(ItemStack.EMPTY, Items.STICK.getDefaultInstance(),
                    ModContent.WATER_TOME.get().getDefaultInstance(), ModContent.EARTH_TOME.get().getDefaultInstance())) {
                player.setItemInHand(InteractionHand.MAIN_HAND, other);
                helper.assertFalse(FireTutorGuidance.isFireLesson(player), "Fire selection alone does not override a different held item");
                helper.assertValueEqual(casting.selectedSpell(), FireMagic.SPELL, "A water consultation never changes fire selection");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            casting.select(SpellCatalog.EARTH_WALL.id());
            helper.assertFalse(FireTutorGuidance.isFireLesson(player), "Other selected spells do not open the fire course");
            helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE), "Routing never teaches a spell");
        } finally { player.discard(); }
        helper.succeed();
    }

    private static String personalState(FakePlayer player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow()
                + player.getInventory().save(new ListTag()) + ":slot=" + player.getInventory().selected;
    }

    @GameTest(template = "empty")
    public static void readsAreDetachedPreserveBothCoursesAndGrantNothing(GameTestHelper helper) {
        FakePlayer first = player(helper), peer = player(helper);
        try {
            prepare(first, true, FireTrainingTrials.CAST_GOAL, FireTrainingTrials.HIT_GOAL);
            prepare(peer, true, 0, 0);
            first.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
            first.setData(ModAttachments.TRAINING, new TrainingProgress(2, false, false, FireTrainingTrials.HIT_GOAL));
            first.setData(ModAttachments.MANA, new ManaData(31, 120, 83, 7));
            CastingData casting = new CastingData();
            casting.select(FireMagic.SPELL);
            first.setData(ModAttachments.CASTING, casting);
            first.getInventory().selected = 2;
            first.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            first.setItemInHand(InteractionHand.OFF_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            first.getInventory().setItem(10, new ItemStack(Items.PAPER, 17));
            first.startUsingItem(InteractionHand.MAIN_HAND);
            int remaining = first.getUseItemRemainingTicks();
            var frozen = FireTutorGuidance.snapshot(peer);
            var frozenMessage = FireTutorGuidance.message(frozen);
            String before = personalState(first);
            var waterBefore = TutorGuidance.snapshot(first);
            for (int i = 0; i < 5; i++) {
                FireTutorGuidance.isFireLesson(first);
                FireTutorGuidance.message(FireTutorGuidance.snapshot(first));
            }
            helper.assertValueEqual(personalState(first), before, "Guidance does not change any attachment, stack, or slot");
            helper.assertValueEqual(TutorGuidance.snapshot(first), waterBefore, "Water progression remains unchanged");
            helper.assertTrue(first.isUsingItem() && first.getUseItemRemainingTicks() == remaining,
                    "A pure snapshot neither finishes nor cancels item use");
            helper.assertFalse(first.getData(ModAttachments.TRAINING).claimed(), "Fire completion cannot claim a water reward");
            helper.assertFalse(first.getInventory().contains(ModContent.TRAINING_RECORD.get().getDefaultInstance()), "No card is granted");
            peer.getData(ModAttachments.KNOWLEDGE).practice(FireMagic.SPELL);
            peer.getData(ModAttachments.TRAINING).recordFireTargetHit();
            helper.assertValueEqual(frozen.casts(), 0, "Captured casts do not change after new practice");
            helper.assertValueEqual(frozen.hits(), 0, "Captured hits do not change after a new hit");
            helper.assertValueEqual(FireTutorGuidance.message(frozen), frozenMessage, "A detached message remains stable");
            helper.assertValueEqual(FireTutorGuidance.snapshot(peer).hits(), 1, "Only the practicing player's current progress changes");
            stage(helper, first, FireTutorGuidance.Stage.COMPLETE);
        } finally { first.discard(); peer.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packagedCourseMessagesHaveTwoLinesAndNativeChatCodec(GameTestHelper helper) {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject language = language(locale);
            helper.assertFalse(language.get(FIRE + "speaker").equals(language.get(WATER + "speaker")),
                    "Each first line identifies its distinct course");
            for (FireTutorGuidance.Stage stage : FireTutorGuidance.Stage.values()) {
                Component message = FireTutorGuidance.message(new FireTutorGuidance.Snapshot(stage, true, 2, 1));
                helper.assertValueEqual(message.getSiblings().size(), 3, "One reply contains dialogue, newline, and rules");
                var speaker = (TranslatableContents) message.getContents();
                var dialogue = (TranslatableContents) message.getSiblings().getFirst().getContents();
                var rule = (TranslatableContents) message.getSiblings().getLast().getContents();
                String suffix = stage.name().toLowerCase(Locale.ROOT);
                helper.assertValueEqual(speaker.getKey(), FIRE + "speaker", "Fire has an explicit private reply marker");
                helper.assertValueEqual(dialogue.getKey(), FIRE + "dialogue." + suffix, "Dialogue matches the fire stage");
                helper.assertValueEqual(rule.getKey(), FIRE + "rule." + suffix, "Rules match the fire stage");
                helper.assertValueEqual(message.getSiblings().get(1).getString(), "\n", "Character text and rules are separate lines");
                helper.assertFalse(message.getStyle().getColor().equals(message.getSiblings().getLast().getStyle().getColor()),
                        "Rules have a distinct visual style");
                Object[] expected = switch (stage) {
                    case CAST -> new Object[]{2, FireTrainingTrials.CAST_GOAL};
                    case DISTANCE -> new Object[]{1, FireTrainingTrials.HIT_GOAL, FireTrainingTrials.MIN_RANGE};
                    default -> new Object[]{};
                };
                helper.assertValueEqual(rule.getArgs().length, expected.length, "Rule arguments match this goal");
                for (int index = 0; index < expected.length; index++)
                    helper.assertValueEqual(rule.getArgs()[index], expected[index], "Rule uses authoritative counts and goals");
                String firstLine = translate(helper, language, speaker) + translate(helper, language, dialogue);
                String rules = translate(helper, language, rule);
                helper.assertFalse(firstLine.contains("\n") || rules.contains("\n"), "Translations retain exactly two lines");
                helper.assertFalse(firstLine.contains("%s") || rules.contains("%s"), "Both locales resolve all parameters");
                if (stage == FireTutorGuidance.Stage.LEARN)
                    helper.assertTrue(rules.contains(language.get("item.jobless_reincarnation.fire_tome").getAsString()),
                            "Learning names the actual fire book");
                if (stage == FireTutorGuidance.Stage.CAST)
                    helper.assertTrue(rules.contains(language.get("item.jobless_reincarnation.training_staff").getAsString()),
                            "Casting names the actual wand");
                assertCodec(helper, new ClientboundSystemChatPacket(message, false));
            }
        }
        helper.succeed();
    }

    private static final class Reader extends FakePlayer {
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        boolean spectator;
        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "FireReader" + UUID.randomUUID().toString().substring(0, 6)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
        }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket message
                    && message.content().getContents() instanceof TranslatableContents contents
                    && (contents.getKey().equals(FIRE + "speaker") || contents.getKey().equals(WATER + "speaker")))
                messages.add(message);
        }
        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Reader actor, peer;
        final RoxyTutorEntity npc;
        boolean closed;
        Fixture(GameTestHelper helper) {
            actor = new Reader(helper.getLevel());
            peer = new Reader(helper.getLevel());
            actor.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            peer.setPos(helper.absoluteVec(new Vec3(6.6, 2, 2.5)));
            helper.getLevel().addNewPlayer(actor);
            helper.getLevel().addNewPlayer(peer);
            npc = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
            if (npc == null) throw new IllegalStateException("Missing tutor type");
            Vec3 position = helper.absoluteVec(new Vec3(5.5, 2, 4.5));
            npc.moveTo(position.x, position.y, position.z, 180, 0);
            npc.setNoAi(true);
            npc.setNoGravity(true);
            if (!helper.getLevel().addFreshEntity(npc)) throw new IllegalStateException("Tutor did not join the level");
        }
        void packet(Reader player, boolean specific) {
            player.connection.handleInteract(specific
                    ? ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0))
                    : ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND));
        }
        PlayerInteractEvent.EntityInteract click(Reader player, Entity target, InteractionHand hand) {
            return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, target));
        }
        void logout(Reader player) { NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            npc.discard();
            logout(actor); logout(peer);
            actor.discard(); peer.discard();
        }
    }

    private static String rule(Component message) {
        return ((TranslatableContents) message.getSiblings().getLast().getContents()).getKey();
    }

    @GameTest(template = "empty")
    public static void nativeTwoPlayerRoutesArePrivateAndShareTheOriginalReplyBudget(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            prepare(f.peer, true, FireTrainingTrials.CAST_GOAL, FireTrainingTrials.HIT_GOAL);
            f.peer.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
            for (int i = 0; i < 5; i++) f.peer.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
            f.peer.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, false, 3));
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Fire consultation sends one private native reply");
            helper.assertTrue(f.peer.messages.isEmpty(), "The nearby water learner sees no fire reply");
            helper.assertValueEqual(rule(f.actor.messages.getFirst().content()), FIRE + "rule.learn", "An unknown fire-book holder gets learning guidance");
            f.packet(f.peer, false);
            helper.assertValueEqual(f.peer.messages.size(), 1, "The other learner has an independent reply budget");
            helper.assertValueEqual(rule(f.peer.messages.getFirst().content()), WATER + "rule.claim_at_board", "Empty hand preserves the existing water reward route");
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_TOME.get().getDefaultInstance());
            f.packet(f.actor, false);
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Switching courses cannot bypass the twenty-tick budget");
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "A rejected course switch cannot steal the conversation");
            f.logout(f.actor);
            f.packet(f.actor, false);
            helper.assertValueEqual(rule(f.actor.messages.getLast().content()), WATER + "rule.learn", "An accepted later query uses the current hand");
            CastingData casting = new CastingData();
            casting.select(FireMagic.SPELL);
            f.peer.setData(ModAttachments.CASTING, casting);
            f.peer.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            f.logout(f.peer);
            f.packet(f.peer, true);
            helper.assertValueEqual(rule(f.peer.messages.getLast().content()), FIRE + "rule.complete", "A fire-selected wand reads that learner's completed fire course");
            helper.assertValueEqual(f.actor.messages.size(), 2, "Peer completion is not broadcast to the first learner");
            helper.assertFalse(f.peer.getData(ModAttachments.TRAINING).claimed(), "Fire completion does not claim the ready water reward");
            for (Reader current : List.of(f.actor, f.peer))
                for (ClientboundSystemChatPacket message : current.messages) assertCodec(helper, message);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void repeatedNativeFireBookClicksDoNotStudyCastSelectOrGrant(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.actor.setNoGravity(true);
        f.peer.setNoGravity(true);
        f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
        String inventory = f.actor.getInventory().save(new ListTag()).toString();
        f.packet(f.actor, true);
        for (int delay = 1; delay < 25; delay++) {
            helper.runAfterDelay(delay, () -> {
                try {
                    f.packet(f.actor, true);
                    f.packet(f.actor, false);
                    helper.assertFalse(f.actor.isUsingItem(), "Held-repeat interactions consume the tutor before book study");
                    helper.assertFalse(FireMagic.isLearned(f.actor), "Repeated native entity clicks never teach fire");
                } catch (RuntimeException | Error failure) { f.close(); throw failure; }
            });
        }
        helper.runAfterDelay(25, () -> {
            try (f) {
                helper.assertValueEqual(f.actor.messages.size(), 2, "A held consultation keeps the original twenty-tick rate limit");
                helper.assertTrue(f.peer.messages.isEmpty(), "Held consultations remain private");
                helper.assertValueEqual(f.actor.getInventory().save(new ListTag()).toString(), inventory, "The fire book and all inventory slots stay unchanged");
                helper.assertFalse(f.actor.hasData(ModAttachments.KNOWLEDGE) || f.actor.hasData(ModAttachments.TRAINING)
                        || f.actor.hasData(ModAttachments.CASTING), "Asking creates no spell, progress, or selection attachment");
                helper.assertFalse(f.actor.isUsingItem(), "A consultation never starts item use");
                helper.assertEntityNotPresent(ModContent.FIRE_BALL.get());
                helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void fireCourseStillRequiresMainHandIdentityRangeSightAndLivingActors(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            var offhand = f.click(f.actor, f.npc, InteractionHand.OFF_HAND);
            helper.assertTrue(offhand.isCanceled() && offhand.getCancellationResult().consumesAction(), "Offhand cannot fall through to item use");
            Reader remote = new Reader(helper.getLevel().getServer().getLevel(Level.NETHER));
            RoxyTutorEntity impostor = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
            if (impostor == null) throw new IllegalStateException("Missing impostor fixture");
            try {
                remote.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
                remote.setPos(f.actor.position());
                f.click(remote, f.npc, InteractionHand.MAIN_HAND);
                impostor.setUUID(f.npc.getUUID());
                impostor.setPos(f.npc.position());
                f.click(f.actor, impostor, InteractionHand.MAIN_HAND);
                f.actor.spectator = true;
                f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.spectator = false;
                f.actor.setHealth(0);
                f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.setHealth(f.actor.getMaxHealth());
                Vec3 position = f.actor.position();
                f.actor.setPos(position.add(0, 0, -20));
                f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.setPos(position);
                BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
                var previous = helper.getLevel().getBlockState(wall);
                try {
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    helper.assertFalse(f.actor.hasLineOfSight(f.npc), "Fixture actually blocks sight");
                    f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                } finally { helper.getLevel().setBlockAndUpdate(wall, previous); }
                helper.assertTrue(f.actor.messages.isEmpty() && remote.messages.isEmpty(), "No rejected query returns private progress");
                f.packet(f.actor, true);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Rejected queries leave the valid reply budget available");
                f.npc.discard();
                f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Removed tutors cannot return fire guidance");
            } finally { remote.discard(); impostor.discard(); }
        }
        helper.succeed();
    }

    /** The public gesture budget must not merge two learners' private twenty-tick reply budgets. */
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void publicLectureAndPrivateTwoPlayerRepliesHaveIndependentBudgets(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        try {
            f.actor.setNoGravity(true);
            f.peer.setNoGravity(true);
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            f.peer.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_TOME.get().getDefaultInstance());
            MagicKnowledge water = new MagicKnowledge();
            water.learn(WaterMagic.SPELL);
            water.practice(WaterMagic.SPELL);
            water.practice(WaterMagic.SPELL);
            f.peer.setData(ModAttachments.KNOWLEDGE, water);
            helper.assertValueEqual(TutorInteractions.REPLY_INTERVAL_TICKS, 20, "Private reply window remains twenty ticks");
            helper.assertValueEqual(NpcAnimations.LECTURE_INTERVAL_TICKS, 40, "Public gesture window is forty ticks");
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "First real consultation sends the actor's private reply");
            helper.assertTrue(f.peer.messages.isEmpty(), "Public lecture never broadcasts private course text");
            helper.assertFalse(NpcAnimations.playLecture(f.npc), "Accepted consultation already started the public gesture");
            f.packet(f.peer, false);
            helper.assertValueEqual(f.peer.messages.size(), 1, "Second learner gets a reply while the shared gesture is cooling down");
            helper.assertValueEqual(rule(f.actor.messages.getFirst().content()), FIRE + "rule.learn", "Actor reads their own unknown fire course");
            helper.assertValueEqual(rule(f.peer.messages.getFirst().content()), WATER + "rule.cast", "Peer reads their own partially practiced water course");
            helper.assertFalse(NpcAnimations.playLecture(f.npc), "Another learner cannot restart the same public gesture");
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        helper.runAfterDelay(20, () -> {
            try {
                f.packet(f.actor, false);
                f.packet(f.peer, true);
                helper.assertValueEqual(f.actor.messages.size(), 2, "Actor's private reply resumes at twenty ticks");
                helper.assertValueEqual(f.peer.messages.size(), 2, "Peer's private reply independently resumes at twenty ticks");
                helper.assertFalse(NpcAnimations.playLecture(f.npc), "Twenty-tick private replies cannot restart the forty-tick gesture");
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        helper.runAfterDelay(39, () -> {
            try {
                helper.assertFalse(NpcAnimations.playLecture(f.npc), "Public gesture still rejects a restart at tick thirty-nine");
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        helper.runAfterDelay(40, () -> {
            try (f) {
                helper.assertTrue(NpcAnimations.playLecture(f.npc), "Public gesture becomes available at tick forty; rejected attempts did not extend it");
                f.packet(f.actor, true);
                f.packet(f.peer, false);
                helper.assertValueEqual(f.actor.messages.size(), 3, "Public trigger does not consume actor's next private reply");
                helper.assertValueEqual(f.peer.messages.size(), 3, "Public trigger does not consume peer's next private reply");
                helper.assertFalse(NpcAnimations.playLecture(f.npc), "Only one shared gesture starts in this window");
                for (ClientboundSystemChatPacket message : f.actor.messages)
                    helper.assertValueEqual(rule(message.content()), FIRE + "rule.learn", "Actor never receives the peer's course");
                for (ClientboundSystemChatPacket message : f.peer.messages)
                    helper.assertValueEqual(rule(message.content()), WATER + "rule.cast", "Peer never receives the actor's course");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void rejectedNativeConsultationsCannotStartTheRegisteredLecture(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
            var controllers = new software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar(new ArrayList<>());
            f.npc.registerControllers(controllers);
            helper.assertTrue(controllers.controllers().stream().anyMatch(c -> c.getName().equals("locomotion")),
                    "Formal Roxy keeps its locomotion controller");
            var gesture = controllers.controllers().stream().filter(c -> c.getName().equals(NpcAnimations.GESTURE_CONTROLLER)).findFirst();
            helper.assertTrue(gesture.isPresent(), "Formal Roxy registers the separate gesture controller");
            helper.assertTrue(gesture.orElseThrow().tryTriggerAnimation(NpcAnimations.LECTURE),
                    "The registered gesture controller recognizes the lecture trigger (server registration only)");
            f.actor.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(f.npc, false, InteractionHand.OFF_HAND));
            f.actor.spectator = true;
            f.packet(f.actor, true);
            f.actor.spectator = false;
            Vec3 original = f.actor.position();
            f.actor.setPos(original.add(0, 0, -20));
            f.packet(f.actor, false);
            f.actor.setPos(original);
            helper.assertTrue(f.actor.messages.isEmpty() && f.peer.messages.isEmpty(), "Rejected native requests send no private course reply");
            helper.assertTrue(NpcAnimations.playLecture(f.npc), "Rejected requests leave this NPC's first public gesture available");
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Valid private reply remains available despite the independent public gesture");
            helper.assertTrue(f.peer.messages.isEmpty(), "Valid reply stays private");
            helper.assertFalse(NpcAnimations.playLecture(f.npc), "A successful query cannot immediately restart the public gesture");
        }
        helper.succeed();
    }

    private static void assertCodec(GameTestHelper helper, ClientboundSystemChatPacket packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(decoded.content(), packet.content(), "Native system-chat codec preserves both lines and counts");
            helper.assertFalse(decoded.overlay(), "Course guidance does not replace the action bar");
        } finally { buffer.release(); }
    }

    private static String translate(GameTestHelper helper, JsonObject language, TranslatableContents contents) {
        helper.assertTrue(language.has(contents.getKey()), "Packaged translation exists: " + contents.getKey());
        String template = language.get(contents.getKey()).getAsString();
        helper.assertFalse(template.isBlank(), "Translation is not blank");
        long placeholders = java.util.regex.Pattern.compile("%s").matcher(template).results().count();
        helper.assertValueEqual(placeholders, (long) contents.getArgs().length, "Translation placeholders match supplied arguments");
        return String.format(Locale.ROOT, template, contents.getArgs());
    }

    private static JsonObject language(String locale) {
        String path = "/assets/jobless_reincarnation/lang/" + locale + ".json";
        var stream = FireTutorGuidanceGameTests.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing packaged language: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read language: " + path, failure); }
    }

    private FireTutorGuidanceGameTests() {}
}
