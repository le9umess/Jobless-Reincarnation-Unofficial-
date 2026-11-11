package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.FamilyInteractions;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.FamilyResidentEntity.Member;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Registered server players and native packets, independent of preview scenes or physical client input. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FamilyInteractionGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.family.";

    private static List<String> translationKeys(Component component) {
        List<String> keys = new ArrayList<>();
        if (component.getContents() instanceof TranslatableContents translated) {
            keys.add(translated.getKey());
            for (Object arg : translated.getArgs()) {
                if (arg instanceof Component child) keys.addAll(translationKeys(child));
            }
        }
        for (Component sibling : component.getSiblings()) keys.addAll(translationKeys(sibling));
        return keys;
    }

    private static final class Reader extends FakePlayer {
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        boolean spectator;

        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "Family" + UUID.randomUUID().toString().substring(0, 8)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
            setNoGravity(true);
        }

        private void capture(Packet<?> packet) {
            // Concurrent GameTest result broadcasts are unrelated chat. Any family reply, including
            // one erroneously sent by another fixture/NPC, still matches and is counted.
            if (packet instanceof ClientboundSystemChatPacket chat
                    && translationKeys(chat.content()).stream().anyMatch(key -> key.startsWith(PREFIX))) messages.add(chat);
        }

        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static EntityType<FamilyResidentEntity> type(Member member) {
        return switch (member) {
            case PAUL -> ModContent.PAUL_GREYRAT.get();
            case ZENITH -> ModContent.ZENITH_GREYRAT.get();
            case LILIA -> ModContent.LILIA_GREYRAT.get();
        };
    }

    private static ItemStack egg(Member member) {
        return switch (member) {
            case PAUL -> ModContent.PAUL_GREYRAT_SPAWN_EGG.get().getDefaultInstance();
            case ZENITH -> ModContent.ZENITH_GREYRAT_SPAWN_EGG.get().getDefaultInstance();
            case LILIA -> ModContent.LILIA_GREYRAT_SPAWN_EGG.get().getDefaultInstance();
        };
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Reader actor;
        final Reader peer;
        final FamilyResidentEntity npc;
        final FamilyResidentEntity second;
        boolean closed;

        Fixture(GameTestHelper helper) { this(helper, Member.PAUL); }

        Fixture(GameTestHelper helper, Member member) {
            this.helper = helper;
            actor = new Reader(helper.getLevel());
            peer = new Reader(helper.getLevel());
            actor.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            peer.setPos(helper.absoluteVec(new Vec3(6.6, 2, 2.5)));
            helper.getLevel().addNewPlayer(actor);
            helper.getLevel().addNewPlayer(peer);
            npc = create(member, helper.absoluteVec(new Vec3(5.5, 2, 4.5)));
            // Same member, different instance: a member-keyed dialogue map must not pass this test.
            second = create(member, helper.absoluteVec(new Vec3(7, 2, 4.5)));
            helper.assertTrue(helper.getLevel().getEntity(actor.getUUID()) == actor
                    && helper.getLevel().getEntity(peer.getUUID()) == peer, "Both actors are registered server players");
        }

        FamilyResidentEntity create(Member member, Vec3 pos) {
            FamilyResidentEntity resident = type(member).create(helper.getLevel());
            if (resident == null) throw new IllegalStateException("Family entity factory returned null");
            resident.moveTo(pos.x, pos.y, pos.z, 180, 0);
            resident.setNoAi(true);
            resident.setNoGravity(true);
            if (!helper.getLevel().addFreshEntity(resident)) throw new IllegalStateException("Family entity did not join the level");
            return resident;
        }

        void packet(Reader player, FamilyResidentEntity target, boolean specific, InteractionHand hand) {
            ServerboundInteractPacket packet = specific
                    ? ServerboundInteractPacket.createInteractionPacket(target, false, hand, new Vec3(0, 1, 0))
                    : ServerboundInteractPacket.createInteractionPacket(target, false, hand);
            player.connection.handleInteract(packet);
        }

        void packet(Reader player, FamilyResidentEntity target, boolean specific) {
            packet(player, target, specific, InteractionHand.MAIN_HAND);
        }

        void consumeBoth(Reader player, Entity target, InteractionHand hand) {
            var specific = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(player, hand, target, new Vec3(0, 1, 0)));
            helper.assertTrue(specific.isCanceled() && specific.getCancellationResult().consumesAction(),
                    "The specific family interaction prevents held-item fall-through");
            var general = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, target));
            helper.assertTrue(general.isCanceled() && general.getCancellationResult().consumesAction(),
                    "The general family interaction prevents held-item fall-through");
        }

        void logout(Reader player) { NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            npc.discard();
            second.discard();
            logout(actor);
            logout(peer);
            actor.discard();
            peer.discard();
        }
    }

    private static void guarded(Fixture fixture, Runnable action) {
        try { action.run(); }
        catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
    }

    private static void lines(GameTestHelper helper, Reader player, Member member, int... expected) {
        helper.assertValueEqual(player.messages.size(), expected.length, "Only the intended actor receives the expected number of family replies");
        for (int index = 0; index < expected.length; index++) {
            List<String> keys = translationKeys(player.messages.get(index).content()).stream()
                    .filter(key -> key.matches("message\\.jobless_reincarnation\\.family\\.(paul|zenith|lilia)_greyrat\\.[0-2]"))
                    .toList();
            helper.assertValueEqual(keys, List.of(PREFIX + member.id() + "." + expected[index]),
                    "A reply contains exactly the expected localizable dialogue, even inside a speaker component");
            helper.assertFalse(player.messages.get(index).overlay(), "Family conversation is private chat rather than an action bar");
        }
    }

    private static void noAttachments(GameTestHelper helper, Reader player) {
        helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE) || player.hasData(ModAttachments.TRAINING)
                || player.hasData(ModAttachments.MANA) || player.hasData(ModAttachments.CASTING)
                || player.hasData(ModAttachments.MANA_GROWTH), "Family chat creates no learning, casting, mana, training, or growth attachments");
    }

    private static void prepareProgress(Reader player) {
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(1, true, false));
        player.setData(ModAttachments.MANA, new ManaData(73, 100, 17, 9));
        player.setData(ModAttachments.CASTING, new CastingData());
        player.setData(ModAttachments.MANA_GROWTH, new ManaGrowthData(2, 7, 0, 0, "family-test"));
    }

    private static String attachmentState(Reader player) {
        return MagicKnowledge.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.KNOWLEDGE)).getOrThrow().toString()
                + TrainingProgress.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.TRAINING)).getOrThrow()
                + ManaData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA)).getOrThrow()
                + CastingData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.CASTING)).getOrThrow()
                + ManaGrowthData.CODEC.encodeStart(JsonOps.INSTANCE, player.getData(ModAttachments.MANA_GROWTH)).getOrThrow();
    }

    private static String inventoryState(Reader player) {
        return player.getInventory().save(new ListTag()).toString() + ":slot=" + player.getInventory().selected;
    }

    @GameTest(template = "empty")
    public static void allThreeMembersReplyPrivatelyWithoutCreatingPlayerProgress(GameTestHelper helper) {
        for (Member member : Member.values()) {
            try (Fixture f = new Fixture(helper, member)) {
                noAttachments(helper, f.actor);
                noAttachments(helper, f.peer);
                String inventory = inventoryState(f.actor);
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                lines(helper, f.actor, member, 0);
                lines(helper, f.peer, member);
                helper.assertTrue(f.npc.conversationPlayer() == f.actor, "The accepted actor is the actual conversation target");
                f.packet(f.peer, f.npc, false);
                lines(helper, f.actor, member, 0);
                lines(helper, f.peer, member, 0);
                helper.assertValueEqual(inventoryState(f.actor), inventory, "Talking leaves the inventory untouched");
                noAttachments(helper, f.actor);
                noAttachments(helper, f.peer);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
                try {
                    ClientboundSystemChatPacket original = f.actor.messages.getFirst();
                    ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, original);
                    ClientboundSystemChatPacket decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
                    helper.assertValueEqual(decoded.content(), original.content(), "Native chat encoding preserves the translated name and line");
                    helper.assertFalse(decoded.overlay(), "The native packet retains private chat placement");
                } finally { buffer.release(); }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realTickCooldownAndThreeLineRotationArePerPlayerAndPerNpc(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long firstTick = helper.getLevel().getGameTime();
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            f.packet(f.actor, f.second, false);
            lines(helper, f.actor, Member.PAUL, 0, 0);
            lines(helper, f.peer, Member.PAUL, 0);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer && f.second.conversationPlayer() == f.actor,
                    "Each NPC retains its last accepted actor");
        });
        for (int delay = 1; delay < FamilyInteractions.REPLY_INTERVAL_TICKS; delay++) {
            helper.runAfterDelay(delay, () -> guarded(f, () -> {
                f.packet(f.actor, f.npc, true);
                f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.second, true);
                f.packet(f.peer, f.npc, true, InteractionHand.OFF_HAND);
                lines(helper, f.actor, Member.PAUL, 0, 0);
                lines(helper, f.peer, Member.PAUL, 0);
                helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Cooldown duplicates cannot steal another actor's gaze");
            }));
        }
        helper.runAfterDelay(FamilyInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            helper.assertValueEqual(helper.getLevel().getGameTime() - firstTick, 20L, "Cooldown opens at twenty actual server ticks");
            f.packet(f.actor, f.npc, false);
            f.packet(f.actor, f.npc, true);
            lines(helper, f.actor, Member.PAUL, 0, 0, 1);
            lines(helper, f.peer, Member.PAUL, 0);
        }));
        helper.runAfterDelay(2 * FamilyInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            f.packet(f.actor, f.second, true);
            lines(helper, f.actor, Member.PAUL, 0, 0, 1, 2, 1);
            lines(helper, f.peer, Member.PAUL, 0, 1);
        }));
        helper.runAfterDelay(3 * FamilyInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            try (f) {
                f.packet(f.actor, f.npc, false);
                f.packet(f.peer, f.npc, true);
                f.packet(f.actor, f.second, false);
                lines(helper, f.actor, Member.PAUL, 0, 0, 1, 2, 1, 0, 2);
                lines(helper, f.peer, Member.PAUL, 0, 1, 2);
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void rejectedClicksDoNotStealOrExtendTheFortyTickPause(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "The last accepted actor owns the pause");
        });
        helper.runAfterDelay(FamilyInteractions.REPLY_INTERVAL_TICKS - 1, () -> guarded(f, () -> {
            f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Rejected cooldown clicks do not steal the conversation");
        }));
        helper.runAfterDelay(FamilyResidentEntity.CONVERSATION_TICKS - 1, () -> guarded(f, () -> {
            f.consumeBoth(f.actor, f.npc, InteractionHand.OFF_HAND);
            f.actor.spectator = true;
            f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
            f.actor.spectator = false;
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "The original forty-tick pause is still live one tick before expiry");
            lines(helper, f.actor, Member.PAUL, 0);
            lines(helper, f.peer, Member.PAUL, 0);
        }));
        helper.runAfterDelay(FamilyResidentEntity.CONVERSATION_TICKS, () -> guarded(f, () -> {
            try (f) {
                helper.assertFalse(f.npc.isConversing(), "Rejected cooldown, offhand, and spectator clicks never extend the original pause");
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void logoutAndNpcLeaveResetOnlyTheirOwnTransientSentenceAndCooldown(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.actor, f.second, false);
            f.packet(f.peer, f.npc, true);
        });
        helper.runAfterDelay(FamilyInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            try (f) {
                f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.second, true);
                f.packet(f.peer, f.npc, false);
                lines(helper, f.actor, Member.PAUL, 0, 0, 1, 1);
                lines(helper, f.peer, Member.PAUL, 0, 1);
                // Raise the actual cleanup subscription without disconnecting the player needed to query it.
                f.logout(f.actor);
                f.packet(f.actor, f.npc, true);
                f.packet(f.actor, f.second, false);
                f.packet(f.peer, f.npc, true);
                lines(helper, f.actor, Member.PAUL, 0, 0, 1, 1, 0, 0);
                lines(helper, f.peer, Member.PAUL, 0, 1);
                NeoForge.EVENT_BUS.post(new EntityLeaveLevelEvent(f.npc, helper.getLevel()));
                f.packet(f.actor, f.npc, false);
                f.packet(f.peer, f.npc, true);
                f.packet(f.actor, f.second, true);
                lines(helper, f.actor, Member.PAUL, 0, 0, 1, 1, 0, 0, 0);
                lines(helper, f.peer, Member.PAUL, 0, 1, 0);
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty")
    public static void invalidHandsActorsIdentitiesReachAndSightCannotReplyOrFallThrough(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Reader remote = new Reader(helper.getLevel().getServer().getLevel(Level.NETHER));
            Reader unregistered = new Reader(helper.getLevel());
            FamilyResidentEntity impostor = type(Member.PAUL).create(helper.getLevel());
            if (impostor == null) throw new IllegalStateException("Impostor fixture did not create");
            try {
                f.consumeBoth(f.actor, f.npc, InteractionHand.OFF_HAND);
                f.packet(f.actor, f.npc, true, InteractionHand.OFF_HAND);
                remote.setPos(f.actor.position());
                f.consumeBoth(remote, f.npc, InteractionHand.MAIN_HAND);
                unregistered.setPos(f.actor.position());
                f.consumeBoth(unregistered, f.npc, InteractionHand.MAIN_HAND);
                unregistered.setUUID(f.actor.getUUID());
                f.consumeBoth(unregistered, f.npc, InteractionHand.MAIN_HAND);
                impostor.setUUID(f.npc.getUUID());
                impostor.setPos(f.npc.position());
                f.consumeBoth(f.actor, impostor, InteractionHand.MAIN_HAND);
                f.actor.spectator = true;
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.spectator = false;
                f.actor.setHealth(0);
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.setHealth(f.actor.getMaxHealth());
                Vec3 original = f.actor.position();
                f.actor.setPos(original.add(0, 0, -20));
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.actor.setPos(original);
                BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
                var previous = helper.getLevel().getBlockState(wall);
                try {
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    helper.assertFalse(f.actor.hasLineOfSight(f.npc), "The obstacle actually blocks the actor-to-NPC eye ray");
                    f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                } finally { helper.getLevel().setBlockAndUpdate(wall, previous); }
                f.npc.setHealth(0);
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                f.npc.setHealth(f.npc.getMaxHealth());
                lines(helper, f.actor, Member.PAUL);
                lines(helper, remote, Member.PAUL);
                lines(helper, unregistered, Member.PAUL);
                helper.assertFalse(f.npc.isConversing(), "No rejected attempt begins a conversation");
                f.packet(f.actor, f.npc, false);
                lines(helper, f.actor, Member.PAUL, 0);
                f.npc.discard();
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                lines(helper, f.actor, Member.PAUL, 0);
                noAttachments(helper, f.actor);
                noAttachments(helper, remote);
                noAttachments(helper, unregistered);
            } finally {
                f.logout(remote);
                f.logout(unregistered);
                remote.discard();
                unregistered.discard();
                impostor.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void heldBooksStaffTagsEggsAndLeadsCannotChangeResidentsOrLearning(GameTestHelper helper) {
        for (Member member : Member.values()) {
            try (Fixture f = new Fixture(helper, member)) {
                prepareProgress(f.actor);
                String attachments = attachmentState(f.actor);
                Component originalName = f.npc.getCustomName().copy();
                ItemStack nameTag = Items.NAME_TAG.getDefaultInstance();
                nameTag.set(DataComponents.CUSTOM_NAME, Component.literal("Unexpected replacement name"));
                List<ItemStack> held = List.of(ModContent.WATER_TOME.get().getDefaultInstance(),
                        ModContent.WATER_SHIELD_TOME.get().getDefaultInstance(), ModContent.FIRE_TOME.get().getDefaultInstance(), ModContent.TRAINING_STAFF.get().getDefaultInstance(),
                        nameTag, egg(member), Items.LEAD.getDefaultInstance());
                int originalResidents = helper.getLevel().getEntitiesOfClass(FamilyResidentEntity.class, f.npc.getBoundingBox().inflate(4)).size();
                for (ItemStack item : held) {
                    f.logout(f.actor);
                    f.actor.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
                    f.actor.setItemInHand(InteractionHand.OFF_HAND, item.copy());
                    String inventory = inventoryState(f.actor);
                    f.packet(f.actor, f.npc, true);
                    f.packet(f.actor, f.npc, false);
                    f.packet(f.actor, f.npc, true, InteractionHand.OFF_HAND);
                    f.consumeBoth(f.actor, f.npc, InteractionHand.OFF_HAND);
                    helper.assertTrue(f.actor.interactOn(f.npc, InteractionHand.MAIN_HAND).consumesAction(),
                            "The real Player interaction hook consumes the entity before item or mob fallback");
                    helper.assertFalse(f.actor.isUsingItem(), "Conversation cannot begin study or a wand chant");
                    helper.assertValueEqual(attachmentState(f.actor), attachments, "Dialogue leaves all five player attachments unchanged");
                    helper.assertValueEqual(inventoryState(f.actor), inventory, "No held item, stack count, or selected slot changes");
                    helper.assertValueEqual(f.npc.getCustomName(), originalName, "A name tag cannot rename the resident");
                    helper.assertFalse(f.npc.isLeashed(), "A lead cannot attach");
                    helper.assertValueEqual(helper.getLevel().getEntitiesOfClass(FamilyResidentEntity.class, f.npc.getBoundingBox().inflate(4)).size(),
                            originalResidents, "The resident's spawn egg cannot create a second entity during dialogue");
                    helper.assertFalse(f.actor.getData(ModAttachments.TRAINING).claimed(), "Family chat cannot claim a training reward");
                    helper.assertFalse(f.actor.getInventory().contains(new ItemStack(ModContent.TRAINING_RECORD.get())), "Family chat grants no reward item");
                    helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
                    helper.assertEntityNotPresent(ModContent.FIRE_BALL.get());
                }
                // One first-line reply for every held item tried above.
                lines(helper, f.actor, member, new int[held.size()]);
                lines(helper, f.peer, member);
                noAttachments(helper, f.peer);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void savedResidentRestartsDialogueWithoutPersistingConversation(GameTestHelper helper) {
        Fixture f = new Fixture(helper, Member.LILIA);
        guarded(f, () -> f.packet(f.actor, f.npc, true));
        helper.runAfterDelay(FamilyInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            FamilyResidentEntity restored = type(Member.LILIA).create(helper.getLevel());
            if (restored == null) throw new IllegalStateException("Reload fixture did not create");
            try (f) {
                f.packet(f.actor, f.npc, false);
                lines(helper, f.actor, Member.LILIA, 0, 1);
                CompoundTag saved = f.npc.saveWithoutId(new CompoundTag());
                f.npc.discard();
                restored.load(saved);
                helper.assertTrue(helper.getLevel().addFreshEntity(restored), "The saved resident rejoins the real entity index");
                helper.assertFalse(restored.isConversing(), "A saved resident never resumes a stale conversation target or pause");
                f.packet(f.actor, restored, true);
                lines(helper, f.actor, Member.LILIA, 0, 1, 0);
                helper.assertTrue(restored.conversationPlayer() == f.actor, "A fresh valid conversation can begin after reload");
                helper.succeed();
            } finally { restored.discard(); }
        }));
    }

    private FamilyInteractionGameTests() {}
}
