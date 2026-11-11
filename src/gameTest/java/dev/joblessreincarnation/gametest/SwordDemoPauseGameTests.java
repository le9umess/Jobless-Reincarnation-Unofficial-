package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.FamilyResidentEntity.Member;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * B01 gesture movement contracts, using live server entities, native interaction packets and ordinary
 * server time. All residents keep their real AI; no test mutates goal selectors or the world clock.
 * NBT reconstruction below is a live entity-loader check, not a disk/server restart claim.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SwordDemoPauseGameTests {
    private static final String BATCH = "swordDemoPauseB01";

    private static final class Reader extends ServerPlayer {
        Reader(GameTestHelper helper) {
            super(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "Demo" + UUID.randomUUID().toString().substring(0, 8)),
                    ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { }
            };
            setNoGravity(true);
            setGameMode(GameType.SURVIVAL);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Reader reader;
        final List<FamilyResidentEntity> residents = new ArrayList<>();
        boolean closed;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            // The ordinary empty fixture is 16 x 6 x 16; retain its controller at local 0,0,0.
            for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
            reader = new Reader(helper);
            reader.setPos(helper.absoluteVec(new Vec3(13.5, 1, 13.5)));
            helper.getLevel().addNewPlayer(reader);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(reader));
            helper.assertTrue(helper.getLevel().getEntity(reader.getUUID()) == reader && !reader.isFakePlayer(),
                    "Native sword guidance uses a real registered human player fixture");
        }

        FamilyResidentEntity spawn(Member member, Vec3 relative) {
            EntityType<FamilyResidentEntity> type = switch (member) {
                case PAUL -> ModContent.PAUL_GREYRAT.get();
                case ZENITH -> ModContent.ZENITH_GREYRAT.get();
                case LILIA -> ModContent.LILIA_GREYRAT.get();
            };
            FamilyResidentEntity resident = type.create(helper.getLevel());
            if (resident == null) throw new IllegalStateException("Missing registered family factory");
            resident.setPos(helper.absoluteVec(relative));
            residents.add(resident);
            helper.assertTrue(helper.getLevel().addFreshEntity(resident), "Resident enters the real entity index");
            helper.assertFalse(resident.isNoAi(), "Resident retains native movement goals");
            helper.assertTrue(resident.getHomeAnchor() != null, "Dry supported entry establishes an actual home");
            return resident;
        }

        void interact(FamilyResidentEntity resident, boolean sword) {
            reader.setItemInHand(InteractionHand.MAIN_HAND,
                    sword ? ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance() : ItemStack.EMPTY);
            helper.assertTrue(reader.canInteractWithEntity(resident, 0.0) && reader.hasLineOfSight(resident),
                    "The packet begins within native reach and sight");
            reader.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                    resident, false, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0)));
            helper.assertTrue(resident.conversationPlayer() == reader, "The native packet enters the existing conversation gate");
            if (sword) {
                helper.assertTrue(reader.getData(ModAttachments.SWORD_TRAINING).started(), "The native sword query starts the course");
                helper.assertTrue(resident.isDemonstratingSword(), "Successful guidance begins an independent sword demonstration");
            }
        }

        void checked(Runnable action) {
            if (closed) return;
            try { action.run(); }
            catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            residents.forEach(Entity::discard);
            reader.getAdvancements().stopListening();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(reader));
            reader.discard();
            removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "advancements", reader.getUUID(), reader.getAdvancements());
            removeOwnMapping(helper.getLevel().getServer().getPlayerList(), "stats", reader.getUUID(), reader.getStats());
        }
    }

    private static void removeOwnMapping(PlayerList list, String name, UUID uuid, Object expected) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            if (!(field.get(list) instanceof Map<?, ?> values)) throw new IllegalStateException("Expected native cache: " + name);
            if (values.get(uuid) == expected) values.remove(uuid);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PlayerList fixture field changed: " + name, error);
        }
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double x = first.x - second.x, z = first.z - second.z;
        return x * x + z * z;
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 180)
    public static void departedReaderKeepsFortyTickPauseThenRealReturnHomeResumes(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            FamilyResidentEntity paul = f.spawn(Member.PAUL, new Vec3(3.5, 1, 8.5));
            GlobalPos home = paul.getHomeAnchor();
            Vec3 homePosition = Vec3.atBottomCenterOf(home.pos());
            paul.setPos(helper.absoluteVec(new Vec3(11.5, 1, 8.5)));
            Vec3 displaced = paul.position();
            long setupTime = helper.getLevel().getGameTime();
            long[] demonstrationStart = { -1L };
            Vec3[] stoppedPosition = { null };
            boolean[] boundarySeen = { false, false };
            helper.onEachTick(() -> f.checked(() -> {
                long now = helper.getLevel().getGameTime();
                helper.assertValueEqual(paul.getHomeAnchor(), home, "Interruption and recovery preserve the original home");
                helper.assertFalse(paul.isNoAi(), "Movement checks never disable Paul's AI");
                if (demonstrationStart[0] < 0) {
                    if (horizontalDistanceSqr(paul.position(), displaced) < 0.04 || paul.getNavigation().isDone()) {
                        helper.assertTrue(now - setupTime < 60, "The real return-home goal must start moving before the interruption");
                        return;
                    }
                    helper.assertTrue(horizontalDistanceSqr(paul.position(), homePosition) < horizontalDistanceSqr(displaced, homePosition),
                            "Paul was genuinely walking toward his original home");
                    f.reader.setPos(paul.position().add(0, 0, 2));
                    f.interact(paul, true);
                    demonstrationStart[0] = now;
                    helper.assertTrue(paul.getNavigation().isDone(), "The successful sword interaction stops an already active path");
                    f.reader.setPos(helper.absoluteVec(new Vec3(14, 1, 14)));
                    helper.assertFalse(f.reader.canInteractWithEntity(paul, 0.0), "The reader really leaves interaction range");
                    return;
                }
                long elapsed = now - demonstrationStart[0];
                helper.assertFalse(paul.isConversing(), "Distance invalidates conversation independently of demonstration timing");
                if (elapsed < 40) {
                    helper.assertTrue(paul.isDemonstratingSword(), "The demonstration remains active before its 40-tick boundary");
                    helper.assertTrue(paul.getNavigation().isDone(), "Live return and stroll goals cannot reacquire movement during the demonstration");
                    // Native walking velocity may decay briefly after the path is stopped. Check the settled
                    // position through the entire remaining animation instead of relying on a state flag alone.
                    if (elapsed == 6) stoppedPosition[0] = paul.position();
                    if (elapsed > 6) helper.assertTrue(horizontalDistanceSqr(paul.position(), stoppedPosition[0]) < 0.0004,
                            "The living resident remains physically still while the departed reader cannot sustain conversation");
                }
                if (elapsed == 39) {
                    boundarySeen[0] = true;
                    helper.assertFalse(NpcAnimations.playSwordDemo(paul), "A repeat on tick 39 is rejected by the per-resident interval");
                }
                if (elapsed == 40) {
                    boundarySeen[1] = true;
                    helper.assertFalse(paul.isDemonstratingSword(), "Exactly 40 ticks ends the pause; the rejected request cannot extend it");
                }
                if (elapsed == 100) {
                    helper.assertTrue(boundarySeen[0] && boundarySeen[1], "Both adjacent real tick boundaries were observed");
                    helper.assertTrue(stoppedPosition[0] != null
                                    && horizontalDistanceSqr(paul.position(), homePosition) + 1.0 < horizontalDistanceSqr(stoppedPosition[0], homePosition),
                            "After expiry, the ordinary return-home goal makes visible progress without another command");
                    f.close();
                    helper.succeed();
                }
            }));
            helper.runAfterDelay(175, () -> f.checked(() -> {
                throw new IllegalStateException("The live movement/pause/recovery sequence did not finish");
            }));
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 80)
    public static void blockedSightAndEachResidentsConversationHaveIndependentLifetimes(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            FamilyResidentEntity paul = f.spawn(Member.PAUL, new Vec3(3.5, 1, 4.5));
            FamilyResidentEntity second = f.spawn(Member.PAUL, new Vec3(10.5, 1, 4.5));
            FamilyResidentEntity zenith = f.spawn(Member.ZENITH, new Vec3(3.5, 1, 10.5));
            FamilyResidentEntity lilia = f.spawn(Member.LILIA, new Vec3(10.5, 1, 10.5));
            f.reader.setPos(helper.absoluteVec(new Vec3(3.5, 1, 2.5)));
            f.interact(paul, true);
            long start = helper.getLevel().getGameTime();
            helper.assertFalse(second.isDemonstratingSword(), "Another Paul does not inherit the first Paul's gesture");
            helper.assertFalse(NpcAnimations.playSwordDemo(zenith) || NpcAnimations.playSwordDemo(lilia),
                    "Neither other family role can start a sword demonstration");
            for (int x = 2; x <= 4; x++) for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE);
            helper.assertFalse(f.reader.hasLineOfSight(paul), "An actual opaque wall blocks the reader's sight");
            helper.startSequence().thenExecuteAfter(2, () -> f.checked(() -> {
                helper.assertFalse(paul.isConversing(), "Occlusion invalidates the real conversation");
                helper.assertTrue(paul.isDemonstratingSword() && paul.getNavigation().isDone(),
                        "The independent demonstration still prevents navigation after sight is lost");
                helper.assertFalse(zenith.isDemonstratingSword() || lilia.isDemonstratingSword(), "Wrong-member rejection creates no hidden pause");
            })).thenExecuteAfter(8, () -> f.checked(() -> {
                helper.assertValueEqual(helper.getLevel().getGameTime() - start, 10L, "Second resident starts ten real ticks later");
                helper.assertTrue(NpcAnimations.playSwordDemo(second), "A distinct Paul starts even inside the first Paul's interval");
                f.reader.setPos(zenith.position().add(0, 0, -2));
                f.interact(zenith, false);
                helper.assertFalse(zenith.isDemonstratingSword(), "A normal empty-hand family reply never starts a sword demonstration");
            })).thenExecuteAfter(30, () -> f.checked(() -> {
                helper.assertValueEqual(helper.getLevel().getGameTime() - start, 40L, "First resident reaches his exact expiry");
                helper.assertFalse(paul.isDemonstratingSword(), "The first Paul's independent pause expires");
                helper.assertTrue(second.isDemonstratingSword(), "The later Paul retains his own remaining ten ticks");
                helper.assertTrue(zenith.isConversing(), "A later ordinary family conversation is not truncated by the first Paul's expiry");
            })).thenExecuteAfter(9, () -> f.checked(() -> {
                helper.assertTrue(second.isDemonstratingSword() && zenith.isConversing(), "Each later interval still lasts through its tick 39");
            })).thenExecuteAfter(1, () -> f.checked(() -> {
                helper.assertFalse(second.isDemonstratingSword() || zenith.isConversing(), "Each later pause ends on its own tick 40");
                f.close();
            })).thenSucceed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 30)
    public static void ordinaryNbtReconstructionCannotRestoreRuntimeDemoOrConversation(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.checked(() -> {
            FamilyResidentEntity original = f.spawn(Member.PAUL, new Vec3(6.5, 1, 7.5));
            f.reader.setPos(helper.absoluteVec(new Vec3(6.5, 1, 5.5)));
            f.interact(original, true);
            GlobalPos home = original.getHomeAnchor();
            UUID identity = original.getUUID();
            CompoundTag saved = new CompoundTag();
            helper.assertTrue(original.save(saved), "Active demonstration participates in ordinary entity saving");
            original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.runAfterDelay(2, () -> f.checked(() -> {
                helper.assertTrue(helper.getLevel().getEntity(identity) == null, "The previous live identity has left before reconstruction");
                Entity loaded = EntityType.loadEntityRecursive(saved.copy(), helper.getLevel(), value -> value);
                helper.assertTrue(loaded instanceof FamilyResidentEntity, "The real registry/NBT loader recreates Paul");
                FamilyResidentEntity restored = (FamilyResidentEntity) loaded;
                f.residents.add(restored);
                helper.assertTrue(helper.getLevel().addFreshEntity(restored), "The reconstructed entity joins the real level");
                helper.assertValueEqual(restored.getUUID(), identity, "Ordinary persistent identity survives reconstruction");
                helper.assertValueEqual(restored.getHomeAnchor(), home, "The home persists while transient activity does not");
                helper.assertFalse(restored.isDemonstratingSword() || restored.isConversing(),
                        "Loading during a gesture cannot restore runtime movement pause or its departed conversation");
                helper.assertFalse(restored.isNoAi(), "Reconstruction leaves autonomous resident AI enabled");
                helper.assertTrue(NpcAnimations.playSwordDemo(restored), "The reconstructed instance can demonstrate immediately, even with the same UUID");
                helper.assertTrue(restored.isDemonstratingSword(), "A new successful trigger starts a fresh independent pause");
                helper.assertFalse(NpcAnimations.playSwordDemo(restored), "Only the fresh instance's new interval now rejects repetition");
                f.close();
                helper.succeed();
            }));
        });
    }

    private SwordDemoPauseGameTests() { }
}
