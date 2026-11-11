package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaEvents;
import dev.joblessreincarnation.magic.ManaGrowth;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.magic.ManaGrowthRules;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import org.jetbrains.annotations.Nullable;

/**
 * Real payment, attachment, clone-event and packet-serializer contracts using isolated player fixtures.
 * Deliberately preloaded progress is not a natural training run or a dedicated-server disk-restart test.
 * These fixtures expect the standard test server configuration and never modify global configuration.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaGrowthLifecycleGameTests {
    private static final class RecordingPlayer extends FakePlayer {
        final List<SyncAttachmentsPayload> received = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();

        RecordingPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "GrowthLife"));
            getAbilities().instabuild = false;
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet, received); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet, received); }
            };
        }

        @Override public void sendSystemMessage(Component message) { messages.add(message); }
    }

    @GameTest(template = "empty")
    public static void oneCommittedPaymentCannotAdvanceGrowthTwice(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment receipt = payment(helper, mana, 24, 80, 7, false);
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "The first real fee arms a half-round");
        helper.assertValueEqual(growth(player).paidInRound(), 24, "The fee is counted once");
        for (int repeat = 0; repeat < 10; repeat++)
            helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "Repeated callback cannot consume the same receipt again");
        helper.assertValueEqual(growth(player).paidInRound(), 24, "Repeated callback does not duplicate paid progress");
        helper.assertValueEqual(mana.mana(), 76, "Only the original fee is deducted");
        helper.assertTrue(player.messages.isEmpty(), "A half-round or repeated receipt emits no growth award");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aReceiptCannotBeAppliedToAnotherPlayersMana(GameTestHelper helper) {
        RecordingPlayer owner = standardPlayer(helper);
        RecordingPlayer other = standardPlayer(helper);
        ManaData.Payment receipt = payment(helper, ManaGrowth.prepare(owner), 24, 80, 7, false);
        ManaGrowth.prepare(other);
        helper.assertValueEqual(ManaGrowth.onSpellPaid(other, receipt), 0, "A different player cannot use another attachment's receipt");
        assertNoRound(helper, growth(other), "The wrong recipient");
        assertNoRound(helper, growth(owner), "The payer has not received a committed callback");
        helper.assertValueEqual(other.getData(ModAttachments.MANA).mana(), 100, "The wrong recipient's gauge is untouched");
        helper.assertValueEqual(owner.getData(ModAttachments.MANA).mana(), 76, "The original fee remains on its payer");
        helper.assertTrue(other.messages.isEmpty() && owner.messages.isEmpty(), "A mismatched receipt emits no award to either player");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingTheManaAttachmentCancelsItsHalfRoundAndPendingReceipt(GameTestHelper helper) {
        RecordingPlayer player = withProgress(helper, 0, 0, 100, 72, new ManaData(28, 100, 0, 0));
        ManaData original = ManaGrowth.prepare(player); // Establish the identity before external replacement.
        ManaData.Payment receipt = payment(helper, original, 24, 80, 7, false);
        ManaData replacement = new ManaData(4, 100, 80, 7);
        player.setData(ModAttachments.MANA, replacement);
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "A paid receipt cannot award against a replacement object with identical values");
        assertNoRound(helper, growth(player), "Replacing an already bound attachment");
        helper.assertValueEqual(growth(player).earnedBonus(), 0, "The interrupted crossing does not award");
        helper.assertValueEqual(replacement.mana(), 4, "Receipt rejection does not refill the replacement");
        helper.assertTrue(player.messages.isEmpty(), "Replacement rejection does not announce an award");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLaterPaymentInvalidatesAnOlderUnconsumedReceipt(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment old = payment(helper, mana, 12, 80, 1, false);
        mana.tick(100, 0); // Advance only the spell cooldown, without recovery or growth.
        ManaData.Payment current = payment(helper, mana, 12, 80, 1, false);
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, old), 0, "The superseded fee cannot be committed late");
        assertNoRound(helper, growth(player), "The superseded receipt");
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, current), 0, "The newest fee is still usable");
        helper.assertValueEqual(growth(player).paidInRound(), 12, "Only the latest committed fee enters the half-round");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recoveryBeforeCommitInvalidatesThePendingReceipt(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment receipt = payment(helper, mana, 24, 1, 1, false);
        mana.tick(100, 1);
        helper.assertValueEqual(mana.mana(), 77, "The fixture exercises actual mana recovery before commit");
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "Recovery makes the old payment snapshot unusable");
        assertNoRound(helper, growth(player), "A receipt whose gauge changed before commit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingThenRestoringCapacityCannotReviveAPendingReceipt(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment receipt = payment(helper, mana, 24, 80, 7, false);
        mana.reconcileMaximum(99);
        mana.reconcileMaximum(100);
        helper.assertValueEqual(mana.mana(), 76, "The temporary maximum change did not alter the remaining gauge");
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "Restoring the same visible capacity cannot revive a stale receipt");
        assertNoRound(helper, growth(player), "A changed-capacity receipt");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeZeroCostReceiptsNeverBecomePaidTraining(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        player.getAbilities().instabuild = true;
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment receipt = payment(helper, mana, 24, 80, 7, true);
        helper.assertValueEqual(mana.mana(), 100, "Creative casting did not pay mana");
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "Creative zero-fee casting cannot train");
        player.getAbilities().instabuild = false;
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "Returning to survival cannot turn a zero-fee receipt into payment");
        assertNoRound(helper, growth(player), "An unpaid creative receipt");
        helper.assertValueEqual(growth(player).earnedBonus(), 0, "Creative casting earned no capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void genericSpendingDoesNotAutomaticallyCountAsASpellCommit(GameTestHelper helper) {
        RecordingPlayer player = standardPlayer(helper);
        ManaData mana = ManaGrowth.prepare(player);
        helper.assertTrue(mana.trySpend(24, 80, 7, false), "The generic spend path still deducts mana");
        ManaGrowth.tick(player);
        helper.assertValueEqual(mana.mana(), 76, "The generic fee is retained");
        assertNoRound(helper, growth(player), "Spending without the spell-commit callback");
        helper.assertValueEqual(growth(player).earnedBonus(), 0, "A generic spend, including the path used for dismantling, does not itself train");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realAwardChangesOnlyCapacityAndDoesNotAdvanceManaTimers(GameTestHelper helper) {
        RecordingPlayer player = withProgress(helper, 0, 0, 100, 72, new ManaData(28, 100, 0, 0));
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment receipt = payment(helper, mana, 24, 80, 7, false);
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 2, "A genuine paid crossing finishes the explicitly preloaded round");
        helper.assertValueEqual(mana.maximum(), 102, "The award reaches the existing mana gauge maximum");
        helper.assertValueEqual(mana.mana(), 4, "The new capacity does not replenish mana");
        helper.assertValueEqual(mana.recoveryDelay(), 80, "Award processing does not advance recovery");
        helper.assertValueEqual(mana.cooldown(), 7, "Award processing does not advance spell cooldown");
        helper.assertValueEqual(growth(player).cooldownRemaining(), 6000, "Growth starts its full independent cooldown");
        helper.assertValueEqual(player.messages.size(), 1, "The awarded player receives one growth message");
        helper.assertValueEqual(ManaGrowth.onSpellPaid(player, receipt), 0, "The same final receipt cannot award again");
        ManaGrowth.prepare(player);
        helper.assertValueEqual(player.messages.size(), 1, "Preparing or replaying does not duplicate the message");
        helper.assertValueEqual(mana.mana(), 4, "Repeated preparation still does not refill");
        helper.assertValueEqual(mana.recoveryDelay(), 80, "Repeated preparation still leaves recovery timing alone");
        helper.assertValueEqual(mana.cooldown(), 7, "Repeated preparation still leaves spell timing alone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void frameworkDeathCloneRetainsGrowthButRefillsManaAndClearsHalfRounds(GameTestHelper helper) {
        RecordingPlayer cooling = withProgress(helper, 4, 1234, 0, 0, new ManaData(37, 104, 31, 11));
        ManaGrowth.prepare(cooling);
        RecordingPlayer respawned = new RecordingPlayer(helper.getLevel());
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, cooling, true));
        helper.assertValueEqual(growth(respawned).earnedBonus(), 4, "Framework copyOnDeath retains earned capacity");
        helper.assertValueEqual(growth(respawned).cooldownRemaining(), 1234, "Death retains remaining growth cooldown");
        helper.assertValueEqual(respawned.getData(ModAttachments.MANA).mana(), 104, "The LOWEST handler refills to the copied effective maximum");
        helper.assertValueEqual(respawned.getData(ModAttachments.MANA).maximum(), 104, "Death uses earned capacity, not the base-only default");
        helper.assertValueEqual(respawned.getData(ModAttachments.MANA).recoveryDelay(), 0, "The new life receives a fresh mana state");
        helper.assertTrue(growth(respawned) != growth(cooling), "The framework copies growth rather than sharing the old object");
        helper.assertValueEqual(cooling.getData(ModAttachments.MANA).mana(), 37, "Refilling the clone cannot refill the original");
        assertNoRound(helper, growth(respawned), "Death while cooling");

        RecordingPlayer partial = withProgress(helper, 4, 0, 104, 24, new ManaData(80, 104, 31, 11));
        ManaGrowth.prepare(partial);
        RecordingPlayer secondLife = new RecordingPlayer(helper.getLevel());
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(secondLife, partial, true));
        assertNoRound(helper, growth(secondLife), "Death during a half-round");
        helper.assertValueEqual(growth(secondLife).earnedBonus(), 4, "Cancelling a death half-round keeps prior rewards");
        helper.assertValueEqual(secondLife.getData(ModAttachments.MANA).mana(), 104, "A half-round death also refills to effective capacity");
        helper.assertValueEqual(growth(partial).paidInRound(), 24, "Cancelling the clone's round does not mutate the original progress object");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void frameworkNonDeathClonePreservesHalfRoundAndCurrentMana(GameTestHelper helper) {
        RecordingPlayer original = withProgress(helper, 4, 0, 104, 24, new ManaData(80, 104, 31, 11));
        ManaGrowth.prepare(original);
        RecordingPlayer returned = new RecordingPlayer(helper.getLevel());
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(returned, original, false));
        ManaEvents.onRespawn(new PlayerEvent.PlayerRespawnEvent(returned, true));
        helper.assertValueEqual(growth(returned).earnedBonus(), 4, "A non-death clone preserves earned capacity");
        helper.assertValueEqual(growth(returned).roundMaximum(), 104, "A non-death clone preserves its round capacity");
        helper.assertValueEqual(growth(returned).paidInRound(), 24, "Framework copying and first runtime binding keep the half-round");
        helper.assertValueEqual(returned.getData(ModAttachments.MANA).mana(), 80, "A non-death clone does not refill mana");
        helper.assertValueEqual(returned.getData(ModAttachments.MANA).recoveryDelay(), 31, "Non-death cloning preserves recovery delay");
        helper.assertValueEqual(returned.getData(ModAttachments.MANA).cooldown(), 11, "Non-death cloning preserves spell cooldown");
        helper.assertTrue(growth(returned) != growth(original), "The non-death progress copy owns an independent object");
        helper.assertTrue(returned.getData(ModAttachments.MANA) != original.getData(ModAttachments.MANA), "The non-death mana copy owns an independent object");
        growth(returned).cancelRound();
        helper.assertValueEqual(growth(original).paidInRound(), 24, "Changing the new life cannot alter the original half-round");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerNbtLoginAndDimensionSyncPreserveProgressAndOnlyClampMana(GameTestHelper helper) {
        RecordingPlayer original = withProgress(helper, 4, 0, 104, 24, new ManaData(80, 104, 31, 11));
        ManaGrowth.prepare(original);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        RecordingPlayer loaded = new RecordingPlayer(helper.getLevel());
        loaded.load(saved);
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(loaded));
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).mana(), 80, "Login after real entity NBT decoding does not refill");
        helper.assertValueEqual(growth(loaded).roundMaximum(), 104, "Player attachment NBT retains the half-round maximum");
        helper.assertValueEqual(growth(loaded).paidInRound(), 24, "Login's first identity binding preserves the paid half-round");
        ManaEvents.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(loaded, Level.OVERWORLD, Level.NETHER));
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).mana(), 80, "Dimension synchronization does not refill current mana");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).recoveryDelay(), 31, "Login and dimension sync do not tick recovery");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).cooldown(), 11, "Login and dimension sync do not tick spell cooldown");
        helper.assertValueEqual(growth(loaded).paidInRound(), 24, "Dimension synchronization retains the same half-round");

        RecordingPlayer excessive = withProgress(helper, 4, 321, 0, 0, new ManaData(140, 150, 31, 11));
        RecordingPlayer clamped = new RecordingPlayer(helper.getLevel());
        clamped.load(excessive.saveWithoutId(new CompoundTag()));
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(clamped));
        helper.assertValueEqual(clamped.getData(ModAttachments.MANA).maximum(), 104, "A loaded stale maximum is reconciled to effective capacity");
        helper.assertValueEqual(clamped.getData(ModAttachments.MANA).mana(), 104, "An excessive saved gauge is clamped downward");
        helper.assertValueEqual(growth(clamped).cooldownRemaining(), 321, "Save/load and lifecycle sync do not consume growth cooldown");
        helper.assertValueEqual(clamped.getData(ModAttachments.MANA).recoveryDelay(), 31, "Clamping does not advance saved recovery delay");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLegacyPlayerWithoutGrowthLoadsWithZeroBonusAndKeepsMana(GameTestHelper helper) {
        checkDefaults(helper);
        RecordingPlayer legacy = new RecordingPlayer(helper.getLevel());
        legacy.setData(ModAttachments.MANA, new ManaData(37, 100, 31, 11));
        helper.assertFalse(legacy.hasData(ModAttachments.MANA_GROWTH), "The legacy fixture has no growth attachment");
        RecordingPlayer loaded = new RecordingPlayer(helper.getLevel());
        loaded.load(legacy.saveWithoutId(new CompoundTag()));
        helper.assertFalse(loaded.hasData(ModAttachments.MANA_GROWTH), "Decoding an old player does not invent saved growth data");
        ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(loaded));
        helper.assertValueEqual(growth(loaded).earnedBonus(), 0, "An old player receives the zero-bonus default");
        helper.assertValueEqual(growth(loaded).cooldownRemaining(), 0, "An old player has no hidden growth cooldown");
        assertNoRound(helper, growth(loaded), "The absent legacy growth record");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).mana(), 37, "Upgrading an old player does not fill their gauge");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).maximum(), 100, "An old player keeps the configured base maximum");
        helper.assertValueEqual(loaded.getData(ModAttachments.MANA).cooldown(), 11, "Legacy spell cooldown remains intact");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualGameModeEventsClearHalfRoundsExceptWhenCancelled(GameTestHelper helper) {
        for (GameType nextMode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            RecordingPlayer player = withProgress(helper, 4, 0, 104, 24, new ManaData(80, 104, 31, 11));
            ManaGrowth.prepare(player);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangeGameModeEvent(player, GameType.SURVIVAL, nextMode));
            assertNoRound(helper, growth(player), "The registered " + nextMode + " transition listener");
            helper.assertValueEqual(growth(player).earnedBonus(), 4, "A mode transition does not erase earned capacity");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 80, "The event listener does not refill mana");

            RecordingPlayer cancelled = withProgress(helper, 4, 0, 104, 24, new ManaData(80, 104, 31, 11));
            ManaGrowth.prepare(cancelled);
            PlayerEvent.PlayerChangeGameModeEvent event =
                    new PlayerEvent.PlayerChangeGameModeEvent(cancelled, GameType.SURVIVAL, nextMode);
            event.setCanceled(true);
            NeoForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "The fixture remains a cancelled mode-change event");
            helper.assertValueEqual(growth(cancelled).paidInRound(), 24, "A cancelled mode event cannot clear valid paid progress");
            helper.assertValueEqual(growth(cancelled).roundMaximum(), 104, "A cancelled mode event keeps its valid round maximum");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void frameworkSyncSendsOnlyTheEffectiveGaugeToItsOwner(GameTestHelper helper) throws ReflectiveOperationException {
        RecordingPlayer owner = withProgress(helper, 4, 1234, 0, 0, new ManaData(80, 104, 31, 11));
        ManaGrowth.prepare(owner);
        RecordingPlayer observer = new RecordingPlayer(helper.getLevel());
        var handlerField = AttachmentType.class.getDeclaredField("syncHandler");
        handlerField.setAccessible(true);
        helper.assertTrue(handlerField.get(ModAttachments.MANA_GROWTH.get()) == null,
                "The persistent growth attachment registers no network serializer");

        List<SyncAttachmentsPayload> initial = new ArrayList<>();
        AttachmentSync.syncInitialEntityAttachments(owner, observer, packet -> capture(packet, initial));
        helper.assertFalse(initial.isEmpty(), "The framework's real initial tracking serializer was invoked");
        for (SyncAttachmentsPayload payload : initial) {
            helper.assertFalse(payload.types().contains(ModAttachments.MANA_GROWTH.get()), "Tracking packets never name the private growth record");
            helper.assertFalse(payload.types().contains(ModAttachments.MANA.get()), "Remote initial packets omit the private gauge");
            helper.assertValueEqual(payload.syncPayload().length, 0, "Remote initial packets contain no private bytes");
        }

        owner.received.clear();
        observer.received.clear();
        var update = AttachmentSync.class.getDeclaredMethod("syncUpdate", AttachmentHolder.class, AttachmentType.class, List.class);
        update.setAccessible(true);
        update.invoke(null, owner, ModAttachments.MANA.get(), List.of(observer, owner));
        helper.assertTrue(observer.received.isEmpty(), "The actual watcher-recipient filter excludes the other player");
        helper.assertFalse(owner.received.isEmpty(), "The owner receives an actual mana attachment update");
        RecordingPlayer ownerView = new RecordingPlayer(helper.getLevel());
        for (SyncAttachmentsPayload payload : owner.received) {
            helper.assertFalse(payload.types().contains(ModAttachments.MANA_GROWTH.get()), "Owner gauge updates do not attach private growth state");
            AttachmentSync.receiveSyncedDataAttachments(ownerView, ownerView.registryAccess(), payload.types(), payload.syncPayload());
        }
        helper.assertValueEqual(ownerView.getData(ModAttachments.MANA).mana(), 80, "The real framework decoder receives current mana");
        helper.assertValueEqual(ownerView.getData(ModAttachments.MANA).maximum(), 104, "The owner receives earned effective capacity");
        helper.assertValueEqual(ownerView.getData(ModAttachments.MANA).recoveryDelay(), 0, "Private recovery timers remain outside the mana stream codec");
        helper.assertFalse(ownerView.hasData(ModAttachments.MANA_GROWTH), "Decoding owner gauge updates does not create a client growth record");
        helper.succeed();
    }

    private static RecordingPlayer standardPlayer(GameTestHelper helper) {
        return withProgress(helper, 0, 0, 0, 0, ManaData.full(100));
    }

    private static RecordingPlayer withProgress(GameTestHelper helper, int earned, int cooldown,
            int roundMaximum, int paid, ManaData mana) {
        checkDefaults(helper);
        RecordingPlayer player = new RecordingPlayer(helper.getLevel());
        player.setData(ModAttachments.MANA_GROWTH,
                new ManaGrowthData(earned, cooldown, roundMaximum, paid, ManaGrowthRules.current().signature()));
        player.setData(ModAttachments.MANA, mana);
        player.received.clear();
        player.messages.clear();
        return player;
    }

    private static void checkDefaults(GameTestHelper helper) {
        ManaGrowthRules rules = ManaGrowthRules.current();
        helper.assertTrue(rules.enabled(), "Fixture expects enabled growth on the development test server");
        helper.assertValueEqual(rules.baseMaximum(), 100, "Fixture expects the standard base capacity");
        helper.assertValueEqual(rules.bonusLimit(), 100, "Fixture expects the standard bonus limit");
        helper.assertValueEqual(rules.gainPerRound(), 2, "Fixture expects the standard per-round award");
        helper.assertValueEqual(rules.cooldownTicks(), 6000, "Fixture expects the standard growth cooldown");
    }

    private static ManaGrowthData growth(RecordingPlayer player) { return player.getData(ModAttachments.MANA_GROWTH); }

    private static ManaData.Payment payment(GameTestHelper helper, ManaData mana, int cost,
            int delay, int cooldown, boolean creative) {
        ManaData.Payment receipt = mana.trySpendWithReceipt(cost, delay, cooldown, creative);
        helper.assertTrue(receipt != null, "The fixture makes a successful actual spend");
        return receipt;
    }

    private static void assertNoRound(GameTestHelper helper, ManaGrowthData growth, String cause) {
        helper.assertValueEqual(growth.roundMaximum(), 0, cause + " clears the round maximum");
        helper.assertValueEqual(growth.paidInRound(), 0, cause + " clears accumulated payment");
    }

    private static void capture(Packet<?> packet, List<SyncAttachmentsPayload> into) {
        if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof SyncAttachmentsPayload sync)
            into.add(sync);
    }

    private ManaGrowthLifecycleGameTests() {}
}