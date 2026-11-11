package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaEvents;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only server lifecycle regressions; no client simulation or private network claims. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellSelectionGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "SelectTest")); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Caster player(GameTestHelper helper, boolean both) {
        Caster player = new Caster(helper.getLevel());
        player.setPos(helper.absoluteVec(new Vec3(4, 2, 4)));
        player.setYRot(0);
        player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
        if (both) player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        return player;
    }
    private static void hold(Caster player, int ticks) {
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < ticks; i++) player.tickUse();
    }
    private static int ready(Caster player) {
        return SpellCatalog.WATER_BOLT.chantTicks(player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt();
    }
    @GameTest(template = "empty")
    public static void recallSlotsStayStableAcrossLearningAndEmptyHandedSwaps(GameTestHelper h) {
        Caster p = player(h, false);
        p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.FIRE_BALL.id());
        SpellCatalog.normalizeSelection(p);
        var data = p.getData(ModAttachments.CASTING);
        h.assertValueEqual(data.spellAt(p, 5), SpellCatalog.FIRE_BALL, "Fire is in slot 6 even when learned second");
        h.assertTrue(data.spellAt(p, 1) == null, "Unlearned shields leave a hole");
        p.setItemInHand(InteractionHand.MAIN_HAND, Items.AIR.getDefaultInstance());
        h.assertTrue(SpellCasting.moveSlot(p, SpellCatalog.FIRE_BALL.id(), 1, data.revision()), "Empty-handed journal moves fire into the shield slot");
        p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_SHIELD.id());
        SpellCatalog.normalizeSelection(p);
        h.assertValueEqual(data.spellAt(p, 1), SpellCatalog.FIRE_BALL, "Learning cannot displace an existing slot");
        h.assertValueEqual(data.spellAt(p, 2), SpellCatalog.WATER_SHIELD, "New shield uses first free slot");
        int revision = data.revision();
        h.assertTrue(SpellCasting.moveSlot(p, SpellCatalog.WATER_BOLT.id(), 1, revision), "Occupied slots can swap");
        h.assertFalse(SpellCasting.moveSlot(p, SpellCatalog.WATER_BOLT.id(), 3, revision), "Old revision cannot replay the rearrangement");
        h.assertValueEqual(data.spellAt(p, 0), SpellCatalog.FIRE_BALL, "Swap preserves the displaced spell");
        h.assertValueEqual(SpellCatalog.selected(p), SpellCatalog.WATER_BOLT, "Rearranging never selects or casts");
        h.assertTrue(data.previousSpell() == null, "No previous spell invented by a move");
        Caster restored = new Caster(h.getLevel());
        restored.load(p.saveWithoutId(new CompoundTag()));
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(restored));
        h.assertValueEqual(restored.getData(ModAttachments.CASTING).spellAt(restored, 0), SpellCatalog.FIRE_BALL, "Real player NBT preserves slot IDs");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void quickRecallAcknowledgesOnlyValidDifferentChoices(GameTestHelper h) {
        Caster p = player(h, true), other = player(h, true);
        SpellCatalog.normalizeSelection(p);
        var data = p.getData(ModAttachments.CASTING);
        int first = data.revision();
        h.assertTrue(data.previousSpell() == null, "An old character has no previous spell");
        hold(p, ready(p));
        h.assertFalse(SpellCasting.recall(p, 0, SpellCatalog.WATER_BOLT.id(), first), "Current spell is a no-op");
        h.assertFalse(SpellCasting.recall(p, 8, SpellCatalog.EARTH_WALL.id(), first), "Empty slot cannot choose a forged ID");
        h.assertFalse(SpellCasting.recall(p, 2, SpellCatalog.WATER_BOLT.id(), first), "Wrong expected slot identity is rejected");
        h.assertFalse(SpellCasting.recall(p, 2, SpellCatalog.EARTH_WALL.id(), first - 1), "Expired input cannot interrupt");
        h.assertTrue(p.isUsingItem() && data.activeSpell() != null, "Rejected input leaves charge intact");
        h.assertTrue(SpellCasting.recall(p, -1, SpellCatalog.EARTH_WALL.id(), first), "First tap falls back to next learned spell");
        p.releaseUsingItem();
        assertFree(h, p, "Confirmed change followed by release");
        h.assertFalse(SpellCasting.recall(p, -1, SpellCatalog.WATER_BOLT.id(), first), "Duplicate input cannot toggle back");
        h.assertTrue(SpellCasting.recall(p, -1, SpellCatalog.WATER_BOLT.id(), data.revision()), "Next real tap selects the previous spell");
        h.assertTrue(SpellCasting.recall(p, -1, SpellCatalog.EARTH_WALL.id(), data.revision()), "Two spells alternate without cycling through the catalog");
        h.assertValueEqual(SpellCatalog.selected(other), SpellCatalog.WATER_BOLT, "Character selections remain independent");
        var saved = p.saveWithoutId(new CompoundTag());
        var restored = new Caster(h.getLevel());
        restored.load(saved);
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(restored));
        h.assertValueEqual(restored.getData(ModAttachments.CASTING).previousSpell(), SpellCatalog.WATER_BOLT.id(), "Previous spell survives rejoin");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oldAndMalformedRecallSlotsRecoverOnlyLearnedSpells(GameTestHelper h) {
        Caster p = player(h, true);
        var stored = new CompoundTag();
        stored.putString("selectedSpell", SpellCatalog.EARTH_WALL.id().toString());
        stored.putString("previousSpell", SpellCatalog.HEALING.id().toString());
        var slots = new net.minecraft.nbt.ListTag();
        for (String id : new String[] {"other:missing", "INVALID ID", "jobless_reincarnation:earth_wall",
                "jobless_reincarnation:earth_wall", "jobless_reincarnation:healing"})
            slots.add(net.minecraft.nbt.StringTag.valueOf(id));
        stored.put("recallSlots", slots);
        p.setData(ModAttachments.CASTING, CastingData.CODEC.parse(NbtOps.INSTANCE, stored).getOrThrow());
        SpellCatalog.normalizeSelection(p);
        var data = p.getData(ModAttachments.CASTING);
        h.assertValueEqual(data.spellAt(p, 0), SpellCatalog.WATER_BOLT, "Known missing spell regains its default slot");
        h.assertTrue(data.spellAt(p, 1) == null && data.spellAt(p, 3) == null && data.spellAt(p, 4) == null, "Invalid, duplicate and unlearned slots discarded");
        h.assertTrue(data.previousSpell() == null && !SpellCatalog.HEALING.knows(p), "No leaked knowledge or invalid previous");
        h.assertValueEqual(data.spellAt(p, 2), SpellCatalog.EARTH_WALL, "Valid assignment preserved");
        h.succeed();
    }

    private static void assertFree(GameTestHelper helper, Caster player, String reason) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), MagicConfig.MAX_MANA.getAsInt(), reason + " mana");
        helper.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(player), 0, reason + " water practice");
        helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(player), 0, reason + " earth practice");
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
    }

    @GameTest(template = "empty")
    public static void oldAndUnknownSelectionFallsBackWithoutGrantingKnowledge(GameTestHelper helper) {
        Caster old = player(helper, false);
        old.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
        old.setData(ModAttachments.MANA, new ManaData(37, 100, 31, 11));
        helper.assertFalse(old.hasData(ModAttachments.CASTING), "Old fixture has no selection attachment");
        CompoundTag saved = old.saveWithoutId(new CompoundTag());
        Caster loaded = player(helper, false);
        loaded.load(saved);
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(loaded));
        helper.assertValueEqual(SpellCatalog.selected(loaded), SpellCatalog.WATER_BOLT, "Existing water save selects water");
        helper.assertValueEqual(WaterMagic.practiceCount(loaded), 1, "Old practice survives upgrade");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).mana(), 37, "Old mana survives upgrade");
        loaded.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        helper.assertValueEqual(SpellCatalog.normalizeSelection(loaded), SpellCatalog.WATER_BOLT, "Unlearned selection falls back");
        helper.assertFalse(SpellCatalog.EARTH_WALL.knows(loaded), "Fallback cannot teach a spell");
        Caster earthOnly = new Caster(helper.getLevel());
        earthOnly.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        earthOnly.getData(ModAttachments.CASTING).select(ResourceLocation.parse("other:removed_spell"));
        helper.assertValueEqual(SpellCatalog.normalizeSelection(earthOnly), SpellCatalog.EARTH_WALL, "Unknown id uses first learned spell");
        Caster novice = new Caster(helper.getLevel());
        helper.assertValueEqual(SpellCatalog.normalizeSelection(novice), SpellCatalog.WATER_BOLT, "Empty knowledge displays water");
        helper.assertFalse(SpellCatalog.WATER_BOLT.knows(novice), "Default display grants no knowledge");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void saveDropsActiveLockAndOwnerCodecRetainsDisplayState(GameTestHelper helper) {
        Caster player = player(helper, true);
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        hold(player, 1);
        CastingData original = player.getData(ModAttachments.CASTING);
        helper.assertValueEqual(SpellCatalog.active(player), SpellCatalog.EARTH_WALL, "Fixture holds earth spell");
        var encoded = CastingData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CastingData restored = CastingData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        helper.assertValueEqual(restored.selectedSpell(), SpellCatalog.EARTH_WALL.id(), "Save retains selection");
        helper.assertTrue(restored.activeSpell() == null, "Save does not retain active cast");
        var buffer = Unpooled.buffer();
        try {
            CastingData.STREAM_CODEC.encode(buffer, original);
            CastingData client = CastingData.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(client.activeSpell(), SpellCatalog.EARTH_WALL.id(), "Owner HUD receives locked spell");
            client.select(SpellCatalog.WATER_BOLT.id());
            helper.assertValueEqual(original.selectedSpell(), SpellCatalog.EARTH_WALL.id(), "Decoded selection is independent");
        } finally { buffer.release(); }
        CastingData empty = CastingData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        helper.assertValueEqual(empty.selectedSpell(), SpellCatalog.WATER_BOLT.id(), "Missing field defaults to water");
        CompoundTag malformed = new CompoundTag();
        malformed.putString("selectedSpell", "Invalid ID!");
        helper.assertValueEqual(CastingData.CODEC.parse(NbtOps.INSTANCE, malformed).getOrThrow().selectedSpell(), SpellCatalog.WATER_BOLT.id(), "Malformed field safely defaults");
        player.stopUsingItem();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deathCloneKeepsSelectionButNoSharedRuntimeState(GameTestHelper helper) {
        Caster original = player(helper, true);
        original.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        hold(original, 1);
        Caster respawned = player(helper, false);
        respawned.copyAttachmentsFrom(original, true);
        ManaEvents.onClone(new PlayerEvent.Clone(respawned, original, true));
        ManaEvents.onRespawn(new PlayerEvent.PlayerRespawnEvent(respawned, false));
        helper.assertValueEqual(SpellCatalog.selected(respawned), SpellCatalog.EARTH_WALL, "Death retains learned selection");
        helper.assertTrue(SpellCatalog.active(original) == null && SpellCatalog.active(respawned) == null, "Neither clone retains active spell");
        helper.assertFalse(original.isUsingItem(), "Old entity's use is cancelled");
        respawned.getData(ModAttachments.CASTING).select(SpellCatalog.WATER_BOLT.id());
        helper.assertValueEqual(original.getData(ModAttachments.CASTING).selectedSpell(), SpellCatalog.EARTH_WALL.id(), "Clone selection is independent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void switchThenReleaseCannotCastOrTransferCharge(GameTestHelper helper) {
        Caster player = player(helper, true);
        Caster other = player(helper, true);
        hold(player, ready(player));
        helper.assertTrue(SpellCasting.cycle(player), "Both learned spells can cycle");
        helper.assertFalse(player.isUsingItem(), "Switch cancels using without releasing");
        helper.assertTrue(SpellCatalog.active(player) == null, "Switch clears active lock");
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.EARTH_WALL, "Server selected earth");
        helper.assertValueEqual(SpellCatalog.selected(other), SpellCatalog.WATER_BOLT, "Other player's selection is independent");
        assertFree(helper, player, "Switch plus same-tick release");
        hold(player, 1);
        helper.assertValueEqual(player.getTicksUsingItem(), 1, "New earth use starts at its own first tick");
        player.releaseUsingItem();
        assertFree(helper, player, "New spell early release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneLearnedSpellDoesNotInterruptAnActiveCast(GameTestHelper helper) {
        Caster player = player(helper, false);
        hold(player, SpellCatalog.WATER_BOLT.chantTicks(player));
        helper.assertFalse(SpellCasting.cycle(player), "No alternative spell means no switch");
        helper.assertTrue(player.isUsingItem(), "Single-spell request keeps current chant");
        helper.assertValueEqual(SpellCatalog.active(player), SpellCatalog.WATER_BOLT, "Current lock remains");
        player.releaseUsingItem();
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Original cast still resolves once");
        helper.assertEntityPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releaseUsesLockedSpellEvenIfSelectionChanges(GameTestHelper helper) {
        Caster player = player(helper, true);
        hold(player, SpellCatalog.WATER_BOLT.chantTicks(player));
        // Simulate an independent server-side selection mutation; normal cycle cancels instead.
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        player.releaseUsingItem();
        helper.assertEntityPresent(ModContent.WATER_BOLT.get());
        helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "Original water spell receives practice");
        helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(player), 0, "New selection gains no practice");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), MagicConfig.MAX_MANA.getAsInt() - MagicConfig.WATER_BOLT_COST.getAsInt(), "Original spell determines cost");
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.EARTH_WALL, "Release does not rewrite selection");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameItemReplacementAndForcedStartCannotBypassLock(GameTestHelper helper) {
        Caster player = player(helper, true);
        hold(player, ready(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.tickUse();
        player.releaseUsingItem();
        assertFree(helper, player, "Equal-item replacement");
        helper.assertTrue(SpellCatalog.active(player) == null, "Stale release clears lock");
        Caster novice = new Caster(helper.getLevel());
        novice.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        novice.startUsingItem(InteractionHand.MAIN_HAND);
        helper.assertFalse(novice.isUsingItem(), "Direct vanilla start still validates knowledge");
        player.startUsingItem(InteractionHand.MAIN_HAND);
        helper.assertValueEqual(SpellCatalog.active(player), SpellCatalog.WATER_BOLT, "Legitimate direct start acquires lock");
        player.stopUsingItem();
        helper.assertTrue(SpellCatalog.active(player) == null, "Ordinary stop immediately clears lock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dimensionLogoutAndSneakReleaseCancelWithoutSettling(GameTestHelper helper) {
        Caster player = player(helper, true);
        hold(player, ready(player));
        ManaEvents.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, Level.NETHER));
        player.releaseUsingItem();
        assertFree(helper, player, "Dimension lifecycle");
        hold(player, ready(player));
        ManaEvents.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
        player.releaseUsingItem();
        assertFree(helper, player, "Logout lifecycle");
        hold(player, ready(player));
        player.setShiftKeyDown(true);
        ManaEvents.onPlayerTick(new PlayerTickEvent.Post(player));
        player.releaseUsingItem();
        player.setShiftKeyDown(false);
        assertFree(helper, player, "Sneak release lifecycle");
        helper.assertFalse(player.isUsingItem(), "Lifecycle cancellation stops vanilla use");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cyclingCannotBypassWaterCooldownAndInvalidContextsAreIgnored(GameTestHelper helper) {
        Caster player = player(helper, true);
        helper.assertTrue(WaterMagic.cast(player), "Water cast creates shared mana cooldown");
        int delay = player.getData(ModAttachments.MANA).recoveryDelay();
        int cooldown = player.getData(ModAttachments.MANA).cooldown();
        helper.assertTrue(SpellCasting.cycle(player), "Cycling is allowed during cooldown");
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        helper.assertFalse(player.isUsingItem(), "Earth cannot start during water cooldown");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), cooldown, "Switch preserves shared cooldown");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).recoveryDelay(), delay, "Switch preserves recovery delay");
        Caster menuPlayer = player(helper, true);
        menuPlayer.containerMenu = ChestMenu.threeRows(1, menuPlayer.getInventory(), new SimpleContainer(27));
        helper.assertFalse(SpellCasting.cycle(menuPlayer), "Container requests are ignored");
        menuPlayer.containerMenu = menuPlayer.inventoryMenu;
        menuPlayer.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        helper.assertFalse(SpellCasting.cycle(menuPlayer), "A stick cannot send valid selection request");
        helper.assertValueEqual(SpellCatalog.selected(menuPlayer), SpellCatalog.WATER_BOLT, "Rejected context preserves selection");
        helper.succeed();
    }

    private SpellSelectionGameTests() {}
}
