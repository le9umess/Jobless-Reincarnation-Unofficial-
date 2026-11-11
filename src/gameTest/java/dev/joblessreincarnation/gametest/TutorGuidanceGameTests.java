package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure guidance checks: no NPC fixture, global language mutation, preview flags, or world writes. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TutorGuidanceGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.tutor.";

    private static FakePlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "TutorGuidance"));
    }

    private static void prepare(FakePlayer player, boolean learned, int casts, int hits, boolean charged, boolean claimed) {
        MagicKnowledge knowledge = new MagicKnowledge();
        if (learned) knowledge.learn(WaterMagic.SPELL);
        for (int i = 0; i < casts; i++) knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(hits, charged, claimed));
    }

    private static void stage(GameTestHelper helper, FakePlayer player, TutorGuidance.Stage expected) {
        helper.assertValueEqual(TutorGuidance.snapshot(player).stage(), expected, "Next step follows existing personal milestones");
    }

    @GameTest(template = "empty")
    public static void allStagesRespectPrerequisitesAndClaimedState(GameTestHelper helper) {
        FakePlayer player = player(helper);
        try {
            stage(helper, player, TutorGuidance.Stage.LEARN);
            prepare(player, false, 0, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, player, TutorGuidance.Stage.LEARN);
            prepare(player, true, 0, 0, false, false);
            stage(helper, player, TutorGuidance.Stage.CAST);
            prepare(player, true, TrainingTrials.CAST_GOAL - 1, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, player, TutorGuidance.Stage.CAST);
            prepare(player, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL - 1, true, false);
            stage(helper, player, TutorGuidance.Stage.DISTANCE);
            prepare(player, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, false, false);
            stage(helper, player, TutorGuidance.Stage.SHAPING);
            prepare(player, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, player, TutorGuidance.Stage.CLAIM_AT_BOARD);
            prepare(player, true, TrainingTrials.CAST_GOAL + 1, TrainingTrials.HIT_GOAL, true, false);
            stage(helper, player, TutorGuidance.Stage.CLAIM_AT_BOARD);
            prepare(player, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, true);
            stage(helper, player, TutorGuidance.Stage.COMPLETE);
            // A persisted claimed flag is never turned back into a new reward invitation.
            prepare(player, false, 0, 0, false, true);
            stage(helper, player, TutorGuidance.Stage.COMPLETE);
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingAndPartialAttachmentsAreNeverCreatedByConsulting(GameTestHelper helper) {
        FakePlayer empty = player(helper);
        FakePlayer knowledgeOnly = player(helper);
        FakePlayer progressOnly = player(helper);
        try {
            MagicKnowledge knowledge = new MagicKnowledge();
            knowledge.learn(WaterMagic.SPELL);
            knowledgeOnly.setData(ModAttachments.KNOWLEDGE, knowledge);
            progressOnly.setData(ModAttachments.TRAINING, new TrainingProgress(1, false, false));
            for (int repeat = 0; repeat < 4; repeat++) {
                for (FakePlayer current : List.of(empty, knowledgeOnly, progressOnly))
                    TutorGuidance.message(TutorGuidance.snapshot(current));
            }
            helper.assertFalse(empty.hasData(ModAttachments.KNOWLEDGE) || empty.hasData(ModAttachments.TRAINING),
                    "Consulting an unknown player creates no progress attachments");
            helper.assertFalse(knowledgeOnly.hasData(ModAttachments.TRAINING), "Learned players do not gain a default training attachment");
            helper.assertFalse(progressOnly.hasData(ModAttachments.KNOWLEDGE), "Partial training does not create knowledge");
            helper.assertTrue(knowledgeOnly.getData(ModAttachments.KNOWLEDGE) == knowledge, "Existing knowledge is not replaced");
            for (FakePlayer current : List.of(empty, knowledgeOnly, progressOnly)) {
                helper.assertFalse(current.hasData(ModAttachments.MANA) || current.hasData(ModAttachments.CASTING),
                        "Guidance does not initialize mana or casting");
                helper.assertFalse(current.getInventory().contains(ModContent.TRAINING_RECORD.get().getDefaultInstance()),
                        "No consultation grants a practice card");
            }
            stage(helper, knowledgeOnly, TutorGuidance.Stage.CAST);
            stage(helper, progressOnly, TutorGuidance.Stage.LEARN);
        } finally {
            empty.discard(); knowledgeOnly.discard(); progressOnly.discard();
        }
        helper.succeed();
    }

    private static String attachmentState(FakePlayer player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow();
    }

    @GameTest(template = "empty")
    public static void guidancePreservesExistingProgressInventorySelectionAndItemUse(GameTestHelper helper) {
        FakePlayer player = player(helper);
        try {
            prepare(player, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.FIRE_BALL.id());
            player.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.FIRE_BALL.id());
            ManaData mana = new ManaData(31, 120, 83, 7);
            CastingData casting = new CastingData();
            casting.select(SpellCatalog.FIRE_BALL.id());
            player.setData(ModAttachments.MANA, mana);
            player.setData(ModAttachments.CASTING, casting);
            player.getInventory().selected = 2;
            player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_TOME.get().getDefaultInstance());
            player.setItemInHand(InteractionHand.OFF_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            player.getInventory().setItem(10, new ItemStack(Items.PAPER, 17));
            player.setPos(helper.absoluteVec(new Vec3(3.5, 2, 3.5)));
            player.startUsingItem(InteractionHand.MAIN_HAND);
            Vec3 position = player.position();
            int remainingUse = player.getUseItemRemainingTicks();
            String before = attachmentState(player);
            List<ItemStack> stacks = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                    .mapToObj(index -> player.getInventory().getItem(index).copy()).toList();
            for (int i = 0; i < 5; i++) TutorGuidance.message(TutorGuidance.snapshot(player));
            helper.assertValueEqual(attachmentState(player), before, "Consultation cannot modify any serialized attachment data");
            helper.assertTrue(player.getData(ModAttachments.MANA) == mana && player.getData(ModAttachments.CASTING) == casting,
                    "Mana and casting objects are not replaced");
            for (int slot = 0; slot < stacks.size(); slot++)
                helper.assertTrue(ItemStack.matches(stacks.get(slot), player.getInventory().getItem(slot)),
                        "Every inventory stack including offhand stays unchanged: " + slot);
            helper.assertValueEqual(player.getInventory().selected, 2, "Consultation never switches the selected slot");
            helper.assertValueEqual(player.position(), position, "Consultation never moves the player");
            helper.assertTrue(player.isUsingItem() && player.getUseItemRemainingTicks() == remainingUse,
                    "A pure read does not finish, restart, or cancel active item use");
            helper.assertFalse(player.getData(ModAttachments.TRAINING).claimed(), "Ready progress is not automatically claimed");
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void detachedSnapshotsAndDifferentPlayersStayIndependent(GameTestHelper helper) {
        FakePlayer first = player(helper);
        FakePlayer second = player(helper);
        try {
            prepare(first, true, 0, 0, false, false);
            prepare(second, true, TrainingTrials.CAST_GOAL, TrainingTrials.HIT_GOAL, true, false);
            var frozen = TutorGuidance.snapshot(first);
            Component frozenMessage = TutorGuidance.message(frozen);
            first.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
            first.getData(ModAttachments.TRAINING).recordTargetHit(true);
            second.getData(ModAttachments.TRAINING).claim();
            helper.assertValueEqual(frozen.casts(), 0, "Later practice cannot change a previous snapshot");
            helper.assertValueEqual(frozen.hits(), 0, "Later target hits cannot change a previous snapshot");
            helper.assertFalse(frozen.charged() || frozen.claimed(), "Snapshot booleans contain detached values");
            helper.assertTrue(frozen.learned(), "Learned with no casts remains distinct from unknown");
            helper.assertValueEqual(TutorGuidance.message(frozen), frozenMessage, "Rendering a frozen snapshot is stable");
            var current = TutorGuidance.snapshot(first);
            helper.assertValueEqual(current.casts(), 1, "New queries see only the first player's actual practice");
            helper.assertFalse(current.claimed(), "Another player's claim cannot change this player's state");
            stage(helper, first, TutorGuidance.Stage.CAST);
            stage(helper, second, TutorGuidance.Stage.COMPLETE);
        } finally { first.discard(); second.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoLineMessagesUsePackagedTranslationsAndAuthoritativeGoals(GameTestHelper helper) {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject language = resource("/assets/jobless_reincarnation/lang/" + locale + ".json");
            for (var stage : TutorGuidance.Stage.values()) {
                var snapshot = new TutorGuidance.Snapshot(stage, true, 2, 1, false, false);
                Component message = TutorGuidance.message(snapshot);
                helper.assertValueEqual(message.getSiblings().size(), 3, "Speaker has dialogue, newline, and game-rule siblings");
                var speaker = (TranslatableContents) message.getContents();
                var dialogue = (TranslatableContents) message.getSiblings().get(0).getContents();
                var rule = (TranslatableContents) message.getSiblings().get(2).getContents();
                String suffix = stage.name().toLowerCase(Locale.ROOT);
                helper.assertValueEqual(speaker.getKey(), PREFIX + "speaker", "One stable formal marker identifies the message");
                helper.assertValueEqual(dialogue.getKey(), PREFIX + "dialogue." + suffix, "Dialogue follows the selected stage");
                helper.assertValueEqual(rule.getKey(), PREFIX + "rule." + suffix, "Rule follows the same stage");
                helper.assertValueEqual(message.getSiblings().get(1).getString(), "\n", "Character dialogue and rules occupy separate lines");
                helper.assertFalse(message.getStyle().getColor().equals(message.getSiblings().get(2).getStyle().getColor()),
                        "Character dialogue and game rules are visually distinct");
                Object[] expected = switch (stage) {
                    case CAST -> new Object[]{2, TrainingTrials.CAST_GOAL};
                    case DISTANCE -> new Object[]{1, TrainingTrials.HIT_GOAL, TrainingTrials.MIN_RANGE};
                    case SHAPING -> new Object[]{TrainingTrials.MIN_RANGE};
                    default -> new Object[]{};
                };
                helper.assertValueEqual(rule.getArgs().length, expected.length, "Rule placeholders match the selected goal");
                for (int index = 0; index < expected.length; index++)
                    helper.assertValueEqual(rule.getArgs()[index], expected[index], "Rule uses actual count or authoritative constant");
                String firstLine = translated(helper, language, speaker) + translated(helper, language, dialogue);
                String secondLine = translated(helper, language, rule);
                helper.assertFalse(firstLine.contains("\n") || secondLine.contains("\n"), "Translations cannot introduce extra dialogue lines");
                helper.assertFalse(firstLine.contains("%s") || secondLine.contains("%s"), "All parameters resolve in both packaged languages");
                if (stage == TutorGuidance.Stage.LEARN)
                    helper.assertTrue(secondLine.contains(language.get("item.jobless_reincarnation.water_tome").getAsString()),
                            "Learning text uses the registered tome name");
                if (stage == TutorGuidance.Stage.CAST)
                    helper.assertTrue(secondLine.contains(language.get("item.jobless_reincarnation.training_staff").getAsString()),
                            "Casting text uses the registered wand name");
                if (stage == TutorGuidance.Stage.CLAIM_AT_BOARD) {
                    helper.assertTrue(secondLine.contains(language.get("block.jobless_reincarnation.training_board").getAsString()),
                            "Reward directions use the registered board name");
                    helper.assertTrue(secondLine.contains(language.get("item.jobless_reincarnation.training_record").getAsString()),
                            "Reward directions use the registered card name");
                }
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
                try {
                    ClientboundSystemChatPacket original = new ClientboundSystemChatPacket(message, false);
                    ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, original);
                    ClientboundSystemChatPacket decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
                    helper.assertValueEqual(decoded.content(), message, "One native system-chat packet retains both lines and parameters");
                    helper.assertFalse(decoded.overlay(), "Tutorial guidance does not replace the action bar");
                } finally { buffer.release(); }
            }
        }
        helper.succeed();
    }

    private static String translated(GameTestHelper helper, JsonObject language, TranslatableContents content) {
        helper.assertTrue(language.has(content.getKey()), "Packaged translation exists: " + content.getKey());
        String template = language.get(content.getKey()).getAsString();
        helper.assertFalse(template.isBlank(), "Packaged translation is not empty: " + content.getKey());
        long placeholders = java.util.regex.Pattern.compile("%s").matcher(template).results().count();
        helper.assertValueEqual(placeholders, (long) content.getArgs().length, "Packaged placeholders match supplied arguments: " + content.getKey());
        return String.format(Locale.ROOT, template, content.getArgs());
    }

    private static JsonObject resource(String path) {
        var stream = TutorGuidanceGameTests.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing packaged resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read packaged resource: " + path, failure); }
    }

    private TutorGuidanceGameTests() {}
}
