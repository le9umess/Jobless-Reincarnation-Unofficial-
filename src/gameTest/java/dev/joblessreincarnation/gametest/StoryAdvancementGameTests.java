package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.magic.WaterShieldSpell;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Native advancement state, real success hooks and packet semantics; no FakePlayer/no-op progress. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryAdvancementGameTests {
    private static final String BATCH = "storyAdvA01";
    private static final String SYLPH_REPLY = "message.jobless_reincarnation.sylphiette.";
    private static final List<ResourceLocation> STORY = List.of(StoryAdvancements.ROOT, StoryAdvancements.GREYRAT_HOME,
            StoryAdvancements.FIRST_SPELL, StoryAdvancements.MEET_SYLPHIETTE, StoryAdvancements.WATER_SHIELD_BLOCK);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }
    private static AdvancementHolder advancement(Actor actor, ResourceLocation id) {
        return advancement(actor.serverLevel(), id);
    }
    private static AdvancementHolder advancement(ServerLevel level, ResourceLocation id) {
        var result = level.getServer().getAdvancements().get(id);
        if (result == null) throw new IllegalStateException("Missing native loaded advancement: " + id);
        return result;
    }
    private static boolean done(Actor actor, ResourceLocation id) {
        return actor.getAdvancements().getOrStartProgress(advancement(actor, id)).isDone();
    }
    private static boolean isSylphReply(Component component) {
        if (component.getContents() instanceof TranslatableContents text) {
            if (text.getKey().startsWith(SYLPH_REPLY)) return true;
            for (Object arg : text.getArgs()) if (arg instanceof Component child && isSylphReply(child)) return true;
        }
        return component.getSiblings().stream().anyMatch(StoryAdvancementGameTests::isSylphReply);
    }

    /** Uses the actual PlayerAdvancements created by PlayerList, with a random fixture-only identity. */
    private static final class Actor extends ServerPlayer implements AutoCloseable {
        final List<ClientboundUpdateAdvancementsPacket> updates = new ArrayList<>();
        final List<ClientboundSystemChatPacket> replies = new ArrayList<>();
        final Map<ResourceLocation, AdvancementHolder> clientDefinitions = new HashMap<>();
        boolean spectator;
        boolean closed;
        Actor(GameTestHelper helper) { this(helper, new GameProfile(UUID.randomUUID(), "Adv" + UUID.randomUUID().toString().substring(0, 8))); }
        Actor(GameTestHelper helper, GameProfile profile) {
            super(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
            setNoGravity(true);
            getAbilities().instabuild = false;
        }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundUpdateAdvancementsPacket update) {
                // Round-trip now: vanilla packet maps otherwise retain mutable AdvancementProgress references.
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
                try {
                    ClientboundUpdateAdvancementsPacket.STREAM_CODEC.encode(buffer, update);
                    var decoded = ClientboundUpdateAdvancementsPacket.STREAM_CODEC.decode(buffer);
                    if (decoded.shouldReset()) clientDefinitions.clear();
                    decoded.getRemoved().forEach(clientDefinitions::remove);
                    decoded.getAdded().forEach(holder -> clientDefinitions.put(holder.id(), holder));
                    // Match ClientAdvancements: progress criteria are interpreted with advertised requirements.
                    decoded.getProgress().forEach((id, progress) -> {
                        var definition = clientDefinitions.get(id);
                        if (definition == null) throw new IllegalStateException("Progress without advertised definition: " + id);
                        progress.update(definition.value().requirements());
                    });
                    updates.add(decoded);
                } finally { buffer.release(); }
            } else if (packet instanceof ClientboundSystemChatPacket chat && isSylphReply(chat.content())) replies.add(chat);
        }
        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
        void login() { NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(this)); }
        void flush() { getAdvancements().flushDirty(this); }
        void connected() { login(); flush(); updates.clear(); }
        void completeUse() { completeUsingItem(); }
        void register() { serverLevel().addNewPlayer(this); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            getAdvancements().stopListening();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(this));
            discard();
            removeOwnMapping(server.getPlayerList(), "advancements", getUUID(), getAdvancements());
            removeOwnMapping(server.getPlayerList(), "stats", getUUID(), getStats());
        }
    }
    /** Construction caches both maps, even without placeNewPlayer; remove only this test's exact objects. */
    private static void removeOwnMapping(PlayerList list, String name, UUID id, Object expected) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            Object raw = field.get(list);
            if (!(raw instanceof Map<?, ?> values)) throw new IllegalStateException("PlayerList cache changed: " + name);
            if (values.get(id) == expected) values.remove(id);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot clean fixture's " + name, error); }
    }
    private record Tome(Item item, SpellCatalog spell) { }
    private static List<Tome> tomes() {
        return List.of(new Tome(ModContent.WATER_TOME.get(), SpellCatalog.WATER_BOLT),
                new Tome(ModContent.EARTH_TOME.get(), SpellCatalog.EARTH_WALL),
                new Tome(ModContent.AIR_TOME.get(), SpellCatalog.AIR_BURST),
                new Tome(ModContent.HEALING_TOME.get(), SpellCatalog.HEALING),
                new Tome(ModContent.FIRE_TOME.get(), SpellCatalog.FIRE_BALL),
                new Tome(ModContent.WATER_SHIELD_TOME.get(), SpellCatalog.WATER_SHIELD));
    }
    private static void startStudy(Actor actor, Tome tome) {
        actor.setItemInHand(InteractionHand.MAIN_HAND, tome.item().getDefaultInstance());
        tome.item().use(actor.level(), actor, InteractionHand.MAIN_HAND);
    }
    private static void finishStudy(Actor actor, Tome tome) {
        startStudy(actor, tome);
        // Invoke LivingEntity's normal completed-use dispatch, which calls the actual held tome's hook.
        actor.completeUse();
    }
    private static ClientboundUpdateAdvancementsPacket completedPacket(GameTestHelper helper, Actor actor,
            ResourceLocation id, boolean reset) {
        var found = actor.updates.stream().filter(packet -> packet.getProgress().containsKey(id)
                && packet.getProgress().get(id).isDone()).toList();
        helper.assertValueEqual(found.size(), 1, "Exactly one native packet carries this completion: " + id);
        helper.assertValueEqual(found.getFirst().shouldReset(), reset, "Completion uses the expected native reset/incremental semantics");
        return found.getFirst();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void loadedStoryDefinitionsHaveIndependentVisibleBranchesAndNoRewards(GameTestHelper helper) {
        var expectedIcons = List.of(ModContent.TRAINING_STAFF.get(), Items.OAK_DOOR, Items.BOOK, Items.OAK_SAPLING, ModContent.WATER_SHIELD_TOME.get());
        for (int i = 0; i < STORY.size(); i++) {
            var value = advancement(helper.getLevel(), STORY.get(i)).value();
            var display = value.display().orElseThrow();
            helper.assertValueEqual(value.rewards(), AdvancementRewards.EMPTY, "Story progress grants no gameplay rewards");
            helper.assertFalse(display.shouldAnnounceChat() || display.isHidden(), "Visible story milestones never broadcast chat");
            helper.assertValueEqual(display.shouldShowToast(), i != 0, "Only milestone children request native toast");
            helper.assertTrue(display.getIcon().is(expectedIcons.get(i)), "Approved familiar icon survives native JSON decode");
            helper.assertValueEqual(display.getType(), i == 3 ? AdvancementType.GOAL : AdvancementType.TASK, "Approved native frame");
            helper.assertTrue(display.getTitle().getContents() instanceof TranslatableContents
                    && display.getDescription().getContents() instanceof TranslatableContents, "Story labels remain localized");
            if (i == 0) {
                helper.assertTrue(value.parent().isEmpty(), "Organization node owns its tab");
                helper.assertValueEqual(display.getBackground().orElseThrow(), ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png"), "Native oak-plank tab background");
                helper.assertTrue(value.criteria().get("joined").trigger() == CriteriaTriggers.TICK, "Root has a native fallback tick trigger");
            } else helper.assertValueEqual(value.parent().orElseThrow(), i == 4 ? StoryAdvancements.FIRST_SPELL : StoryAdvancements.ROOT, "Shield success follows the learning branch; other milestones are independent root children");
        }
        helper.assertTrue(advancement(helper.getLevel(), StoryAdvancements.GREYRAT_HOME).value().criteria().get("visited").trigger()
                == CriteriaTriggers.LOCATION, "House uses the native location trigger");
        for (var entry : Map.of(StoryAdvancements.FIRST_SPELL, "learned", StoryAdvancements.MEET_SYLPHIETTE, "talked", StoryAdvancements.WATER_SHIELD_BLOCK, "blocked").entrySet())
            helper.assertTrue(advancement(helper.getLevel(), entry.getKey()).value().criteria().get(entry.getValue()).trigger()
                    == CriteriaTriggers.IMPOSSIBLE, "Story success comes from the validated gameplay hook");
        try (Actor actor = new Actor(helper)) {
            CriteriaTriggers.TICK.trigger(actor);
            helper.assertTrue(done(actor, StoryAdvancements.ROOT), "Native fallback root works even without login reconciliation");
            actor.flush();
            var packet = completedPacket(helper, actor, StoryAdvancements.ROOT, true);
            helper.assertTrue(packet.getAdded().stream().map(AdvancementHolder::id).toList().containsAll(STORY), "All four incomplete milestones, including the shield grandchild, are delivered visibly with the root");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void eachTomeAwardsOnlyWhenCompletedWithIndependentNativeDeltaAndNoRepeat(GameTestHelper helper) {
        try (Actor peer = new Actor(helper)) {
            peer.connected();
            for (Tome tome : tomes()) try (Actor actor = new Actor(helper)) {
                actor.connected();
                actor.setItemInHand(InteractionHand.MAIN_HAND, tome.item().getDefaultInstance());
                CriteriaTriggers.INVENTORY_CHANGED.trigger(actor, actor.getInventory(), actor.getMainHandItem());
                helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL), "Possessing a tome is not learning");
                startStudy(actor, tome);
                helper.assertTrue(actor.isUsingItem(), "Real tome use begins studying");
                helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL), "Beginning study is not completion");
                actor.releaseUsingItem();
                helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL) || tome.spell().knows(actor), "Interrupted study grants neither knowledge nor progress");
                finishStudy(actor, tome);
                helper.assertTrue(tome.spell().knows(actor), "Real completed-use hook learned " + tome.spell());
                helper.assertValueEqual(tome.spell().practiceCount(actor), 0, "Learning milestone does not require a cast");
                helper.assertTrue(done(actor, StoryAdvancements.FIRST_SPELL), "Any one of the six supported first spells grants the milestone");
                helper.assertFalse(done(actor, StoryAdvancements.GREYRAT_HOME) || done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Learning does not fabricate visits or conversations");
                actor.flush();
                completedPacket(helper, actor, StoryAdvancements.FIRST_SPELL, false);
                actor.updates.clear();
                tome.item().finishUsingItem(actor.getMainHandItem(), actor.level(), actor);
                actor.flush();
                helper.assertTrue(actor.updates.isEmpty(), "Repeated completed-use callback cannot issue another advancement update");
                helper.assertFalse(done(peer, StoryAdvancements.FIRST_SPELL), "Another player sharing the server receives no knowledge milestone");
            }
            peer.flush();
            helper.assertTrue(peer.updates.isEmpty(), "Private completion never changes another player's progress packet");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void rejectedTomeCompletionCannotGrantKnowledgeOrStoryProgress(GameTestHelper helper) {
        for (Tome tome : tomes()) try (Actor actor = new Actor(helper)) {
            actor.connected();
            var stack = tome.item().getDefaultInstance();
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.setItemInHand(InteractionHand.OFF_HAND, stack);
            actor.startUsingItem(InteractionHand.OFF_HAND);
            tome.item().finishUsingItem(stack, actor.level(), actor);
            actor.stopUsingItem();
            actor.setItemInHand(InteractionHand.MAIN_HAND, stack);
            actor.startUsingItem(InteractionHand.MAIN_HAND);
            actor.spectator = true;
            tome.item().finishUsingItem(stack, actor.level(), actor);
            actor.spectator = false;
            actor.setHealth(0);
            tome.item().finishUsingItem(stack, actor.level(), actor);
            actor.setHealth(actor.getMaxHealth());
            actor.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
            tome.item().finishUsingItem(stack, actor.level(), actor);
            actor.stopUsingItem();
            helper.assertFalse(tome.spell().knows(actor) || done(actor, StoryAdvancements.FIRST_SPELL), "Offhand, spectator, dead or switched-item completions cannot bypass learning guards");
            actor.flush();
            helper.assertTrue(actor.updates.isEmpty(), "Rejected learning never creates a toast-bearing increment");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void successfulConversationOnlyAwardsActualSpeakerAndRejectedConsumedClicksDoNot(GameTestHelper helper) {
        try (Actor actor = new Actor(helper); Actor peer = new Actor(helper)) {
            actor.connected(); peer.connected(); actor.register(); peer.register();
            peer.setPos(helper.absoluteVec(new Vec3(6.6, 2, 2.5)));
            SylphietteEntity npc = ModContent.SYLPHIETTE.get().create(helper.getLevel());
            if (npc == null) throw new IllegalStateException("Sylphiette factory failed");
            npc.moveTo(helper.absoluteVec(new Vec3(5.5, 2, 4.5)), 180, 0);
            npc.setNoAi(true); npc.setNoGravity(true);
            helper.getLevel().addFreshEntity(npc);
            try {
                consume(helper, actor, npc, InteractionHand.OFF_HAND);
                helper.assertFalse(done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Consumed offhand input is not a successful conversation");
                BlockPos wall = helper.absolutePos(new BlockPos(5, 3, 3));
                var old = helper.getLevel().getBlockState(wall);
                try {
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    helper.assertFalse(actor.hasLineOfSight(npc), "Fixture actually blocks the eye ray");
                    consume(helper, actor, npc, InteractionHand.MAIN_HAND);
                    helper.assertFalse(done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Blocked conversation cannot grant encounter");
                } finally { helper.getLevel().setBlockAndUpdate(wall, old); }
                helper.assertTrue(actor.replies.isEmpty(), "Rejected clicks issue no actual line");
                actor.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND));
                helper.assertValueEqual(actor.replies.size(), 1, "The successful native interaction really sent a private reply");
                helper.assertTrue(done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Actual reply grants encounter");
                helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL) || done(actor, StoryAdvancements.GREYRAT_HOME), "Encounter can be the first independent branch");
                helper.assertFalse(done(peer, StoryAdvancements.MEET_SYLPHIETTE), "Nearby observer receives no encounter");
                actor.flush(); completedPacket(helper, actor, StoryAdvancements.MEET_SYLPHIETTE, false);
                actor.updates.clear();
                consume(helper, actor, npc, InteractionHand.MAIN_HAND);
                actor.flush();
                helper.assertTrue(actor.updates.isEmpty() && actor.replies.size() == 1, "Cooldown duplicates create neither reply nor repeat toast");
                // Revoke only the test's criterion to expose an erroneous unconditional CONSUME hook during cooldown.
                actor.getAdvancements().revoke(advancement(actor, StoryAdvancements.MEET_SYLPHIETTE), "talked");
                consume(helper, actor, npc, InteractionHand.MAIN_HAND);
                helper.assertFalse(done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Cooldown's consumed click cannot itself award encounter");
                peer.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(npc, false, InteractionHand.MAIN_HAND));
                helper.assertTrue(done(peer, StoryAdvancements.MEET_SYLPHIETTE) && peer.replies.size() == 1, "Each player can independently complete their own first conversation");
            } finally { npc.discard(); }
        }
        helper.succeed();
    }
    private static void consume(GameTestHelper helper, Actor actor, SylphietteEntity npc, InteractionHand hand) {
        var result = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, hand, npc));
        helper.assertTrue(result.isCanceled() && result.getCancellationResult().consumesAction(), "NPC event consumes the interaction even for rejected input");
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void legacyZeroPracticeKnowledgeReconcilesBeforeFirstResetAndStaysIdempotent(GameTestHelper helper) {
        for (Tome tome : tomes()) try (Actor actor = new Actor(helper)) {
            MagicKnowledge legacy = new MagicKnowledge();
            legacy.learn(tome.spell().id());
            actor.setData(ModAttachments.KNOWLEDGE, legacy);
            helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL), "Old knowledge and advancement storage are separate before login");
            actor.login();
            helper.assertTrue(done(actor, StoryAdvancements.ROOT) && done(actor, StoryAdvancements.FIRST_SPELL), "Supported zero-practice old knowledge is reconciled synchronously at login");
            actor.flush();
            completedPacket(helper, actor, StoryAdvancements.FIRST_SPELL, true);
            helper.assertFalse(done(actor, StoryAdvancements.GREYRAT_HOME) || done(actor, StoryAdvancements.MEET_SYLPHIETTE), "Login does not invent undocumented travel or social history");
            helper.assertValueEqual(tome.spell().practiceCount(actor), 0, "Reconciliation does not fabricate practice");
            actor.updates.clear();
            actor.login(); actor.flush();
            helper.assertTrue(actor.updates.isEmpty(), "Repeated login event does not issue a duplicate milestone packet");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void freshAndUnknownLegacyKnowledgeDoNotReceiveLearningMilestone(GameTestHelper helper) {
        try (Actor fresh = new Actor(helper); Actor unknown = new Actor(helper)) {
            MagicKnowledge legacy = new MagicKnowledge();
            legacy.learn(id("future_unimplemented_magic"));
            legacy.practice(id("future_unimplemented_magic"));
            unknown.setData(ModAttachments.KNOWLEDGE, legacy);
            fresh.login(); unknown.login(); fresh.flush(); unknown.flush();
            helper.assertTrue(done(fresh, StoryAdvancements.ROOT) && done(unknown, StoryAdvancements.ROOT), "Both players still get their organization tab");
            helper.assertFalse(done(fresh, StoryAdvancements.FIRST_SPELL) || done(unknown, StoryAdvancements.FIRST_SPELL), "Absent or unsupported spell records cannot fabricate a supported learning milestone");
            helper.assertTrue(fresh.getData(ModAttachments.KNOWLEDGE).snapshot().isEmpty(), "Existing login synchronization may create an empty attachment but grants no spell");
            helper.assertValueEqual(legacy.snapshot(), unknown.getData(ModAttachments.KNOWLEDGE).snapshot(), "Unknown legacy records are preserved, not rewritten");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void nativeAdvancementSaveAndFreshPlayerReloadPreserveIndependentCompletion(GameTestHelper helper) throws IOException {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Persistence fixture only runs in the disposable GameTest server");
        GameProfile profile = new GameProfile(UUID.randomUUID(), "AdvSave");
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).toAbsolutePath().normalize();
        Path path = directory.resolve(profile.getId() + ".json").normalize();
        helper.assertTrue(path.getParent().equals(directory) && !Files.exists(path), "Fixture owns a new random UUID save file inside this test world's advancement folder");
        try {
            try (Actor actor = new Actor(helper, profile)) {
                actor.connected(); finishStudy(actor, tomes().getFirst());
                helper.assertTrue(done(actor, StoryAdvancements.FIRST_SPELL), "Real completed use precedes native save");
                actor.getAdvancements().save();
                helper.assertTrue(Files.isRegularFile(path) && Files.size(path) > 0, "Native PlayerAdvancements.save wrote actual JSON");
            }
            try (Actor restored = new Actor(helper, profile); Actor other = new Actor(helper)) {
                helper.assertTrue(done(restored, StoryAdvancements.ROOT) && done(restored, StoryAdvancements.FIRST_SPELL), "Fresh native PlayerAdvancements reads completion from disk for the same UUID");
                helper.assertFalse(done(restored, StoryAdvancements.GREYRAT_HOME) || done(restored, StoryAdvancements.MEET_SYLPHIETTE), "Uncompleted branches remain incomplete across reload");
                helper.assertFalse(restored.hasData(ModAttachments.KNOWLEDGE), "Restored completion came from native advancement JSON, not login knowledge reconciliation");
                restored.login(); restored.flush();
                completedPacket(helper, restored, StoryAdvancements.FIRST_SPELL, true);
                helper.assertFalse(done(other, StoryAdvancements.FIRST_SPELL), "A different UUID cannot inherit stored story progress");
            }
        } finally { Files.deleteIfExists(path); }
        helper.succeed();
    }

    @GameTest(template = "house_h01_empty", batch = BATCH)
    public static void nativeHouseLocationRequiresRegisteredPieceAndTrueThreeDimensionalPresence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOptional(id("greyrat_home")).orElseThrow();
        var pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getOptional(id("greyrat_home/start")).orElseThrow();
        var element = pool.getRandomTemplate(RandomSource.create(23001L));
        BlockPos origin = helper.absolutePos(new BlockPos(5, 2, 5));
        var box = element.getBoundingBox(level.getStructureManager(), origin, Rotation.NONE);
        var piece = new PoolElementStructurePiece(level.getStructureManager(), element, origin, element.getGroundLevelDelta(), Rotation.NONE, box, LiquidSettings.APPLY_WATERLOGGING);
        ChunkPos startPos = new ChunkPos(origin);
        var start = new StructureStart(structure, startPos, 0, new PiecesContainer(List.of(piece)));
        Map<LevelChunk, Map<Structure, StructureStart>> savedStarts = new HashMap<>();
        Map<LevelChunk, Map<Structure, LongSet>> savedReferences = new HashMap<>();
        double x = (box.minX() + box.maxX()) / 2.0 + 0.5;
        double y = box.minY() + 1.0;
        double z = (box.minZ() + box.maxZ()) / 2.0 + 0.5;
        try (Actor actor = new Actor(helper); Actor peer = new Actor(helper)) {
            actor.connected(); peer.connected();
            actor.setPos(x, y, z);
            // The test deliberately does not place art/NPCs: location is structural provenance, not a palette detector.
            CriteriaTriggers.LOCATION.trigger(actor);
            helper.assertFalse(done(actor, StoryAdvancements.GREYRAT_HOME), "Being at candidate template coordinates without a registered start does not count");
            try {
                for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    LevelChunk chunk = level.getChunk(cx, cz);
                    savedStarts.put(chunk, new HashMap<>(chunk.getAllStarts()));
                    Map<Structure, LongSet> references = new HashMap<>();
                    chunk.getAllReferences().forEach((key, value) -> references.put(key, new LongOpenHashSet(value)));
                    savedReferences.put(chunk, references);
                    chunk.addReferenceForStructure(structure, startPos.toLong());
                }
                level.getChunk(startPos.x, startPos.z).setStartForStructure(structure, start);
                for (Vec3 outside : List.of(new Vec3(x, box.maxY() + 1.0, z), new Vec3(x, box.minY() - 1.0, z), new Vec3(box.maxX() + 1.0, y, z))) {
                    actor.setPos(outside); CriteriaTriggers.LOCATION.trigger(actor);
                    helper.assertFalse(done(actor, StoryAdvancements.GREYRAT_HOME), "Above, below and horizontally outside actual piece do not count: " + outside);
                }
                actor.setPos(x, y, z);
                helper.assertTrue(level.structureManager().getStructureWithPieceAt(actor.blockPosition(), structure).isValid(), "Native manager sees the real registered house piece");
                CriteriaTriggers.LOCATION.trigger(actor);
                helper.assertTrue(done(actor, StoryAdvancements.GREYRAT_HOME), "Native loaded location predicate awards an actual structure visit");
                helper.assertFalse(done(actor, StoryAdvancements.FIRST_SPELL) || done(actor, StoryAdvancements.MEET_SYLPHIETTE), "House can be the first milestone independently");
                helper.assertFalse(done(peer, StoryAdvancements.GREYRAT_HOME), "House visit does not grant an observer's progress");
                actor.flush(); completedPacket(helper, actor, StoryAdvancements.GREYRAT_HOME, false);
                actor.updates.clear(); CriteriaTriggers.LOCATION.trigger(actor); actor.flush();
                helper.assertTrue(actor.updates.isEmpty(), "Repeated native location detection does not produce a new completion");
            } finally {
                savedStarts.forEach(LevelChunk::setAllStarts);
                savedReferences.forEach(LevelChunk::setAllReferences);
            }
        }
        helper.succeed();
    }

    private static final Vec3 SHIELD_BASE = new Vec3(8.5, 2, 5.5);

    private static WaterShieldEntity screen(GameTestHelper helper, ServerPlayer owner, Vec3 relativeBase) {
        var shield = new WaterShieldEntity(helper.getLevel(), owner, helper.absoluteVec(relativeBase), 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(shield), "The real shield is admitted to the server level");
        return shield;
    }
    private static <T extends AbstractArrow> T flying(GameTestHelper helper, T arrow, Vec3 relative, Vec3 motion,
            @Nullable Entity shooter) {
        Vec3 position = helper.absoluteVec(relative);
        arrow.setPos(position);
        // Start a genuine new flight segment; never let a constructor's zero coordinates fabricate a crossing.
        arrow.xo = position.x; arrow.yo = position.y; arrow.zo = position.z;
        arrow.setOwner(shooter);
        arrow.setDeltaMovement(motion);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "The projectile is admitted to the real level");
        return arrow;
    }
    private static Arrow shot(GameTestHelper helper, Vec3 relative, Vec3 motion, @Nullable Entity shooter) {
        return flying(helper, new Arrow(helper.getLevel(), 0, 0, 0, new ItemStack(Items.ARROW), null), relative, motion, shooter);
    }
    private static ProjectileImpactEvent impact(GameTestHelper helper, AbstractArrow arrow, Vec3 relativeEnd) {
        Vec3 end = helper.absoluteVec(relativeEnd);
        return NeoForge.EVENT_BUS.post(new ProjectileImpactEvent(arrow,
                new BlockHitResult(end, Direction.SOUTH, BlockPos.containing(end), false)));
    }
    /** Accelerates only this disposable entity's clock to expose the expiration-before-next-tick boundary. */
    @SuppressWarnings("unchecked")
    private static void ageScreen(WaterShieldEntity shield, int age) {
        try {
            Field field = WaterShieldEntity.class.getDeclaredField("START_GAME_TIME");
            field.setAccessible(true);
            var accessor = (EntityDataAccessor<Long>) field.get(null);
            shield.getEntityData().set(accessor, shield.level().getGameTime() - age);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot age test shield", failure); }
    }
    private static void noShieldCompletion(GameTestHelper helper, Actor actor, String reason) {
        helper.assertFalse(done(actor, StoryAdvancements.WATER_SHIELD_BLOCK), reason);
        actor.flush();
        helper.assertTrue(actor.updates.stream().noneMatch(packet -> packet.getProgress().containsKey(StoryAdvancements.WATER_SHIELD_BLOCK)
                && packet.getProgress().get(StoryAdvancements.WATER_SHIELD_BLOCK).isDone()), "No completed shield progress can be sent: " + reason);
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void paidWaterShieldNeedsAnActualBlockAndAwardsOnlyItsCasterOnce(GameTestHelper helper) {
        try (Actor owner = new Actor(helper); Actor shooter = new Actor(helper); Actor observer = new Actor(helper)) {
            owner.connected(); shooter.connected(); observer.connected();
            finishStudy(owner, new Tome(ModContent.WATER_SHIELD_TOME.get(), SpellCatalog.WATER_SHIELD));
            owner.flush(); owner.updates.clear();
            owner.setPos(helper.absoluteVec(new Vec3(8.5, 2, 3.5)));
            owner.setYRot(0); owner.setXRot(0);
            owner.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            owner.setData(ModAttachments.MANA, ManaData.full(100));
            helper.assertTrue(WaterShieldSpell.cast(owner), "The learned spell releases through the real payment path");
            helper.assertValueEqual(WaterShieldSpell.practiceCount(owner), 1, "This really was a paid survival cast");
            noShieldCompletion(helper, owner, "Learning and successful release alone are not blocking an arrow");
            var shield = helper.getLevel().getEntitiesOfClass(WaterShieldEntity.class, helper.getBounds(),
                    candidate -> owner.getUUID().equals(candidate.ownerId())).getFirst();
            Arrow first = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), shooter);
            try {
                shield.tick();
                helper.assertTrue(first.isRemoved(), "The normal shield tick actually absorbs the other player's arrow");
                helper.assertTrue(done(owner, StoryAdvancements.WATER_SHIELD_BLOCK), "The shield's caster receives the blocking milestone");
                owner.flush(); completedPacket(helper, owner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                noShieldCompletion(helper, shooter, "Shooting into another player's screen is not the shooter's achievement");
                noShieldCompletion(helper, observer, "A nearby player cannot inherit another caster's block");
                owner.updates.clear();
                Arrow second = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
                try {
                    shield.tick(); owner.flush();
                    helper.assertTrue(second.isRemoved(), "The screen continues to stop later arrows");
                    helper.assertTrue(owner.updates.isEmpty(), "A repeated block does not send another progress increment");
                    helper.assertValueEqual(WaterShieldSpell.practiceCount(owner), 1, "Blocking does not add practice or replay spell payment");
                } finally { second.discard(); }
            } finally { first.discard(); shield.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void arrowImpactBeforeTheShieldTickAwardsTheProtectingCaster(GameTestHelper helper) {
        try (Actor owner = new Actor(helper); Actor peer = new Actor(helper)) {
            owner.connected(); peer.connected();
            var shield = screen(helper, owner, SHIELD_BASE);
            Husk target = EntityType.HUSK.create(helper.getLevel());
            if (target == null) throw new IllegalStateException("Husk fixture failed");
            target.setPos(helper.absoluteVec(new Vec3(8.5, 2, 5.0)));
            target.setNoAi(true); target.setNoGravity(true);
            helper.getLevel().addFreshEntity(target);
            Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), peer);
            try {
                // Tick only the arrow: its real imminent entity hit dispatches ProjectileImpactEvent before shield.tick.
                arrow.tick();
                helper.assertTrue(arrow.isRemoved(), "The real arrow impact is intercepted before a shield tick");
                helper.assertValueEqual(target.getHealth(), target.getMaxHealth(), "The body behind the screen takes no arrow damage");
                helper.assertValueEqual(shield.tickCount, 0, "The ordinary shield-tick path has not run");
                helper.assertTrue(done(owner, StoryAdvancements.WATER_SHIELD_BLOCK), "The impact safety path grants the same milestone");
                owner.flush(); completedPacket(helper, owner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                noShieldCompletion(helper, peer, "The projectile shooter receives no protection milestone");
            } finally { arrow.discard(); target.discard(); shield.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void selfShotsMissesAndUnsupportedProjectilesNeverGrantShieldProgress(GameTestHelper helper) {
        try (Actor owner = new Actor(helper)) {
            owner.connected();
            var shield = screen(helper, owner, SHIELD_BASE);
            var projectiles = List.of(
                    shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), owner),
                    shot(helper, new Vec3(10.5, 3, 7.4), new Vec3(0, 0, -3), null),
                    shot(helper, new Vec3(8.5, 5, 7.4), new Vec3(0, 0, -3), null),
                    shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -0.2), null),
                    flying(helper, new ThrownTrident(helper.getLevel(), 0, 0, 0, new ItemStack(Items.TRIDENT)),
                            new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null));
            try {
                shield.tick();
                for (AbstractArrow arrow : projectiles) {
                    helper.assertFalse(arrow.isRemoved(), "Self-shot, side/top miss, short flight and trident remain untouched");
                    Vec3 endpoint = arrow.position().add(arrow.getDeltaMovement());
                    Vec3 relative = endpoint.subtract(helper.absoluteVec(Vec3.ZERO));
                    helper.assertFalse(impact(helper, arrow, relative).isCanceled(), "The impact safety path also rejects the ineligible crossing");
                }
                noShieldCompletion(helper, owner, "Only supported, foreign, genuinely crossing arrows count");
            } finally { projectiles.forEach(Entity::discard); shield.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void aLandedArrowUnderTheWaterShieldIsNotABlockingAchievement(GameTestHelper helper) {
        Arrow landed = shot(helper, new Vec3(8.5, 2.6, 5.5), new Vec3(0, -0.6, 0.01), null);
        helper.startSequence().thenExecuteAfter(15, () -> {
            try (Actor owner = new Actor(helper)) {
                owner.connected();
                helper.assertFalse(landed.isRemoved(), "The real arrow has landed rather than vanished");
                helper.assertTrue(landed.tickCount > 1 && landed.position().distanceToSqr(landed.xo, landed.yo, landed.zo) < 1.0E-4,
                        "The fixture is a stationary landed arrow, not a new projectile");
                var shield = screen(helper, owner, SHIELD_BASE);
                try {
                    shield.tick();
                    helper.assertFalse(landed.isRemoved(), "The shield leaves an arrow embedded in the ground");
                    noShieldCompletion(helper, owner, "Standing over an old arrow cannot earn a block");
                } finally { shield.discard(); }
            } finally { landed.discard(); }
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void ordinaryTippedAndSpectralArrowsEachCountWhenActuallyStopped(GameTestHelper helper) {
        for (int variant = 0; variant < 3; variant++) try (Actor owner = new Actor(helper)) {
            owner.connected();
            var shield = screen(helper, owner, SHIELD_BASE);
            AbstractArrow arrow = switch (variant) {
                case 1 -> new Arrow(helper.getLevel(), 0, 0, 0, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON), null);
                case 2 -> new SpectralArrow(helper.getLevel(), 0, 0, 0, new ItemStack(Items.SPECTRAL_ARROW), null);
                default -> new Arrow(helper.getLevel(), 0, 0, 0, new ItemStack(Items.ARROW), null);
            };
            flying(helper, arrow, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
            try {
                shield.tick();
                helper.assertTrue(arrow.isRemoved() && done(owner, StoryAdvancements.WATER_SHIELD_BLOCK),
                        "Supported arrow variant really blocks and counts: " + variant);
                owner.flush(); completedPacket(helper, owner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
            } finally { arrow.discard(); shield.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void twoOverlappingScreensCreditOnlyTheFirstActualBlocker(GameTestHelper helper) {
        try (Actor frontOwner = new Actor(helper); Actor rearOwner = new Actor(helper); Actor shooter = new Actor(helper)) {
            frontOwner.connected(); rearOwner.connected(); shooter.connected();
            var front = screen(helper, frontOwner, SHIELD_BASE);
            var rear = screen(helper, rearOwner, new Vec3(8.5, 2, 3.5));
            Arrow inbound = shot(helper, new Vec3(8.5, 3, 8.5), new Vec3(0, 0, -6), shooter);
            Arrow outbound = null;
            try {
                helper.assertTrue(impact(helper, inbound, new Vec3(8.5, 3, 2.5)).isCanceled(), "The first intercepted impact is canceled");
                helper.assertTrue(inbound.isRemoved() && done(frontOwner, StoryAdvancements.WATER_SHIELD_BLOCK), "The nearest screen's owner receives this block");
                noShieldCompletion(helper, rearOwner, "A second screen on the same trajectory did not absorb this arrow");
                noShieldCompletion(helper, shooter, "The shooter is never mistaken for the protecting owner");
                frontOwner.flush(); completedPacket(helper, frontOwner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                frontOwner.updates.clear();
                // Replaying the same already-consumed projectile cannot hand its block to the screen behind it.
                helper.assertFalse(impact(helper, inbound, new Vec3(8.5, 3, 2.5)).isCanceled(), "A removed arrow is not a second impact");
                noShieldCompletion(helper, rearOwner, "Repeated event delivery cannot transfer attribution");
                outbound = shot(helper, new Vec3(8.5, 3, 1.5), new Vec3(0, 0, 7), shooter);
                helper.assertTrue(impact(helper, outbound, new Vec3(8.5, 3, 8.5)).isCanceled(), "A second arrow arrives from the opposite direction");
                helper.assertTrue(outbound.isRemoved() && done(rearOwner, StoryAdvancements.WATER_SHIELD_BLOCK), "The other caster independently earns their real block");
                rearOwner.flush(); completedPacket(helper, rearOwner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                frontOwner.flush();
                helper.assertTrue(frontOwner.updates.isEmpty(), "The other caster's later block cannot replay the first owner's milestone");
            } finally { inbound.discard(); if (outbound != null) outbound.discard(); front.discard(); rear.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void invalidShieldOwnersCannotEarnProgressDuringAnImpactRace(GameTestHelper helper) {
        ServerLevel original = helper.getLevel();
        ServerLevel elsewhere = original.getServer().getLevel(Level.NETHER);
        helper.assertTrue(elsewhere != null && elsewhere != original, "The fixture has a different real server dimension");
        for (int invalid = 0; invalid < 4; invalid++) try (Actor owner = new Actor(helper)) {
            owner.connected();
            var shield = screen(helper, owner, SHIELD_BASE);
            Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
            try {
                switch (invalid) {
                    case 0 -> owner.setHealth(0);
                    case 1 -> owner.spectator = true;
                    case 2 -> owner.discard();
                    case 3 -> owner.setServerLevel(elsewhere);
                    default -> throw new IllegalStateException();
                }
                // Existing 0.24 impact cleanup may still consume an arrow before the shield's next tick.
                // This milestone must never use a dead, spectator, removed/offline or dimension-changed owner.
                impact(helper, arrow, new Vec3(8.5, 3, 4.4));
                noShieldCompletion(helper, owner, "Invalid owner state cannot earn progression: " + invalid);
            } finally { owner.setServerLevel(original); arrow.discard(); shield.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void ownerlessAndRemovedScreensCannotGrantNearbyPlayersProgress(GameTestHelper helper) {
        try (Actor observer = new Actor(helper)) {
            observer.connected();
            WaterShieldEntity ownerless = ModContent.WATER_SHIELD.get().create(helper.getLevel());
            if (ownerless == null) throw new IllegalStateException("Water shield factory failed");
            ownerless.setPos(helper.absoluteVec(SHIELD_BASE));
            ageScreen(ownerless, 0);
            helper.getLevel().addFreshEntity(ownerless);
            Arrow first = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
            try {
                impact(helper, first, new Vec3(8.5, 3, 4.4));
                noShieldCompletion(helper, observer, "A data-only or orphaned screen has no caster to credit");
            } finally { first.discard(); ownerless.discard(); }
            var removed = screen(helper, observer, SHIELD_BASE);
            removed.discard();
            Arrow second = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
            try {
                impact(helper, second, new Vec3(8.5, 3, 4.4));
                // Also expose an already-queued tick on an entity discarded by replacement/removal.
                removed.tick();
                noShieldCompletion(helper, observer, "A removed screen cannot claim a late queued hit");
            } finally { second.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void shieldLifetimeBoundaryIsCheckedAtTheMomentOfBlocking(GameTestHelper helper) {
        for (int age : List.of(WaterShieldEntity.LIFETIME_TICKS - 1, WaterShieldEntity.LIFETIME_TICKS))
            try (Actor owner = new Actor(helper)) {
                owner.connected();
                var shield = screen(helper, owner, SHIELD_BASE);
                ageScreen(shield, age);
                Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
                try {
                    impact(helper, arrow, new Vec3(8.5, 3, 4.4));
                    if (age < WaterShieldEntity.LIFETIME_TICKS) {
                        helper.assertTrue(arrow.isRemoved() && done(owner, StoryAdvancements.WATER_SHIELD_BLOCK), "The last live tick can still count a real block");
                        owner.flush(); completedPacket(helper, owner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                    } else noShieldCompletion(helper, owner, "An expired screen cannot award before its removal tick runs");
                } finally { arrow.discard(); shield.discard(); }
            }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void creativeCastersCanEarnTheMilestoneButFakePlayersCannot(GameTestHelper helper) {
        try (Actor creative = new Actor(helper)) {
            creative.connected(); creative.getAbilities().instabuild = true;
            var shield = screen(helper, creative, SHIELD_BASE);
            Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
            try {
                shield.tick();
                helper.assertTrue(arrow.isRemoved() && done(creative, StoryAdvancements.WATER_SHIELD_BLOCK), "A creative player's real block is an allowed personal milestone");
                creative.flush(); completedPacket(helper, creative, StoryAdvancements.WATER_SHIELD_BLOCK, false);
            } finally { arrow.discard(); shield.discard(); }
        }
        FakePlayer machine = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ShieldMachine"));
        machine.setPos(helper.absoluteVec(new Vec3(8.5, 2, 3.5)));
        var shield = screen(helper, machine, SHIELD_BASE);
        Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
        try {
            shield.tick();
            helper.assertTrue(arrow.isRemoved(), "Fake-player automation still retains existing shield gameplay");
            helper.assertFalse(machine.getAdvancements().getOrStartProgress(advancement(helper.getLevel(), StoryAdvancements.WATER_SHIELD_BLOCK)).isDone(),
                    "Automation does not earn a human story milestone");
        } finally {
            arrow.discard(); shield.discard(); machine.getAdvancements().stopListening(); machine.discard();
            removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "advancements", machine.getUUID(), machine.getAdvancements());
            removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "stats", machine.getUUID(), machine.getStats());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void oldWaterShieldKnowledgeAndPracticeNeverBackfillTheBlockMilestone(GameTestHelper helper) {
        for (int practices : List.of(0, 1, 80)) try (Actor owner = new Actor(helper)) {
            MagicKnowledge legacy = new MagicKnowledge();
            legacy.learn(WaterShieldSpell.SPELL);
            for (int count = 0; count < practices; count++) legacy.practice(WaterShieldSpell.SPELL);
            owner.setData(ModAttachments.KNOWLEDGE, legacy);
            owner.login(); owner.flush();
            helper.assertTrue(done(owner, StoryAdvancements.ROOT) && done(owner, StoryAdvancements.FIRST_SPELL), "Old shield knowledge preserves the existing silent learning backfill");
            completedPacket(helper, owner, StoryAdvancements.FIRST_SPELL, true);
            noShieldCompletion(helper, owner, "Old cast counts cannot establish that any arrow was actually blocked");
            helper.assertValueEqual(WaterShieldSpell.practiceCount(owner), practices, "Migration preserves the old exact practice count");
            owner.updates.clear(); owner.login(); owner.flush();
            helper.assertTrue(owner.updates.isEmpty(), "Repeated login remains silent and cannot backfill a shield hit");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void actualShieldCompletionPersistsByUuidWithoutAToastOnReload(GameTestHelper helper) throws IOException {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "The save fixture is restricted to the disposable test server");
        GameProfile profile = new GameProfile(UUID.randomUUID(), "ShieldSave");
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).toAbsolutePath().normalize();
        Path path = directory.resolve(profile.getId() + ".json").normalize();
        helper.assertTrue(path.getParent().equals(directory) && !Files.exists(path), "This fixture owns only its fresh UUID advancement file");
        try {
            try (Actor owner = new Actor(helper, profile)) {
                owner.connected();
                var shield = screen(helper, owner, SHIELD_BASE);
                Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
                try {
                    shield.tick();
                    helper.assertTrue(arrow.isRemoved() && done(owner, StoryAdvancements.WATER_SHIELD_BLOCK), "Native save follows an actual successful block");
                    owner.flush(); completedPacket(helper, owner, StoryAdvancements.WATER_SHIELD_BLOCK, false);
                    owner.getAdvancements().save();
                    helper.assertTrue(Files.isRegularFile(path) && Files.size(path) > 0, "The vanilla advancement store wrote the completion to disk");
                } finally { arrow.discard(); shield.discard(); }
            }
            try (Actor restored = new Actor(helper, profile); Actor other = new Actor(helper)) {
                helper.assertTrue(done(restored, StoryAdvancements.WATER_SHIELD_BLOCK), "The same UUID reloads the actual completed criterion");
                helper.assertFalse(restored.hasData(ModAttachments.KNOWLEDGE), "Completion came from native advancement JSON without knowledge backfill");
                restored.login(); restored.flush();
                completedPacket(helper, restored, StoryAdvancements.WATER_SHIELD_BLOCK, true);
                noShieldCompletion(helper, other, "A different UUID never inherits this stored block");
                restored.updates.clear();
                var shield = screen(helper, restored, SHIELD_BASE);
                Arrow arrow = shot(helper, new Vec3(8.5, 3, 7.4), new Vec3(0, 0, -3), null);
                try {
                    shield.tick(); restored.flush();
                    helper.assertTrue(arrow.isRemoved() && restored.updates.isEmpty(), "Blocking again after reconnect cannot send a fresh completion toast");
                } finally { arrow.discard(); shield.discard(); }
            }
        } finally { Files.deleteIfExists(path); }
        helper.succeed();
    }

    private StoryAdvancementGameTests() { }
}
