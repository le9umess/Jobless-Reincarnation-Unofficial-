package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server authority and cancellation regressions through the same API used by production payloads. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JournalSelectionGameTests {
    private static final class Caster extends FakePlayer {
        boolean spectator;
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "JournalTest")); }
        @Override public boolean isSpectator() { return spectator; }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Caster player(GameTestHelper helper, boolean all) {
        Caster player = new Caster(helper.getLevel());
        player.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
        player.setYRot(0); player.setXRot(0);
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
        if (all) for (SpellCatalog spell : SpellCatalog.values()) player.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.getData(ModAttachments.CASTING).select(SpellCatalog.WATER_BOLT.id());
        return player;
    }
    private static void charged(Caster player) {
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        int ticks = SpellCatalog.WATER_BOLT.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt();
        for (int i = 0; i < ticks; i++) player.tickUse();
    }
    private static void free(GameTestHelper helper, Caster player) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "No mana payment");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "No cooldown starts");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).recoveryDelay(), 0, "No recovery delay starts");
        for (SpellCatalog spell : SpellCatalog.values()) helper.assertValueEqual(spell.practiceCount(player), 0, "No practice credit: " + spell);
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
    }
    private static void intact(GameTestHelper helper, Caster player, Map<ResourceLocation, Integer> knowledge) {
        helper.assertValueEqual(player.getData(ModAttachments.KNOWLEDGE).snapshot(), knowledge, "Knowledge/practice unchanged");
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.WATER_BOLT, "Rejected request keeps selection");
    }
    private static Caster wall(GameTestHelper helper) {
        Caster player = player(helper, true);
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        WallIdentity identity = new WallIdentity(player.getUUID(), UUID.randomUUID(), helper.absolutePos(new BlockPos(5, 2, 6)), Direction.Axis.X);
        for (BlockPos cell : identity.footprint()) {
            helper.getLevel().setBlock(cell, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
            ((EarthWallBlockEntity)helper.getLevel().getBlockEntity(cell)).bind(identity);
        }
        helper.assertTrue(EarthWallDismissal.plan(player).valid(), "Owned wall visible");
        return player;
    }

    @GameTest(template = "empty")
    public static void directChoicePreservesPracticeManaAndBothTimers(GameTestHelper helper) {
        Caster player = player(helper, true), other = player(helper, true);
        for (SpellCatalog spell : SpellCatalog.values()) for (int i = 0; i <= spell.ordinal(); i++) player.getData(ModAttachments.KNOWLEDGE).practice(spell.id());
        Map<ResourceLocation, Integer> before = player.getData(ModAttachments.KNOWLEDGE).snapshot();
        player.setData(ModAttachments.MANA, new ManaData(52, 100, 33, 17));
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), 35);
        for (SpellCatalog spell : SpellCatalog.values()) {
            helper.assertTrue(SpellCasting.select(player, spell.id()), "Learned spell can be chosen: " + spell);
            helper.assertValueEqual(SpellCatalog.selected(player), spell, "Exact requested selection");
            helper.assertValueEqual(player.getData(ModAttachments.KNOWLEDGE).snapshot(), before, "Choice does not train/teach");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 52, "Choice spends no mana");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 17, "Shared cooldown unchanged");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).recoveryDelay(), 33, "Recovery delay unchanged");
            helper.assertTrue(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Item cooldown survives");
            helper.assertValueEqual(SpellCatalog.selected(other), SpellCatalog.WATER_BOLT, "Other player unaffected");
        }
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        helper.assertFalse(player.isUsingItem(), "Selection cannot bypass the existing cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownAndUnlearnedChoicesCannotMutateOrInterrupt(GameTestHelper helper) {
        Caster player = player(helper, false);
        charged(player);
        int ticks = player.getTicksUsingItem();
        Map<ResourceLocation, Integer> before = player.getData(ModAttachments.KNOWLEDGE).snapshot();
        for (ResourceLocation id : new ResourceLocation[]{ResourceLocation.parse("jobless_reincarnation:missing"), ResourceLocation.parse("other:water_bolt"), SpellCatalog.HEALING.id()}) {
            helper.assertFalse(SpellCasting.select(player, id), "Unknown/unlearned request rejected");
            helper.assertTrue(player.isUsingItem(), "Rejected request cannot cancel existing chant");
            helper.assertValueEqual(SpellCatalog.active(player), SpellCatalog.WATER_BOLT, "Original spell lock survives");
            helper.assertValueEqual(player.getTicksUsingItem(), ticks, "Original progress survives");
            intact(helper, player, before);
        }
        SpellCasting.cancel(player);
        free(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidPlayerContextsHaveNoSelectionSideEffects(GameTestHelper helper) {
        for (int context = 0; context < 5; context++) {
            Caster player = player(helper, true);
            Map<ResourceLocation, Integer> before = player.getData(ModAttachments.KNOWLEDGE).snapshot();
            switch (context) {
                case 0 -> player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                case 1 -> {
                    player.setItemInHand(InteractionHand.OFF_HAND, player.getMainHandItem());
                    player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
                }
                case 2 -> player.containerMenu = ChestMenu.threeRows(1, player.getInventory(), new SimpleContainer(27));
                case 3 -> player.spectator = true;
                case 4 -> player.setHealth(0);
            }
            helper.assertFalse(SpellCasting.select(player, SpellCatalog.HEALING.id()), "Invalid context rejected: " + context);
            intact(helper, player, before);
            free(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameChoicePreservesReadyCastUntilPlayerReleases(GameTestHelper helper) {
        Caster player = player(helper, true);
        charged(player);
        helper.assertTrue(player.isUsingItem(), "Charged water fixture");
        helper.assertTrue(SpellCasting.select(player, SpellCatalog.WATER_BOLT.id()), "Same-spell explicit choice accepted");
        helper.assertTrue(player.isUsingItem(), "Recalling the current spell preserves held input");
        helper.assertValueEqual(SpellCatalog.active(player), SpellCatalog.WATER_BOLT, "The same cast stays locked");
        free(helper, player);
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(player), 1, "Only the later real release casts");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void differentChoiceCannotTransferChargeOrReleaseOldSpell(GameTestHelper helper) {
        Caster player = player(helper, true);
        charged(player);
        helper.assertTrue(SpellCasting.select(player, SpellCatalog.AIR_BURST.id()), "Direct air selection accepted");
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.AIR_BURST, "Air selected");
        free(helper, player);
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        player.tickUse();
        helper.assertValueEqual(player.getTicksUsingItem(), 1, "New spell starts from first tick");
        player.releaseUsingItem();
        free(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void openingJournalCancelsReadyCastButKeepsSelection(GameTestHelper helper) {
        Caster player = player(helper, true);
        charged(player);
        SpellCasting.openJournal(player);
        helper.assertFalse(player.isUsingItem(), "Journal stops charged use");
        helper.assertTrue(SpellCatalog.active(player) == null, "Journal clears cast lock");
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.WATER_BOLT, "Viewing does not select another spell");
        player.releaseUsingItem();
        free(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void journalCancelsAllFiveTomesBeforeCompletionWithoutTeaching(GameTestHelper helper) {
        Item[] tomes = {ModContent.WATER_TOME.get(), ModContent.EARTH_TOME.get(), ModContent.AIR_TOME.get(), ModContent.HEALING_TOME.get(), ModContent.FIRE_TOME.get()};
        for (int i = 0; i < tomes.length; i++) {
            Caster player = new Caster(helper.getLevel());
            ItemStack tome = tomes[i].getDefaultInstance();
            player.setItemInHand(InteractionHand.MAIN_HAND, tome);
            tomes[i].use(player.level(), player, InteractionHand.MAIN_HAND);
            int ticks = tomes[i].getUseDuration(tome, player);
            for (int tick = 0; tick < ticks - 1; tick++) player.tickUse();
            helper.assertTrue(player.isUsingItem(), "Tome is about to complete: " + i);
            SpellCasting.openJournal(player);
            player.tickUse();
            helper.assertFalse(player.isUsingItem(), "Journal cancels study: " + i);
            for (SpellCatalog spell : SpellCatalog.values()) helper.assertFalse(spell.knows(player), "No knowledge granted");
            helper.assertTrue(player.getMainHandItem() == tome && tome.getCount() == 1, "Reusable tome unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyHandViewingAndUnrelatedItemUseStayUntouched(GameTestHelper helper) {
        Caster empty = new Caster(helper.getLevel());
        SpellCasting.openJournal(empty);
        helper.assertFalse(empty.hasData(ModAttachments.KNOWLEDGE), "Empty-hand viewing does not create knowledge");
        helper.assertFalse(empty.hasData(ModAttachments.CASTING), "Empty-hand viewing does not select fallback");
        for (Item item : new Item[]{Items.APPLE, Items.BOW}) {
            Caster player = new Caster(helper.getLevel());
            player.setItemInHand(InteractionHand.MAIN_HAND, item.getDefaultInstance());
            player.startUsingItem(InteractionHand.MAIN_HAND);
            player.tickUse();
            int ticks = player.getTicksUsingItem();
            helper.assertTrue(player.isUsingItem(), "Unrelated use fixture active");
            SpellCasting.openJournal(player);
            helper.assertTrue(player.isUsingItem(), "Pure cancel leaves food/bow alone");
            helper.assertValueEqual(player.getTicksUsingItem(), ticks, "Food/bow timer untouched");
            player.stopUsingItem();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void openingJournalCancelsReadyDismissalAndPreservesWall(GameTestHelper helper) {
        Caster player = wall(helper);
        var cells = EarthWallDismissal.plan(player).positions();
        helper.assertTrue(EarthWallDismantling.begin(player, 81), "Dismissal begins");
        helper.runAfterDelay(MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt(), () -> {
            EarthWallDismantling.tick(player);
            SpellCasting.openJournal(player);
            helper.assertFalse(EarthWallDismantling.active(player), "Journal clears H session");
            helper.assertFalse(EarthWallDismantling.release(player, 81), "Late release cannot remove wall");
            for (BlockPos cell : cells) helper.assertTrue(helper.getLevel().getBlockState(cell).is(ModContent.EARTH_WALL.get()), "Original wall intact");
            helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.EARTH_WALL, "Journal keeps earth selection");
            free(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void dismissalSurvivesSameChoiceUntilADifferentSpellIsSelected(GameTestHelper helper) {
        Caster player = wall(helper);
        var cells = EarthWallDismissal.plan(player).positions();
        helper.assertTrue(EarthWallDismantling.begin(player, 91), "Dismissal begins");
        helper.assertFalse(SpellCasting.select(player, ResourceLocation.parse("jobless_reincarnation:missing")), "Unknown ID rejected");
        helper.assertTrue(EarthWallDismantling.active(player), "Rejected selection leaves H intact");
        helper.assertTrue(SpellCasting.select(player, SpellCatalog.EARTH_WALL.id()), "Explicit same earth choice accepted");
        helper.assertTrue(EarthWallDismantling.active(player), "Looking at the same spell leaves H intact");
        helper.assertTrue(SpellCasting.select(player, SpellCatalog.WATER_BOLT.id()), "A different known spell can be selected");
        helper.assertFalse(EarthWallDismantling.active(player), "Changing spells cancels H");
        helper.assertFalse(EarthWallDismantling.release(player, 91), "Stale H release cannot resolve");
        for (BlockPos cell : cells) helper.assertTrue(helper.getLevel().getBlockState(cell).is(ModContent.EARTH_WALL.get()), "No wall removed");
        free(helper, player);
        helper.succeed();
    }
    private JournalSelectionGameTests() {}
}
