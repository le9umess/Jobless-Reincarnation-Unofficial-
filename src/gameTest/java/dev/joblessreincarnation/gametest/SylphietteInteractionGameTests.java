package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.SylphietteInteractions;
import dev.joblessreincarnation.npc.SylphietteEntity;
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
public final class SylphietteInteractionGameTests {
    private static final String PREFIX = "message.jobless_reincarnation.sylphiette.";

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
            super(level, new GameProfile(UUID.randomUUID(), "Sylph" + UUID.randomUUID().toString().substring(0, 8)));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            getAbilities().instabuild = false;
            setNoGravity(true);
        }

        private void capture(Packet<?> packet) {
            // Concurrent GameTest result broadcasts are unrelated chat. Any Sylphiette reply, including
            // one erroneously sent by another fixture/NPC, still matches and is counted.
            if (packet instanceof ClientboundSystemChatPacket chat
                    && translationKeys(chat.content()).stream().anyMatch(key -> key.startsWith(PREFIX))) messages.add(chat);
        }

        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static EntityType<SylphietteEntity> type() { return ModContent.SYLPHIETTE.get(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Reader actor, peer;
        final SylphietteEntity npc, second;
        boolean closed;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            actor = new Reader(helper.getLevel()); peer = new Reader(helper.getLevel());
            actor.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            peer.setPos(helper.absoluteVec(new Vec3(6.6, 2, 2.5)));
            helper.getLevel().addNewPlayer(actor); helper.getLevel().addNewPlayer(peer);
            npc = create(helper.absoluteVec(new Vec3(5.5, 2, 4.5)));
            second = create(helper.absoluteVec(new Vec3(7, 2, 4.5)));
            helper.assertTrue(helper.getLevel().getEntity(actor.getUUID()) == actor && helper.getLevel().getEntity(peer.getUUID()) == peer,
                "Both real registered players can independently talk to two same-type NPC instances");
        }
        SylphietteEntity create(Vec3 position) {
            SylphietteEntity resident = type().create(helper.getLevel());
            if (resident == null) throw new IllegalStateException("Sylphiette factory returned null");
            resident.moveTo(position.x, position.y, position.z, 180, 0);
            resident.setNoAi(true); resident.setNoGravity(true);
            if (!helper.getLevel().addFreshEntity(resident)) throw new IllegalStateException("Sylphiette did not join");
            return resident;
        }
        void packet(Reader player, SylphietteEntity target, boolean specific, InteractionHand hand) {
            ServerboundInteractPacket packet = specific
                ? ServerboundInteractPacket.createInteractionPacket(target, false, hand, new Vec3(0, 1, 0))
                : ServerboundInteractPacket.createInteractionPacket(target, false, hand);
            player.connection.handleInteract(packet);
        }
        void packet(Reader player, SylphietteEntity target, boolean specific) { packet(player, target, specific, InteractionHand.MAIN_HAND); }
        void consumeBoth(Reader player, Entity target, InteractionHand hand) {
            var specific = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(player, hand, target, new Vec3(0, 1, 0)));
            helper.assertTrue(specific.isCanceled() && specific.getCancellationResult().consumesAction(), "Specific target interaction consumes held-item fallback");
            var general = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, target));
            helper.assertTrue(general.isCanceled() && general.getCancellationResult().consumesAction(), "General target interaction consumes held-item fallback");
        }
        void logout(Reader player) { NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); }
        @Override public void close() {
            if (closed) return;
            closed = true; npc.discard(); second.discard();
            logout(actor); logout(peer); actor.discard(); peer.discard();
        }
    }
    private static void guarded(Fixture fixture, Runnable action) {
        try { action.run(); } catch (RuntimeException | Error error) { fixture.close(); throw error; }
    }
    /** Actual keys were inspected in the character-side source; never call its line(int) to produce test expectations. */
    private static void lines(GameTestHelper helper, Reader player, int... expected) {
        helper.assertValueEqual(player.messages.size(), expected.length, "Only intended player receives the expected private reply count");
        for (int i = 0; i < expected.length; i++) {
            List<String> keys = translationKeys(player.messages.get(i).content()).stream()
                .filter(key -> key.equals(PREFIX + "0") || key.equals(PREFIX + "1") || key.equals(PREFIX + "2")).toList();
            helper.assertValueEqual(keys, List.of(PREFIX + expected[i]), "The actual packet contains precisely the expected dialogue in the three-line cycle");
            helper.assertFalse(player.messages.get(i).overlay(), "Conversation uses private chat, not an action bar");
        }
    }
    private static void noAttachments(GameTestHelper helper, Reader player) {
        helper.assertFalse(player.hasData(ModAttachments.KNOWLEDGE) || player.hasData(ModAttachments.TRAINING)
                || player.hasData(ModAttachments.MANA) || player.hasData(ModAttachments.CASTING)
                || player.hasData(ModAttachments.MANA_GROWTH), "Sylphiette chat creates no learning, casting, mana, training, or growth attachments");
    }

    private static void prepareProgress(Reader player) {
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(1, true, false));
        player.setData(ModAttachments.MANA, new ManaData(73, 100, 17, 9));
        player.setData(ModAttachments.CASTING, new CastingData());
        player.setData(ModAttachments.MANA_GROWTH, new ManaGrowthData(2, 7, 0, 0, "sylphie-test"));
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

    private static JsonObject language(String locale) {
        String path = "/assets/jobless_reincarnation/lang/" + locale + ".json";
        var input = SylphietteInteractionGameTests.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing packaged language " + path);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (IOException error) { throw new IllegalStateException("Cannot read " + path, error); }
    }
    private static String localized(GameTestHelper helper, JsonObject language, Component component) {
        String text;
        if (component.getContents() instanceof TranslatableContents contents) {
            helper.assertTrue(language.has(contents.getKey()), "Actual reply has a packaged translation: " + contents.getKey());
            String format = language.get(contents.getKey()).getAsString();
            helper.assertFalse(format.isBlank(), "Used translation is not empty");
            long placeholders = Pattern.compile("%(?:[0-9]+\\$)?s").matcher(format).results().count();
            helper.assertValueEqual(placeholders, (long)contents.getArgs().length, "Actual nested reply's translation parameters match in each locale");
            Object[] arguments = Arrays.stream(contents.getArgs()).map(arg -> arg instanceof Component child ? localized(helper, language, child) : arg).toArray();
            text = String.format(Locale.ROOT, format, arguments);
        } else if (component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents literal) text = literal.text();
        else throw new IllegalStateException("Unexpected reply content " + component.getContents());
        StringBuilder result = new StringBuilder(text);
        for (Component sibling : component.getSiblings()) result.append(localized(helper, language, sibling));
        return result.toString();
    }
    private static void actualPacketCodecsAndBothLocales(GameTestHelper helper, Reader player) {
        for (ClientboundSystemChatPacket packet : player.messages) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
                var decoded = ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
                helper.assertValueEqual(decoded.content(), packet.content(), "Native chat codec preserves this real structured reply");
                helper.assertFalse(decoded.overlay(), "Native codec retains private chat placement");
                for (String locale : List.of("zh_cn", "en_us")) {
                    String text = localized(helper, language(locale), decoded.content());
                    helper.assertFalse(text.isBlank() || text.contains(PREFIX) || text.contains("%s"), "Both packaged locales resolve without missing keys or arguments");
                }
            } finally { buffer.release(); }
        }
    }

    @GameTest(template = "empty", batch = "sylphieS01Interaction")
    public static void nativeConversationIsPrivateAndCreatesNoPlayerProgress(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            noAttachments(helper, f.actor); noAttachments(helper, f.peer);
            String inventory = inventoryState(f.actor);
            f.packet(f.actor, f.npc, true);
            f.packet(f.actor, f.npc, false);
            lines(helper, f.actor, 0); lines(helper, f.peer);
            helper.assertTrue(f.npc.conversationPlayer() == f.actor, "Accepted real actor owns this conversation");
            f.packet(f.peer, f.npc, false);
            lines(helper, f.actor, 0); lines(helper, f.peer, 0);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "The second player can query independently");
            helper.assertValueEqual(inventoryState(f.actor), inventory, "Talking does not change inventory");
            noAttachments(helper, f.actor); noAttachments(helper, f.peer);
            actualPacketCodecsAndBothLocales(helper, f.actor);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sylphieS01Interaction", timeoutTicks = 100)
    public static void realTickCooldownAndThreeLineRotationArePerPlayerAndPerNpc(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long firstTick = helper.getLevel().getGameTime();
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            f.packet(f.actor, f.second, false);
            lines(helper, f.actor, 0, 0);
            lines(helper, f.peer, 0);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer && f.second.conversationPlayer() == f.actor,
                    "Each NPC retains its last accepted actor");
        });
        for (int delay = 1; delay < SylphietteInteractions.REPLY_INTERVAL_TICKS; delay++) {
            helper.runAfterDelay(delay, () -> guarded(f, () -> {
                f.packet(f.actor, f.npc, true);
                f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.second, true);
                f.packet(f.peer, f.npc, true, InteractionHand.OFF_HAND);
                lines(helper, f.actor, 0, 0);
                lines(helper, f.peer, 0);
                helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Cooldown duplicates cannot steal another actor's gaze");
            }));
        }
        helper.runAfterDelay(SylphietteInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            helper.assertValueEqual(helper.getLevel().getGameTime() - firstTick, 20L, "Cooldown opens at twenty actual server ticks");
            f.packet(f.actor, f.npc, false);
            f.packet(f.actor, f.npc, true);
            lines(helper, f.actor, 0, 0, 1);
            lines(helper, f.peer, 0);
        }));
        helper.runAfterDelay(2 * SylphietteInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            f.packet(f.actor, f.second, true);
            lines(helper, f.actor, 0, 0, 1, 2, 1);
            lines(helper, f.peer, 0, 1);
        }));
        helper.runAfterDelay(3 * SylphietteInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            try (f) {
                f.packet(f.actor, f.npc, false);
                f.packet(f.peer, f.npc, true);
                f.packet(f.actor, f.second, false);
                lines(helper, f.actor, 0, 0, 1, 2, 1, 0, 2);
                lines(helper, f.peer, 0, 1, 2);
                actualPacketCodecsAndBothLocales(helper, f.actor);
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty", batch = "sylphieS01Interaction", timeoutTicks = 70)
    public static void rejectedClicksDoNotStealOrExtendTheFortyTickPause(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.peer, f.npc, false);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "The last accepted actor owns the pause");
        });
        helper.runAfterDelay(SylphietteInteractions.REPLY_INTERVAL_TICKS - 1, () -> guarded(f, () -> {
            f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "Rejected cooldown clicks do not steal the conversation");
        }));
        helper.runAfterDelay(SylphietteEntity.CONVERSATION_TICKS - 1, () -> guarded(f, () -> {
            f.consumeBoth(f.actor, f.npc, InteractionHand.OFF_HAND);
            f.actor.spectator = true;
            f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
            f.actor.spectator = false;
            helper.assertTrue(f.npc.conversationPlayer() == f.peer, "The original forty-tick pause is still live one tick before expiry");
            lines(helper, f.actor, 0);
            lines(helper, f.peer, 0);
        }));
        helper.runAfterDelay(SylphietteEntity.CONVERSATION_TICKS, () -> guarded(f, () -> {
            try (f) {
                helper.assertFalse(f.npc.isConversing(), "Rejected cooldown, offhand, and spectator clicks never extend the original pause");
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty", batch = "sylphieS01Interaction", timeoutTicks = 60)
    public static void logoutAndNpcLeaveResetOnlyTheirOwnTransientSentenceAndCooldown(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        guarded(f, () -> {
            f.packet(f.actor, f.npc, true);
            f.packet(f.actor, f.second, false);
            f.packet(f.peer, f.npc, true);
        });
        helper.runAfterDelay(SylphietteInteractions.REPLY_INTERVAL_TICKS, () -> guarded(f, () -> {
            try (f) {
                f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.second, true);
                f.packet(f.peer, f.npc, false);
                lines(helper, f.actor, 0, 0, 1, 1);
                lines(helper, f.peer, 0, 1);
                // Raise the actual cleanup subscription without disconnecting the player needed to query it.
                f.logout(f.actor);
                f.packet(f.actor, f.npc, true);
                f.packet(f.actor, f.second, false);
                f.packet(f.peer, f.npc, true);
                lines(helper, f.actor, 0, 0, 1, 1, 0, 0);
                lines(helper, f.peer, 0, 1);
                NeoForge.EVENT_BUS.post(new EntityLeaveLevelEvent(f.npc, helper.getLevel()));
                f.packet(f.actor, f.npc, false);
                f.packet(f.peer, f.npc, true);
                f.packet(f.actor, f.second, true);
                lines(helper, f.actor, 0, 0, 1, 1, 0, 0, 0);
                lines(helper, f.peer, 0, 1, 0);
                helper.succeed();
            }
        }));
    }

    @GameTest(template = "empty", batch = "sylphieS01Interaction")
    public static void invalidHandsActorsIdentitiesReachAndSightCannotReplyOrFallThrough(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Reader remote = new Reader(helper.getLevel().getServer().getLevel(Level.NETHER));
            Reader unregistered = new Reader(helper.getLevel());
            SylphietteEntity impostor = type().create(helper.getLevel());
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
                lines(helper, f.actor);
                lines(helper, remote);
                lines(helper, unregistered);
                helper.assertFalse(f.npc.isConversing(), "No rejected attempt begins a conversation");
                f.packet(f.actor, f.npc, false);
                lines(helper, f.actor, 0);
                f.npc.discard();
                f.consumeBoth(f.actor, f.npc, InteractionHand.MAIN_HAND);
                lines(helper, f.actor, 0);
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

    @GameTest(template = "empty", batch = "sylphieS01Interaction")
    public static void booksWandEggLeadAndNameTagAreConsumedWithoutGameplayChanges(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            prepareProgress(f.actor); String attachments = attachmentState(f.actor);
            Component name = f.npc.getCustomName().copy();
            ItemStack tag = Items.NAME_TAG.getDefaultInstance();
            tag.set(DataComponents.CUSTOM_NAME, Component.literal("Unexpected replacement name"));
            List<ItemStack> held = List.of(ModContent.WATER_TOME.get().getDefaultInstance(), ModContent.FIRE_TOME.get().getDefaultInstance(),
                ModContent.TRAINING_STAFF.get().getDefaultInstance(), tag, ModContent.SYLPHIETTE_SPAWN_EGG.get().getDefaultInstance(), Items.LEAD.getDefaultInstance());
            int originalCount = helper.getLevel().getEntitiesOfClass(SylphietteEntity.class, f.npc.getBoundingBox().inflate(4)).size();
            for (ItemStack item : held) {
                f.logout(f.actor);
                f.actor.setItemInHand(InteractionHand.MAIN_HAND, item.copy()); f.actor.setItemInHand(InteractionHand.OFF_HAND, item.copy());
                String inventory = inventoryState(f.actor);
                f.packet(f.actor, f.npc, true); f.packet(f.actor, f.npc, false);
                f.packet(f.actor, f.npc, true, InteractionHand.OFF_HAND); f.consumeBoth(f.actor, f.npc, InteractionHand.OFF_HAND);
                helper.assertTrue(f.actor.interactOn(f.npc, InteractionHand.MAIN_HAND).consumesAction(), "Actual player hook consumes this character before item or mob fallback");
                helper.assertFalse(f.actor.isUsingItem(), "Conversation cannot start studying or chanting");
                helper.assertValueEqual(attachmentState(f.actor), attachments, "All five existing knowledge/resource/progress attachments remain unchanged");
                helper.assertValueEqual(inventoryState(f.actor), inventory, "No held stack, count or selected slot changes");
                helper.assertValueEqual(f.npc.getCustomName(), name, "Name tag cannot rename the character through dialogue");
                helper.assertFalse(f.npc.isLeashed(), "Lead cannot attach during dialogue");
                helper.assertValueEqual(helper.getLevel().getEntitiesOfClass(SylphietteEntity.class, f.npc.getBoundingBox().inflate(4)).size(), originalCount, "Her own egg cannot spawn another NPC through dialogue");
                helper.assertFalse(f.actor.getData(ModAttachments.TRAINING).claimed(), "Talking grants no training completion");
                helper.assertFalse(f.actor.getInventory().contains(new ItemStack(ModContent.TRAINING_RECORD.get())), "No reward item is granted");
                helper.assertEntityNotPresent(ModContent.WATER_BOLT.get()); helper.assertEntityNotPresent(ModContent.FIRE_BALL.get());
            }
            lines(helper, f.actor, 0, 0, 0, 0, 0, 0); lines(helper, f.peer); noAttachments(helper, f.peer);
        }
        helper.succeed();
    }

    private SylphietteInteractionGameTests() { }
}
