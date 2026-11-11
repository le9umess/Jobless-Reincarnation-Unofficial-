package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.TomeFeedback;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server item-use/message integration. Client hand animation needs native-client validation. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TomeFeedbackGameTests {
    private record Notice(String key, boolean overlay) {}

    private static final class Reader extends FakePlayer {
        final List<Notice> notices = new ArrayList<>();
        boolean spectator;
        Reader(ServerLevel level) { this(level, UUID.randomUUID()); }
        Reader(ServerLevel level, UUID uuid) { super(level, new GameProfile(uuid, "TomeFeedback")); }
        @Override public boolean isSpectator() { return spectator; }
        @Override public void displayClientMessage(Component message, boolean overlay) {
            notices.add(new Notice(message.getContents() instanceof TranslatableContents text
                    ? text.getKey() : message.getString(), overlay));
        }
        void tickStudy() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }

    private static Item tome(SpellCatalog spell) {
        return switch (spell) {
            case WATER_BOLT -> ModContent.WATER_TOME.get();
            case EARTH_WALL -> ModContent.EARTH_TOME.get();
            case AIR_BURST -> ModContent.AIR_TOME.get();
            case HEALING -> ModContent.HEALING_TOME.get();
            case FIRE_BALL -> ModContent.FIRE_TOME.get();
            case WATER_SHIELD -> ModContent.WATER_SHIELD_TOME.get();
            case MIRE -> ModContent.MIRE_TOME.get();
            case STONE_CANNON -> ModContent.STONE_CANNON_TOME.get();
        };
    }

    private static String alreadyKey(SpellCatalog spell) {
        return "message.jobless_reincarnation." + switch (spell) {
            case WATER_BOLT -> "already_learned";
            case EARTH_WALL -> "earth_already_learned";
            case AIR_BURST -> "air_already_learned";
            case HEALING -> "healing_already_learned";
            case FIRE_BALL -> "fire_already_learned";
            case WATER_SHIELD -> "water_shield.already_learned";
            case MIRE -> "mire.already_learned";
            case STONE_CANNON -> "stone_cannon.already_learned";
        };
    }

    private static String learnedKey(SpellCatalog spell) {
        return "message.jobless_reincarnation.learned_" + spell.id().getPath();
    }

    private static Reader reader(GameTestHelper helper, SpellCatalog spell, boolean learned) {
        Reader reader = new Reader(helper.getLevel());
        reader.getAbilities().instabuild = false;
        reader.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
        if (learned) reader.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
        else if (spell == SpellCatalog.MIRE) {
            // Only this mixed spell requires these two books; other fixtures stay unknown.
            reader.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
            reader.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        }
        if (!learned && spell == SpellCatalog.STONE_CANNON) reader.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        return reader;
    }

    private static InteractionResultHolder<ItemStack> use(Reader reader) {
        return reader.getMainHandItem().getItem().use(reader.level(), reader, InteractionHand.MAIN_HAND);
    }

    private static void notice(GameTestHelper helper, Reader reader, int count, String key) {
        helper.assertValueEqual(reader.notices.size(), count, "Exact private-message count");
        helper.assertValueEqual(reader.notices.get(count - 1), new Notice(key, true),
                "Correct actionbar message remains the latest feedback");
    }

    private static void consumed(GameTestHelper helper, Reader reader) {
        ItemStack original = reader.getMainHandItem();
        var result = use(reader);
        helper.assertValueEqual(result.getResult(), InteractionResult.CONSUME,
                "Server result consumes the request, including a suppressed reminder");
        helper.assertTrue(result.getResult().consumesAction(), "Suppression cannot fall through to the offhand");
        helper.assertTrue(result.getObject() == original, "Use returns the original reusable book");
        helper.assertFalse(reader.isUsingItem(), "Known book cannot restart studying");
    }

    private static void finishStudy(GameTestHelper helper, Reader reader, SpellCatalog spell) {
        ItemStack original = reader.getMainHandItem();
        use(reader);
        helper.assertTrue(reader.isUsingItem(), "Unknown tome begins actual item use");
        int duration = original.getItem().getUseDuration(original, reader);
        for (int tick = 0; tick < duration - 1; tick++) reader.tickStudy();
        helper.assertFalse(spell.knows(reader), "Knowledge is not granted before the study boundary");
        reader.tickStudy();
        helper.assertTrue(spell.knows(reader), "Actual completion grants the requested knowledge");
        helper.assertFalse(reader.isUsingItem(), "Actual completion ends study");
        helper.assertTrue(reader.getMainHandItem() == original && original.getCount() == 1,
                "Completed study preserves the original reusable tome");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fiveTomesAllowFirstReminderAndExactlyFortyTickRenewal(GameTestHelper helper) {
        List<Reader> readers = new ArrayList<>();
        long start = helper.getLevel().getServer().overworld().getGameTime();
        for (SpellCatalog spell : SpellCatalog.values()) {
            Reader reader = reader(helper, spell, true);
            readers.add(reader);
            consumed(helper, reader);
            notice(helper, reader, 1, alreadyKey(spell));
            consumed(helper, reader);
            notice(helper, reader, 1, alreadyKey(spell));
        }
        helper.runAfterDelay(39, () -> {
            helper.assertValueEqual(helper.getLevel().getServer().overworld().getGameTime() - start, 39L,
                    "Thirty-nine real server ticks elapsed");
            for (int index = 0; index < readers.size(); index++) {
                Reader reader = readers.get(index);
                consumed(helper, reader);
                notice(helper, reader, 1, alreadyKey(SpellCatalog.values()[index]));
            }
        });
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(helper.getLevel().getServer().overworld().getGameTime() - start, 40L,
                    "Forty real server ticks elapsed");
            for (int index = 0; index < readers.size(); index++) {
                Reader reader = readers.get(index);
                consumed(helper, reader);
                notice(helper, reader, 2, alreadyKey(SpellCatalog.values()[index]));
                consumed(helper, reader);
                notice(helper, reader, 2, alreadyKey(SpellCatalog.values()[index]));
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void remindersBelongToEachPlayerAndSpellNotEachBookStack(GameTestHelper helper) {
        Reader first = new Reader(helper.getLevel()), second = new Reader(helper.getLevel());
        for (SpellCatalog spell : SpellCatalog.values()) {
            first.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
            second.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
        }
        int count = 0;
        for (SpellCatalog spell : SpellCatalog.values()) {
            first.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            second.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            consumed(helper, first);
            consumed(helper, second);
            notice(helper, first, ++count, alreadyKey(spell));
            notice(helper, second, count, alreadyKey(spell));
            first.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            second.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            consumed(helper, first);
            consumed(helper, second);
            notice(helper, first, count, alreadyKey(spell));
            notice(helper, second, count, alreadyKey(spell));
        }
        first.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.WATER_BOLT).getDefaultInstance());
        consumed(helper, first);
        helper.assertValueEqual(first.notices.size(), count, "Switching through all books does not refill the first spell");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void respawnWithSameUuidAndEntityIdGetsIndependentReminderBudget(GameTestHelper helper) {
        Reader original = reader(helper, SpellCatalog.FIRE_BALL, true);
        consumed(helper, original);
        Reader replacement = new Reader(helper.getLevel(), original.getUUID());
        replacement.setId(original.getId());
        replacement.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.FIRE_BALL.id());
        replacement.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.FIRE_BALL).getDefaultInstance());
        helper.assertTrue(original != replacement, "Respawn creates a distinct player object");
        helper.assertValueEqual(replacement.getUUID(), original.getUUID(), "UUID is intentionally reused");
        helper.assertValueEqual(replacement.getId(), original.getId(), "Entity ID is intentionally reused");
        consumed(helper, replacement);
        notice(helper, replacement, 1, alreadyKey(SpellCatalog.FIRE_BALL));
        consumed(helper, original);
        consumed(helper, replacement);
        notice(helper, original, 1, alreadyKey(SpellCatalog.FIRE_BALL));
        notice(helper, replacement, 1, alreadyKey(SpellCatalog.FIRE_BALL));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void offhandDeadAndSpectatorRequestsNeverSpendReminderBudget(GameTestHelper helper) {
        for (SpellCatalog spell : SpellCatalog.values()) for (int context = 0; context < 3; context++) {
            Reader reader = reader(helper, spell, true);
            InteractionHand hand = InteractionHand.MAIN_HAND;
            switch (context) {
                case 0 -> {
                    hand = InteractionHand.OFF_HAND;
                    reader.setItemInHand(hand, reader.getMainHandItem());
                    reader.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                case 1 -> reader.setHealth(0);
                case 2 -> reader.spectator = true;
            }
            var result = tome(spell).use(reader.level(), reader, hand);
            helper.assertValueEqual(result.getResult(), InteractionResult.PASS, "Invalid context is rejected before feedback");
            helper.assertTrue(reader.notices.isEmpty(), "Invalid request cannot produce a private reminder");
            helper.assertFalse(reader.isUsingItem(), "Invalid request cannot start study");
            reader.spectator = false;
            reader.setHealth(reader.getMaxHealth());
            reader.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            reader.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            consumed(helper, reader);
            notice(helper, reader, 1, alreadyKey(spell));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void learningSuccessIsProtectedWhileAnotherUnknownTomeCanStillBeStudied(GameTestHelper helper) {
        List<Reader> readers = new ArrayList<>();
        long start = helper.getLevel().getServer().overworld().getGameTime();
        for (SpellCatalog spell : SpellCatalog.values()) {
            Reader reader = reader(helper, spell, false);
            readers.add(reader);
            finishStudy(helper, reader, spell);
            notice(helper, reader, 1, learnedKey(spell));
            consumed(helper, reader);
            notice(helper, reader, 1, learnedKey(spell));
            SpellCatalog selectedAfterFirst = SpellCatalog.selected(reader);
            helper.assertValueEqual(selectedAfterFirst, spell == SpellCatalog.MIRE ? SpellCatalog.WATER_BOLT : spell == SpellCatalog.STONE_CANNON ? SpellCatalog.EARTH_WALL : spell,
                    "First study preserves existing prerequisite selection, or selects the first learned spell");
            SpellCatalog other = java.util.Arrays.stream(SpellCatalog.values())
                    .filter(candidate -> candidate != SpellCatalog.MIRE && candidate != SpellCatalog.STONE_CANNON && !candidate.knows(reader))
                    .findFirst().orElseThrow();
            helper.assertFalse(other.knows(reader), "The second book is genuinely unknown, not a prerequisite already seeded");
            reader.setItemInHand(InteractionHand.MAIN_HAND, tome(other).getDefaultInstance());
            finishStudy(helper, reader, other);
            notice(helper, reader, 2, learnedKey(other));
            consumed(helper, reader);
            notice(helper, reader, 2, learnedKey(other));
            reader.setItemInHand(InteractionHand.MAIN_HAND, tome(spell).getDefaultInstance());
            consumed(helper, reader);
            notice(helper, reader, 2, learnedKey(other));
            helper.assertValueEqual(SpellCatalog.selected(reader), selectedAfterFirst, "Learning a second spell preserves a valid selection");
            helper.assertValueEqual(spell.practiceCount(reader), 0, "Studying does not grant casting practice");
            helper.assertValueEqual(other.practiceCount(reader), 0, "Second book cannot grant casting practice");
        }
        helper.runAfterDelay(39, () -> {
            helper.assertValueEqual(helper.getLevel().getServer().overworld().getGameTime() - start, 39L,
                    "Completion protection is measured in real world ticks");
            for (Reader reader : readers) {
                consumed(helper, reader);
                helper.assertValueEqual(reader.notices.size(), 2, "Suppressed reminders preserve the success message");
            }
        });
        helper.runAfterDelay(40, () -> {
            for (int index = 0; index < readers.size(); index++) {
                Reader reader = readers.get(index);
                consumed(helper, reader);
                notice(helper, reader, 3, alreadyKey(SpellCatalog.values()[index]));
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void repeatedKnownBooksPreserveGameplayInventoryAndCooldowns(GameTestHelper helper) {
        Reader reader = new Reader(helper.getLevel());
        for (SpellCatalog spell : SpellCatalog.values()) {
            reader.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
            for (int cast = 0; cast <= spell.ordinal(); cast++) reader.getData(ModAttachments.KNOWLEDGE).practice(spell.id());
            reader.getInventory().setItem(spell.ordinal(), tome(spell).getDefaultInstance());
        }
        reader.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 13));
        reader.setItemInHand(InteractionHand.OFF_HAND, Items.SHIELD.getDefaultInstance());
        reader.setData(ModAttachments.MANA, new ManaData(43, 100, 31, 17));
        reader.setData(ModAttachments.TRAINING, new TrainingProgress(2, true, true, 2));
        reader.getData(ModAttachments.CASTING).select(SpellCatalog.HEALING.id());
        reader.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), 35);
        for (SpellCatalog spell : SpellCatalog.values()) reader.getCooldowns().addCooldown(tome(spell), 11);
        for (int tick = 0; tick < 3; tick++) reader.getCooldowns().tick();
        float staffCooldown = reader.getCooldowns().getCooldownPercent(ModContent.TRAINING_STAFF.get(), 0);
        float bookCooldown = reader.getCooldowns().getCooldownPercent(tome(SpellCatalog.WATER_BOLT), 0);
        helper.assertTrue(staffCooldown > 0 && staffCooldown < 1 && bookCooldown > 0 && bookCooldown < 1,
                "Partially elapsed cooldowns expose accidental timer resets");
        var knowledge = reader.getData(ModAttachments.KNOWLEDGE).snapshot();
        List<ItemStack> inventory = new ArrayList<>();
        for (int slot = 0; slot < reader.getInventory().getContainerSize(); slot++)
            inventory.add(reader.getInventory().getItem(slot).copy());
        for (SpellCatalog spell : SpellCatalog.values()) {
            reader.getInventory().selected = spell.ordinal();
            for (int repeat = 0; repeat < 10; repeat++) consumed(helper, reader);
        }
        helper.assertValueEqual(reader.notices.size(), SpellCatalog.values().length, "Only one reminder per spell despite repeated requests");
        helper.assertValueEqual(reader.getData(ModAttachments.KNOWLEDGE).snapshot(), knowledge, "Knowledge and practice remain exact");
        helper.assertValueEqual(SpellCatalog.selected(reader), SpellCatalog.HEALING, "Reminders never choose a spell");
        helper.assertTrue(SpellCatalog.active(reader) == null, "Reminders do not acquire a casting lock");
        ManaData mana = reader.getData(ModAttachments.MANA);
        helper.assertValueEqual(mana.mana(), 43, "No mana spent");
        helper.assertValueEqual(mana.maximum(), 100, "Mana capacity unchanged");
        helper.assertValueEqual(mana.recoveryDelay(), 31, "Recovery timer unchanged");
        helper.assertValueEqual(mana.cooldown(), 17, "Spell cooldown unchanged");
        TrainingProgress training = reader.getData(ModAttachments.TRAINING);
        helper.assertValueEqual(training.targetHits(), 2, "Water hits unchanged");
        helper.assertTrue(training.chargedTargetHit() && training.claimed(), "Water completion flags unchanged");
        helper.assertValueEqual(training.fireTargetHits(), 2, "Fire hits unchanged");
        helper.assertTrue(reader.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Staff cooldown is retained");
        helper.assertValueEqual(reader.getCooldowns().getCooldownPercent(ModContent.TRAINING_STAFF.get(), 0), staffCooldown,
                "Staff cooldown has not advanced or restarted");
        for (SpellCatalog spell : SpellCatalog.values()) {
            helper.assertTrue(reader.getCooldowns().isOnCooldown(tome(spell)), "Existing book cooldown retained");
            helper.assertValueEqual(reader.getCooldowns().getCooldownPercent(tome(spell), 0), bookCooldown,
                    "Feedback introduces no change to existing item cooldowns");
        }
        for (int slot = 0; slot < inventory.size(); slot++)
            helper.assertTrue(ItemStack.matches(inventory.get(slot), reader.getInventory().getItem(slot)), "Inventory slot unchanged: " + slot);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void localTimestampRollbackRecoversWithoutChangingWorldTime(GameTestHelper helper) throws ReflectiveOperationException {
        Reader reader = reader(helper, SpellCatalog.FIRE_BALL, true);
        consumed(helper, reader);
        long now = helper.getLevel().getServer().overworld().getGameTime();
        Field field = TomeFeedback.class.getDeclaredField("LAST_FEEDBACK");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Player, EnumMap<SpellCatalog, Long>> records = (Map<Player, EnumMap<SpellCatalog, Long>>) field.get(null);
        // Simulate a rewind only in this unregistered test player's entry. Other tests share world time.
        records.get(reader).put(SpellCatalog.FIRE_BALL, now + 1);
        try {
            consumed(helper, reader);
            notice(helper, reader, 2, alreadyKey(SpellCatalog.FIRE_BALL));
            consumed(helper, reader);
            notice(helper, reader, 2, alreadyKey(SpellCatalog.FIRE_BALL));
            helper.assertValueEqual(helper.getLevel().getServer().overworld().getGameTime(), now, "Shared world time is never mutated");
        } finally {
            records.remove(reader);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void logoutClearsOnlyTheLeavingPlayersRuntimeReminder(GameTestHelper helper) {
        Reader leaving = reader(helper, SpellCatalog.WATER_BOLT, true);
        Reader staying = reader(helper, SpellCatalog.WATER_BOLT, true);
        consumed(helper, leaving);
        consumed(helper, staying);
        TomeFeedback.logout(new PlayerEvent.PlayerLoggedOutEvent(leaving));
        consumed(helper, leaving);
        notice(helper, leaving, 2, alreadyKey(SpellCatalog.WATER_BOLT));
        consumed(helper, staying);
        notice(helper, staying, 1, alreadyKey(SpellCatalog.WATER_BOLT));
        helper.succeed();
    }

    private TomeFeedbackGameTests() {}
}