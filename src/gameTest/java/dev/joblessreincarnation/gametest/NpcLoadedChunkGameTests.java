package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real missing-neighbour regression: only the remote centre is FULL, with no player or ticking-area ticket.
 * This does not execute inside a FULL conversion callback and cannot replace a fresh natural-world run with
 * the default watchdog. The timeout also cannot interrupt a production method that blocks the server thread.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcLoadedChunkGameTests {
    private static final TicketType<UUID> CENTRE_TICKET = TicketType.create("jr_npc_full_boundary_test", UUID::compareTo);
    private static final List<String> RESIDENTS = List.of("paul_greyrat", "zenith_greyrat", "lilia_greyrat", "roxy_tutor", "sylphiette");
    private static final int STANDING_Y = 96, PREPARE_DEADLINE = 20_000, OBSERVE_TICKS = 20;
    private static final long PREPARE_TIMEOUT_MILLIS = 30_000;
    private static int nextFixture;

    private record Resident(Mob mob, Supplier<GlobalPos> anchor, BooleanSupplier reanchor) { }

    @GameTest(template = "empty", batch = "npcLoadedChunks", timeoutTicks = 20_100)
    public static void collisionEdgeRejectsMissingFullNeighborWithoutLoading(GameTestHelper helper) {
        // Separate reruns from any earlier fixture still unloading; all positions remain inside the world border.
        Fixture fixture = new Fixture(helper, new ChunkPos(62_500 + 32 * nextFixture++, -62_500));
        fixture.start();
    }

    /** A snapshot of a real holder, not getChunkNow (which can expose currentlyLoading) or a loading request. */
    private static LevelChunk completedFull(ServerLevel level, ChunkPos pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        return holder != null && holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    private static Resident create(GameTestHelper helper, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(type), id, "Exact registered NPC type: " + path);
        Entity entity = type.create(helper.getLevel());
        if (entity instanceof FamilyResidentEntity family) return new Resident(family, family::getHomeAnchor, family::reanchor);
        if (entity instanceof RoxyTutorEntity roxy) return new Resident(roxy, roxy::getTutorAnchor, roxy::reanchor);
        if (entity instanceof SylphietteEntity sylphiette) return new Resident(sylphiette, sylphiette::getHomeAnchor, sylphiette::reanchor);
        throw new IllegalStateException("Registered type did not create a resident: " + path);
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos centre, east;
        private final UUID ticketOwner = UUID.randomUUID();
        private final List<BlockPos> supports = new ArrayList<>();
        private LevelChunk centreChunk;
        private Mob activeResident;
        private boolean ticketActive, closed;
        private long checkedAt = -1;
        private final long prepareStartedNanos = System.nanoTime();
        private long lastProgressTick = -1, lastProgressMillis = -1;
        private String lastProgressState = "";

        private Fixture(GameTestHelper helper, ChunkPos centre) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.centre = centre;
            this.east = new ChunkPos(centre.x + 1, centre.z);
        }

        private void start() {
            try {
                helper.assertTrue(level.getServer().isSameThread(), "Fixture runs on the server thread");
                helper.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(centre.toLong()) == null
                    && level.getChunkSource().chunkMap.getVisibleChunkIfPresent(east.toLong()) == null,
                    "Fixture requires an untouched remote centre and east neighbour");
                // Distance 0 requests FULL only. This custom ticket has no expiration and is removed by cleanup.
                level.getChunkSource().addRegionTicket(CENTRE_TICKET, centre, 0, ticketOwner);
                ticketActive = true;
                logPreparation("requested", true);
                // GameTestServer skips the normal tick delay. Queue only one poll, rather than thousands of
                // onEachTick callbacks, while asynchronous generation and entity I/O make real progress.
                helper.runAfterDelay(1, this::tick);
            } catch (RuntimeException | Error error) {
                cleanup();
                throw error;
            }
        }

        private void tick() {
            if (closed) return;
            try {
                if (centreChunk == null) {
                    LevelChunk ready = completedFull(level, centre);
                    // FULL generation and entity visibility are separate callbacks. This second snapshot avoids
                    // racing the live-index assertion; areEntitiesLoaded only reads the entity-manager status map.
                    if (ready == null || !level.areEntitiesLoaded(centre.toLong())) {
                        logPreparation("waiting", false);
                        if (helper.getTick() >= PREPARE_DEADLINE || elapsedMillis() >= PREPARE_TIMEOUT_MILLIS) {
                            logPreparation("deadline", true);
                            helper.fail("Fixture never reached completed centre FULL with entities loaded; tick="
                                + helper.getTick() + ", elapsedMs=" + elapsedMillis() + "; " + preparationState()
                                + "; boundary checks were not executed");
                        }
                        helper.runAfterDelay(1, this::tick);
                        return;
                    }
                    logPreparation("ready", true);
                    centreChunk = ready;
                    assertIsolated("before support preparation");
                    prepareSupport(8);
                    prepareSupport(15);
                    assertIsolated("after support preparation");
                    JoblessReincarnation.LOGGER.info("[JR_NPC_FULL_BOUNDARY] fixture centre={} east={} centreFull=true eastFull=false tick={}",
                        centre, east, helper.getTick());
                    for (String path : RESIDENTS) checkResident(path);
                    checkedAt = helper.getTick();
                }
                // Catch a delayed FULL ticket/promotion as well as a synchronously completed unwanted load.
                assertIsolated("after NPC checks, observation tick " + (helper.getTick() - checkedAt));
                if (helper.getTick() - checkedAt >= OBSERVE_TICKS) {
                    cleanup();
                    JoblessReincarnation.LOGGER.info("[JR_NPC_FULL_BOUNDARY] PASS residents=5 edgeRejected=true centreAccepted=true oldAnchorPreserved=true observedTicks={}",
                        OBSERVE_TICKS);
                    helper.succeed();
                } else {
                    helper.runAfterDelay(1, this::tick);
                }
            } catch (RuntimeException | Error error) {
                cleanup();
                throw error;
            }
        }

        private long elapsedMillis() {
            return (System.nanoTime() - prepareStartedNanos) / 1_000_000L;
        }

        private String chunkState(ChunkPos pos) {
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
            if (holder == null) return "holder=false, entitiesLoaded=" + level.areEntitiesLoaded(pos.toLong());
            // All diagnostic accesses are snapshots. currentlyLoading is evidence only, never readiness.
            return "holder=true, ticket=" + holder.getTicketLevel() + ", latest=" + holder.getLatestStatus()
                + ", full=" + (holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk)
                + ", currentlyLoading=" + (holder.currentlyLoading != null)
                + ", entitiesLoaded=" + level.areEntitiesLoaded(pos.toLong());
        }

        private String preparationState() {
            return "centre={" + chunkState(centre) + "}, east={" + chunkState(east) + "}";
        }

        private void logPreparation(String phase, boolean force) {
            String state = preparationState();
            long tick = helper.getTick(), millis = elapsedMillis();
            if (force || !state.equals(lastProgressState) || tick - lastProgressTick >= 2_000
                || millis - lastProgressMillis >= 5_000) {
                JoblessReincarnation.LOGGER.info("[JR_NPC_FULL_BOUNDARY] prepare={} tick={} elapsedMs={} centre={} east={} {}",
                    phase, tick, millis, centre, east, state);
                lastProgressState = state;
                lastProgressTick = tick;
                lastProgressMillis = millis;
            }
        }

        private void assertIsolated(String phase) {
            helper.assertTrue(completedFull(level, centre) == centreChunk, "Centre FULL remains complete: " + phase);
            helper.assertTrue(completedFull(level, east) == null, "East neighbour must remain genuinely missing FULL: " + phase);
            // hasChunk checks FULL ticket eligibility, not completion; here it detects an unwanted pending request.
            helper.assertFalse(level.getChunkSource().hasChunk(east.x, east.z), "East neighbour must not acquire a FULL ticket: " + phase);
        }

        private void prepareSupport(int localX) {
            BlockPos support = new BlockPos(centre.getMinBlockX() + localX, STANDING_Y - 1, centre.getMinBlockZ() + 8);
            helper.assertTrue(centreChunk.getBlockState(support).isAir(), "Fixture support starts in air");
            for (int dy = 1; dy <= 3; dy++) {
                helper.assertTrue(centreChunk.getBlockState(support.above(dy)).isAir(), "Fixture body space starts clear");
            }
            // Direct edits to the already complete centre: no level getChunk, neighbour update or neighbour read.
            supports.add(support);
            centreChunk.setBlockState(support, Blocks.STONE.defaultBlockState(), false);
            helper.assertTrue(centreChunk.getBlockState(support).is(Blocks.STONE), "Fixture has actual solid support");
        }

        private void checkResident(String path) {
            Resident resident = create(helper, path);
            activeResident = resident.mob();
            try {
                Mob mob = resident.mob();
                Vec3 edge = new Vec3(centre.getMinBlockX() + 15.5, STANDING_Y, centre.getMinBlockZ() + 8.5);
                Vec3 middle = new Vec3(centre.getMinBlockX() + 8.5, STANDING_Y, centre.getMinBlockZ() + 8.5);
                mob.setNoAi(true);
                mob.setNoGravity(true);
                mob.moveTo(edge);
                helper.assertTrue(resident.anchor().get() == null, path + ": constructor leaves home unset");
                helper.assertTrue(mob.getBoundingBox().inflate(0.1).maxX < centre.getMaxBlockX() + 1.0,
                    path + ": old 0.1 inflation stays inside centre, while vanilla collision scans local x=16");
                assertIsolated(path + " before join");
                // EntityType.spawn would adjust the placement through its own collision queries; create/add does not.
                helper.assertTrue(level.addFreshEntity(mob), path + ": real entity joins the completed centre");
                helper.assertTrue(mob.isAddedToLevel() && level.getEntity(mob.getUUID()) == mob, path + ": live entity identity is registered");
                helper.assertTrue(resident.anchor().get() == null, path + ": boundary join cannot create an unsafe home");
                helper.assertFalse(resident.reanchor().getAsBoolean(), path + ": missing collision neighbour rejects reanchor");
                helper.assertValueEqual(mob.position(), edge, path + ": rejected reanchor does not move the NPC");
                helper.assertTrue(resident.anchor().get() == null, path + ": rejection leaves home unset");
                assertIsolated(path + " after boundary rejection");

                mob.moveTo(middle);
                helper.assertTrue(resident.reanchor().getAsBoolean(), path + ": supported centre is a positive control");
                GlobalPos home = GlobalPos.of(level.dimension(), BlockPos.containing(middle));
                helper.assertValueEqual(resident.anchor().get(), home, path + ": positive control anchors the actual position");
                mob.moveTo(edge);
                helper.assertFalse(resident.reanchor().getAsBoolean(), path + ": return to boundary is still rejected");
                helper.assertValueEqual(resident.anchor().get(), home, path + ": failed boundary attempt preserves good home");
                helper.assertValueEqual(mob.position(), edge, path + ": failed boundary attempt preserves position");
                assertIsolated(path + " after centre control and second rejection");
            } finally {
                activeResident.discard();
                activeResident = null;
            }
        }

        private void cleanup() {
            if (closed) return;
            closed = true;
            try {
                if (activeResident != null) activeResident.discard();
                if (centreChunk != null) {
                    for (BlockPos support : supports) centreChunk.setBlockState(support, Blocks.AIR.defaultBlockState(), false);
                }
            } finally {
                if (ticketActive) {
                    level.getChunkSource().removeRegionTicket(CENTRE_TICKET, centre, 0, ticketOwner);
                    ticketActive = false;
                }
            }
        }
    }
}
