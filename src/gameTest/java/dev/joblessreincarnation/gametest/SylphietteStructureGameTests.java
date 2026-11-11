package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.worldgen.SylphietteMeetingStructure;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SYLPHIE-S01/H03: exercises v004 and decoded registries through ordinary placement.
 * Dimensions and source feet are read from the actual resource, not a replacement miniature or generator code.
 * Deliberate server-thread placements cannot prove asynchronous natural generation, disk reload or rendering.
 * Old saved vanilla jigsaw pieces retain their v001/v003 resources, including deferred clipped placement.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SylphietteStructureGameTests {
    private static final String ARENA = "house_h01_empty", BATCH = "sylphS01Structure";
    private static final String ANCHOR_TAG = "JR_HomeAnchor"; // Final IF1: per-entity key does not make her a household member.
    private static final int CLEAR_RADIUS = 3;
    private static final ResourceLocation ENTITY = id("sylphiette");
    private static final ResourceLocation TEMPLATE = id("sylphiette_meeting/main_v004"), V003_TEMPLATE = id("sylphiette_meeting/main_v003"), V002_TEMPLATE = id("sylphiette_meeting/main_v002"), OLD_TEMPLATE = id("sylphiette_meeting/main"), POOL = id("sylphiette_meeting/terrain_start"), OLD_POOL = id("sylphiette_meeting/start");
    private static final ResourceLocation STRUCTURE = id("sylphiette_meeting_gentle"), OLD_STRUCTURE = id("sylphiette_meeting"), SET = id("sylphiette_meetings");
    private static final UUID EDITOR_UUID = UUID.fromString("7e08cd5a-5c6f-4dd4-80b5-356377b96108");

    private record Fixture(StructureTemplate template, SinglePoolElement pool, CompoundTag source,
                           CompoundTag templateWitness, BlockPos origin, Vec3 feet, Mirror mirror, Rotation rotation) { }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path); }
    private static BoundingBox bounds(GameTestHelper helper) {
        return BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 0, 1)), helper.absolutePos(new BlockPos(38, 19, 38)));
    }
    private static AABB area(GameTestHelper helper) {
        return new AABB(helper.absoluteVec(new Vec3(1, 0, 1)), helper.absoluteVec(new Vec3(39, 20, 39)));
    }
    private static List<SylphietteEntity> residents(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(SylphietteEntity.class, area(helper), e -> !e.isRemoved());
    }
    private static void cleanup(GameTestHelper helper) { residents(helper).forEach(Entity::discard); }
    private static CompoundTag raw(GameTestHelper helper) { return raw(helper, TEMPLATE); }
    private static CompoundTag raw(GameTestHelper helper, ResourceLocation template) {
        try (InputStream stream = helper.getLevel().getServer().getResourceManager().open(id("structure/" + template.getPath() + ".nbt"))) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException e) { throw new IllegalStateException("Cannot read delivered meeting template", e); }
    }
    private static Vec3 vector(GameTestHelper helper, ListTag list, String label) {
        helper.assertValueEqual(list.size(), 3, label + " has exactly three coordinates");
        Vec3 value = new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
        helper.assertTrue(Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z), label + " is finite");
        return value;
    }
    private static Fixture fixture(GameTestHelper helper, Mirror mirror, Rotation rotation, int xShift) {
        StructureTemplate template = helper.getLevel().getStructureManager().get(TEMPLATE).orElseThrow();
        var size = template.getSize();
        // This is a compact tree clearing, not a hidden assertion about the final author's exact dimensions.
        helper.assertTrue(size.getX() >= 9 && size.getX() <= 34 && size.getY() >= 4 && size.getY() <= 19
            && size.getZ() >= 9 && size.getZ() <= 34, "Delivered compact structure fits the existing isolated test arena");
        CompoundTag source = raw(helper);
        // Outside the rounded hill, absent records preserve terrain. SinglePoolElement does not ignore STRUCTURE_VOID.
        ListTag palette = source.getList("palette", Tag.TAG_COMPOUND);
        for (int index = 0; index < palette.size(); index++) {
            helper.assertFalse(palette.getCompound(index).getString("Name").equals("minecraft:structure_void"), "Outer terrain is preserved by omitted records, never an explicitly placed structure_void state");
        }
        ListTag blocks = source.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            ListTag position = blocks.getCompound(index).getList("pos", Tag.TAG_INT);
            boolean cornerX = position.getInt(0) == 0 || position.getInt(0) == size.getX() - 1;
            boolean cornerZ = position.getInt(2) == 0 || position.getInt(2) == size.getZ() - 1;
            helper.assertFalse(cornerX && cornerZ && position.getInt(1) == 0, "Four outside ground corners remain omitted instead of overwriting existing terrain");
        }
        ListTag entries = source.getList("entities", Tag.TAG_COMPOUND);
        helper.assertValueEqual(entries.size(), 1, "A meeting point contains exactly one authored NPC, with no household residents");
        CompoundTag record = entries.getCompound(0), nbt = record.getCompound("nbt");
        helper.assertValueEqual(nbt.getString("id"), ENTITY.toString(), "The one authoring entry is the registered Sylphiette type");
        helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(ENTITY), "Sylphiette is formally registered");
        for (String forbidden : List.of("UUID", "UUIDMost", "UUIDLeast", "Passengers", "Motion", "NoAI", "NoGravity", "Brain", "Leash", "PortalCooldown")) {
            helper.assertFalse(nbt.contains(forbidden), "Reusable NPC has no stale editing field " + forbidden);
        }
        for (String key : nbt.getAllKeys()) {
            helper.assertFalse(key.contains("Anchor") || key.contains("Conversation"), "The template stores no world anchor or conversation state");
            if (nbt.contains(key, Tag.TAG_COMPOUND)) helper.assertFalse(GlobalPos.CODEC.parse(NbtOps.INSTANCE, nbt.getCompound(key)).result().isPresent(), "No baked GlobalPos in reusable NPC");
        }
        Vec3 feet = vector(helper, record.getList("pos", Tag.TAG_DOUBLE), "Authored local feet");
        helper.assertTrue(feet.x > 0 && feet.x < size.getX() && feet.y >= 1 && feet.y < size.getY()
            && feet.z > 0 && feet.z < size.getZ(), "Local feet lie inside the template and above its support layer");
        ListTag block = record.getList("blockPos", Tag.TAG_INT);
        helper.assertValueEqual(block.size(), 3, "Slice ownership uses one complete block position");
        helper.assertValueEqual(new BlockPos(block.getInt(0), block.getInt(1), block.getInt(2)), BlockPos.containing(feet), "Slice owner is the actual feet block");
        if (nbt.contains("Pos")) helper.assertValueEqual(vector(helper, nbt.getList("Pos", Tag.TAG_DOUBLE), "Embedded feet"), feet, "Embedded position is local rather than a stale editor-world coordinate");
        ListTag facing = nbt.getList("Rotation", Tag.TAG_FLOAT);
        helper.assertValueEqual(facing.size(), 2, "Authored NPC provides yaw and pitch");
        helper.assertTrue(Float.isFinite(facing.getFloat(0)), "Authored yaw is finite");
        helper.assertValueEqual(facing.getFloat(1), 0F, "Initial model head pitch comes from animation and look control");

        StructurePoolElement element = helper.getLevel().registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getOptional(POOL)
            .orElseThrow().getRandomTemplate(RandomSource.create(2108L));
        helper.assertTrue(element instanceof SinglePoolElement, "Registered start pool selects the standard single-template element");
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int x : new int[] {0, size.getX() - 1}) for (int z : new int[] {0, size.getZ() - 1}) {
            BlockPos corner = StructureTemplate.transform(new BlockPos(x, 0, z), mirror, rotation, BlockPos.ZERO);
            minX = Math.min(minX, corner.getX()); minZ = Math.min(minZ, corner.getZ());
        }
        BlockPos origin = helper.absolutePos(new BlockPos(3 - minX + xShift, 0, 3 - minZ));
        BoundingBox footprint = template.getBoundingBox(origin, rotation, BlockPos.ZERO, mirror), arena = bounds(helper);
        helper.assertTrue(arena.isInside(new BlockPos(footprint.minX(), footprint.minY(), footprint.minZ()))
            && arena.isInside(new BlockPos(footprint.maxX(), footprint.maxY(), footprint.maxZ())), "Every placed block remains in this fixture's arena");
        return new Fixture(template, (SinglePoolElement)element, source, template.save(new CompoundTag()), origin, feet, mirror, rotation);
    }

    /** Independent coordinate expectation, including the one-block entity pivot offset; does not call entity transform. */
    private static Vec3 expected(Fixture f) {
        double x = f.feet.x, z = f.feet.z;
        if (f.mirror == Mirror.FRONT_BACK) x = 1.0 - x;
        else if (f.mirror == Mirror.LEFT_RIGHT) z = 1.0 - z;
        Vec3 offset = switch (f.rotation) {
            case NONE -> new Vec3(x, f.feet.y, z);
            case CLOCKWISE_90 -> new Vec3(1.0 - z, f.feet.y, x);
            case CLOCKWISE_180 -> new Vec3(1.0 - x, f.feet.y, 1.0 - z);
            case COUNTERCLOCKWISE_90 -> new Vec3(z, f.feet.y, 1.0 - x);
        };
        return Vec3.atLowerCornerOf(f.origin).add(offset);
    }
    private static void place(GameTestHelper helper, Fixture f, BoundingBox clip) {
        ServerLevel level = helper.getLevel();
        if (f.mirror == Mirror.NONE) {
            helper.assertTrue(f.pool.place(level.getStructureManager(), level, level.structureManager(), level.getChunkSource().getGenerator(),
                f.origin, f.origin, f.rotation, clip, RandomSource.create(218L), LiquidSettings.IGNORE_WATERLOGGING, false), "Actual registered pool accepts the meeting-point placement");
        } else placeTemplate(helper, f.template, f, clip);
    }
    private static void placeTemplate(GameTestHelper helper, StructureTemplate template, Fixture f, BoundingBox clip) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true).setKnownShape(true)
            .setMirror(f.mirror).setRotation(f.rotation).setBoundingBox(clip);
        helper.assertTrue(template.placeInWorld(helper.getLevel(), f.origin, f.origin, settings, RandomSource.create(218L), 18), "Actual template accepts the explicit placement settings");
    }
    private static GlobalPos savedAnchor(GameTestHelper helper, SylphietteEntity resident) {
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(resident.save(saved), "Sylphiette participates in ordinary entity saving");
        helper.assertTrue(saved.contains(ANCHOR_TAG, Tag.TAG_COMPOUND), "The exact agreed anchor key is persisted: " + ANCHOR_TAG);
        GlobalPos anchor = GlobalPos.CODEC.parse(NbtOps.INSTANCE, saved.getCompound(ANCHOR_TAG)).result().orElseThrow();
        helper.assertValueEqual(anchor, resident.getHomeAnchor(), "Public home and ordinary NBT agree");
        return anchor;
    }
    private static SylphietteEntity check(GameTestHelper helper, Fixture f) {
        List<SylphietteEntity> all = residents(helper);
        helper.assertValueEqual(all.size(), 1, "Completed point has exactly one live Sylphiette");
        SylphietteEntity npc = all.getFirst();
        Vec3 target = expected(f);
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(npc.getType()), ENTITY, "Placement creates the precise approved entity type");
        helper.assertTrue(npc.position().distanceToSqr(target) < 1.0E-12, "Actual fractional entity position follows the independent transform expectation");
        helper.assertTrue(helper.getLevel().getEntity(npc.getUUID()) == npc && npc.isAddedToLevel(), "The server entity index owns this exact NPC");
        helper.assertFalse(npc.isNoAi() || npc.isNoGravity(), "Delivered NPC retains natural AI and gravity");
        helper.assertTrue(npc.shouldBeSaved() && npc.isPersistenceRequired() && !npc.removeWhenFarAway(1_000_000), "The encounter NPC uses persistent normal storage");
        GlobalPos home = GlobalPos.of(helper.getLevel().dimension(), BlockPos.containing(target));
        helper.assertValueEqual(npc.getHomeAnchor(), home, "A safe transformed landing initializes the actual world home");
        helper.assertValueEqual(savedAnchor(helper, npc), home, "Ordinary saving keeps that home");
        helper.assertValueEqual(npc.getRestrictCenter(), home.pos(), "Navigation uses this instance's home");
        assertStandingBody(helper, npc, target, "initial landing");
        helper.assertValueEqual(raw(helper), f.source, "Placement leaves the delivered resource untouched");
        helper.assertValueEqual(f.template.save(new CompoundTag()), f.templateWitness, "Placement leaves cached authoring NBT untouched");
        return npc;
    }
    private static void assertStandingBody(GameTestHelper helper, SylphietteEntity npc, Vec3 feet, String label) {
        AABB body = npc.getBoundingBox().move(feet.subtract(npc.position()));
        helper.assertFalse(helper.getLevel().getBlockCollisions(npc, body).iterator().hasNext(), "Actual child body has block clearance at " + label);
        helper.assertTrue(helper.getLevel().getBlockCollisions(npc, body.move(0, -0.05, 0)).iterator().hasNext(), "Actual child body is supported at " + label);
        helper.assertTrue(helper.getLevel().getFluidState(BlockPos.containing(feet)).isEmpty(), "Standing feet are not in fluid at " + label);
    }
    private static void rotationCase(GameTestHelper helper, Rotation rotation) {
        try { Fixture f = fixture(helper, Mirror.NONE, rotation, 0); place(helper, f, bounds(helper)); check(helper, f); }
        finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void meetingPlacesUnrotated(GameTestHelper h) { rotationCase(h, Rotation.NONE); }
    @GameTest(template = ARENA, batch = BATCH) public static void meetingPlacesClockwise(GameTestHelper h) { rotationCase(h, Rotation.CLOCKWISE_90); }
    @GameTest(template = ARENA, batch = BATCH) public static void meetingPlacesHalfTurn(GameTestHelper h) { rotationCase(h, Rotation.CLOCKWISE_180); }
    @GameTest(template = ARENA, batch = BATCH) public static void meetingPlacesCounterclockwise(GameTestHelper h) { rotationCase(h, Rotation.COUNTERCLOCKWISE_90); }


    private static BlockPos world(Fixture f, BlockPos local) {
        return StructureTemplate.transform(local, f.mirror, f.rotation, BlockPos.ZERO).offset(f.origin);
    }
    /** Exact natural oak from sealed seed 12345, start chunk (505,-307), local root (8,3,14).
     * These synchronous placements test overwrite geometry, not asynchronous decoration scheduling. */
    private static List<BlockPos> observedOakLeaves() {
        int[][] coordinates = {
            {7,5,12},{8,5,12},{9,5,12},{10,5,12},{6,5,13},{7,5,13},{8,5,13},{9,5,13},{10,5,13},
            {6,5,14},{7,5,14},{9,5,14},{10,5,14},{6,5,15},{7,5,15},{8,5,15},{9,5,15},{10,5,15},
            {7,5,16},{8,5,16},{9,5,16},{10,5,16},{7,6,12},{8,6,12},{9,6,12},{10,6,12},
            {6,6,13},{7,6,13},{8,6,13},{9,6,13},{10,6,13},{6,6,14},{7,6,14},{9,6,14},{10,6,14},
            {6,6,15},{7,6,15},{8,6,15},{9,6,15},{10,6,15},{7,6,16},{8,6,16},{9,6,16},{10,6,16},
            {7,7,13},{8,7,13},{9,7,13},{7,7,14},{9,7,14},{8,7,15},{9,7,15},
            {8,8,13},{7,8,14},{8,8,14},{9,8,14},{8,8,15}
        };
        List<BlockPos> leaves = new ArrayList<>();
        for (int[] p : coordinates) leaves.add(new BlockPos(p[0], p[1], p[2]));
        return leaves;
    }
    private static void seedObservedOak(GameTestHelper helper, Fixture f, List<BlockPos> leaves) {
        ServerLevel level = helper.getLevel();
        level.setBlock(world(f, new BlockPos(8, 2, 14)), Blocks.DIRT.defaultBlockState(), 18);
        for (int y = 3; y <= 7; y++) level.setBlock(world(f, new BlockPos(8, y, 14)), Blocks.OAK_LOG.defaultBlockState(), 18);
        for (BlockPos p : leaves) level.setBlock(world(f, p), Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1), 18);
    }
    private static void existingOakCase(GameTestHelper helper, Rotation rotation) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, rotation, 0);
            ServerLevel level = helper.getLevel();
            List<BlockPos> leaves = observedOakLeaves();
            helper.assertValueEqual(leaves.size(), 56, "The fixture contains every leaf from the sealed natural oak");
            BlockPos exterior = world(f, new BlockPos(0, 9, 0));
            level.setBlock(exterior, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            seedObservedOak(helper, f, leaves);
            StructureTemplate reduced = level.getStructureManager().get(V002_TEMPLATE).orElseThrow();
            placeTemplate(helper, reduced, f, bounds(helper));
            for (int y = 3; y <= 5; y++) helper.assertTrue(level.getBlockState(world(f, new BlockPos(8, y, 14))).isAir(), "V002 clears the lower trunk as the negative control");
            for (int y = 6; y <= 7; y++) helper.assertTrue(level.getBlockState(world(f, new BlockPos(8, y, 14))).is(Blocks.OAK_LOG), "V002 leaves the known unsupported upper trunk as the negative control");
            long remainingLeaves = leaves.stream().filter(p -> level.getBlockState(world(f, p)).is(Blocks.OAK_LEAVES)).count();
            helper.assertValueEqual(remainingLeaves, 34L, "V002 leaves the known upper canopy outside its reduced AIR envelope");
            cleanup(helper);
            // Recreate the whole oak so the active v004 must still remove lower and upper blocks itself.
            seedObservedOak(helper, f, leaves);
            place(helper, f, bounds(helper));
            check(helper, f);
            helper.assertTrue(level.getBlockState(world(f, new BlockPos(8, 2, 14))).is(Blocks.GRASS_BLOCK), "Late template placement restores the oak's dirt base to authored lawn");
            for (int y = 3; y <= 7; y++) helper.assertTrue(level.getBlockState(world(f, new BlockPos(8, y, 14))).isAir(), "Active V004 retains the full AIR envelope and removes every observed natural trunk block");
            for (BlockPos p : leaves) helper.assertTrue(level.getBlockState(world(f, p)).isAir(), "Active V004 removes every observed natural leaf at " + p);
            helper.assertTrue(level.getBlockState(world(f, new BlockPos(14, 3, 7))).is(Blocks.OAK_LOG), "The authored landmark trunk remains");
            helper.assertTrue(level.getBlockState(exterior).is(Blocks.GOLD_BLOCK), "The omitted exterior remains untouched rather than being cleared as a cuboid");
        } finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void restoredAirRemovesExistingOakUnrotated(GameTestHelper h) { existingOakCase(h, Rotation.NONE); }
    @GameTest(template = ARENA, batch = BATCH) public static void restoredAirRemovesExistingOakClockwise(GameTestHelper h) { existingOakCase(h, Rotation.CLOCKWISE_90); }
    @GameTest(template = ARENA, batch = BATCH) public static void restoredAirRemovesExistingOakHalfTurn(GameTestHelper h) { existingOakCase(h, Rotation.CLOCKWISE_180); }
    @GameTest(template = ARENA, batch = BATCH) public static void restoredAirRemovesExistingOakCounterclockwise(GameTestHelper h) { existingOakCase(h, Rotation.COUNTERCLOCKWISE_90); }

    private static void mirrorCase(GameTestHelper helper, Mirror mirror, Rotation rotation) {
        try { Fixture f = fixture(helper, mirror, rotation, 0); place(helper, f, bounds(helper)); check(helper, f); }
        finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void leftRightMirrorWithQuarterTurnPreservesLanding(GameTestHelper h) { mirrorCase(h, Mirror.LEFT_RIGHT, Rotation.CLOCKWISE_90); }
    @GameTest(template = ARENA, batch = BATCH) public static void frontBackMirrorWithHalfTurnPreservesLanding(GameTestHelper h) { mirrorCase(h, Mirror.FRONT_BACK, Rotation.CLOCKWISE_180); }

    private static void slicedCase(GameTestHelper helper, boolean reverse) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.NONE, 0);
            BoundingBox arena = bounds(helper); List<BoundingBox> slices = new ArrayList<>();
            for (int x = Math.floorDiv(arena.minX(), 16) * 16; x <= arena.maxX(); x += 16) {
                for (int z = Math.floorDiv(arena.minZ(), 16) * 16; z <= arena.maxZ(); z += 16) {
                    slices.add(new BoundingBox(Math.max(x, arena.minX()), arena.minY(), Math.max(z, arena.minZ()),
                        Math.min(x + 15, arena.maxX()), arena.maxY(), Math.min(z + 15, arena.maxZ())));
                }
            }
            if (reverse) Collections.reverse(slices);
            boolean placed = false; UUID firstUuid = null;
            for (BoundingBox slice : slices) {
                place(helper, f, slice);
                placed |= slice.isInside(BlockPos.containing(expected(f)));
                List<SylphietteEntity> all = residents(helper);
                helper.assertValueEqual(all.size(), placed ? 1 : 0, "Each disjoint real chunk slice inserts only the entity whose feet it owns");
                if (placed) {
                    SylphietteEntity npc = all.getFirst();
                    if (firstUuid == null) firstUuid = npc.getUUID();
                    else helper.assertValueEqual(npc.getUUID(), firstUuid, "Later slices do not replace or duplicate the inserted NPC");
                }
            }
            check(helper, f);
        } finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void forwardChunkSlicesPlaceOneNpc(GameTestHelper h) { slicedCase(h, false); }
    @GameTest(template = ARENA, batch = BATCH) public static void reverseChunkSlicesPlaceOneNpc(GameTestHelper h) { slicedCase(h, true); }

    @GameTest(template = ARENA, batch = BATCH)
    public static void authoredClearingHasActualSupportClearanceAndShortPaths(GameTestHelper helper) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.NONE, 0); place(helper, f, bounds(helper));
            SylphietteEntity npc = check(helper, f); GlobalPos home = npc.getHomeAnchor();
            helper.assertValueEqual(npc.homeRadius(), CLEAR_RADIUS, "NPC restriction matches the agreed three-block clearing");
            npc.setOnGround(true);
            // The full 7 x 7 square and all 147 authored air cells are promised by H02.
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
                BlockPos target = home.pos().offset(dx, 0, dz);
                assertStandingBody(helper, npc, Vec3.atBottomCenterOf(target), "clearing offset " + dx + "," + dz);
                helper.assertTrue(helper.getLevel().getBlockState(target.below()).is(Blocks.GRASS_BLOCK), "Every clearing cell retains grass support");
                for (int dy = 0; dy < 3; dy++) helper.assertTrue(helper.getLevel().getBlockState(target.above(dy)).isAir()
                    && helper.getLevel().getFluidState(target.above(dy)).isEmpty(), "Every clearing cell has three dry AIR blocks");
            }
            // Actual route probes are limited to the four radius endpoints; this is geometry, not autonomous patrol.
            for (BlockPos delta : List.of(new BlockPos(CLEAR_RADIUS, 0, 0), new BlockPos(-CLEAR_RADIUS, 0, 0),
                new BlockPos(0, 0, CLEAR_RADIUS), new BlockPos(0, 0, -CLEAR_RADIUS))) {
                BlockPos target = home.pos().offset(delta);
                Path path = npc.getNavigation().createPath(target, 0);
                helper.assertTrue(path != null && path.canReach(), "A real child navigation path reaches clear radius endpoint " + delta);
                helper.assertValueEqual(path.getEndNode().asBlockPos(), target, "The route reaches the requested flat cell");
                for (int i = 0; i < path.getNodeCount(); i++) {
                    helper.assertValueEqual(path.getNode(i).y, home.pos().getY(), "The short clearing path stays on the agreed flat layer");
                }
            }
            helper.assertValueEqual(npc.getHomeAnchor(), home, "Route probes do not reanchor the NPC");
        } finally { cleanup(helper); }
        helper.succeed();
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag(); for (double value : values) list.add(DoubleTag.valueOf(value)); return list;
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void editingUuidAndAbsolutePositionAreDiscardedOnIndependentPlacements(GameTestHelper helper) {
        Fixture first = fixture(helper, Mirror.NONE, Rotation.NONE, 0);
        CompoundTag edited = first.source.copy();
        CompoundTag authoredNpc = edited.getList("entities", Tag.TAG_COMPOUND).getCompound(0).getCompound("nbt");
        authoredNpc.putUUID("UUID", EDITOR_UUID);
        authoredNpc.put("Pos", doubles(12345.0, 123.0, -12345.0));
        CompoundTag editedWitness = edited.copy();
        StructureTemplate probe = new StructureTemplate();
        probe.load(helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), edited);
        CompoundTag probeWitness = probe.save(new CompoundTag());
        UUID firstUuid;
        GlobalPos firstHome;
        try {
            placeTemplate(helper, probe, first, bounds(helper)); SylphietteEntity npc = check(helper, first);
            helper.assertFalse(npc.getUUID().equals(EDITOR_UUID), "First placement discards the editor's entity UUID");
            firstUuid = npc.getUUID(); firstHome = npc.getHomeAnchor();
        } finally { cleanup(helper); }
        // Clear only this fixture's placed footprint before shifting the second independent site.
        BoundingBox footprint = first.template.getBoundingBox(first.origin, first.rotation, BlockPos.ZERO, first.mirror);
        for (BlockPos position : BlockPos.betweenClosed(footprint.minX(), footprint.minY(), footprint.minZ(), footprint.maxX(), footprint.maxY(), footprint.maxZ())) {
            helper.getLevel().setBlock(position, Blocks.AIR.defaultBlockState(), 18);
        }
        try {
            Fixture next = fixture(helper, Mirror.NONE, Rotation.NONE, 2);
            placeTemplate(helper, probe, next, bounds(helper)); SylphietteEntity npc = check(helper, next);
            helper.assertFalse(npc.getUUID().equals(EDITOR_UUID) || npc.getUUID().equals(firstUuid), "Second site receives its own fresh UUID");
            helper.assertFalse(npc.getHomeAnchor().equals(firstHome), "Second site owns its own distinct world home");
            helper.assertValueEqual(edited, editedWitness, "Template placement leaves the editor-input witness untouched");
            helper.assertValueEqual(probe.save(new CompoundTag()), probeWitness, "Placement does not mutate the reusable UUID fixture");
        } finally { cleanup(helper); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void registeredWorldgenLinksOneRepeatablePlainsMeetingPoint(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        Structure structure = registries.registryOrThrow(Registries.STRUCTURE).getOptional(STRUCTURE).orElseThrow();
        helper.assertTrue(structure instanceof SylphietteMeetingStructure, "The registered meeting point filters terrain before retaining vanilla pool pieces");
        helper.assertValueEqual(BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type()), OLD_STRUCTURE, "The H02 codec has its own registered type");
        helper.assertValueEqual(structure.terrainAdaptation(), TerrainAdjustment.NONE, "IF5 keeps the filtered clearing free of rectangular beard adaptation");
        helper.assertValueEqual(structure.step(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION, "The clearing places after this chunk's vegetal decoration; this registry assertion does not prove neighboring chunk order");
        JsonObject encoded = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow().getAsJsonObject();
        helper.assertValueEqual(encoded.get("start_pool").getAsString(), POOL.toString(), "The decoded structure links the delivered start pool");
        helper.assertValueEqual(encoded.get("biomes").getAsString(), "#jobless_reincarnation:has_structure/sylphiette_meeting", "The structure uses its own extensible biome tag");
        helper.assertValueEqual(encoded.get("type").getAsString(), OLD_STRUCTURE.toString(), "Structure serialization preserves the H02 type");
        var limits = ((SylphietteMeetingStructure)structure).terrainLimits();
        helper.assertValueEqual(limits.footprintRise(), 3, "The actual registered footprint rise threshold is frozen");
        helper.assertValueEqual(limits.bufferMargin(), 4, "The actual registered external sampling margin is frozen");
        helper.assertValueEqual(limits.bufferRise(), 5, "The actual registered external rise threshold is frozen");
        helper.assertValueEqual(limits.minBaseAboveSeaLevel(), 2, "IF5 uses a two-block base margin above the generator sea level");
        try (var reader = new InputStreamReader(helper.getLevel().getServer().getResourceManager().open(id("worldgen/structure/sylphiette_meeting_gentle.json")), StandardCharsets.UTF_8)) {
            JsonObject resource = JsonParser.parseReader(reader).getAsJsonObject();
            helper.assertValueEqual(resource.get("min_base_above_sea_level").getAsInt(), 2, "The delivered resource explicitly fixes the sea-level margin");
            helper.assertValueEqual(resource.get("terrain_adaptation").getAsString(), "none", "The delivered resource explicitly disables beard density changes");
            helper.assertValueEqual(resource.get("step").getAsString(), "top_layer_modification", "The delivered resource explicitly selects the late decoration step");
        } catch (IOException e) { throw new IllegalStateException("Cannot read delivered IF5 structure JSON", e); }
        var biomes = registries.registryOrThrow(Registries.BIOME);
        var tagged = biomes.getTag(TagKey.create(Registries.BIOME, id("has_structure/sylphiette_meeting"))).orElseThrow();
        for (var key : List.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS)) {
            var biome = biomes.getHolderOrThrow(key);
            helper.assertTrue(tagged.contains(biome) && structure.biomes().contains(biome), "Default plains biome is eligible: " + key.location());
        }
        StructureTemplatePool pool = registries.registryOrThrow(Registries.TEMPLATE_POOL).getOptional(POOL).orElseThrow();
        JsonObject poolJson = StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, pool).getOrThrow().getAsJsonObject();
        helper.assertValueEqual(poolJson.getAsJsonArray("elements").size(), 1, "The actual pool has one authored encounter entry");
        JsonObject element = poolJson.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element");
        helper.assertValueEqual(element.get("location").getAsString(), TEMPLATE.toString(), "Pool resolves to the delivered encounter template");
        helper.assertValueEqual(element.get("projection").getAsString(), "rigid", "NPC and clearing keep one shared placement transform");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).getOptional(id("greyrat_homes")).isEmpty(), "V01 no longer scatters separate homes");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).getOptional(id("sylphiette_meetings")).isEmpty(), "V01 no longer scatters separate tree encounters");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).containsKey(id("buena_village")), "Both scenes are now provided by the single village");
        helper.succeed();
    }

    /** A constructed v001 vanilla piece is saved after one true chunk slice, then loaded through the normal
     * StructureStart/registered-piece reader under H02. It is not a hand-reimplemented NBT decoder or a
     * promise about every historical world; the preserved v001 bytes and deferred old AIR behavior are exercised. */
    @GameTest(template = ARENA, batch = BATCH)
    public static void savedV001PieceKeepsOldTemplateAndFinishesDeferredChunkSlices(GameTestHelper helper) {
        try {
            try (InputStream stream = helper.getLevel().getServer().getResourceManager().open(id("structure/" + OLD_TEMPLATE.getPath() + ".nbt"))) {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
                helper.assertValueEqual(hash, "76bff0a0559a2472cdfd1d56296d37aafb746db56e96793b567be668f03fbe14", "The released 0.21 v001 resource is byte-identical");
            } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot hash released v001 resource", e); }
            ServerLevel level = helper.getLevel();
            Fixture next = fixture(helper, Mirror.NONE, Rotation.NONE, 0);
            CompoundTag oldSource = raw(helper, OLD_TEMPLATE);
            var registries = level.registryAccess();
            var oldPool = registries.registryOrThrow(Registries.TEMPLATE_POOL).getOptional(OLD_POOL).orElseThrow();
            JsonObject oldPoolJson = StructureTemplatePool.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), oldPool).getOrThrow().getAsJsonObject();
            helper.assertValueEqual(oldPoolJson.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element").get("location").getAsString(), OLD_TEMPLATE.toString(), "The original start pool still resolves to main");
            StructurePoolElement oldSelection = oldPool.getRandomTemplate(RandomSource.create(2108L));
            helper.assertTrue(oldSelection instanceof SinglePoolElement, "The preserved start pool still chooses a vanilla single element");
            SinglePoolElement oldElement = (SinglePoolElement)oldSelection;
            var oldPiece = new PoolElementStructurePiece(level.getStructureManager(), oldElement, next.origin,
                oldElement.getGroundLevelDelta(), next.rotation, oldElement.getBoundingBox(level.getStructureManager(), next.origin, next.rotation), LiquidSettings.APPLY_WATERLOGGING);
            Structure current = registries.registryOrThrow(Registries.STRUCTURE).getOptional(OLD_STRUCTURE).orElseThrow();
            helper.assertTrue(current instanceof JigsawStructure, "The old structure ID still resolves to the vanilla jigsaw type");
            helper.assertValueEqual(current.terrainAdaptation(), TerrainAdjustment.BEARD_THIN, "Old starts retain their original beard terrain semantics in future slices");
            helper.assertValueEqual(current.step(), GenerationStep.Decoration.SURFACE_STRUCTURES, "The preserved old structure keeps its original surface step");
            JsonObject oldJson = Structure.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), current).getOrThrow().getAsJsonObject();
            helper.assertValueEqual(oldJson.get("start_pool").getAsString(), OLD_POOL.toString(), "Old structure retains its original start pool");
            helper.assertValueEqual(oldJson.get("project_start_to_heightmap").getAsString(), "WORLD_SURFACE_WG", "Old vanilla generation fields remain decodable");
            ChunkPos startChunk = new ChunkPos(next.origin);
            StructureStart before = new StructureStart(current, startChunk, 0, new PiecesContainer(List.of(oldPiece)));
            BoundingBox arena = bounds(helper);
            BlockPos feet = BlockPos.containing(expected(next));
            ChunkPos npcChunk = new ChunkPos(feet);
            BoundingBox firstSlice = new BoundingBox(Math.max(npcChunk.getMinBlockX(), arena.minX()), arena.minY(), Math.max(npcChunk.getMinBlockZ(), arena.minZ()),
                Math.min(npcChunk.getMaxBlockX(), arena.maxX()), arena.maxY(), Math.min(npcChunk.getMaxBlockZ(), arena.maxZ()));
            Set<BlockPos> nextCells = new HashSet<>();
            // V003 restores v001 bytes; preserved v002 remains the independent reduced-AIR witness.
            ListTag nextBlocks = raw(helper, V002_TEMPLATE).getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < nextBlocks.size(); i++) {
                ListTag p = nextBlocks.getCompound(i).getList("pos", Tag.TAG_INT);
                nextCells.add(new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)));
            }
            BlockPos witness = null;
            ListTag oldBlocks = oldSource.getList("blocks", Tag.TAG_COMPOUND), oldPalette = oldSource.getList("palette", Tag.TAG_COMPOUND);
            for (int i = 0; i < oldBlocks.size(); i++) {
                CompoundTag block = oldBlocks.getCompound(i); ListTag p = block.getList("pos", Tag.TAG_INT);
                BlockPos local = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)), world = next.origin.offset(local);
                if (oldPalette.getCompound(block.getInt("state")).getString("Name").equals("minecraft:air")
                    && !nextCells.contains(local) && !firstSlice.isInside(world) && arena.isInside(world)) { witness = world; break; }
            }
            helper.assertTrue(witness != null, "A deferred chunk owns a real v001 AIR cell which v002 deliberately omits");
            level.setBlock(witness, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            before.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), RandomSource.create(2209L), firstSlice, npcChunk);
            helper.assertTrue(level.getBlockState(witness).is(Blocks.GOLD_BLOCK), "The first chunk cannot alter a deferred chunk's witness block");
            helper.assertValueEqual(residents(helper).size(), 1, "The first chunk owns exactly one old-template NPC");
            SylphietteEntity npc = residents(helper).getFirst(); UUID uuid = npc.getUUID(); GlobalPos home = savedAnchor(helper, npc);
            helper.assertValueEqual(home, GlobalPos.of(level.dimension(), feet), "The old-template NPC anchors at its actual feet");
            var context = StructurePieceSerializationContext.fromLevel(level);
            CompoundTag saved = before.createTag(context, startChunk);
            helper.assertValueEqual(saved.getString("id"), OLD_STRUCTURE.toString(), "Saved 0.21 starts retain the old structure resource ID");
            helper.assertValueEqual(saved.getList("Children", Tag.TAG_COMPOUND).getCompound(0).getString("id"), "minecraft:jigsaw", "The saved piece uses the unchanged vanilla serialization type");
            helper.assertValueEqual(saved.getList("Children", Tag.TAG_COMPOUND).getCompound(0).getCompound("pool_element").getString("location"), OLD_TEMPLATE.toString(), "The saved piece owns its old template reference independently from the changed start pool");
            StructureStart restored = StructureStart.loadStaticStart(context, saved, 42L);
            helper.assertTrue(restored != null && restored.isValid() && restored.getPieces().size() == 1
                && restored.getPieces().getFirst() instanceof PoolElementStructurePiece, "The standard start reader restores one real vanilla pool piece");
            helper.assertValueEqual(restored.getPieces().getFirst().createTag(context).getCompound("pool_element").getString("location"), OLD_TEMPLATE.toString(), "Normal reload retains main rather than silently substituting the active main_v004");
            helper.assertValueEqual(restored.getPieces().getFirst().getBoundingBox(), oldPiece.getBoundingBox(), "Old piece bounds and origin survive reload");
            boolean witnessPlaced = false;
            for (int x = Math.floorDiv(arena.minX(), 16) * 16; x <= arena.maxX(); x += 16) for (int z = Math.floorDiv(arena.minZ(), 16) * 16; z <= arena.maxZ(); z += 16) {
                ChunkPos chunk = new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (chunk.equals(npcChunk)) continue;
                BoundingBox clip = new BoundingBox(Math.max(x, arena.minX()), arena.minY(), Math.max(z, arena.minZ()), Math.min(x + 15, arena.maxX()), arena.maxY(), Math.min(z + 15, arena.maxZ()));
                restored.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), RandomSource.create(2209L), clip, chunk);
                witnessPlaced |= clip.isInside(witness);
                helper.assertTrue(witnessPlaced ? level.getBlockState(witness).isAir() : level.getBlockState(witness).is(Blocks.GOLD_BLOCK), "Only the owning deferred chunk applies the old template's original AIR record");
                helper.assertValueEqual(residents(helper).size(), 1, "Deferred chunks do not duplicate the old NPC");
                helper.assertValueEqual(residents(helper).getFirst().getUUID(), uuid, "The first slice's NPC identity remains stable");
                helper.assertValueEqual(savedAnchor(helper, residents(helper).getFirst()), home, "Finishing old slices does not move the NPC's saved home");
            }
            helper.assertTrue(witnessPlaced, "The deferred witness chunk was genuinely placed");
            helper.assertValueEqual(raw(helper, OLD_TEMPLATE), oldSource, "Old resource bytes are not edited by deferred placement");
            helper.assertValueEqual(raw(helper), next.source, "Active V004 resource is not edited by old-piece loading");
        } finally { cleanup(helper); }
        helper.succeed();
    }


    private static Map<BlockPos, BlockState> sourceStates(GameTestHelper helper, CompoundTag source) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        ListTag blocks = source.getList("blocks", Tag.TAG_COMPOUND), palette = source.getList("palette", Tag.TAG_COMPOUND);
        var lookup = helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag p = block.getList("pos", Tag.TAG_INT);
            BlockPos local = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            helper.assertTrue(states.put(local, NbtUtils.readBlockState(lookup, palette.getCompound(block.getInt("state")))) == null,
                "Each authored coordinate has exactly one block state");
        }
        return states;
    }
    private static boolean inProtectedSquare(BlockPos local) {
        return local.getX() >= 7 && local.getX() <= 13 && local.getZ() >= 10 && local.getZ() <= 16;
    }
    private static void assertProtectedSquare(GameTestHelper helper, Fixture f, SylphietteEntity npc) {
        for (int x = 7; x <= 13; x++) for (int z = 10; z <= 16; z++) {
            BlockPos support = world(f, new BlockPos(x, 2, z));
            helper.assertTrue(helper.getLevel().getBlockState(support).is(Blocks.GRASS_BLOCK), "Decoration preserves every full-height grass support in the protected 7 x 7");
            for (int y = 3; y <= 5; y++) {
                BlockPos body = world(f, new BlockPos(x, y, z));
                helper.assertTrue(helper.getLevel().getBlockState(body).isAir() && helper.getLevel().getFluidState(body).isEmpty(), "Decoration preserves all 147 dry authored AIR cells");
            }
            assertStandingBody(helper, npc, Vec3.atBottomCenterOf(support.above()), "protected decorated clearing " + x + "," + z);
        }
    }
    private static void assertDecoration(GameTestHelper helper, Fixture f, Map<BlockPos, BlockState> decoration, boolean updateShapes) {
        ServerLevel level = helper.getLevel();
        int paths = 0;
        for (var entry : decoration.entrySet()) {
            BlockPos local = entry.getKey(), pos = world(f, local);
            BlockState expectedState = entry.getValue().mirror(f.mirror).rotate(f.rotation), actual = level.getBlockState(pos);
            helper.assertFalse(inProtectedSquare(local), "Every new decorative cell stays outside the protected square");
            helper.assertValueEqual(actual, expectedState, "Native placement and scheduled updates retain authored decoration at " + local);
            if (actual.is(Blocks.DIRT_PATH)) {
                paths++;
                helper.assertTrue(actual.canSurvive(level, pos), "Path has valid native overhead clearance at " + local);
                helper.assertFalse(level.getBlockState(pos.above()).isSolid(), "No full solid decoration sits directly on a path");
                var shape = actual.getCollisionShape(level, pos);
                helper.assertFalse(shape.isEmpty(), "Path provides a physical surface");
                helper.assertValueEqual(shape.max(Direction.Axis.Y), 15.0 / 16.0, "Path retains its native lower collision height");
                if (updateShapes) {
                    // A path's scheduled tick unconditionally converts it to dirt. Only its native UP
                    // update is allowed to schedule that tick; never schedule one on a valid path by hand.
                    BlockState updated = actual.updateShape(Direction.UP, level.getBlockState(pos.above()), level, pos, pos.above());
                    level.setBlock(pos, updated, 18);
                }
            } else if (actual.getBlock() instanceof BushBlock) {
                helper.assertTrue(actual.canSurvive(level, pos), "The actual plant accepts its authored support at " + local);
                helper.assertFalse(level.getBlockState(pos.below()).is(Blocks.DIRT_PATH), "Flowers do not use a lowered dirt path as soil");
                helper.assertTrue(actual.getCollisionShape(level, pos).isEmpty(), "Small plants remain nonblocking decoration");
                if (updateShapes) {
                    BlockState updated = actual.updateShape(Direction.DOWN, level.getBlockState(pos.below()), level, pos, pos.below());
                    level.setBlock(pos, updated, 18);
                }
            }
        }
        helper.assertTrue(paths > 0, "The new template actually contains the requested peripheral paths");
    }
    private static void decorationSurvivalCase(GameTestHelper helper, Rotation rotation) {
        Fixture f = fixture(helper, Mirror.NONE, rotation, 0);
        Map<BlockPos, BlockState> oldStates = sourceStates(helper, raw(helper, V003_TEMPLATE)), nextStates = sourceStates(helper, f.source);
        helper.assertValueEqual(nextStates.keySet(), oldStates.keySet(), "Decoration retains the original authored footprint and omitted exterior");
        Map<BlockPos, BlockState> decoration = new HashMap<>();
        nextStates.forEach((position, state) -> {
            BlockState previous = oldStates.get(position);
            if (!state.equals(previous)) {
                helper.assertTrue((previous.is(Blocks.GRASS_BLOCK) && (state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT)))
                    || (previous.isAir() && state.getBlock() instanceof BushBlock),
                    "Only existing grass surfaces and peripheral plant AIR may change; landmark tree and foundation remain intact");
                decoration.put(position, state);
            }
        });
        UUID uuid;
        GlobalPos home;
        try {
            place(helper, f, bounds(helper));
            SylphietteEntity npc = check(helper, f);
            uuid = npc.getUUID(); home = savedAnchor(helper, npc);
            assertProtectedSquare(helper, f, npc);
            assertDecoration(helper, f, decoration, true);
        } catch (RuntimeException | Error failure) { cleanup(helper); throw failure; }
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertValueEqual(residents(helper).size(), 1, "Twenty normal ticks keep exactly one encounter NPC");
                SylphietteEntity npc = residents(helper).getFirst();
                helper.assertValueEqual(npc.getUUID(), uuid, "Decoration updates do not replace the NPC");
                helper.assertValueEqual(savedAnchor(helper, npc), home, "Decoration updates preserve the persistent home");
                helper.assertFalse(npc.isNoAi() || npc.isNoGravity(), "NPC keeps natural AI and gravity during the survival probe");
                assertProtectedSquare(helper, f, npc);
                assertDecoration(helper, f, decoration, false);
                helper.assertValueEqual(raw(helper), f.source, "Survival checks leave the active resource untouched");
            } finally { cleanup(helper); }
            helper.succeed();
        });
    }
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 60) public static void peripheralDecorationSurvivesUnrotated(GameTestHelper h) { decorationSurvivalCase(h, Rotation.NONE); }
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 60) public static void peripheralDecorationSurvivesClockwise(GameTestHelper h) { decorationSurvivalCase(h, Rotation.CLOCKWISE_90); }
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 60) public static void peripheralDecorationSurvivesHalfTurn(GameTestHelper h) { decorationSurvivalCase(h, Rotation.CLOCKWISE_180); }
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 60) public static void peripheralDecorationSurvivesCounterclockwise(GameTestHelper h) { decorationSurvivalCase(h, Rotation.COUNTERCLOCKWISE_90); }

    /** A real 0.22-style gentle start stores a vanilla jigsaw piece with main_v003. Loading that
     * saved start after the start pool selects v004 must finish its remaining chunks with v003. */
    @GameTest(template = ARENA, batch = BATCH)
    public static void savedV003PieceKeepsOldDecorationAndFinishesDeferredChunkSlices(GameTestHelper helper) {
        try {
            try (InputStream stream = helper.getLevel().getServer().getResourceManager().open(id("structure/" + V003_TEMPLATE.getPath() + ".nbt"))) {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
                helper.assertValueEqual(hash, "76bff0a0559a2472cdfd1d56296d37aafb746db56e96793b567be668f03fbe14", "The released 0.22 v003 resource is byte-identical");
            } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot hash released v003 resource", e); }
            ServerLevel level = helper.getLevel();
            Fixture next = fixture(helper, Mirror.NONE, Rotation.NONE, 0);
            CompoundTag oldSource = raw(helper, V003_TEMPLATE);
            Map<BlockPos, BlockState> oldStates = sourceStates(helper, oldSource), nextStates = sourceStates(helper, next.source);
            ChunkPos initialNpcChunk = new ChunkPos(BlockPos.containing(expected(next)));
            boolean initialHasWitness = false;
            for (BlockPos local : oldStates.keySet()) {
                BlockState nextState = nextStates.get(local);
                if (oldStates.get(local).is(Blocks.GRASS_BLOCK) && nextState != null && nextState.is(Blocks.DIRT_PATH)
                    && !new ChunkPos(world(next, local)).equals(initialNpcChunk)) { initialHasWitness = true; break; }
            }
            // The test arena can start at any chunk alignment. If all new paths share the NPC's
            // first chunk, shift the whole fixture three blocks: local arena X remains 6..28
            // inside 1..38. The strict deferred-cell assertion below is still mandatory.
            if (!initialHasWitness) next = fixture(helper, Mirror.NONE, Rotation.NONE, 3);
            SinglePoolElement oldElement = StructurePoolElement.single(V003_TEMPLATE.toString()).apply(StructureTemplatePool.Projection.RIGID);
            var oldPiece = new PoolElementStructurePiece(level.getStructureManager(), oldElement, next.origin,
                oldElement.getGroundLevelDelta(), next.rotation, oldElement.getBoundingBox(level.getStructureManager(), next.origin, next.rotation), LiquidSettings.APPLY_WATERLOGGING);
            Structure current = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOptional(STRUCTURE).orElseThrow();
            helper.assertTrue(current instanceof SylphietteMeetingStructure, "Saved 0.22 gentle starts retain their registered structure type");
            helper.assertValueEqual(current.terrainAdaptation(), TerrainAdjustment.NONE, "Saved gentle starts retain the unmodified terrain behavior");
            helper.assertValueEqual(current.step(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION, "Saved gentle starts retain the late decoration step");
            var context = StructurePieceSerializationContext.fromLevel(level);
            JsonObject activeElement = StructurePoolElement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), next.pool).getOrThrow().getAsJsonObject();
            helper.assertValueEqual(activeElement.get("location").getAsString(), TEMPLATE.toString(), "The current start pool really selects v004 while this saved piece selects v003");
            ChunkPos startChunk = new ChunkPos(next.origin), npcChunk = new ChunkPos(BlockPos.containing(expected(next)));
            StructureStart before = new StructureStart(current, startChunk, 0, new PiecesContainer(List.of(oldPiece)));
            BoundingBox arena = bounds(helper);
            BoundingBox firstSlice = new BoundingBox(Math.max(npcChunk.getMinBlockX(), arena.minX()), arena.minY(), Math.max(npcChunk.getMinBlockZ(), arena.minZ()),
                Math.min(npcChunk.getMaxBlockX(), arena.maxX()), arena.maxY(), Math.min(npcChunk.getMaxBlockZ(), arena.maxZ()));
            BlockPos witnessLocal = null;
            // Select a real changed cell outside the first owning chunk, not an artificial miniature.
            for (BlockPos local : oldStates.keySet()) {
                BlockState nextState = nextStates.get(local);
                if (oldStates.get(local).is(Blocks.GRASS_BLOCK) && nextState != null && nextState.is(Blocks.DIRT_PATH)
                    && !firstSlice.isInside(world(next, local))) { witnessLocal = local; break; }
            }
            helper.assertTrue(witnessLocal != null, "A genuine old grass/new path difference belongs to a deferred chunk");
            BlockPos witness = world(next, witnessLocal);
            BlockState oldState = oldStates.get(witnessLocal), newState = nextStates.get(witnessLocal);
            level.setBlock(witness, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            before.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), RandomSource.create(2212L), firstSlice, npcChunk);
            helper.assertTrue(level.getBlockState(witness).is(Blocks.GOLD_BLOCK), "The first chunk leaves the deferred decoration witness untouched");
            helper.assertValueEqual(residents(helper).size(), 1, "The first old-template chunk places exactly one NPC");
            SylphietteEntity npc = residents(helper).getFirst();
            UUID uuid = npc.getUUID(); GlobalPos home = savedAnchor(helper, npc);
            helper.assertValueEqual(home, GlobalPos.of(level.dimension(), BlockPos.containing(expected(next))), "The v003 NPC anchors at its actual transformed feet");
            CompoundTag saved = before.createTag(context, startChunk);
            helper.assertValueEqual(saved.getString("id"), STRUCTURE.toString(), "The saved 0.22 start owns the gentle structure ID");
            CompoundTag savedPiece = saved.getList("Children", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertValueEqual(savedPiece.getString("id"), "minecraft:jigsaw", "The saved 0.22 piece uses native vanilla jigsaw serialization");
            helper.assertValueEqual(savedPiece.getCompound("pool_element").getString("location"), V003_TEMPLATE.toString(), "Saved pieces own main_v003 independently from today's start pool");
            StructureStart restored = StructureStart.loadStaticStart(context, saved, 42L);
            helper.assertTrue(restored != null && restored.isValid() && restored.getPieces().size() == 1
                && restored.getPieces().getFirst() instanceof PoolElementStructurePiece, "The native structure-start reader restores the old vanilla piece");
            helper.assertValueEqual(restored.getPieces().getFirst().createTag(context).getCompound("pool_element").getString("location"), V003_TEMPLATE.toString(), "Loading retains main_v003 rather than upgrading it to main_v004");
            helper.assertValueEqual(restored.getPieces().getFirst().getBoundingBox(), oldPiece.getBoundingBox(), "Saved v003 bounds remain stable");
            boolean witnessPlaced = false;
            for (int x = Math.floorDiv(arena.minX(), 16) * 16; x <= arena.maxX(); x += 16) for (int z = Math.floorDiv(arena.minZ(), 16) * 16; z <= arena.maxZ(); z += 16) {
                ChunkPos chunk = new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (chunk.equals(npcChunk)) continue;
                BoundingBox clip = new BoundingBox(Math.max(x, arena.minX()), arena.minY(), Math.max(z, arena.minZ()), Math.min(x + 15, arena.maxX()), arena.maxY(), Math.min(z + 15, arena.maxZ()));
                restored.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), RandomSource.create(2212L), clip, chunk);
                witnessPlaced |= clip.isInside(witness);
                helper.assertValueEqual(level.getBlockState(witness), witnessPlaced ? oldState : Blocks.GOLD_BLOCK.defaultBlockState(), "Only the owning deferred chunk applies the old grass state");
                helper.assertValueEqual(residents(helper).size(), 1, "Completing old chunks does not duplicate the NPC");
                helper.assertValueEqual(residents(helper).getFirst().getUUID(), uuid, "The first chunk's NPC UUID remains stable");
                helper.assertValueEqual(savedAnchor(helper, residents(helper).getFirst()), home, "Completing old chunks does not move its persistent home");
            }
            helper.assertTrue(witnessPlaced, "The selected old/new difference was genuinely placed from the loaded start");
            helper.assertFalse(level.getBlockState(witness).equals(newState), "An old deferred slice does not acquire the new peripheral path");
            helper.assertValueEqual(raw(helper, V003_TEMPLATE), oldSource, "Deferred placement leaves the released v003 resource untouched");
            helper.assertValueEqual(raw(helper), next.source, "Deferred placement leaves the new v004 resource untouched");
            assertProtectedSquare(helper, next, residents(helper).getFirst());
        } finally { cleanup(helper); }
        helper.succeed();
    }

    private SylphietteStructureGameTests() { }
}
