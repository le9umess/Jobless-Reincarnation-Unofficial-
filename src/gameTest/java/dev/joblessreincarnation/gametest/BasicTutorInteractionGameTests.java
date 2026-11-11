package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.TutorInteractions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Real server interaction entry points and actual private chat packets, not client gesture automation. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BasicTutorInteractionGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.basic_tutor.";
    private static final String WATER = "message.jobless_reincarnation.tutor.";
    private static final String FIRE = "message.jobless_reincarnation.fire_tutor.";
    private static final List<SpellCatalog> BASIC = List.of(SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.HEALING, SpellCatalog.WATER_SHIELD);

    private static ItemStack tome(SpellCatalog spell) {
        return switch (spell) {
            case EARTH_WALL -> ModContent.EARTH_TOME.get().getDefaultInstance();
            case AIR_BURST -> ModContent.AIR_TOME.get().getDefaultInstance();
            case HEALING -> ModContent.HEALING_TOME.get().getDefaultInstance();
            case WATER_SHIELD -> ModContent.WATER_SHIELD_TOME.get().getDefaultInstance();
            default -> throw new IllegalArgumentException("Expected a new course");
        };
    }

    private static final class Reader extends FakePlayer {
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        boolean spectator;
        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "C05" + UUID.randomUUID().toString().substring(0, 6)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
            setNoGravity(true);
        }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket message
                    && message.content().getContents() instanceof TranslatableContents contents
                    && ((contents.getKey().startsWith(PREFIX) && contents.getKey().endsWith(".speaker"))
                    || contents.getKey().equals(WATER + "speaker") || contents.getKey().equals(FIRE + "speaker")))
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
            if (npc == null) throw new IllegalStateException("Missing registered Roxy tutor");
            Vec3 position = helper.absoluteVec(new Vec3(5.5, 2, 4.5));
            npc.moveTo(position.x, position.y, position.z, 180, 0);
            npc.setNoAi(true);
            npc.setNoGravity(true);
            if (!helper.getLevel().addFreshEntity(npc)) throw new IllegalStateException("Tutor failed admission");
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

    private static String key(Component component) { return ((TranslatableContents) component.getContents()).getKey(); }

    private static void expected(GameTestHelper helper, ClientboundSystemChatPacket packet, SpellCatalog spell, boolean learned) {
        String course = PREFIX + spell.id().getPath() + ".";
        helper.assertValueEqual(key(packet.content()), course + "speaker", "Private reply identifies only the requested course");
        helper.assertValueEqual(key(packet.content().getSiblings().get(2)), course + "rule." + (learned ? "practice" : "learn"),
                "Private reply reads the actor's own knowledge");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(decoded.content(), packet.content(), "Wire chat serialization preserves this real reply");
            helper.assertFalse(decoded.overlay(), "Native course reply is private chat, not a broadcast action bar");
        } finally { buffer.release(); }
    }

    @GameTest(template = "empty")
    public static void allThreeBooksUseRegisteredEntityEventsWithoutStudyingOrGranting(GameTestHelper helper) {
        for (SpellCatalog spell : BASIC) {
            try (Fixture f = new Fixture(helper)) {
                f.actor.setItemInHand(InteractionHand.MAIN_HAND, tome(spell));
                f.actor.setItemInHand(InteractionHand.OFF_HAND, Items.APPLE.getDefaultInstance());
                String inventory = f.actor.getInventory().save(new ListTag()).toString();
                var specific = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(
                        f.actor, InteractionHand.MAIN_HAND, f.npc, new Vec3(0, 1, 0)));
                helper.assertTrue(specific.isCanceled() && specific.getCancellationResult().consumesAction(), "Specific tutor event consumes the held book");
                var general = f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
                helper.assertTrue(general.isCanceled() && general.getCancellationResult().consumesAction(), "General tutor event also consumes the held book");
                helper.assertValueEqual(f.actor.messages.size(), 1, "Specific and general events cannot duplicate one consultation");
                expected(helper, f.actor.messages.getFirst(), spell, false);
                helper.assertTrue(f.peer.messages.isEmpty(), "Nearby player receives no private course");
                helper.assertFalse(f.actor.isUsingItem(), "Consultation does not start book study or offhand food");
                helper.assertFalse(f.actor.hasData(ModAttachments.KNOWLEDGE) || f.actor.hasData(ModAttachments.TRAINING)
                        || f.actor.hasData(ModAttachments.CASTING), "A new reader gains no knowledge, progress, or spell selection");
                helper.assertValueEqual(f.actor.getInventory().save(new ListTag()).toString(), inventory, "Books and all inventory contents are unchanged");
                helper.assertTrue(f.npc.conversationPlayer() == f.actor, "Accepted new-course consultation still starts the existing conversation");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeWandRoutesReadEachKnownCourseAndLeaveTheSelectionUntouched(GameTestHelper helper) {
        for (SpellCatalog spell : BASIC) {
            try (Fixture f = new Fixture(helper)) {
                MagicKnowledge knowledge = new MagicKnowledge();
                knowledge.learn(spell.id());
                knowledge.practice(spell.id());
                f.actor.setData(ModAttachments.KNOWLEDGE, knowledge);
                CastingData casting = new CastingData();
                casting.select(spell.id());
                f.actor.setData(ModAttachments.CASTING, casting);
                f.actor.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
                f.actor.setItemInHand(InteractionHand.OFF_HAND, ModContent.FIRE_TOME.get().getDefaultInstance());
                f.packet(f.actor, true);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Native wand interaction produces one reply");
                expected(helper, f.actor.messages.getFirst(), spell, true);
                helper.assertValueEqual(casting.selectedSpell(), spell.id(), "Native consultation does not change selection");
                helper.assertTrue(casting.activeSpell() == null && !f.actor.isUsingItem(), "Native consultation never begins casting");
                helper.assertValueEqual(knowledge.practiceCount(spell.id()), 1, "Asking does not increment successful casts");
                helper.assertFalse(f.actor.hasData(ModAttachments.TRAINING), "Asking does not create a reward track");
                helper.assertTrue(f.peer.messages.isEmpty(), "Offhand book does not broadcast or reroute the reply");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void nativeNewCoursesShareOneReplyBudgetPerLearnerAndRemainPrivate(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        try {
            helper.assertValueEqual(TutorInteractions.REPLY_INTERVAL_TICKS, 20, "Existing per-player conversation interval remains unchanged");
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.EARTH_WALL));
            f.peer.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.AIR_BURST));
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Actor receives earth guidance");
            helper.assertTrue(f.peer.messages.isEmpty(), "Peer cannot see the actor's earth guidance");
            helper.assertFalse(NpcAnimations.playLecture(f.npc), "Accepted consultation starts the shared public lecture");
            f.packet(f.peer, false);
            helper.assertValueEqual(f.peer.messages.size(), 1, "Another learner receives air guidance despite public animation cooldown");
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.HEALING));
            f.packet(f.actor, false);
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Switching between new courses cannot bypass per-player reply cooldown");
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Rejected course switches do not steal the accepted conversation");
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        helper.runAfterDelay(19, () -> {
            try {
                f.packet(f.actor, true);
                f.packet(f.peer, false);
                helper.assertValueEqual(f.actor.messages.size(), 1, "Actor is still limited one tick before the boundary");
                helper.assertValueEqual(f.peer.messages.size(), 1, "Peer has its own equally bounded interval");
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        helper.runAfterDelay(20, () -> {
            try (f) {
                f.packet(f.actor, false);
                f.packet(f.peer, true);
                helper.assertValueEqual(f.actor.messages.size(), 2, "Rejected attempts do not postpone the exact reopening tick");
                helper.assertValueEqual(f.peer.messages.size(), 2, "Peer reopens independently at the same boundary");
                expected(helper, f.actor.messages.getFirst(), SpellCatalog.EARTH_WALL, false);
                expected(helper, f.actor.messages.getLast(), SpellCatalog.HEALING, false);
                for (var message : f.peer.messages) expected(helper, message, SpellCatalog.AIR_BURST, false);
                helper.assertFalse(NpcAnimations.playLecture(f.npc), "Twenty-tick private replies do not restart the forty-tick public lecture");
                helper.assertFalse(f.actor.isUsingItem() || f.peer.isUsingItem(), "New course consultations never start either held book");
                helper.assertFalse(f.actor.hasData(ModAttachments.KNOWLEDGE) || f.peer.hasData(ModAttachments.KNOWLEDGE), "Repeated consultations never learn a spell");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void invalidNewCourseRequestsConsumeItemsWithoutReplyOrCooldown(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.actor.setItemInHand(InteractionHand.MAIN_HAND, tome(SpellCatalog.HEALING));
            f.actor.setItemInHand(InteractionHand.OFF_HAND, Items.APPLE.getDefaultInstance());
            var offhand = f.click(f.actor, f.npc, InteractionHand.OFF_HAND);
            helper.assertTrue(offhand.isCanceled() && offhand.getCancellationResult().consumesAction(), "Offhand request is consumed before fallback item use");
            f.actor.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(f.npc, false, InteractionHand.OFF_HAND));
            f.actor.spectator = true;
            f.packet(f.actor, true);
            f.actor.spectator = false;
            f.actor.setHealth(0);
            f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
            f.actor.setHealth(f.actor.getMaxHealth());
            Vec3 position = f.actor.position();
            f.actor.setPos(position.add(0, 0, -20));
            f.packet(f.actor, false);
            f.actor.setPos(position);
            RoxyTutorEntity impostor = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
            if (impostor == null) throw new IllegalStateException("Missing impostor fixture");
            try {
                impostor.setUUID(f.npc.getUUID());
                impostor.setPos(f.npc.position());
                f.click(f.actor, impostor, InteractionHand.MAIN_HAND);
            } finally { impostor.discard(); }
            BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
            var original = helper.getLevel().getBlockState(wall);
            try {
                helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                helper.assertFalse(f.actor.hasLineOfSight(f.npc), "Temporary block genuinely obstructs the tutor");
                f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
            } finally { helper.getLevel().setBlockAndUpdate(wall, original); }
            helper.assertTrue(f.actor.messages.isEmpty() && f.peer.messages.isEmpty(), "Invalid actors, hands, identity, range, and sight reveal no private course");
            helper.assertFalse(f.actor.isUsingItem(), "Invalid consultations never start book study or offhand food");
            helper.assertTrue(NpcAnimations.playLecture(f.npc), "Rejected interactions leave the public lecture budget unused");
            f.packet(f.actor, true);
            helper.assertValueEqual(f.actor.messages.size(), 1, "Rejected requests leave the first valid private reply available");
            expected(helper, f.actor.messages.getFirst(), SpellCatalog.HEALING, false);
            f.npc.discard();
            f.click(f.actor, f.npc, InteractionHand.MAIN_HAND);
            helper.assertValueEqual(f.actor.messages.size(), 1, "A removed tutor cannot answer the new course");
        }
        helper.succeed();
    }

    private BasicTutorInteractionGameTests() {}
}