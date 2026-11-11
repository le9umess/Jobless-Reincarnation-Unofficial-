package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Isolated B01 server contracts. Positive practice uses real ServerPlayer.attack or the native attack
 * packet, never recordHit. doTick advances vanilla recovery without changing the server's global clock;
 * asynchronous gaps exercise the real global hit budget. This is not client input or artwork approval.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SwordLessonGameTests {
    private static final String BATCH = "swordLessonB01";
    private static final String LESSON = "message.jobless_reincarnation.sword_lesson.";
    private static final Vec3 ACTOR = new Vec3(5.5, 2, 2.5);
    private static final Vec3 DUMMY = new Vec3(5.5, 2, 4.5);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }

    /** Real native advancements and packet serialization, with a fresh test-only UUID. */
    static class Actor extends ServerPlayer implements AutoCloseable {
        final List<dev.joblessreincarnation.magic.SpellBurstOptions> spellBursts = new ArrayList<>();
        final List<ClientboundSystemChatPacket> messages = new ArrayList<>();
        int flowOpeningCues;
        int forcedFlowCues;
        int lightCutCues;
        int lastFlowCaster = -1;
        record Dust(int kind, int subject, boolean forced) {}
        final List<Dust> dustCues = new ArrayList<>();
        final List<ClientboundUpdateAdvancementsPacket> updates = new ArrayList<>();
        final Map<ResourceLocation, AdvancementHolder> definitions = new HashMap<>();
        @Nullable Inventory refusingInventory;
        int permissionLevel;
        boolean closed;

        Actor(GameTestHelper helper, GameProfile profile) {
            super(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
            setPos(helper.absoluteVec(ACTOR));
            setNoGravity(true);
            setGameMode(GameType.SURVIVAL);
        }

        private void capture(Packet<?> packet) {
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket cue
                    && cue.getParticle() instanceof dev.joblessreincarnation.magic.SpellBurstOptions burst)spellBursts.add(burst);
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particles
                    && particles.getParticle() instanceof dev.joblessreincarnation.magic.SpellBurstOptions cue
                    && cue.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.FLOW_OPENING) {
                flowOpeningCues++;
                lastFlowCaster = cue.caster();
                if (particles.isOverrideLimiter()) forcedFlowCues++;
            }
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particles
                    && particles.getParticle() instanceof dev.joblessreincarnation.magic.SpellBurstOptions cue
                    && cue.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_CUT) lightCutCues++;
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particles
                    && particles.getParticle() instanceof dev.joblessreincarnation.magic.SpellBurstOptions cue
                    && (cue.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.TEAR_ASH
                    || cue.kind() == dev.joblessreincarnation.magic.SpellBurstOptions.TEAR_ASH_GUARDED)) {
                dustCues.add(new Dust(cue.kind(), cue.caster(), particles.isOverrideLimiter()));
            }

            if (packet instanceof ClientboundSystemChatPacket chat) messages.add(chat);
            if (!(packet instanceof ClientboundUpdateAdvancementsPacket update)) return;
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try {
                ClientboundUpdateAdvancementsPacket.STREAM_CODEC.encode(buffer, update);
                var decoded = ClientboundUpdateAdvancementsPacket.STREAM_CODEC.decode(buffer);
                if (decoded.shouldReset()) definitions.clear();
                decoded.getRemoved().forEach(definitions::remove);
                decoded.getAdded().forEach(holder -> definitions.put(holder.id(), holder));
                decoded.getProgress().forEach((key, progress) -> {
                    var definition = definitions.get(key);
                    if (definition == null) throw new IllegalStateException("Progress without native definition: " + key);
                    progress.update(definition.value().requirements());
                });
                updates.add(decoded);
            } finally { buffer.release(); }
        }

        @Override protected int getPermissionLevel() { return permissionLevel; }
        @Override public Inventory getInventory() {
            return refusingInventory == null ? super.getInventory() : refusingInventory;
        }
        void connect() {
            serverLevel().addNewPlayer(this);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(this));
            flush();
            updates.clear();
            messages.clear();
        }
        void flush() { getAdvancements().flushDirty(this); }
        void recover() {
            // Connection.tick normally calls doTick; these isolated connections are not on PlayerList.
            for (int tick = 0; tick < 20; tick++) doTick();
            if (getAttackStrengthScale(0.5F) < 1.0F) throw new IllegalStateException("Vanilla recovery fixture did not recover");
        }
        void sword() { setItemInHand(InteractionHand.MAIN_HAND, ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance()); }
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

    private static Object playerListField(PlayerList list, String name) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(list);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("PlayerList fixture field changed: " + name, error); }
    }
    private static void removeOwnMapping(PlayerList list, String name, UUID uuid, Object expected) {
        if (!(playerListField(list, name) instanceof Map<?, ?> values)) throw new IllegalStateException("Expected native cache: " + name);
        if (values.get(uuid) == expected) values.remove(uuid);
    }

    static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final List<Actor> actors = new ArrayList<>();
        final List<Entity> entities = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final List<Path> files = new ArrayList<>();
        boolean closed;
        Fixture(GameTestHelper helper) { this.helper = helper; }
        Actor actor() { return actor(new GameProfile(UUID.randomUUID(), "Sword" + UUID.randomUUID().toString().substring(0, 8))); }
        Actor actor(GameProfile profile) {
            Actor player = new Actor(helper, profile);
            actors.add(player);
            player.connect();
            return player;
        }
        void block(BlockPos relative, BlockState state) {
            BlockPos absolute = helper.absolutePos(relative);
            blocks.putIfAbsent(absolute, helper.getLevel().getBlockState(absolute));
            helper.getLevel().setBlockAndUpdate(absolute, state);
        }
        <T extends Entity> T admit(T entity, Vec3 relative) {
            entity.setPos(helper.absoluteVec(relative));
            entity.setNoGravity(true);
            entities.add(entity);
            helper.assertTrue(helper.getLevel().addFreshEntity(entity), "The fixture entity must enter the real level");
            return entity;
        }
        PracticeDummyEntity dummy(@Nullable Actor owner, Vec3 relative) {
            block(BlockPos.containing(relative.x, relative.y - 1, relative.z), Blocks.STONE.defaultBlockState());
            PracticeDummyEntity dummy = ModContent.PRACTICE_DUMMY.get().create(helper.getLevel());
            if (dummy == null) throw new IllegalStateException("Practice dummy factory missing");
            dummy.setOwnerId(owner == null ? null : owner.getUUID());
            admit(dummy, relative);
            helper.assertTrue(dummy.hasSupport(), "The admitted fixture has loaded dry solid support");
            return dummy;
        }
        FamilyResidentEntity paul(Vec3 relative) {
            FamilyResidentEntity paul = ModContent.PAUL_GREYRAT.get().create(helper.getLevel());
            if (paul == null) throw new IllegalStateException("Paul factory missing");
            paul.setNoAi(true);
            return admit(paul, relative);
        }
        void checked(Runnable action) {
            if (closed) return;
            try { action.run(); }
            catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        Path freshFile(LevelResource directory, String name) {
            helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Disk fixture is restricted to disposable GameTestServer");
            Path parent = helper.getLevel().getServer().getWorldPath(directory).toAbsolutePath().normalize();
            Path path = parent.resolve(name).normalize();
            helper.assertTrue(path.getParent().equals(parent) && !Files.exists(path), "The test owns only a fresh file inside the test world");
            files.add(path);
            return path;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            entities.forEach(Entity::discard);
            actors.forEach(Actor::close);
            blocks.forEach((position, state) -> helper.getLevel().setBlockAndUpdate(position, state));
            for (Path file : files) try { Files.deleteIfExists(file); }
            catch (IOException error) { throw new IllegalStateException("Could not remove owned test file " + file, error); }
        }
    }

    private static SwordTrainingProgress progress(ServerPlayer actor) { return actor.getData(ModAttachments.SWORD_TRAINING); }
    private static AdvancementHolder lesson(ServerLevel level) {
        var result = level.getServer().getAdvancements().get(StoryAdvancements.FIRST_SWORD_LESSON);
        if (result == null) throw new IllegalStateException("Missing native sword lesson advancement");
        return result;
    }
    private static boolean done(Actor actor) { return actor.getAdvancements().getOrStartProgress(lesson(actor.serverLevel())).isDone(); }
    private static long completionPackets(Actor actor) {
        return actor.updates.stream().filter(packet -> {
            var result = packet.getProgress().get(StoryAdvancements.FIRST_SWORD_LESSON);
            return result != null && result.isDone();
        }).count();
    }
    private static boolean hasKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents text) {
            if (text.getKey().equals(key)) return true;
            for (Object argument : text.getArgs()) if (argument instanceof Component child && hasKey(child, key)) return true;
        }
        return component.getSiblings().stream().anyMatch(child -> hasKey(child, key));
    }
    private static boolean heard(Actor actor, String key) { return actor.messages.stream().anyMatch(packet -> hasKey(packet.content(), key)); }
    private static void interact(Actor actor, Entity target, InteractionHand hand, boolean specific, boolean crouching) {
        actor.connection.handleInteract(specific
                ? ServerboundInteractPacket.createInteractionPacket(target, crouching, hand, new Vec3(0, 1, 0))
                : ServerboundInteractPacket.createInteractionPacket(target, crouching, hand));
    }
    private static void attackPacket(Actor actor, Entity target) {
        actor.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
    }
    private static void start(GameTestHelper helper, Actor actor, FamilyResidentEntity paul) {
        actor.sword();
        interact(actor, paul, InteractionHand.MAIN_HAND, true, false);
        helper.assertTrue(progress(actor).started() && !progress(actor).recognized(), "A real registered Paul's native interaction starts only the course");
        helper.assertTrue(heard(actor, LESSON + "dialogue.start"), "The actor receives the localized course-start branch");
        helper.assertTrue(paul.conversationPlayer() == actor, "The course goes through the established conversation gate");
    }
    private static List<ItemEntity> drops(ServerLevel level, PracticeDummyEntity dummy) {
        return level.getEntitiesOfClass(ItemEntity.class, dummy.getBoundingBox().inflate(2),
                item -> item.getItem().is(ModContent.PRACTICE_DUMMY_ITEM.get()) && !item.isRemoved());
    }
    private static int inventoryDummies(Actor player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModContent.PRACTICE_DUMMY_ITEM.get())) count += stack.getCount();
        }
        return count;
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 100)
    public static void nativeAttackRecoveryWeakStrikesGlobalIntervalAndTwoPlayers(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            Actor actor = f.actor(), peer = f.actor();
            peer.setPos(helper.absoluteVec(new Vec3(7.5, 2, 2.5)));
            FamilyResidentEntity paul = f.paul(new Vec3(5.5, 2, 1));
            start(helper, actor, paul);
            start(helper, peer, paul);
            PracticeDummyEntity first = f.dummy(actor, DUMMY);
            PracticeDummyEntity second = f.dummy(actor, new Vec3(7.5, 2, 4.5));
            Cow bystander = EntityType.COW.create(helper.getLevel());
            if (bystander == null) throw new IllegalStateException("Cow fixture failed");
            bystander.setNoAi(true);
            f.admit(bystander, new Vec3(6.5, 2, 4.5));
            float health = bystander.getHealth();
            actor.recover();
            helper.assertFalse(actor.isFakePlayer(), "Positive progression uses a real ServerPlayer");
            actor.attack(first);
            helper.assertValueEqual(progress(actor).hits(), 1, "Player.attack reaches hurt before vanilla resets recovery");
            helper.assertTrue(actor.getAttackStrengthScale(0.5F) < 0.1F, "A completed attack really resets vanilla recovery");
            attackPacket(actor, first);
            helper.assertValueEqual(progress(actor).hits(), 1, "An immediate weak native attack cannot count twice");
            actor.recover();
            actor.attack(second);
            helper.assertValueEqual(progress(actor).hits(), 1, "Recovery alone cannot bypass the same-tick budget by changing dummies");
            actor.recover();
            first.hurt(actor.damageSources().playerAttack(actor), 4);
            helper.assertValueEqual(progress(actor).hits(), 1, "Repeated delivery of a full direct hurt cannot reuse the interval");
            peer.recover();
            attackPacket(peer, first);
            helper.assertValueEqual(progress(peer).hits(), 1, "A second human can use another owner's dummy in the same tick");
            helper.assertValueEqual(progress(actor).hits(), 1, "The second player's hit cannot affect the first attachment");
            helper.assertValueEqual(actor.getMainHandItem().getDamageValue(), 0, "A nonliving dummy never spends sword durability");
            helper.assertValueEqual(bystander.getHealth(), health, "Practice never sweeps the living bystander");
            helper.assertFalse(actor.getMainHandItem().canPerformAction(ItemAbilities.SWORD_SWEEP), "The item explicitly disables SWORD_SWEEP");
            helper.startSequence().thenExecuteAfter(9, () -> f.checked(() -> {
                actor.recover();
                actor.attack(second);
                helper.assertValueEqual(progress(actor).hits(), 1, "Nine server ticks are still inside the cross-dummy interval");
            })).thenExecuteAfter(1, () -> f.checked(() -> {
                actor.recover();
                actor.attack(second);
                helper.assertValueEqual(progress(actor).hits(), 2, "Exactly ten server ticks release the player's global interval");
            })).thenExecuteAfter(14, () -> f.checked(() -> {
                actor.resetAttackStrengthTicker();
                for (int tick = 0; tick < 11; tick++) actor.doTick();
                float strength = actor.getAttackStrengthScale(0.5F);
                helper.assertTrue(strength > 0.9F && strength < 1.0F, "This real vanilla wooden-sword swing is strong but not fully recovered");
                actor.attack(first);
                helper.assertValueEqual(progress(actor).hits(), 2, "The greater-than-0.9 shortcut must not count");
                helper.assertTrue(actor.getAttackStrengthScale(0.5F) < 0.1F, "Even an ineligible weak hit resets recovery normally");
            })).thenExecuteAfter(14, () -> f.checked(() -> {
                actor.recover();
                attackPacket(actor, first);
                helper.assertValueEqual(progress(actor).hits(), 3, "After real server time and recovery, the native attack packet counts");
                helper.assertValueEqual(bystander.getHealth(), health, "All later practice leaves the bystander untouched");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void wrongWeaponsSourcesModesReachAndOcclusionCannotTrain(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Actor actor = f.actor();
            PracticeDummyEntity dummy = f.dummy(actor, DUMMY);
            actor.sword(); actor.recover(); actor.attack(dummy);
            helper.assertFalse(actor.hasData(ModAttachments.SWORD_TRAINING), "An unstarted course does not materialize progress on hit");
            start(helper, actor, f.paul(new Vec3(5.5, 2, 1)));
            actor.setItemInHand(InteractionHand.MAIN_HAND, Items.WOODEN_SWORD.getDefaultInstance());
            actor.recover(); actor.attack(dummy);
            helper.assertValueEqual(progress(actor).hits(), 0, "Vanilla wooden sword is not the lesson item");
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.setItemInHand(InteractionHand.OFF_HAND, ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance());
            actor.recover(); actor.attack(dummy);
            helper.assertValueEqual(progress(actor).hits(), 0, "An offhand practice sword cannot turn an empty-hand attack into practice");
            actor.sword(); actor.recover();
            Arrow arrow = new Arrow(helper.getLevel(), actor.getX(), actor.getEyeY(), actor.getZ(), Items.ARROW.getDefaultInstance(), null);
            arrow.setOwner(actor);
            f.entities.add(arrow);
            dummy.hurt(actor.damageSources().arrow(arrow, actor), 4);
            dummy.hurt(new DamageSource(helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DamageTypes.PLAYER_ATTACK), arrow, actor), 4);
            dummy.hurt(actor.damageSources().magic(), 4);
            dummy.hurt(actor.damageSources().generic(), 4);
            helper.assertValueEqual(progress(actor).hits(), 0, "Projectile, forged indirect PLAYER_ATTACK, magic and environmental sources are rejected");
            helper.assertFalse(dummy.canBeHitByProjectile(), "The dummy opts out of normal projectile collision");
            actor.setPos(dummy.position().add(0, 0, -4));
            helper.assertFalse(actor.canInteractWithEntity(dummy, 0), "The reach fixture exceeds the player's native unpadded reach");
            helper.assertTrue(actor.canInteractWithEntity(dummy, 1), "It remains within the vanilla packet tolerance, exposing the stricter hurt gate");
            actor.recover(); attackPacket(actor, dummy);
            helper.assertValueEqual(progress(actor).hits(), 0, "Packet reach tolerance cannot become extra lesson reach");
            actor.setPos(helper.absoluteVec(ACTOR));
            f.block(new BlockPos(5, 2, 3), Blocks.STONE.defaultBlockState());
            f.block(new BlockPos(5, 3, 3), Blocks.STONE.defaultBlockState());
            helper.assertFalse(actor.hasLineOfSight(dummy), "The fixture really blocks the eye ray");
            actor.recover(); attackPacket(actor, dummy);
            helper.assertValueEqual(progress(actor).hits(), 0, "Occluded native attack packets cannot train");
            f.block(new BlockPos(5, 2, 3), Blocks.AIR.defaultBlockState());
            f.block(new BlockPos(5, 3, 3), Blocks.AIR.defaultBlockState());
            actor.setGameMode(GameType.CREATIVE); actor.recover(); actor.attack(dummy);
            helper.assertValueEqual(progress(actor).hits(), 0, "Creative strikes are demonstrations only");
            actor.setGameMode(GameType.SPECTATOR); dummy.hurt(actor.damageSources().playerAttack(actor), 4);
            helper.assertValueEqual(progress(actor).hits(), 0, "Spectator damage injection cannot train");
            actor.setGameMode(GameType.SURVIVAL); actor.setHealth(0);
            dummy.hurt(actor.damageSources().playerAttack(actor), 4);
            helper.assertValueEqual(progress(actor).hits(), 0, "A dead player cannot credit a queued damage callback");
            actor.setHealth(actor.getMaxHealth());
            FakePlayer machine = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SwordMachine")) {
                @Override public float getAttackStrengthScale(float partialTick) { return 1.0F; }
            };
            try {
                machine.setPos(helper.absoluteVec(ACTOR));
                machine.setItemInHand(InteractionHand.MAIN_HAND, ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance());
                machine.getAbilities().instabuild = false;
                helper.getLevel().addNewPlayer(machine);
                progress(machine).start();
                dummy.hurt(machine.damageSources().playerAttack(machine), 4);
                helper.assertValueEqual(progress(machine).hits(), 0, "Even a registered, recovered fake-player fixture cannot train");
            } finally {
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(machine));
                machine.getAdvancements().stopListening(); machine.discard();
                removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "advancements", machine.getUUID(), machine.getAdvancements());
                removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "stats", machine.getUUID(), machine.getStats());
            }
            actor.setGameMode(GameType.ADVENTURE); actor.recover(); attackPacket(actor, dummy);
            helper.assertValueEqual(progress(actor).hits(), 1, "Adventure can genuinely practice after all rejected cases");
            helper.assertFalse(done(actor), "One legitimate hit cannot grant the lesson milestone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 80)
    public static void paulQueriesKeepExistingChatAndValidateNativeInteractions(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            Actor actor = f.actor(), peer = f.actor();
            peer.setPos(helper.absoluteVec(new Vec3(7.5, 2, 2.5)));
            FamilyResidentEntity paul = f.paul(new Vec3(5.5, 2, 4.5));
            actor.sword();
            interact(actor, paul, InteractionHand.OFF_HAND, true, false);
            helper.assertFalse(actor.hasData(ModAttachments.SWORD_TRAINING), "Offhand query never starts the course");
            actor.setPos(paul.position().add(0, 0, -4));
            interact(actor, paul, InteractionHand.MAIN_HAND, false, false);
            helper.assertFalse(actor.hasData(ModAttachments.SWORD_TRAINING), "Paul applies unpadded reach as well");
            actor.setPos(helper.absoluteVec(ACTOR));
            f.block(new BlockPos(5, 3, 3), Blocks.STONE.defaultBlockState());
            f.block(new BlockPos(5, 2, 3), Blocks.STONE.defaultBlockState());
            interact(actor, paul, InteractionHand.MAIN_HAND, false, false);
            helper.assertFalse(actor.hasData(ModAttachments.SWORD_TRAINING), "An obstructed request cannot start the lesson");
            f.block(new BlockPos(5, 3, 3), Blocks.AIR.defaultBlockState());
            f.block(new BlockPos(5, 2, 3), Blocks.AIR.defaultBlockState());
            actor.setItemInHand(InteractionHand.MAIN_HAND, Items.WOODEN_SWORD.getDefaultInstance());
            interact(actor, paul, InteractionHand.MAIN_HAND, false, false);
            helper.assertTrue(heard(actor, "message.jobless_reincarnation.family.paul_greyrat.0"), "Ordinary wooden sword retains Paul's existing chat branch");
            helper.assertFalse(actor.hasData(ModAttachments.SWORD_TRAINING), "Ordinary conversation cannot start training");
            helper.assertFalse(heard(peer, "message.jobless_reincarnation.family.paul_greyrat.0"), "The existing reply remains private");
            helper.startSequence().thenExecuteAfter(21, () -> f.checked(() -> {
                start(helper, actor, paul);
                int replies = actor.messages.size();
                interact(actor, paul, InteractionHand.MAIN_HAND, false, false);
                helper.assertValueEqual(actor.messages.size(), replies, "The second native interaction branch is throttled without another reply");
                helper.assertFalse(peer.hasData(ModAttachments.SWORD_TRAINING), "Another registered player gains no course state");
                actor.setGameMode(GameType.CREATIVE);
            })).thenExecuteAfter(21, () -> f.checked(() -> {
                interact(actor, paul, InteractionHand.MAIN_HAND, false, false);
                helper.assertTrue(progress(actor).started() && progress(actor).hits() == 0 && !done(actor), "Creative may consult but cannot recognize a zero-hit course");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 450)
    public static void twentyRealHitsNeedPaulAndPersistByUuidWithOnePersonalCompletion(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            Actor actor = f.actor(), peer = f.actor();
            peer.setPos(helper.absoluteVec(new Vec3(8.5, 2, 2.5)));
            FamilyResidentEntity firstPaul = f.paul(new Vec3(5.5, 2, 1));
            FamilyResidentEntity secondPaul = f.paul(new Vec3(7.5, 2, 1));
            PracticeDummyEntity dummy = f.dummy(peer, DUMMY);
            start(helper, actor, firstPaul);
            var sequence = helper.startSequence();
            for (int hit = 1; hit <= SwordTrainingProgress.GOAL; hit++) {
                final int expected = hit;
                sequence.thenExecuteAfter(14, () -> f.checked(() -> {
                    actor.recover();
                    if ((expected & 1) == 0) attackPacket(actor, dummy); else actor.attack(dummy);
                    helper.assertValueEqual(progress(actor).hits(), expected, "Each eligible real strike adds exactly one personal hit");
                    helper.assertFalse(progress(actor).recognized() || done(actor), "No hit, including number twenty, recognizes the course");
                    if (expected == 1) {
                        interact(actor, secondPaul, InteractionHand.MAIN_HAND, true, false);
                        helper.assertValueEqual(progress(actor).hits(), 1, "A second Paul reads the same course and cannot reset earned hits");
                        helper.assertTrue(heard(actor, LESSON + "dialogue.practicing"), "The second Paul gives progress rather than another start");
                    }
                }));
            }
            sequence.thenExecute(() -> f.checked(() -> {
                helper.assertValueEqual(progress(actor).phase(), SwordTrainingProgress.Phase.READY, "Twenty hits stop at awaiting recognition");
                actor.recover(); actor.attack(dummy);
                helper.assertValueEqual(progress(actor).hits(), 20, "Extra attacks cannot exceed the fixed goal");
                actor.flush(); peer.flush();
                helper.assertValueEqual(completionPackets(actor), 0L, "Finishing practice sends no milestone completion");
                interact(actor, firstPaul, InteractionHand.MAIN_HAND, false, false);
                helper.assertTrue(progress(actor).recognized() && done(actor), "Returning to Paul performs the native recognition award");
                actor.flush(); peer.flush();
                helper.assertValueEqual(completionPackets(actor), 1L, "Only the recognized player receives one serialized completion");
                helper.assertFalse(done(peer), "Using the peer's dummy never awards its owner");
                helper.assertValueEqual(completionPackets(peer), 0L, "The other player receives no lesson toast packet");
                helper.assertFalse(heard(peer, LESSON + "speaker")
                        || heard(peer, "message.jobless_reincarnation.sword_practice.counted"), "Course and count feedback stay private to the actual student");
                actor.updates.clear();
                interact(actor, secondPaul, InteractionHand.MAIN_HAND, true, false);
                actor.flush();
                helper.assertValueEqual(completionPackets(actor), 0L, "A second Paul cannot grant a second completion");
                helper.assertTrue(heard(actor, LESSON + "dialogue.done"), "Already recognized state uses the done branch");
                helper.assertValueEqual(actor.getMainHandItem().getDamageValue(), 0, "Twenty real practice attacks do not consume durability");
            })).thenExecuteAfter(21, () -> f.checked(() -> {
                interact(actor, firstPaul, InteractionHand.MAIN_HAND, true, false);
                actor.flush();
                helper.assertValueEqual(completionPackets(actor), 0L, "A later accepted conversation also cannot repeat the completion");
                persistCompletedCourse(helper, f, actor, peer);
                f.close();
            })).thenSucceed();
        });
    }

    /** Real PlayerDataStorage and PlayerAdvancements files, followed by fresh objects with the same UUID. */
    private static void persistCompletedCourse(GameTestHelper helper, Fixture f, Actor actor, Actor peer) {
        GameProfile profile = actor.getGameProfile();
        Path playerFile = f.freshFile(LevelResource.PLAYER_DATA_DIR, profile.getId() + ".dat");
        f.freshFile(LevelResource.PLAYER_DATA_DIR, profile.getId() + ".dat_old");
        Path advancementFile = f.freshFile(LevelResource.PLAYER_ADVANCEMENTS_DIR, profile.getId() + ".json");
        PlayerDataStorage storage = (PlayerDataStorage) playerListField(helper.getLevel().getServer().getPlayerList(), "playerIo");
        storage.save(actor);
        actor.getAdvancements().save();
        helper.assertTrue(Files.isRegularFile(playerFile) && Files.isRegularFile(advancementFile), "Both native stores actually wrote this UUID to disk");
        actor.close();
        Actor restored = new Actor(helper, profile);
        f.actors.add(restored);
        helper.assertTrue(storage.load(restored).isPresent(), "PlayerDataStorage really reads the saved player file");
        helper.assertValueEqual(progress(restored).hits(), 20, "The attachment reloads all legitimately earned hits");
        helper.assertTrue(progress(restored).recognized() && done(restored), "Both course facts and the native criterion reload for the same UUID");
        helper.assertFalse(peer.hasData(ModAttachments.SWORD_TRAINING) || done(peer), "The other UUID remains independent of both files");
        helper.getLevel().addNewPlayer(restored);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(restored));
        restored.flush();
        helper.assertValueEqual(completionPackets(restored), 1L, "Reconnect receives the existing native completed state");
        helper.assertTrue(restored.updates.stream().filter(packet -> packet.getProgress().containsKey(StoryAdvancements.FIRST_SWORD_LESSON))
                .allMatch(ClientboundUpdateAdvancementsPacket::shouldReset), "Reload is a reset snapshot, never a new incremental toast");
        restored.updates.clear();
        FamilyResidentEntity paul = f.paul(new Vec3(5.5, 2, 1));
        interact(restored, paul, InteractionHand.MAIN_HAND, false, false);
        restored.flush();
        helper.assertValueEqual(completionPackets(restored), 0L, "Consulting after disk reload cannot re-award the milestone");
    }

    private static InteractionResult place(Actor actor, BlockPos support, Direction face) {
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), face, support, false);
        return actor.getMainHandItem().useOn(new UseOnContext(actor, InteractionHand.MAIN_HAND, hit));
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void frameworkDeathAndReturnCloneCopyRealPracticeWithoutSharingOrBackfill(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Actor original = f.actor();
            start(helper, original, f.paul(new Vec3(5.5, 2, 1)));
            PracticeDummyEntity dummy = f.dummy(original, DUMMY);
            original.recover(); original.attack(dummy);
            helper.assertValueEqual(progress(original).hits(), 1, "The clone source contains an actual eligible practice hit");
            for (boolean death : List.of(true, false)) {
                Actor clone = f.actor();
                // Fresh UUIDs isolate this test's native advancement caches. The actual framework
                // Clone event and attachment copy handlers, not a manual Codec copy, do the copying.
                NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(clone, original, death));
                helper.assertTrue(progress(clone).started() && progress(clone).hits() == 1 && !progress(clone).recognized(),
                        "Framework clone preserves the source's course facts for death=" + death);
                helper.assertTrue(progress(clone) != progress(original), "The clone receives a distinct mutable attachment");
                helper.assertFalse(done(clone), "Copying unfinished course facts never backfills recognition");
                clone.sword(); clone.recover(); clone.attack(dummy);
                helper.assertValueEqual(progress(clone).hits(), 2, "The clone's own actual hit advances its copied attachment");
                helper.assertValueEqual(progress(original).hits(), 1, "The clone's hit cannot mutate its source attachment");
                clone.close();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void placementChecksPermissionsSupportOverlapAndAdmissionBeforeCharging(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Actor actor = f.actor();
            BlockPos supportRelative = new BlockPos(5, 1, 4);
            BlockPos support = helper.absolutePos(supportRelative);
            f.block(supportRelative, Blocks.STONE.defaultBlockState());
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PRACTICE_DUMMY_ITEM.get(), 2));
            ItemStack supply = actor.getMainHandItem();
            helper.assertValueEqual(supply.getMaxStackSize(), 16, "The placement item has the approved stack limit");
            actor.getAbilities().mayBuild = false;
            helper.assertFalse(place(actor, support, Direction.UP).consumesAction(), "No native build permission rejects placement before item use");
            actor.getAbilities().mayBuild = true;
            actor.setGameMode(GameType.ADVENTURE);
            helper.assertFalse(place(actor, support, Direction.UP).consumesAction(), "Adventure consults/trains but cannot place");
            actor.setGameMode(GameType.SURVIVAL);
            helper.assertValueEqual(place(actor, support, Direction.NORTH), InteractionResult.FAIL, "A side face is not a dry solid top placement");
            f.block(supportRelative, Blocks.AIR.defaultBlockState());
            helper.assertValueEqual(place(actor, support, Direction.UP), InteractionResult.FAIL, "Missing support fails without payment");
            f.block(supportRelative, Blocks.STONE.defaultBlockState());
            f.block(supportRelative.above(), Blocks.WATER.defaultBlockState());
            helper.assertValueEqual(place(actor, support, Direction.UP), InteractionResult.FAIL, "Submerged space fails without payment");
            f.block(supportRelative.above(), Blocks.STONE.defaultBlockState());
            helper.assertValueEqual(place(actor, support, Direction.UP), InteractionResult.FAIL, "A real block collision fails without payment");
            f.block(supportRelative.above(), Blocks.AIR.defaultBlockState());
            PracticeDummyEntity occupied = f.dummy(actor, DUMMY);
            helper.assertValueEqual(place(actor, support, Direction.UP), InteractionResult.FAIL, "A registered overlapping entity fails without payment");
            occupied.discard();
            int[] canceled = {0};
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getLevel() == helper.getLevel() && event.getEntity() instanceof PracticeDummyEntity candidate
                        && actor.getUUID().equals(candidate.ownerId())) { canceled[0]++; event.setCanceled(true); }
            };
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, veto);
            try { helper.assertValueEqual(place(actor, support, Direction.UP), InteractionResult.FAIL, "A native admission veto rejects the transaction"); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertValueEqual(canceled[0], 1, "The failure injection actually reached EntityJoinLevelEvent");
            helper.assertValueEqual(supply.getCount(), 2, "All permission, geometry and admission failures preserve both items");
            helper.assertTrue(place(actor, support, Direction.UP).consumesAction(), "A valid UP use admits a placed dummy");
            helper.assertValueEqual(supply.getCount(), 1, "Only successful world admission consumes one item");
            var placed = helper.getLevel().getEntitiesOfClass(PracticeDummyEntity.class,
                    ModContent.PRACTICE_DUMMY.get().getDimensions().makeBoundingBox(helper.absoluteVec(DUMMY)));
            helper.assertValueEqual(placed.size(), 1, "Exactly one actual placed entity exists");
            f.entities.addAll(placed);
            helper.assertValueEqual(placed.getFirst().ownerId(), actor.getUUID(), "Placement records the real placing UUID");
            helper.assertTrue(placed.getFirst().hasSupport(), "The placed prop has real loaded solid support");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void reclaimRequiresOwnerCrouchEmptyMainHandAndCommitsExactlyOneItem(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Actor owner = f.actor(), outsider = f.actor();
            outsider.setPos(helper.absoluteVec(new Vec3(7.5, 2, 2.5)));
            PracticeDummyEntity dummy = f.dummy(owner, DUMMY);
            interact(outsider, dummy, InteractionHand.MAIN_HAND, true, true);
            interact(owner, dummy, InteractionHand.MAIN_HAND, false, false);
            interact(owner, dummy, InteractionHand.OFF_HAND, true, true);
            owner.sword();
            interact(owner, dummy, InteractionHand.MAIN_HAND, false, true);
            helper.assertFalse(dummy.isRemoved(), "Foreign owner, standing, offhand and occupied main hand all preserve the dummy");
            helper.assertValueEqual(inventoryDummies(owner) + inventoryDummies(outsider), 0, "Invalid native interactions return no item");
            // A truly full vanilla inventory necessarily occupies the selected main-hand slot.
            // This natural state tests the input gate; it is NOT claimed as an Inventory.add failure.
            for (int slot = 0; slot < owner.getInventory().items.size(); slot++) owner.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
            interact(owner, dummy, InteractionHand.MAIN_HAND, false, true);
            helper.assertFalse(dummy.isRemoved(), "Naturally full inventory with occupied main hand cannot reclaim");
            owner.getInventory().clearContent();
            owner.setGameMode(GameType.ADVENTURE);
            interact(owner, dummy, InteractionHand.MAIN_HAND, false, true);
            helper.assertFalse(dummy.isRemoved(), "Adventure cannot actively reclaim even its own prop");
            owner.setGameMode(GameType.SURVIVAL);
            int[] refused = {0};
            // Explicit delivery-failure injection: real empty main hand, rejecting test Inventory.
            owner.refusingInventory = new Inventory(owner) {
                @Override public boolean add(ItemStack stack) { refused[0]++; return false; }
            };
            try { interact(owner, dummy, InteractionHand.MAIN_HAND, true, true); }
            finally { owner.refusingInventory = null; }
            helper.assertValueEqual(refused[0], 1, "The delivery rejection was exercised, not an earlier input rejection");
            helper.assertFalse(dummy.isRemoved(), "Failed inventory delivery preserves the live prop");
            helper.assertValueEqual(inventoryDummies(owner), 0, "Failed delivery creates no item");
            interact(owner, dummy, InteractionHand.MAIN_HAND, true, true);
            helper.assertTrue(dummy.isRemoved(), "A valid owner reclaim retires the prop");
            helper.assertValueEqual(inventoryDummies(owner), 1, "Successful reclaim delivers exactly one placement item");
            // Replay both already-resolved callbacks as well as packets after world removal.
            ItemStack returned = owner.getMainHandItem();
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            owner.getInventory().setItem(1, returned);
            int before = inventoryDummies(owner);
            dummy.interact(owner, InteractionHand.MAIN_HAND);
            dummy.interactAt(owner, new Vec3(0, 1, 0), InteractionHand.MAIN_HAND);
            interact(owner, dummy, InteractionHand.MAIN_HAND, false, true);
            helper.assertValueEqual(inventoryDummies(owner), before, "Neither callback nor packet replay can return a second item");
            helper.assertTrue(drops(helper.getLevel(), dummy).isEmpty(), "Active reclaim has no ground drop");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void creativeReclaimRequiresPermissionForForeignOrOwnerlessPropsAndNeverDrops(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Actor owner = f.actor(), admin = f.actor();
            admin.setPos(helper.absoluteVec(new Vec3(7.5, 2, 2.5)));
            admin.setGameMode(GameType.CREATIVE);
            PracticeDummyEntity foreign = f.dummy(owner, DUMMY);
            interact(admin, foreign, InteractionHand.MAIN_HAND, false, true);
            helper.assertFalse(foreign.isRemoved(), "Creative without permission level two cannot reclaim another owner's prop");
            admin.permissionLevel = 2;
            interact(admin, foreign, InteractionHand.MAIN_HAND, true, true);
            helper.assertTrue(foreign.isRemoved(), "A creative administrator can reclaim the foreign prop");
            PracticeDummyEntity orphan = f.dummy(null, DUMMY);
            interact(owner, orphan, InteractionHand.MAIN_HAND, false, true);
            helper.assertFalse(orphan.isRemoved(), "Survival cannot claim an ownerless saved prop");
            interact(admin, orphan, InteractionHand.MAIN_HAND, false, true);
            helper.assertTrue(orphan.isRemoved(), "A creative administrator can also clear an ownerless prop");
            helper.assertValueEqual(inventoryDummies(admin), 0, "Creative removal never generates a placement item");
            helper.assertTrue(drops(helper.getLevel(), orphan).isEmpty(), "Creative removal never spawns ground loot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 80)
    public static void supportLossAdmissionVetoPreservesDummyAndRetryProducesAtMostOneDrop(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            Actor owner = f.actor();
            start(helper, owner, f.paul(new Vec3(5.5, 2, 1)));
            PracticeDummyEntity dummy = f.dummy(owner, DUMMY);
            int[] attempts = {0};
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getLevel() == helper.getLevel() && event.getEntity() instanceof ItemEntity item
                        && item.getItem().is(ModContent.PRACTICE_DUMMY_ITEM.get())
                        && item.position().distanceToSqr(dummy.position()) < 0.01) {
                    attempts[0]++;
                    dummy.tick(); // Reentrant support callbacks must not begin another removal transaction.
                    event.setCanceled(true);
                }
            };
            f.block(new BlockPos(5, 1, 4), Blocks.AIR.defaultBlockState());
            owner.recover(); owner.attack(dummy);
            helper.assertValueEqual(progress(owner).hits(), 0, "An unsupported dummy stops accepting practice immediately");
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, veto);
            try { dummy.tick(); dummy.tick(); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertValueEqual(attempts[0], 1, "A canceled admission and reentrant tick attempt only one drop");
            helper.assertFalse(dummy.isRemoved(), "Canceled drop admission preserves the entity instead of losing the item");
            helper.assertTrue(drops(helper.getLevel(), dummy).isEmpty(), "Rejected admission leaves no item entity behind");
            helper.startSequence().thenExecuteAfter(22, () -> f.checked(() -> {
                dummy.tick();
                var returned = drops(helper.getLevel(), dummy);
                f.entities.addAll(returned);
                helper.assertTrue(dummy.isRemoved(), "After the retry interval a successfully admitted drop retires the prop");
                helper.assertValueEqual(returned.size(), 1, "Support loss admits exactly one ItemEntity");
                helper.assertValueEqual(returned.getFirst().getItem().getCount(), 1, "That entity contains exactly one placement item");
                dummy.tick(); dummy.tick();
                helper.assertValueEqual(drops(helper.getLevel(), dummy).size(), 1, "Queued ticks after removal cannot duplicate the drop");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void nativeEntityNbtDiskRoundTripPreservesOwnerPositionAndImmobility(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper)) {
            Actor owner = f.actor();
            PracticeDummyEntity original = f.dummy(owner, DUMMY);
            original.setYRot(137);
            Vec3 position = original.position();
            UUID uuid = original.getUUID();
            CompoundTag saved = new CompoundTag();
            helper.assertTrue(original.save(saved), "The registered entity supports vanilla type-bearing save");
            Path path = f.freshFile(LevelResource.ROOT, "b01-dummy-" + uuid + ".nbt");
            NbtIo.writeCompressed(saved, path);
            helper.assertTrue(Files.size(path) > 0, "Native compressed entity NBT was really written to the test world");
            original.discard();
            Entity decoded = EntityType.loadEntityRecursive(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), helper.getLevel(), entity -> entity);
            helper.assertTrue(decoded instanceof PracticeDummyEntity, "The vanilla entity factory recreates the registered dummy type");
            PracticeDummyEntity restored = (PracticeDummyEntity) decoded;
            f.entities.add(restored);
            helper.assertTrue(helper.getLevel().addFreshEntity(restored), "The saved UUID can be re-admitted after the original unload/removal");
            helper.assertValueEqual(restored.getUUID(), uuid, "The persistent entity UUID survives");
            helper.assertValueEqual(restored.ownerId(), owner.getUUID(), "The owner UUID survives disk serialization");
            helper.assertValueEqual(restored.position(), position, "Native entity position survives disk serialization");
            helper.assertValueEqual(restored.getYRot(), 137.0F, "Native yaw survives disk serialization");
            restored.push(1, 1, 1);
            restored.move(MoverType.PISTON, new Vec3(1, 1, 1));
            restored.setDeltaMovement(new Vec3(1, 1, 1));
            restored.tick();
            helper.assertValueEqual(restored.position(), position, "Push, piston move and tick velocity cannot move the fixed prop");
            helper.assertValueEqual(restored.getDeltaMovement(), Vec3.ZERO, "A tick clears transient movement");
            helper.assertFalse(restored.startRiding(owner, true) || restored.canUsePortal(false), "The prop cannot ride or enter a portal");
            helper.assertTrue(restored.hasSupport(), "The reloaded entity still validates its actual support");
            restored.kill();
            helper.assertTrue(drops(helper.getLevel(), restored).isEmpty(), "Administrative kill has no salvage drop");
        }
        helper.succeed();
    }

    private static void recipe(GameTestHelper helper, int width, int height, List<ItemStack> grid, Item expected, String expectedId) {
        var input = CraftingInput.of(width, height, grid);
        var result = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
        helper.assertValueEqual(result.id(), ResourceLocation.parse(expectedId), "The actual loaded RecipeManager chooses the intended recipe");
        ItemStack output = result.value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(output.is(expected) && output.getCount() == 1, "The actual recipe assembles exactly the intended item");
    }
    private static ItemStack stack(Item item) { return item.getDefaultInstance(); }

    @GameTest(template = "empty", batch = BATCH)
    public static void loadedRecipesMirrorTagsAndVanillaConflictsAndAdvancementContract(GameTestHelper helper) {
        for (Item planks : List.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.CRIMSON_PLANKS)) {
            recipe(helper, 2, 2, List.of(ItemStack.EMPTY, stack(planks), stack(planks), stack(Items.STICK)),
                    ModContent.PRACTICE_WOODEN_SWORD.get(), id("practice_wooden_sword").toString());
            recipe(helper, 2, 2, List.of(stack(planks), ItemStack.EMPTY, stack(Items.STICK), stack(planks)),
                    ModContent.PRACTICE_WOODEN_SWORD.get(), id("practice_wooden_sword").toString());
        }
        for (Item log : List.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.STRIPPED_OAK_LOG)) {
            recipe(helper, 2, 2, List.of(stack(Items.STICK), ItemStack.EMPTY, stack(log), stack(Items.STICK)),
                    ModContent.PRACTICE_DUMMY_ITEM.get(), id("practice_dummy").toString());
            recipe(helper, 3, 1, List.of(stack(log), stack(Items.STICK), stack(Items.STICK)),
                    ModContent.PRACTICE_DUMMY_ITEM.get(), id("practice_dummy").toString());
        }
        recipe(helper, 1, 3, List.of(stack(Items.OAK_PLANKS), stack(Items.OAK_PLANKS), stack(Items.STICK)), Items.WOODEN_SWORD, "minecraft:wooden_sword");
        recipe(helper, 1, 3, List.of(stack(Items.OAK_PLANKS), stack(Items.STICK), stack(Items.STICK)), Items.WOODEN_SHOVEL, "minecraft:wooden_shovel");
        for (CraftingInput wrong : List.of(
                CraftingInput.of(2, 2, List.of(ItemStack.EMPTY, stack(Items.DIRT), stack(Items.OAK_PLANKS), stack(Items.STICK))),
                CraftingInput.of(2, 2, List.of(ItemStack.EMPTY, stack(Items.OAK_PLANKS), stack(Items.OAK_PLANKS), ItemStack.EMPTY)),
                CraftingInput.of(3, 1, List.of(stack(Items.OAK_LOG), stack(Items.STICK), ItemStack.EMPTY)),
                CraftingInput.of(3, 1, List.of(stack(Items.OAK_LOG), stack(Items.STICK), stack(Items.DIRT))))) {
            var found = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, wrong, helper.getLevel());
            helper.assertTrue(found.isEmpty() || (!found.get().id().equals(id("practice_wooden_sword")) && !found.get().id().equals(id("practice_dummy"))),
                    "Missing or wrong ingredients never match either B01 recipe");
        }
        var advancement = lesson(helper.getLevel()).value();
        var display = advancement.display().orElseThrow();
        helper.assertValueEqual(advancement.parent().orElseThrow(), StoryAdvancements.ROOT, "The first lesson is an independent story child");
        helper.assertValueEqual(advancement.rewards(), AdvancementRewards.EMPTY, "Recognition grants no items or other gameplay rewards");
        helper.assertValueEqual(display.getType(), AdvancementType.TASK, "The first lesson uses the task frame");
        helper.assertTrue(display.getIcon().is(ModContent.PRACTICE_WOODEN_SWORD.get()), "The native icon is the practice sword");
        helper.assertTrue(display.shouldShowToast() && !display.shouldAnnounceChat() && !display.isHidden(), "The visible milestone is personal and never broadcasts completion");
        helper.assertTrue(advancement.criteria().size() == 1 && advancement.criteria().get("recognized").trigger() == CriteriaTriggers.IMPOSSIBLE,
                "Only the explicit recognized success criterion can complete the lesson");
        helper.succeed();
    }

    private SwordLessonGameTests() { }
}
