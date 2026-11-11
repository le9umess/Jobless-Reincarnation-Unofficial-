package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.training.AuraVisual;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaEvents;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import org.jetbrains.annotations.Nullable;

/** Invokes the real 21.1.251 framework serializers and recipient path, rather than reproducing their branches. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PrivateAttachmentSyncGameTests {
    private static final class RecordingPlayer extends FakePlayer {
        final List<SyncAttachmentsPayload> received = new ArrayList<>();
        RecordingPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "PrivateSync"));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet, received); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet, received); }
            };
        }
    }
    private static void capture(Packet<?> packet, List<SyncAttachmentsPayload> into) {
        if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof SyncAttachmentsPayload sync) into.add(sync);
    }
    private static RecordingPlayer prepared(GameTestHelper helper) {
        RecordingPlayer owner = new RecordingPlayer(helper.getLevel());
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(SpellCatalog.WATER_BOLT.id()); knowledge.learn(SpellCatalog.EARTH_WALL.id());
        for (int i = 0; i < 3; i++) knowledge.practice(SpellCatalog.WATER_BOLT.id());
        for (int i = 0; i < 7; i++) knowledge.practice(SpellCatalog.EARTH_WALL.id());
        owner.setData(ModAttachments.KNOWLEDGE, knowledge);
        owner.setData(ModAttachments.MANA, new ManaData(73, 100, 40, 20));
        CastingData selection = new CastingData(); selection.select(SpellCatalog.EARTH_WALL.id());
        owner.setData(ModAttachments.CASTING, selection);
        owner.received.clear();
        return owner;
    }
    private static List<AttachmentType<?>> privateTypes() {
        return List.of(ModAttachments.MANA.get(), ModAttachments.KNOWLEDGE.get(), ModAttachments.CASTING.get());
    }
    private static void apply(List<SyncAttachmentsPayload> packets, RecordingPlayer target) {
        for (var packet : packets) AttachmentSync.receiveSyncedDataAttachments(target, target.registryAccess(), packet.types(), packet.syncPayload());
    }
    private static void assertPrivateValues(GameTestHelper helper, RecordingPlayer clientView) {
        helper.assertTrue(clientView.hasData(ModAttachments.MANA) && clientView.hasData(ModAttachments.KNOWLEDGE) && clientView.hasData(ModAttachments.CASTING), "Actual update packets contain all three private types");
        helper.assertValueEqual(clientView.getData(ModAttachments.MANA).mana(), 73, "Owner receives non-default mana");
        helper.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(clientView), 3, "Owner receives water practice");
        helper.assertValueEqual(SpellCatalog.EARTH_WALL.practiceCount(clientView), 7, "Owner receives distinct earth practice");
        helper.assertValueEqual(clientView.getData(ModAttachments.CASTING).selectedSpell(), SpellCatalog.EARTH_WALL.id(), "Owner receives non-default selection");
    }

    @GameTest(template = "empty")
    public static void actualInitialTrackingPayloadContainsNoPrivateTypesOrBytes(GameTestHelper helper) {
        RecordingPlayer owner = prepared(helper);
        RecordingPlayer observer = new RecordingPlayer(helper.getLevel());
        List<SyncAttachmentsPayload> initial = new ArrayList<>();
        // This is precisely the public tracking entry whose private helper omits sendToPlayer in 21.1.251.
        AttachmentSync.syncInitialEntityAttachments(owner, observer, packet -> capture(packet, initial));
        helper.assertFalse(initial.isEmpty(), "Fixture traverses the framework's actual initial-sync serializer");
        for (var payload : initial) {
            helper.assertTrue(payload.types().stream().noneMatch(privateTypes()::contains), "Remote initial payload omits private attachment type IDs");
            helper.assertValueEqual(payload.syncPayload().length, 0, "No presence marker or private serialized bytes remain");
        }
        RecordingPlayer remoteView = new RecordingPlayer(helper.getLevel());
        apply(initial, remoteView);
        helper.assertFalse(remoteView.hasData(ModAttachments.MANA) || remoteView.hasData(ModAttachments.KNOWLEDGE) || remoteView.hasData(ModAttachments.CASTING), "Real framework decoding neither reads empty bytes nor creates remote defaults");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void explicitLifecycleUpdatesDeliverPrivateDataAfterSuppressedInitialSync(GameTestHelper helper) {
        RecordingPlayer owner = prepared(helper);
        // Even the owner's generic initial packet is intentionally empty; lifecycle updates replace it.
        AttachmentSync.syncInitialPlayerAttachments(owner);
        RecordingPlayer initialView = new RecordingPlayer(helper.getLevel());
        apply(owner.received, initialView);
        helper.assertFalse(initialView.hasData(ModAttachments.MANA) || initialView.hasData(ModAttachments.KNOWLEDGE) || initialView.hasData(ModAttachments.CASTING), "Generic initial owner packets are safely suppressed too");
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            owner.received.clear();
            if (lifecycle == 0) ManaEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(owner));
            else if (lifecycle == 1) ManaEvents.onRespawn(new PlayerEvent.PlayerRespawnEvent(owner, false));
            else ManaEvents.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(owner, Level.OVERWORLD, Level.NETHER));
            RecordingPlayer view = new RecordingPlayer(helper.getLevel());
            apply(owner.received, view);
            assertPrivateValues(helper, view);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualFrameworkUpdateRecipientFilterExcludesTrackingPeer(GameTestHelper helper) throws ReflectiveOperationException {
        RecordingPlayer owner = prepared(helper);
        RecordingPlayer observer = new RecordingPlayer(helper.getLevel());
        // The framework's real update method accepts its computed watcher list privately.
        // Feed two real ServerPlayer fixtures to that exact method, then examine its actual packets.
        var method = AttachmentSync.class.getDeclaredMethod("syncUpdate", AttachmentHolder.class, AttachmentType.class, List.class);
        method.setAccessible(true);
        for (var type : privateTypes()) method.invoke(null, owner, type, List.of(observer, owner));
        helper.assertTrue(observer.received.isEmpty(), "Real framework update path sends no private packet to another recipient");
        RecordingPlayer ownerView = new RecordingPlayer(helper.getLevel());
        apply(owner.received, ownerView);
        assertPrivateValues(helper, ownerView);
        owner.removeData(ModAttachments.CASTING);
        owner.received.clear();
        method.invoke(null, owner, ModAttachments.CASTING.get(), List.of(observer, owner));
        apply(owner.received, ownerView);
        helper.assertFalse(ownerView.hasData(ModAttachments.CASTING), "Owner-only removal decodes without a stale selection");
        helper.assertTrue(observer.received.isEmpty(), "Private removal is also excluded for the tracking peer");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void trackingPeerGetsOnlyBattleAuraPhaseAndTick(GameTestHelper helper) throws ReflectiveOperationException {
        RecordingPlayer owner = prepared(helper), observer = new RecordingPlayer(helper.getLevel());
        long now = helper.getLevel().getGameTime();
        var impact = new AuraVisual(AuraVisual.Phase.GUARD, now);
        owner.setData(ModAttachments.BATTLE_AURA_VISUAL, impact);
        owner.getData(ModAttachments.SWORD_TRAINING).start();
        owner.getData(ModAttachments.AURA_GATHERING);
        List<SyncAttachmentsPayload> initial = new ArrayList<>();
        AttachmentSync.syncInitialEntityAttachments(owner, observer, packet -> capture(packet, initial));
        RecordingPlayer peerView = new RecordingPlayer(helper.getLevel());
        apply(initial, peerView);
        helper.assertValueEqual(peerView.getData(ModAttachments.BATTLE_AURA_VISUAL), impact, "New tracker can decode the current public phase");
        helper.assertFalse(peerView.hasData(ModAttachments.SWORD_TRAINING) || peerView.hasData(ModAttachments.MANA)
                || peerView.hasData(ModAttachments.AURA_GATHERING),
                "No learned or mana values accompany the flash to another player");
        helper.assertTrue(impact.flash(now, 0) == 1 && impact.flash(now + 8, 0) == 0, "An old hit never restarts a late flash");
        var sync = AttachmentSync.class.getDeclaredMethod("syncUpdate", AttachmentHolder.class, AttachmentType.class, List.class);
        sync.setAccessible(true);
        var gather = new AuraVisual(AuraVisual.Phase.GATHER, now + 1);
        owner.setData(ModAttachments.BATTLE_AURA_VISUAL, gather);
        observer.received.clear();
        sync.invoke(null, owner, ModAttachments.BATTLE_AURA_VISUAL.get(), List.of(observer));
        apply(observer.received, peerView);
        helper.assertValueEqual(peerView.getData(ModAttachments.BATTLE_AURA_VISUAL), gather, "The tracker receives the start tick and phase only");
        owner.setData(ModAttachments.BATTLE_AURA_VISUAL, AuraVisual.OFF);
        observer.received.clear();
        sync.invoke(null, owner, ModAttachments.BATTLE_AURA_VISUAL.get(), List.of(observer));
        sync.invoke(null, owner, ModAttachments.MANA.get(), List.of(observer));
        apply(observer.received, peerView);
        helper.assertValueEqual(peerView.getData(ModAttachments.BATTLE_AURA_VISUAL), AuraVisual.OFF, "A live tracker can clear the effect");
        helper.assertFalse(peerView.hasData(ModAttachments.MANA), "Later public updates also keep the pool private");
        helper.succeed();
    }

    private PrivateAttachmentSyncGameTests() {}
}
