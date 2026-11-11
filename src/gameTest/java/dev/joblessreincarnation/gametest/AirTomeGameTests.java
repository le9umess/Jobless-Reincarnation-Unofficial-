package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual tome-use and loaded datapack integration, excluded from release artifacts. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirTomeGameTests {
    private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "air_tome");
    private static final class Reader extends FakePlayer {
        Reader(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "AirReader")); }
        void tickStudy() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Reader reader(GameTestHelper helper) {
        Reader reader = new Reader(helper.getLevel());
        reader.getAbilities().instabuild = false;
        reader.setItemInHand(InteractionHand.MAIN_HAND, ModContent.AIR_TOME.get().getDefaultInstance());
        return reader;
    }
    private static void study(Reader reader, int ticks) {
        ModContent.AIR_TOME.get().use(reader.level(), reader, InteractionHand.MAIN_HAND);
        for (int tick = 0; tick < ticks; tick++) reader.tickStudy();
    }

    @GameTest(template = "empty", timeoutTicks = 1250)
    public static void firstTomeTeachesAirAtTheRealStudyBoundary(GameTestHelper helper) {
        Reader reader = reader(helper);
        ItemStack originalBook = reader.getMainHandItem();
        int duration = MagicConfig.STUDY_TICKS.getAsInt();
        int mana = reader.getData(ModAttachments.MANA).mana();
        ModContent.AIR_TOME.get().use(reader.level(), reader, InteractionHand.MAIN_HAND);
        helper.assertTrue(reader.isUsingItem(), "Main-hand reading starts actual vanilla item use");
        // Spread actual updateUsingItem callbacks across server ticks, including the completion boundary.
        for (int elapsed = 1; elapsed <= duration; elapsed++) {
            int tick = elapsed;
            helper.runAfterDelay(tick, () -> {
                reader.tickStudy();
                if (tick < duration) {
                    helper.assertFalse(SpellCatalog.AIR_BURST.knows(reader), "Unfinished reading grants no air knowledge");
                    return;
                }
                helper.assertTrue(SpellCatalog.AIR_BURST.knows(reader), "Completed study learns air");
                helper.assertFalse(SpellCatalog.WATER_BOLT.knows(reader), "Learning air cannot grant water");
                helper.assertValueEqual(SpellCatalog.selected(reader), SpellCatalog.AIR_BURST, "First known air spell becomes valid fallback");
                helper.assertValueEqual(reader.getData(ModAttachments.CASTING).selectedSpell(), SpellCatalog.AIR_BURST.id(), "Fallback is stored authoritatively");
                helper.assertValueEqual(SpellCatalog.AIR_BURST.practiceCount(reader), 0, "Study is not successful casting practice");
                helper.assertValueEqual(reader.getData(ModAttachments.MANA).mana(), mana, "Study does not consume mana");
                helper.assertTrue(reader.getMainHandItem() == originalBook && originalBook.getCount() == 1, "The same book remains reusable");
                helper.assertFalse(reader.isUsingItem(), "Completed reading stops vanilla use");
                helper.assertTrue(SpellCatalog.active(reader) == null, "Reading does not acquire a casting lock");
                helper.succeed();
            });
        }
    }

    @GameTest(template = "empty")
    public static void learningAirPreservesBothOlderSpellsAndSelection(GameTestHelper helper) {
        Reader reader = reader(helper);
        reader.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        for (int i = 0; i < 7; i++) reader.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        reader.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        for (int i = 0; i < 3; i++) reader.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.EARTH_WALL.id());
        reader.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        reader.getData(ModAttachments.TRAINING).recordTargetHit(true);
        SpellCatalog.normalizeSelection(reader);
        study(reader, MagicConfig.STUDY_TICKS.getAsInt());
        helper.assertTrue(SpellCatalog.AIR_BURST.knows(reader), "Second tome teaches air");
        helper.assertValueEqual(WaterMagic.practiceCount(reader), 7, "Learning another spell does not reset water practice");
        helper.assertValueEqual(SpellCatalog.selected(reader), SpellCatalog.EARTH_WALL, "A valid selected spell is not forcibly switched");
        helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(reader), 3, "Earth practice remains independent");
        helper.assertValueEqual(reader.getData(ModAttachments.TRAINING).targetHits(), 1, "Air study cannot add water board hits");
        helper.assertTrue(reader.getData(ModAttachments.TRAINING).chargedTargetHit(), "Existing water board progress is retained");
        helper.assertFalse(reader.getData(ModAttachments.TRAINING).claimed(), "Air study cannot claim a water board reward");
        helper.assertValueEqual(SpellCatalog.AIR_BURST.practiceCount(reader), 0, "Air starts independently at zero practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void earlyReleaseOrBookChangeDoesNotFinishStudy(GameTestHelper helper) {
        Reader reader = reader(helper);
        int duration = MagicConfig.STUDY_TICKS.getAsInt();
        study(reader, duration - 1);
        reader.releaseUsingItem();
        for (int tick = 0; tick < duration + 2; tick++) reader.tickStudy();
        helper.assertFalse(SpellCatalog.AIR_BURST.knows(reader), "One-tick-early release cannot learn later");
        study(reader, Math.max(0, duration - 1));
        reader.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        reader.tickStudy();
        for (int tick = 0; tick < duration + 2; tick++) reader.tickStudy();
        helper.assertFalse(SpellCatalog.AIR_BURST.knows(reader), "Changing to another item cancels the book");
        helper.assertFalse(reader.isUsingItem(), "Changed item stops study");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void offhandAndRepeatedReadingCannotGrantPractice(GameTestHelper helper) {
        Reader reader = reader(helper);
        reader.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        reader.setItemInHand(InteractionHand.OFF_HAND, ModContent.AIR_TOME.get().getDefaultInstance());
        ModContent.AIR_TOME.get().use(reader.level(), reader, InteractionHand.OFF_HAND);
        helper.assertFalse(reader.isUsingItem(), "Offhand book does not begin studying");
        reader.setItemInHand(InteractionHand.MAIN_HAND, ModContent.AIR_TOME.get().getDefaultInstance());
        study(reader, MagicConfig.STUDY_TICKS.getAsInt());
        reader.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.AIR_BURST.id());
        study(reader, MagicConfig.STUDY_TICKS.getAsInt());
        helper.assertFalse(reader.isUsingItem(), "Known tome reports progress without another study use");
        helper.assertValueEqual(SpellCatalog.AIR_BURST.practiceCount(reader), 1, "Re-reading preserves existing practice exactly");
        helper.assertFalse(SpellCatalog.WATER_BOLT.knows(reader), "Re-reading cannot unlock another spell");
        helper.assertValueEqual(reader.getMainHandItem().getCount(), 1, "Repeated reading preserves the book");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedAirRecipeIsShapelessAndConsumesOnlyItsIngredients(GameTestHelper helper) {
        Item[] materials = {Items.BOOK, Items.AMETHYST_SHARD, Items.FEATHER};
        int[][] orders = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
        for (int[] order : orders) {
            List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            grid.set(0, new ItemStack(materials[order[0]]));
            grid.set(4, new ItemStack(materials[order[1]]));
            grid.set(8, new ItemStack(materials[order[2]]));
            CraftingInput input = CraftingInput.of(3, 3, grid);
            var holder = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
            helper.assertValueEqual(holder.id(), RECIPE, "Loaded datapack matches every ingredient order with gaps");
            ItemStack result = holder.value().assemble(input, helper.getLevel().registryAccess());
            helper.assertTrue(result.is(ModContent.AIR_TOME.get()) && result.getCount() == 1, "Crafting produces exactly one registered air tome");
            helper.assertTrue(holder.value().getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "Air ingredients have no unrelated container remainder");
        }
        CraftingInput wrong = CraftingInput.of(3, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.DIRT)));
        var wrongMatch = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, wrong, helper.getLevel());
        helper.assertTrue(wrongMatch.isEmpty() || !wrongMatch.get().id().equals(RECIPE), "Dirt cannot silently replace the feather ingredient");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedAdvancementUnlocksAirRecipeWithEitherCriterion(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "recipes/air_tome");
        var holder = helper.getLevel().getServer().getAdvancements().get(id);
        helper.assertTrue(holder != null, "Air recipe advancement survives actual datapack decoding");
        var advancement = holder.value();
        helper.assertTrue(advancement.requirements().test("has_feather"::equals), "Having feather independently satisfies the unlock requirement");
        helper.assertTrue(advancement.requirements().test("has_the_recipe"::equals), "Already knowing recipe independently satisfies the requirement");
        helper.assertFalse(advancement.requirements().test(name -> false), "Unknown criteria cannot complete the advancement");
        helper.assertTrue(advancement.rewards().recipes().contains(RECIPE), "Loaded reward unlocks the actual air recipe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packagedAirModelsAndLocalizationsAreAvailable(GameTestHelper helper) {
        for (String model : List.of("item/air_tome")) {
            JsonObject json = resource("/assets/jobless_reincarnation/models/" + model + ".json");
            helper.assertTrue(json.has("textures") && !json.getAsJsonObject("textures").isEmpty(), "Model has bound materials: " + model);
            helper.assertTrue(json.has("elements") && !json.getAsJsonArray("elements").isEmpty(), "Model provides geometry: " + model);
        }
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject json = resource("/assets/jobless_reincarnation/lang/" + language + ".json");
            for (String key : List.of("item.jobless_reincarnation.air_tome",
                    "spell.jobless_reincarnation.air_burst", "message.jobless_reincarnation.learned_air_burst")) {
                helper.assertTrue(json.has(key) && !json.get(key).getAsString().isBlank(), "Localized visible air text exists: " + language + "/" + key);
            }
        }
        helper.succeed();
    }

    private static JsonObject resource(String path) {
        var stream = AirTomeGameTests.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing packaged resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read packaged resource: " + path, failure); }
    }
    private AirTomeGameTests() {}
}
