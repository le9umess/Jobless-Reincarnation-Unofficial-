package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.magic.MireSpell;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellTraining;
import dev.joblessreincarnation.magic.WaterShieldSpell;
import dev.joblessreincarnation.npc.BasicTutorGuidance;
import dev.joblessreincarnation.npc.FireTutorGuidance;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.npc.TutorLessons;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** C05: observes existing knowledge; no new course counters, ranks, or rewards. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BasicTutorGuidanceGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.basic_tutor.";
    private static final List<SpellCatalog> BASIC = List.of(SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.HEALING, SpellCatalog.WATER_SHIELD, SpellCatalog.MIRE, SpellCatalog.STONE_CANNON);

    private static FakePlayer reader(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "BasicGuidance"));
    }

    private static ItemStack tome(SpellCatalog spell) {
        return switch (spell) {
            case WATER_BOLT -> ModContent.WATER_TOME.get().getDefaultInstance();
            case EARTH_WALL -> ModContent.EARTH_TOME.get().getDefaultInstance();
            case AIR_BURST -> ModContent.AIR_TOME.get().getDefaultInstance();
            case HEALING -> ModContent.HEALING_TOME.get().getDefaultInstance();
            case FIRE_BALL -> ModContent.FIRE_TOME.get().getDefaultInstance();
            case WATER_SHIELD -> ModContent.WATER_SHIELD_TOME.get().getDefaultInstance();
            case MIRE -> ModContent.MIRE_TOME.get().getDefaultInstance();
            case STONE_CANNON -> ModContent.STONE_CANNON_TOME.get().getDefaultInstance();
        };
    }

    /** Codec fixture avoids a million practice calls when a server config has unusually high thresholds. */
    private static MagicKnowledge knowledge(SpellCatalog spell, int casts) {
        JsonObject values = new JsonObject();
        values.addProperty(spell.id().toString(), casts);
        JsonObject encoded = new JsonObject();
        encoded.add("spells", values);
        return MagicKnowledge.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    private static int range(SpellCatalog spell) {
        return switch (spell) {
            case EARTH_WALL -> MagicConfig.EARTH_WALL_RANGE.getAsInt();
            case AIR_BURST -> MagicConfig.AIR_BURST_RANGE.getAsInt();
            case HEALING -> MagicConfig.HEALING_RANGE.getAsInt();
            case WATER_SHIELD -> (int) WaterShieldSpell.DISTANCE;
            case MIRE -> (int) MireSpell.RANGE;
            case STONE_CANNON -> (int) dev.joblessreincarnation.magic.StoneCannonProjectile.MAX_DISTANCE;
            default -> throw new IllegalArgumentException("Not a new course");
        };
    }

    @GameTest(template = "empty")
    public static void allFiveMainHandBooksAndRawWandSelectionsRouteWithoutMutation(GameTestHelper helper) {
        FakePlayer player = reader(helper);
        try {
            CastingData casting = new CastingData();
            player.setData(ModAttachments.CASTING, casting);
            for (SpellCatalog selected : SpellCatalog.values()) {
                casting.select(selected.id());
                player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
                helper.assertValueEqual(TutorLessons.lesson(player), selected, "A valid raw wand selection opens its course even before learning");
                for (SpellCatalog book : SpellCatalog.values()) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, tome(book));
                    helper.assertValueEqual(TutorLessons.lesson(player), book, "Main-hand textbook takes precedence over a different selection");
                    helper.assertValueEqual(casting.selectedSpell(), selected.id(), "Consulting a textbook does not select its spell");
                    helper.assertTrue(casting.activeSpell() == null, "Routing never begins a cast");
                }
            }
            ResourceLocation unknown = ResourceLocation.parse("jobless_reincarnation:unimplemented_lesson");
            casting.select(unknown);
            player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            helper.assertValueEqual(TutorLessons.lesson(player), SpellCatalog.WATER_BOLT, "Unknown raw selection safely uses the water course");
            helper.assertValueEqual(casting.selectedSpell(), unknown, "Fallback must not normalize the saved selection");
            for (SpellCatalog offhand : SpellCatalog.values()) {
                player.setItemInHand(InteractionHand.OFF_HAND, tome(offhand));
                for (ItemStack other : List.of(ItemStack.EMPTY, Items.STICK.getDefaultInstance(), Items.APPLE.getDefaultInstance())) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, other);
                    helper.assertValueEqual(TutorLessons.lesson(player), SpellCatalog.WATER_BOLT, "Offhand books and unrelated main-hand items do not choose a course");
                }
            }
            helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE) || player.hasData(ModAttachments.TRAINING)
                    || player.hasData(ModAttachments.MANA) || player.hasData(ModAttachments.MANA_GROWTH), "Course routing neither teaches nor initializes unrelated data");
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingAndPartialAttachmentsRemainAbsentAcrossEveryCourse(GameTestHelper helper) {
        FakePlayer empty = reader(helper), known = reader(helper), trained = reader(helper);
        try {
            MagicKnowledge existing = knowledge(SpellCatalog.EARTH_WALL, 0);
            known.setData(ModAttachments.KNOWLEDGE, existing);
            trained.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true, 3));
            for (FakePlayer player : List.of(empty, known, trained)) {
                for (int repeat = 0; repeat < 3; repeat++) {
                    for (SpellCatalog spell : SpellCatalog.values()) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, tome(spell));
                        helper.assertValueEqual(TutorLessons.lesson(player), spell, "Every book can request guidance before learning");
                        TutorLessons.message(player);
                    }
                    player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
                    helper.assertValueEqual(TutorLessons.lesson(player), SpellCatalog.WATER_BOLT, "A wand without casting data uses water");
                    for (SpellCatalog spell : BASIC) BasicTutorGuidance.message(BasicTutorGuidance.snapshot(player, spell));
                }
                helper.assertFalse(player.hasData(ModAttachments.CASTING) || player.hasData(ModAttachments.MANA)
                        || player.hasData(ModAttachments.MANA_GROWTH), "Reading must not initialize selection, mana, or growth");
            }
            helper.assertFalse(empty.hasData(ModAttachments.KNOWLEDGE) || empty.hasData(ModAttachments.TRAINING), "New reader stays attachment-free");
            helper.assertFalse(known.hasData(ModAttachments.TRAINING), "Knowledge alone does not create task progress");
            helper.assertFalse(trained.hasData(ModAttachments.KNOWLEDGE), "Claimed water progress cannot create knowledge");
            helper.assertTrue(known.getData(ModAttachments.KNOWLEDGE) == existing, "Read preserves the existing knowledge object");
            helper.assertValueEqual(BasicTutorGuidance.snapshot(trained, SpellCatalog.EARTH_WALL).stage(), BasicTutorGuidance.Stage.LEARN,
                    "Water and fire completion do not complete an unknown earth lesson");
        } finally { empty.discard(); known.discard(); trained.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void threeCoursesUseExistingPracticeStagesAtBothBoundaries(GameTestHelper helper) {
        FakePlayer player = reader(helper);
        try {
            player.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true, 3));
            int practiced = SpellTraining.practicedAt(), adept = SpellTraining.adeptAt();
            for (SpellCatalog spell : BASIC) {
                player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
                var unknown = BasicTutorGuidance.snapshot(player, spell);
                helper.assertValueEqual(unknown.spell(), spell, "Snapshot identifies the requested course");
                helper.assertFalse(unknown.learned(), "Absent knowledge stays unlearned");
                helper.assertValueEqual(unknown.stage(), BasicTutorGuidance.Stage.LEARN, "Unknown course starts at learning");
                helper.assertValueEqual(unknown.casts(), 0, "Unknown course has no successful casts");
                for (int count : new int[]{0, practiced - 1, practiced, adept - 1, adept, MagicKnowledge.MAX_PRACTICE}) {
                    player.setData(ModAttachments.KNOWLEDGE, knowledge(spell, count));
                    var snapshot = BasicTutorGuidance.snapshot(player, spell);
                    BasicTutorGuidance.Stage expected = count >= adept ? BasicTutorGuidance.Stage.ADEPT
                            : count >= practiced ? BasicTutorGuidance.Stage.PRACTICED : BasicTutorGuidance.Stage.NOVICE;
                    helper.assertTrue(snapshot.learned(), "Known spell with zero casts differs from unknown");
                    helper.assertValueEqual(snapshot.stage(), expected, "Teaching uses the configured existing practice stage");
                    helper.assertValueEqual(snapshot.casts(), count, "Reading does not clamp progress to a new course goal");
                    helper.assertValueEqual(snapshot.nextMilestone(), count >= adept ? 0 : count >= practiced ? adept : practiced,
                            "Next milestone is the actual existing threshold, or zero when no next stage exists");
                    helper.assertValueEqual(snapshot.range(), range(spell), "Course range follows the live spell configuration");
                }
            }
        } finally { player.discard(); }
        helper.succeed();
    }

    private static String state(FakePlayer player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + ManaGrowthData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA_GROWTH)).getOrThrow()
                + player.getInventory().save(new ListTag()) + ":slot=" + player.getInventory().selected;
    }

    @GameTest(template = "empty")
    public static void queriesAreIsolatedReadOnlyAndTheirSnapshotsAreDetached(GameTestHelper helper) {
        FakePlayer first = reader(helper), peer = reader(helper);
        try {
            MagicKnowledge existing = knowledge(SpellCatalog.EARTH_WALL, 0);
            existing.learn(SpellCatalog.AIR_BURST.id());
            existing.practice(SpellCatalog.AIR_BURST.id());
            first.setData(ModAttachments.KNOWLEDGE, existing);
            first.setData(ModAttachments.CASTING, new CastingData());
            first.getData(ModAttachments.CASTING).select(SpellCatalog.AIR_BURST.id());
            first.setData(ModAttachments.TRAINING, new TrainingProgress(2, false, false, 2));
            first.setData(ModAttachments.MANA, new ManaData(31, 120, 83, 7));
            first.setData(ModAttachments.MANA_GROWTH, new ManaGrowthData(10, 99, 0, 0, "c05-preserve"));
            first.getInventory().selected = 2;
            first.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.HEALING));
            first.setItemInHand(InteractionHand.OFF_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            first.getInventory().setItem(10, new ItemStack(Items.PAPER, 17));
            first.startUsingItem(InteractionHand.MAIN_HAND);
            int useRemaining = first.getUseItemRemainingTicks();
            peer.setData(ModAttachments.KNOWLEDGE, knowledge(SpellCatalog.EARTH_WALL, SpellTraining.adeptAt()));
            var frozen = BasicTutorGuidance.snapshot(first, SpellCatalog.EARTH_WALL);
            Component frozenMessage = BasicTutorGuidance.message(frozen);
            var peerBefore = BasicTutorGuidance.snapshot(peer, SpellCatalog.EARTH_WALL);
            String before = state(first);
            for (int repeat = 0; repeat < 5; repeat++) {
                TutorLessons.lesson(first);
                TutorLessons.message(first);
                for (SpellCatalog spell : BASIC) BasicTutorGuidance.message(BasicTutorGuidance.snapshot(first, spell));
            }
            helper.assertValueEqual(state(first), before, "Consulting preserves all five attachments, all slots, and current selection");
            helper.assertTrue(first.isUsingItem() && first.getUseItemRemainingTicks() == useRemaining, "Pure queries do not finish or cancel an ongoing item use");
            helper.assertFalse(first.getData(ModAttachments.TRAINING).claimed(), "The new lessons do not claim the water reward");
            helper.assertFalse(first.getInventory().contains(ModContent.TRAINING_RECORD.get().getDefaultInstance()), "No reward card is granted");
            existing.practice(SpellCatalog.EARTH_WALL.id());
            helper.assertValueEqual(frozen.casts(), 0, "The earlier snapshot does not retain mutable knowledge");
            helper.assertValueEqual(BasicTutorGuidance.message(frozen), frozenMessage, "A message from the frozen snapshot stays identical");
            helper.assertValueEqual(BasicTutorGuidance.snapshot(first, SpellCatalog.EARTH_WALL).casts(), 1, "A fresh snapshot sees actual later practice");
            helper.assertValueEqual(BasicTutorGuidance.snapshot(first, SpellCatalog.AIR_BURST).casts(), 1, "Earth practice does not change air count");
            helper.assertValueEqual(BasicTutorGuidance.snapshot(first, SpellCatalog.HEALING).stage(), BasicTutorGuidance.Stage.LEARN, "Another course remains unlearned");
            helper.assertValueEqual(BasicTutorGuidance.snapshot(peer, SpellCatalog.EARTH_WALL), peerBefore, "Another player's existing expertise remains private and unchanged");
        } finally { first.discard(); peer.discard(); }
        helper.succeed();
    }

    /** All mutation and restoration occur synchronously on the server thread; no callback sees temporary config. */
    @GameTest(template = "empty", batch = "c05TutorGuidanceConfig")
    public static void liveThresholdAndRangeChangesAffectNewSnapshotsButNotCapturedOnes(GameTestHelper helper) {
        int practiced = MagicConfig.PRACTICED_CASTS.getAsInt(), adept = MagicConfig.ADEPT_CASTS.getAsInt();
        int earth = MagicConfig.EARTH_WALL_RANGE.getAsInt(), air = MagicConfig.AIR_BURST_RANGE.getAsInt(), healing = MagicConfig.HEALING_RANGE.getAsInt();
        FakePlayer player = reader(helper);
        try {
            MagicConfig.PRACTICED_CASTS.set(3);
            MagicConfig.ADEPT_CASTS.set(7);
            MagicConfig.EARTH_WALL_RANGE.set(13);
            MagicConfig.AIR_BURST_RANGE.set(11);
            MagicConfig.HEALING_RANGE.set(7);
            List<BasicTutorGuidance.Snapshot> frozen = new ArrayList<>();
            List<Component> messages = new ArrayList<>();
            for (SpellCatalog spell : BASIC) {
                player.setData(ModAttachments.KNOWLEDGE, knowledge(spell, 3));
                var snapshot = BasicTutorGuidance.snapshot(player, spell);
                helper.assertValueEqual(snapshot.stage(), BasicTutorGuidance.Stage.PRACTICED, "Changed practice threshold applies immediately");
                helper.assertValueEqual(snapshot.nextMilestone(), 7, "Changed next threshold is shown");
                helper.assertValueEqual(snapshot.range(), range(spell), "Changed range is captured");
                frozen.add(snapshot);
                messages.add(BasicTutorGuidance.message(snapshot));
            }
            // Deliberately reverse raw thresholds; existing SpellTraining coerces adept to practiced + 1.
            MagicConfig.PRACTICED_CASTS.set(8);
            MagicConfig.ADEPT_CASTS.set(2);
            MagicConfig.EARTH_WALL_RANGE.set(2);
            MagicConfig.AIR_BURST_RANGE.set(3);
            MagicConfig.HEALING_RANGE.set(1);
            for (int index = 0; index < BASIC.size(); index++) {
                SpellCatalog spell = BASIC.get(index);
                player.setData(ModAttachments.KNOWLEDGE, knowledge(spell, 3));
                var current = BasicTutorGuidance.snapshot(player, spell);
                helper.assertValueEqual(current.stage(), BasicTutorGuidance.Stage.NOVICE, "New snapshots reflect threshold increases without deleting earned counts");
                helper.assertValueEqual(current.casts(), 3, "Config change does not rewrite saved practice");
                helper.assertValueEqual(current.nextMilestone(), 8, "Current novice target uses updated settings");
                helper.assertValueEqual(current.range(), range(spell), "New range is read for each new query");
                helper.assertValueEqual(frozen.get(index).stage(), BasicTutorGuidance.Stage.PRACTICED, "Old snapshot does not reread config");
                helper.assertValueEqual(BasicTutorGuidance.message(frozen.get(index)), messages.get(index), "Message generation uses captured thresholds and range");
                player.setData(ModAttachments.KNOWLEDGE, knowledge(spell, 8));
                helper.assertValueEqual(BasicTutorGuidance.snapshot(player, spell).nextMilestone(), 9, "Adept threshold shares the existing normalization rule");
                player.setData(ModAttachments.KNOWLEDGE, knowledge(spell, 9));
                helper.assertValueEqual(BasicTutorGuidance.snapshot(player, spell).stage(), BasicTutorGuidance.Stage.ADEPT, "Normalized upper boundary completes only the practice stage");
            }
        } finally {
            MagicConfig.PRACTICED_CASTS.set(practiced);
            MagicConfig.ADEPT_CASTS.set(adept);
            MagicConfig.EARTH_WALL_RANGE.set(earth);
            MagicConfig.AIR_BURST_RANGE.set(air);
            MagicConfig.HEALING_RANGE.set(healing);
            player.discard();
        }
        helper.assertValueEqual(MagicConfig.PRACTICED_CASTS.getAsInt(), practiced, "Original lower threshold restored");
        helper.assertValueEqual(MagicConfig.ADEPT_CASTS.getAsInt(), adept, "Original upper threshold restored");
        helper.assertValueEqual(MagicConfig.EARTH_WALL_RANGE.getAsInt(), earth, "Original earth range restored");
        helper.assertValueEqual(MagicConfig.AIR_BURST_RANGE.getAsInt(), air, "Original air range restored");
        helper.assertValueEqual(MagicConfig.HEALING_RANGE.getAsInt(), healing, "Original healing range restored");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void waterAndFireMessagesRemainExactAndAreNotNewBasicCourses(GameTestHelper helper) {
        FakePlayer player = reader(helper);
        try {
            for (SpellCatalog spell : List.of(SpellCatalog.WATER_BOLT, SpellCatalog.FIRE_BALL)) {
                player.setItemInHand(InteractionHand.MAIN_HAND, tome(spell));
                Component expected = spell == SpellCatalog.WATER_BOLT
                        ? TutorGuidance.message(TutorGuidance.snapshot(player)) : FireTutorGuidance.message(FireTutorGuidance.snapshot(player));
                helper.assertValueEqual(TutorLessons.message(player), expected, "The existing course service still owns water and fire wording");
                boolean rejected = false;
                try { BasicTutorGuidance.snapshot(player, spell); }
                catch (IllegalArgumentException correct) { rejected = true; }
                helper.assertTrue(rejected, "New basic guidance cannot accidentally replace an existing course");
            }
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packagedBilingualMessagesResolveEveryStageAndUseNativeChatCodec(GameTestHelper helper) {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject language = language(locale);
            for (SpellCatalog spell : BASIC) {
                String course = PREFIX + spell.id().getPath() + ".";
                for (BasicTutorGuidance.Stage stage : BasicTutorGuidance.Stage.values()) {
                    boolean learned = stage != BasicTutorGuidance.Stage.LEARN;
                    int casts = switch (stage) { case LEARN, NOVICE -> 0; case PRACTICED -> 20; case ADEPT -> 60; };
                    int next = stage == BasicTutorGuidance.Stage.ADEPT ? 0 : stage == BasicTutorGuidance.Stage.PRACTICED ? 60 : 20;
                    var snapshot = new BasicTutorGuidance.Snapshot(spell, stage, learned, casts, next, 7);
                    Component message = BasicTutorGuidance.message(snapshot);
                    helper.assertValueEqual(((TranslatableContents) message.getContents()).getKey(), course + "speaker", "Course is identifiable in the private speaker marker");
                    helper.assertValueEqual(message.getSiblings().size(), learned ? 5 : 3, "Learned courses add one progress line; learning remains two lines");
                    helper.assertValueEqual(message.getSiblings().get(1).getString(), "\n", "Rules start on a distinct line");
                    helper.assertValueEqual(((TranslatableContents) message.getSiblings().getFirst().getContents()).getKey(),
                            course + "dialogue." + stage.name().toLowerCase(Locale.ROOT), "Dialogue follows the captured stage");
                    helper.assertValueEqual(((TranslatableContents) message.getSiblings().get(2).getContents()).getKey(),
                            course + "rule." + (learned ? "practice" : "learn"), "Rule matches whether the course has been learned");
                    var rules = (TranslatableContents) message.getSiblings().get(2).getContents();
                    Object[] expectedRule = !learned ? new Object[]{tome(spell).getHoverName(), Component.keybind("key.use")}
                            : spell == SpellCatalog.EARTH_WALL ? new Object[]{7, Component.keybind("key.use"), Component.keybind("key.jobless_reincarnation.dismiss_wall")}
                            : spell == SpellCatalog.MIRE ? new Object[]{7, Component.keybind("key.use"), MireEntity.LIFETIME_TICKS / 20}
                            : new Object[]{7, Component.keybind("key.use")};
                    helper.assertValueEqual(rules.getArgs().length, expectedRule.length, "Course rule has all captured range and native keybind arguments");
                    for (int index = 0; index < expectedRule.length; index++)
                        helper.assertValueEqual(rules.getArgs()[index], expectedRule[index], "Course rule argument follows frozen API");
                    if (learned) {
                        helper.assertValueEqual(message.getSiblings().get(3).getString(), "\n", "Personal progress starts on the third line");
                        var progress = (TranslatableContents) message.getSiblings().get(4).getContents();
                        helper.assertValueEqual(progress.getKey(), PREFIX + "progress." + (next == 0 ? "adept" : "next"), "Adept never promises an invented later milestone");
                        helper.assertTrue(Arrays.asList(progress.getArgs()).contains(casts), "Progress includes actual captured successful casts");
                        if (next > 0) helper.assertTrue(Arrays.asList(progress.getArgs()).contains(next), "Progress includes the actual next threshold");
                    }
                    String localized = resolve(helper, language, message);
                    helper.assertValueEqual(localized.lines().count(), learned ? 3L : 2L, "Both locales preserve readable line count");
                    helper.assertFalse(localized.contains(PREFIX) || localized.contains("%s"), "Neither locale exposes unresolved lesson keys or arguments");
                    assertCodec(helper, message);
                }
            }
        }
        helper.succeed();
    }

    private static String resolve(GameTestHelper helper, JsonObject language, Component component) {
        String text;
        if (component.getContents() instanceof TranslatableContents contents) {
            helper.assertTrue(language.has(contents.getKey()), "Packaged translation exists: " + contents.getKey());
            String format = language.get(contents.getKey()).getAsString();
            helper.assertFalse(format.isBlank(), "Every used translation is nonempty");
            long count = Pattern.compile("%(?:[0-9]+\\$)?s").matcher(format).results().count();
            helper.assertValueEqual(count, (long) contents.getArgs().length, "Bilingual placeholder count matches supplied arguments: " + contents.getKey());
            Object[] args = Arrays.stream(contents.getArgs()).map(arg -> arg instanceof Component nested ? resolve(helper, language, nested) : arg).toArray();
            text = String.format(Locale.ROOT, format, args);
        } else if (component.getContents() instanceof KeybindContents) {
            // Native clients resolve the player's rebound key; server-side resource checks must not invent an English key name.
            text = "[bound-key]";
        } else if (component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents literal) {
            text = literal.text();
        } else {
            throw new IllegalStateException("Unexpected lesson component: " + component.getContents());
        }
        StringBuilder result = new StringBuilder(text);
        for (Component sibling : component.getSiblings()) result.append(resolve(helper, language, sibling));
        return result.toString();
    }

    private static void assertCodec(GameTestHelper helper, Component message) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var packet = new ClientboundSystemChatPacket(message, false);
            ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(decoded.content(), message, "Native system-chat codec retains structured lines, keybinds, and private progress");
            helper.assertFalse(decoded.overlay(), "Teaching stays separate from the action bar");
        } finally { buffer.release(); }
    }

    private static JsonObject language(String locale) {
        String path = "/assets/jobless_reincarnation/lang/" + locale + ".json";
        var input = BasicTutorGuidanceGameTests.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing packaged language: " + path);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read language: " + path, failure); }
    }

    private BasicTutorGuidanceGameTests() {}
}