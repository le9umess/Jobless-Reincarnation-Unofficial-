package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * IF2 resident integration checks. The agreed anchor keys and template spawn
 * coordinates are explicit below; release-server and visual checks are recorded separately.
 * Uses the actual delivered template. These are deliberate main-thread placements, not evidence
 * of asynchronous natural worldgen, on-disk chunk reload or client rendering.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HouseFamilyGameTests {
    private static final String ARENA = "house_v2_empty", BATCH = "familyM02";
    private static final ResourceLocation TEMPLATE = id("greyrat_home/main_v2"), POOL = id("greyrat_home/start");
    private static final Map<String, Vec3> POSITIONS = Map.of(
        "roxy_tutor", new Vec3(12.5, 1, 28.5),
        "paul_greyrat", new Vec3(24.5, 1, 27.5),
        "zenith_greyrat", new Vec3(22.5, 1, 11.5),
        "lilia_greyrat", new Vec3(9.5, 1, 8.5));
    private static final Map<String, Float> YAWS = Map.of("roxy_tutor", 0F, "paul_greyrat", 180F, "zenith_greyrat", -90F, "lilia_greyrat", -90F);

    private record Fixture(StructureTemplate template, SinglePoolElement pool, CompoundTag source,
                           CompoundTag templateWitness, BlockPos origin, Mirror mirror, Rotation rotation) { }
    private record Saved(String role, CompoundTag tag, UUID uuid, GlobalPos home, Vec3 position) { }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path); }
    private static String role(Entity entity) { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(); }
    private static boolean family(Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type.getNamespace().equals(JoblessReincarnation.MOD_ID) && POSITIONS.containsKey(type.getPath());
    }
    private static BoundingBox bounds(GameTestHelper helper) {
        return BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 0, 1)), helper.absolutePos(new BlockPos(44, 23, 44)));
    }
    private static AABB area(GameTestHelper helper) {
        return new AABB(helper.absoluteVec(new Vec3(1, 0, 1)), helper.absoluteVec(new Vec3(45, 24, 45)));
    }
    private static List<PathfinderMob> residents(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(PathfinderMob.class, area(helper), e -> !e.isRemoved() && family(e));
    }
    private static void cleanup(GameTestHelper helper) { residents(helper).forEach(Entity::discard); }
    private static CompoundTag save(GameTestHelper helper, PathfinderMob mob) {
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(mob.save(tag), "Registered resident participates in ordinary entity saving: " + role(mob));
        return tag;
    }

    /** Exact IF2 save contract; legacy Roxy and the new family keep distinct existing keys. */
    private static GlobalPos anchor(GameTestHelper helper, CompoundTag saved) {
        ResourceLocation type = ResourceLocation.parse(saved.getString("id"));
        helper.assertTrue(type.getNamespace().equals(JoblessReincarnation.MOD_ID) && POSITIONS.containsKey(type.getPath()),
            "Only a registered approved resident reaches the anchor adapter");
        String key = type.getPath().equals("roxy_tutor") ? "JR_TutorAnchor" : "JR_HomeAnchor";
        helper.assertTrue(saved.contains(key, Tag.TAG_COMPOUND), "The resident persists the exact agreed anchor tag: " + key);
        return GlobalPos.CODEC.parse(NbtOps.INSTANCE, saved.getCompound(key)).result()
            .orElseThrow(() -> new IllegalStateException("Malformed persistent home under " + key));
    }

    private static CompoundTag raw(GameTestHelper helper) {
        try (InputStream stream = helper.getLevel().getServer().getResourceManager().open(id("structure/greyrat_home/main_v2.nbt"))) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException e) { throw new IllegalStateException("Cannot read delivered home template", e); }
    }
    private static Fixture fixture(GameTestHelper helper, Mirror mirror, Rotation rotation, int xShift) {
        StructureTemplate template = helper.getLevel().getStructureManager().get(TEMPLATE).orElseThrow();
        helper.assertValueEqual(template.getSize(), new BlockPos(33, 20, 39), "Resident integration preserves house dimensions");
        CompoundTag source = raw(helper);
        ListTag list = source.getList("entities", Tag.TAG_COMPOUND);
        helper.assertValueEqual(list.size(), POSITIONS.size(), "Delivered template has exactly four residents");
        Set<String> types = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag record = list.getCompound(i), nbt = record.getCompound("nbt");
            String fullId = nbt.getString("id");
            helper.assertTrue(fullId.startsWith(JoblessReincarnation.MOD_ID + ":"), "Every template resident is a formal mod type");
            String type = ResourceLocation.parse(fullId).getPath();
            helper.assertTrue(POSITIONS.containsKey(type) && types.add(type), "Exactly one entry for each approved person: " + type);
            helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(id(type)), "Resident entity type is actually registered: " + type);
            for (String forbidden : List.of("UUID", "Passengers", "Motion", "NoAI", "Brain", "Leash", "PortalCooldown", "JR_TutorAnchor", "JR_HomeAnchor")) {
                helper.assertFalse(nbt.contains(forbidden), "Reusable resident has no stale " + forbidden);
            }
            for (String key : nbt.getAllKeys()) {
                helper.assertFalse(key.contains("Anchor") || key.contains("Conversation"), "Template cannot bake a world home or active conversation");
                if (nbt.contains(key, Tag.TAG_COMPOUND)) helper.assertFalse(GlobalPos.CODEC.parse(NbtOps.INSTANCE, nbt.getCompound(key)).result().isPresent(), "Template has no baked GlobalPos");
            }
            ListTag facing = nbt.getList("Rotation", Tag.TAG_FLOAT);
            helper.assertValueEqual(facing.size(), 2, "Every template resident has yaw and pitch");
            helper.assertValueEqual(facing.getFloat(0), YAWS.get(type), "The final agreed local yaw is stored for " + type);
            helper.assertValueEqual(facing.getFloat(1), 0F, "Residents start without a baked head pitch");
            ListTag pos = record.getList("pos", Tag.TAG_DOUBLE), block = record.getList("blockPos", Tag.TAG_INT);
            Vec3 expected = POSITIONS.get(type);
            helper.assertValueEqual(new Vec3(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)), expected, "Agreed local feet position: " + type);
            helper.assertValueEqual(new BlockPos(block.getInt(0), block.getInt(1), block.getInt(2)), BlockPos.containing(expected), "The slicing owner is the feet block: " + type);
        }
        StructurePoolElement entry = helper.getLevel().registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getOptional(POOL).orElseThrow().getRandomTemplate(RandomSource.create(2107L));
        helper.assertTrue(entry instanceof SinglePoolElement, "Registered pool keeps ordinary SinglePoolElement placement");
        // Translate the block footprint into the 46x24x46 arena for every mirror/rotation.
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int x : new int[] {0, 32}) for (int z : new int[] {0, 38}) {
            BlockPos corner = StructureTemplate.transform(new BlockPos(x, 0, z), mirror, rotation, BlockPos.ZERO);
            minX = Math.min(minX, corner.getX()); minZ = Math.min(minZ, corner.getZ());
        }
        BlockPos origin = helper.absolutePos(new BlockPos(3 - minX + xShift, 0, 3 - minZ));
        BoundingBox footprint = template.getBoundingBox(origin, rotation, BlockPos.ZERO, mirror);
        BoundingBox arena = bounds(helper);
        helper.assertTrue(arena.isInside(new BlockPos(footprint.minX(), footprint.minY(), footprint.minZ()))
            && arena.isInside(new BlockPos(footprint.maxX(), footprint.maxY(), footprint.maxZ())), "Every house block stays within this isolated test arena");
        return new Fixture(template, (SinglePoolElement)entry, source, template.save(new CompoundTag()), origin, mirror, rotation);
    }

    /** Independent entity-coordinate formula includes vanilla's one-block pivot offset, unlike block rotation. */
    private static Vec3 expected(Fixture fixture, String role) {
        Vec3 local = POSITIONS.get(role); double x = local.x, z = local.z;
        if (fixture.mirror == Mirror.FRONT_BACK) x = 1.0 - x;
        else if (fixture.mirror == Mirror.LEFT_RIGHT) z = 1.0 - z;
        Vec3 transformed = switch (fixture.rotation) {
            case NONE -> new Vec3(x, local.y, z);
            case CLOCKWISE_90 -> new Vec3(1.0 - z, local.y, x);
            case CLOCKWISE_180 -> new Vec3(1.0 - x, local.y, 1.0 - z);
            case COUNTERCLOCKWISE_90 -> new Vec3(z, local.y, 1.0 - x);
        };
        return Vec3.atLowerCornerOf(fixture.origin).add(transformed);
    }
    private static void place(GameTestHelper helper, Fixture f, BoundingBox clip) {
        ServerLevel level = helper.getLevel();
        if (f.mirror == Mirror.NONE) {
            helper.assertTrue(f.pool.place(level.getStructureManager(), level, level.structureManager(), level.getChunkSource().getGenerator(), f.origin, f.origin, f.rotation, clip, RandomSource.create(217L), LiquidSettings.IGNORE_WATERLOGGING, false), "Registered pool accepts actual house placement");
        } else {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true).setKnownShape(true).setMirror(f.mirror).setRotation(f.rotation).setBoundingBox(clip);
            helper.assertTrue(f.template.placeInWorld(level, f.origin, f.origin, settings, RandomSource.create(217L), 18), "Actual template accepts mirrored placement");
        }
    }
    private static Map<String, PathfinderMob> check(GameTestHelper helper, Fixture f, Set<String> expectedRoles) {
        List<PathfinderMob> all = residents(helper);
        helper.assertValueEqual(all.size(), expectedRoles.size(), "Placement produces exactly the residents owned by completed slices");
        Map<String, PathfinderMob> result = new HashMap<>(); Set<UUID> uuids = new HashSet<>();
        for (PathfinderMob mob : all) {
            String role = role(mob); Vec3 target = expected(f, role);
            helper.assertTrue(expectedRoles.contains(role) && result.put(role, mob) == null, "One entity per intended resident role");
            helper.assertTrue(uuids.add(mob.getUUID()), "Every resident has a distinct actual UUID");
            helper.assertTrue(mob.position().distanceToSqr(target) < 1.0E-12, "Position follows full entity transform for " + role);
            helper.assertTrue(helper.getLevel().getEntity(mob.getUUID()) == mob, "Actual server index owns the live resident");
            helper.assertFalse(mob.isNoAi(), "Resident retains normal AI");
            helper.assertTrue(mob.shouldBeSaved() && mob.isPersistenceRequired() && !mob.removeWhenFarAway(1000000), "Resident uses persistent normal storage");
            helper.assertValueEqual(anchor(helper, save(helper, mob)), GlobalPos.of(helper.getLevel().dimension(), BlockPos.containing(target)), "Home begins at supported transformed feet position");
            helper.assertValueEqual(mob.getRestrictCenter(), BlockPos.containing(target), "Navigation uses that resident's own new home");
            helper.assertTrue(helper.getLevel().noCollision(mob, mob.getBoundingBox()), "New resident collision body clears actual architecture");
            helper.assertTrue(helper.getLevel().getBlockCollisions(mob, mob.getBoundingBox().move(0, -0.05, 0)).iterator().hasNext(), "New resident has actual support");
        }
        helper.assertValueEqual(raw(helper), f.source, "Placement never modifies the on-disk resource document");
        helper.assertValueEqual(f.template.save(new CompoundTag()), f.templateWitness, "Placement never mutates cached source entity data");
        return result;
    }
    private static void rotationCase(GameTestHelper helper, Rotation rotation) {
        try { Fixture f = fixture(helper, Mirror.NONE, rotation, 0); place(helper, f, bounds(helper)); check(helper, f, POSITIONS.keySet()); }
        finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void allFourResidentsUnrotated(GameTestHelper h) { rotationCase(h, Rotation.NONE); }
    @GameTest(template = ARENA, batch = BATCH) public static void allFourResidentsClockwise(GameTestHelper h) { rotationCase(h, Rotation.CLOCKWISE_90); }
    @GameTest(template = ARENA, batch = BATCH) public static void allFourResidentsHalfTurn(GameTestHelper h) { rotationCase(h, Rotation.CLOCKWISE_180); }
    @GameTest(template = ARENA, batch = BATCH) public static void allFourResidentsCounterclockwise(GameTestHelper h) { rotationCase(h, Rotation.COUNTERCLOCKWISE_90); }
    private static void mirrorCase(GameTestHelper helper, Mirror mirror) {
        for (Rotation rotation : Rotation.values()) {
            try { Fixture f = fixture(helper, mirror, rotation, 0); place(helper, f, bounds(helper)); check(helper, f, POSITIONS.keySet()); }
            finally { cleanup(helper); }
        }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void leftRightMirroredResidentsKeepOwnHomes(GameTestHelper h) { mirrorCase(h, Mirror.LEFT_RIGHT); }
    @GameTest(template = ARENA, batch = BATCH) public static void frontBackMirroredResidentsKeepOwnHomes(GameTestHelper h) { mirrorCase(h, Mirror.FRONT_BACK); }

    private static void slicedCase(GameTestHelper helper, boolean reverse) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.NONE, 0);
            BoundingBox arena = bounds(helper); List<BoundingBox> slices = new ArrayList<>();
            for (int x = Math.floorDiv(arena.minX(), 16) * 16; x <= arena.maxX(); x += 16) {
                for (int z = Math.floorDiv(arena.minZ(), 16) * 16; z <= arena.maxZ(); z += 16) {
                    slices.add(new BoundingBox(Math.max(x, arena.minX()), arena.minY(), Math.max(z, arena.minZ()), Math.min(x + 15, arena.maxX()), arena.maxY(), Math.min(z + 15, arena.maxZ())));
                }
            }
            if (reverse) Collections.reverse(slices);
            Set<String> owned = new HashSet<>(); Map<String, UUID> identities = new HashMap<>();
            for (BoundingBox slice : slices) {
                place(helper, f, slice);
                for (String role : POSITIONS.keySet()) if (slice.isInside(BlockPos.containing(expected(f, role)))) owned.add(role);
                // Some current bodies can overlap a not-yet-placed neighbouring slice; final support/
                // collision is checked after all slices, identity/count after every slice.
                List<PathfinderMob> live = residents(helper);
                helper.assertValueEqual(live.size(), owned.size(), "Each disjoint real chunk-boundary slice places only its own residents");
                Set<String> roles = new HashSet<>();
                for (PathfinderMob mob : live) {
                    String role = role(mob);
                    helper.assertTrue(owned.contains(role) && roles.add(role), "No role is duplicated across slice boundaries");
                    UUID prior = identities.putIfAbsent(role, mob.getUUID());
                    if (prior != null) helper.assertValueEqual(mob.getUUID(), prior, "Later slices never replace a previously inserted entity");
                }
            }
            check(helper, f, POSITIONS.keySet());
        } finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH) public static void realChunkBoundariesForwardKeepOneOfEach(GameTestHelper h) { slicedCase(h, false); }
    @GameTest(template = ARENA, batch = BATCH) public static void realChunkBoundariesReverseKeepOneOfEach(GameTestHelper h) { slicedCase(h, true); }

    @GameTest(template = ARENA, batch = BATCH) public static void ordinarySaveReloadRetainsFourIdentitiesAndHomes(GameTestHelper helper) {
        List<Saved> saved = new ArrayList<>();
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.CLOCKWISE_90, 0);
            place(helper, f, bounds(helper)); Map<String, PathfinderMob> first = check(helper, f, POSITIONS.keySet());
            for (PathfinderMob mob : first.values()) {
                GlobalPos home = anchor(helper, save(helper, mob));
                // Preserve home even though the ordinary saved position has changed.
                mob.moveTo(mob.position().add(0.25, 0, 0));
                CompoundTag tag = save(helper, mob);
                saved.add(new Saved(role(mob), tag.copy(), mob.getUUID(), home, mob.position()));
                mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            }
        } catch (RuntimeException | Error failure) { cleanup(helper); throw failure; }
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(residents(helper).isEmpty(), "Unloading residents does not trigger population replacement");
                for (Saved witness : saved) {
                    CompoundTag loadTag = witness.tag.copy(), pristine = loadTag.copy();
                    Entity loaded = EntityType.loadEntityRecursive(loadTag, helper.getLevel(), value -> value);
                    helper.assertTrue(loaded instanceof PathfinderMob && family(loaded), "Vanilla entity load resolves the same registered family type");
                    PathfinderMob mob = (PathfinderMob)loaded;
                    helper.assertValueEqual(mob.getUUID(), witness.uuid, "Ordinary reload preserves identity");
                    helper.assertValueEqual(mob.position(), witness.position, "Ordinary reload preserves moved position separately from home");
                    helper.assertValueEqual(anchor(helper, save(helper, mob)), witness.home, "Saved home is restored before join");
                    helper.assertTrue(helper.getLevel().addFreshEntity(mob), "The restored resident enters the live index exactly once");
                    helper.assertValueEqual(anchor(helper, save(helper, mob)), witness.home, "Join never reanchors an already valid saved home");
                    helper.assertValueEqual(mob.getRestrictCenter(), witness.home.pos(), "Rejoined AI retains its persisted own restriction");
                    helper.assertValueEqual(loadTag, pristine, "Vanilla load leaves its input save document unchanged");
                }
                helper.assertValueEqual(residents(helper).size(), 4, "Exactly four original entities after normal save/load; no template re-placement");
            } finally { cleanup(helper); }
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = BATCH) public static void successiveDistinctHousePlacementsNeverReuseResidents(GameTestHelper helper) {
        // Two full 33x39 houses cannot fit side by side in this 46x46 arena. Compare successive
        // distinct placement sites, preserving the first group's serialized witnesses. A real
        // server with two simultaneously existing natural houses remains a separate acceptance.
        Map<String, Saved> first = new LinkedHashMap<>();
        try {
            Fixture a = fixture(helper, Mirror.NONE, Rotation.NONE, 0); place(helper, a, bounds(helper));
            for (PathfinderMob mob : check(helper, a, POSITIONS.keySet()).values()) {
                CompoundTag tag = save(helper, mob);
                first.put(role(mob), new Saved(role(mob), tag.copy(), mob.getUUID(), anchor(helper, tag), mob.position()));
            }
        } finally { cleanup(helper); }
        helper.runAfterDelay(2, () -> {
            try {
                Fixture b = fixture(helper, Mirror.NONE, Rotation.NONE, 2); place(helper, b, bounds(helper));
                Map<String, PathfinderMob> next = check(helper, b, POSITIONS.keySet());
                Set<UUID> oldIds = new HashSet<>(); first.values().forEach(v -> oldIds.add(v.uuid));
                for (PathfinderMob mob : next.values()) {
                    Saved original = first.get(role(mob));
                    helper.assertFalse(oldIds.contains(mob.getUUID()), "Another home never reuses any first home's UUID");
                    helper.assertFalse(anchor(helper, save(helper, mob)).equals(original.home), "Each later resident has its own distinct site anchor");
                    helper.assertValueEqual(anchor(helper, original.tag), original.home, "Second placement cannot mutate first home's saved state");
                }
            } finally { cleanup(helper); }
            helper.succeed();
        });
    }
    private static BlockPos world(Fixture f, BlockPos local) {
        return StructureTemplate.transform(local, f.mirror, f.rotation, BlockPos.ZERO).offset(f.origin);
    }
    private static void openEntrance(GameTestHelper helper, Fixture f, BlockPos local) {
        BlockPos position = world(f, local); BlockState state = helper.getLevel().getBlockState(position);
        helper.assertTrue(state.getBlock() instanceof DoorBlock, "Actual house has its expected entrance door");
        ((DoorBlock)state.getBlock()).setOpen(null, helper.getLevel(), state, position, true);
        helper.assertTrue(helper.getLevel().getBlockState(position).getValue(DoorBlock.OPEN), "Actual entrance opens normally");
    }
    private static void assertBodyDimensions(GameTestHelper helper, PathfinderMob mob) {
        helper.assertTrue(Math.abs(mob.getBbWidth() - 0.6F) < 1.0E-6 && Math.abs(mob.getBbHeight() - 1.8F) < 1.0E-6,
            "IF2 family collision body is 0.6 by 1.8; visual hair height is a separate renderer check");
        helper.assertTrue(Math.abs(mob.getEyeHeight() - 1.62F) < 1.0E-6, "Standing family eye height matches IF2");
    }
    private static void assertBodyAt(GameTestHelper helper, PathfinderMob mob, Vec3 feet, String label) {
        AABB body = mob.getBoundingBox().move(feet.subtract(mob.position()));
        helper.assertFalse(helper.getLevel().getBlockCollisions(mob, body).iterator().hasNext(), "Actual registered collision body clears " + label);
        helper.assertTrue(helper.getLevel().getBlockCollisions(mob, body.move(0, -0.05, 0)).iterator().hasNext(), "Actual registered collision body is supported at " + label);
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void familyCollisionBodiesClearOpenDoorwaysAndBothHalvesOfEveryStair(GameTestHelper helper) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.NONE, 0); place(helper, f, bounds(helper));
            Map<String, PathfinderMob> all = check(helper, f, POSITIONS.keySet());
            openEntrance(helper, f, new BlockPos(16, 1, 16)); openEntrance(helper, f, new BlockPos(9, 1, 4));
            for (String role : List.of("paul_greyrat", "zenith_greyrat", "lilia_greyrat")) {
                PathfinderMob mob = all.get(role); assertBodyDimensions(helper, mob);
                for (BlockPos doorway : List.of(new BlockPos(16, 1, 16), new BlockPos(9, 1, 4))) {
                    assertBodyAt(helper, mob, Vec3.atBottomCenterOf(world(f, doorway)), role + " at entrance " + doorway);
                }
                for (int step = 0; step < 4; step++) for (double localZ : new double[] {0.85, 0.35}) {
                    // Same actual lower/upper tread sampling as the existing H01 geometry test.
                    // The wider z offset avoids intersecting the next riser at the lower sample.
                    double surface = step + 1 + (localZ > 0.5 ? 0.5 : 1.0);
                    Vec3 feet = Vec3.atLowerCornerOf(f.origin).add(15.5, surface, 13 - step + localZ);
                    assertBodyAt(helper, mob, feet, role + " on stair " + step + " half " + localZ);
                }
            }
        } finally { cleanup(helper); }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void eachFamilyTypeCanPlanRoutesThroughOpenedHouseWhenDeliberatelyProbed(GameTestHelper helper) {
        try {
            Fixture f = fixture(helper, Mirror.NONE, Rotation.NONE, 0); place(helper, f, bounds(helper));
            Map<String, PathfinderMob> all = check(helper, f, POSITIONS.keySet());
            openEntrance(helper, f, new BlockPos(16, 1, 16)); openEntrance(helper, f, new BlockPos(9, 1, 4));
            BlockPos hall = world(f, new BlockPos(16, 1, 14));
            BlockState surface = helper.getLevel().getBlockState(hall);
            double carpet = surface.getBlock() instanceof CarpetBlock ? surface.getCollisionShape(helper.getLevel(), hall).max(Direction.Axis.Y) : 0.0;
            List<BlockPos> targets = List.of(new BlockPos(11, 1, 6), new BlockPos(10, 1, 13), new BlockPos(21, 1, 7),
                new BlockPos(21, 1, 11), new BlockPos(16, 5, 9), new BlockPos(10, 5, 6), new BlockPos(9, 1, 2), new BlockPos(16, 1, 25));
            for (String role : List.of("paul_greyrat", "zenith_greyrat", "lilia_greyrat")) {
                PathfinderMob mob = all.get(role); assertBodyDimensions(helper, mob);
                GlobalPos originalHome = anchor(helper, save(helper, mob));
                // Deliberate navigation/collision probe, not a claim that their normal 4/5-block
                // home goals patrol upstairs. No server tick advances while this test rearranges them.
                // Spawning gives follow range a random bonus of up to 11.485% either way, and the path search budget follows it. Test the lowest case every time.
                var range = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
                range.removeModifiers();
                range.setBaseValue(range.getBaseValue() * (1.0 - 0.11485));
                mob.setNoAi(true); mob.clearRestriction();
                mob.moveTo(Vec3.atBottomCenterOf(hall).add(0, carpet, 0)); mob.setOnGround(true);
                assertBodyAt(helper, mob, mob.position(), role + " on the actual hallway carpet");
                for (BlockPos local : targets) {
                    BlockPos target = world(f, local); Path route = mob.getNavigation().createPath(target, 0);
                    helper.assertTrue(route != null && route.canReach(), "This actual family type can plan a complete physical route to " + local);
                    helper.assertValueEqual(route.getEndNode().asBlockPos(), target, "The route reaches its actual intended floor cell");
                    if (local.equals(new BlockPos(16, 5, 9))) {
                        helper.assertTrue(route.getNodeCount() >= 5 && route.getEndNode().y - route.getNode(0).y >= 3, "Upstairs path uses a real ascent");
                        for (int i = 1; i < route.getNodeCount(); i++) helper.assertTrue(Math.abs(route.getNode(i).y - route.getNode(i - 1).y) <= 1, "Stair route has no impossible multiblock rise");
                    }
                }
                helper.assertValueEqual(anchor(helper, save(helper, mob)), originalHome, "Probe movement and temporary restriction clearing do not rewrite the persistent home");
            }
        } finally { cleanup(helper); }
        helper.succeed();
    }
    private HouseFamilyGameTests() { }
}
