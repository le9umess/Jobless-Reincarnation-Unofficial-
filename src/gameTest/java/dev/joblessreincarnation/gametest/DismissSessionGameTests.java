package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.network.DismissPayload;
import dev.joblessreincarnation.network.DismissStatusPayload;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DismissSessionGameTests {
    private static FakePlayer fixture(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DismissHold"));
        player.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        player.setData(ModAttachments.MANA, ManaData.full(100));
        WallIdentity wall = new WallIdentity(player.getUUID(), UUID.randomUUID(), helper.absolutePos(new BlockPos(5, 2, 6)), Direction.Axis.X);
        for (var pos : wall.footprint()) {
            helper.getLevel().setBlock(pos, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
            ((EarthWallBlockEntity)helper.getLevel().getBlockEntity(pos)).bind(wall);
        }
        player.setYRot(0); player.setXRot(0);
        helper.assertTrue(EarthWallDismissal.plan(player).valid(), "Fixture has a visible owned wall");
        return player;
    }
    private static void free(GameTestHelper helper, FakePlayer player) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Cancellation does not spend mana");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "Cancellation has no cooldown");
        helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(player), 0, "Dismissal never trains");
        helper.assertTrue(EarthWallDismissal.plan(player).valid(), "Original wall remains");
    }
    @GameTest(template="empty")
    public static void holdUsesServerTimeAndSettlesOnlyOnce(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        List<BlockPos> cells = EarthWallDismissal.plan(player).positions();
        helper.assertTrue(EarthWallDismantling.begin(player, 1), "Begin locks owned wall");
        helper.runAfterDelay(MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt(), () -> {
            EarthWallDismantling.tick(player);
            helper.assertTrue(EarthWallDismantling.release(player, 1), "Full server-time hold succeeds");
            helper.assertFalse(EarthWallDismantling.release(player, 1), "Duplicate release cannot settle");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100-MagicConfig.EARTH_DISMISS_COST.getAsInt(), "One fee");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.EARTH_DISMISS_COOLDOWN.getAsInt(), "Shared cooldown");
            helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(player), 0, "No dismissal practice");
            for (var pos : cells) helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "Whole wall removed");
            helper.succeed();
        });
    }
    @GameTest(template="empty")
    public static void earlyDuplicateAndStaleNonceCannotResolveOrReplaceLock(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        helper.assertFalse(EarthWallDismantling.release(player, 1), "No begin means no release");
        helper.assertTrue(EarthWallDismantling.begin(player, 10), "Begin accepted");
        helper.assertFalse(EarthWallDismantling.begin(player, 11), "Second begin cannot replace a lock");
        helper.assertFalse(EarthWallDismantling.release(player, 11), "Stale/wrong nonce cannot consume current lock");
        EarthWallDismantling.cancel(player, 11);
        helper.assertTrue(EarthWallDismantling.active(player), "Wrong cancel nonce preserves current lock");
        helper.assertFalse(EarthWallDismantling.release(player, 10), "Early release cancels for free");
        helper.assertFalse(EarthWallDismantling.active(player), "Early release consumed lock");
        free(helper, player); helper.succeed();
    }
    @GameTest(template="empty")
    public static void changingEqualWandSlotOrCreativeModeCancelsRuntimeLock(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        var original = player.getMainHandItem();
        helper.assertTrue(EarthWallDismantling.begin(player, 1), "Initial begin");
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
        EarthWallDismantling.tick(player);
        helper.assertFalse(EarthWallDismantling.active(player), "Equal-item replacement invalidates identity");
        helper.assertTrue(EarthWallDismantling.begin(player, 2), "Independent begin");
        player.getInventory().setItem(1, player.getMainHandItem());
        player.getInventory().selected = 1;
        EarthWallDismantling.tick(player);
        helper.assertFalse(EarthWallDismantling.active(player), "Moving original reference to another slot invalidates lock");
        helper.assertTrue(EarthWallDismantling.begin(player, 3), "Third independent begin");
        player.getAbilities().instabuild = true;
        EarthWallDismantling.tick(player);
        helper.assertFalse(EarthWallDismantling.active(player), "Changing game mode cannot turn the hold free");
        player.getAbilities().instabuild = false;
        free(helper, player); helper.succeed();
    }
    @GameTest(template="empty")
    public static void castAndDismissMutuallyExcludeAndCycleCancels(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.isUsingItem(), "Ordinary cast begins");
        helper.assertFalse(EarthWallDismantling.begin(player, 1), "Cannot dismiss during a cast");
        SpellCasting.cancel(player);
        helper.assertTrue(EarthWallDismantling.begin(player, 2), "Dismiss after cast cancellation");
        helper.assertFalse(SpellCasting.canBegin(player), "Server refuses normal cast during dismissal");
        player.startUsingItem(InteractionHand.MAIN_HAND);
        helper.assertFalse(player.isUsingItem(), "Even forced vanilla use Start event cannot bypass exclusion");
        helper.assertTrue(SpellCasting.cycle(player), "G cycles to water");
        helper.assertFalse(EarthWallDismantling.active(player), "G cancels dismissal");
        helper.assertFalse(EarthWallDismantling.release(player, 2), "Old release cannot resolve after G");
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        free(helper, player); helper.succeed();
    }
    @GameTest(template="empty")
    public static void sneakLogoutDimensionAndCloneClearUnpersistedSessions(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        helper.assertTrue(EarthWallDismantling.begin(player, 1), "Initial begin");
        player.setShiftKeyDown(true); EarthWallDismantling.tick(player); player.setShiftKeyDown(false);
        helper.assertFalse(EarthWallDismantling.active(player), "Sneak cancels");
        helper.assertTrue(EarthWallDismantling.begin(player, 2), "Second begin");
        ManaEvents.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, Level.NETHER));
        helper.assertFalse(EarthWallDismantling.active(player), "Dimension lifecycle clears lock");
        helper.assertTrue(EarthWallDismantling.begin(player, 3), "Third begin");
        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        FakePlayer loaded = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DismissLoad"));
        loaded.load(saved);
        helper.assertFalse(EarthWallDismantling.active(loaded), "NBT does not persist an H session");
        ManaEvents.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertFalse(EarthWallDismantling.active(player), "Logout clears lock");
        helper.assertTrue(EarthWallDismantling.begin(player, 4), "Fourth begin");
        ManaEvents.onClone(new PlayerEvent.Clone(loaded, player, true));
        helper.assertFalse(EarthWallDismantling.active(player) || EarthWallDismantling.active(loaded), "Death clone clears both locks");
        free(helper, player); helper.succeed();
    }
    @GameTest(template="empty", timeoutTicks=240)
    public static void abandonedHoldTimesOutWithoutAutomaticallyRemovingWall(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        helper.assertTrue(EarthWallDismantling.begin(player, 99), "Begin accepted");
        helper.runAfterDelay(201, () -> {
            EarthWallDismantling.tick(player);
            helper.assertFalse(EarthWallDismantling.active(player), "Idle hold expires");
            helper.assertFalse(EarthWallDismantling.release(player, 99), "Expired release is ignored");
            free(helper, player); helper.succeed();
        });
    }
    @GameTest(template="empty")
    public static void statusAndIntentCodecsAreBoundedAndCorrelated(GameTestHelper helper) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new DismissPayload(71, DismissPayload.CANCEL);
            DismissPayload.STREAM_CODEC.encode(buffer, request);
            helper.assertValueEqual(DismissPayload.STREAM_CODEC.decode(buffer), request, "Action and nonce roundtrip");
            var status = new DismissStatusPayload(72,true,true,20,6,List.of(BlockPos.ZERO,BlockPos.ZERO.above()));
            DismissStatusPayload.STREAM_CODEC.encode(buffer,status);
            helper.assertValueEqual(DismissStatusPayload.STREAM_CODEC.decode(buffer), status, "Private bounded preview roundtrip");
        } finally { buffer.release(); }
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void losingLockedWallBeforeReadyCancelsWithoutCost(GameTestHelper helper) {
        FakePlayer player = fixture(helper);
        helper.assertTrue(EarthWallDismantling.begin(player, 50), "Begin locks visible owned wall");
        player.setYRot(90);
        helper.runAfterDelay(MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt(), () -> {
            EarthWallDismantling.tick(player);
            helper.assertFalse(EarthWallDismantling.active(player), "Ready cannot acknowledge a lost target");
            player.setYRot(0);
            free(helper,player); helper.succeed();
        });
    }
    private DismissSessionGameTests() {}
}