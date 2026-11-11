package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import dev.joblessreincarnation.worldgen.GreyratHomeStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the delivered house resource through its registered pool, not a replacement miniature fixture.
 * These are deliberate pool placements in a test arena, not evidence of natural new-chunk generation.
 * Interface coordinates follow the author's v5 layout and are centralized below for explicit review.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HouseStructureGameTests {
    private static final String ARENA = "house_v2_empty";
    private static final String BATCH = "houseH01";
    private static final int SIZE_X = 33, SIZE_Y = 20, SIZE_Z = 39;
    private static final int ARENA_SIZE = 46, ARENA_HEIGHT = 24, MARGIN = 3;
    private static final Vec3 RESIDENT = new Vec3(12.5, 1, 28.5);
    private static final BlockPos FRONT_DOOR = new BlockPos(16, 1, 16);
    private static final BlockPos BACK_DOOR = new BlockPos(9, 1, 4);
    private static final BlockPos LOWER_HALL = new BlockPos(16, 1, 14);
    private static final BlockPos UPPER_HALL = new BlockPos(16, 5, 9);
    private static final BlockPos BACK_OUTSIDE = new BlockPos(9, 1, 0);
    private record AdultDestination(String room, BlockPos position) { }
    private static final List<AdultDestination> ADULT_DESTINATIONS = List.of(
        new AdultDestination("upper hallway", UPPER_HALL),
        new AdultDestination("rear outside", BACK_OUTSIDE),
        new AdultDestination("front courtyard", new BlockPos(16, 1, 25)),
        new AdultDestination("kitchen", new BlockPos(11, 1, 6)),
        new AdultDestination("storage room", new BlockPos(10, 1, 13)),
        new AdultDestination("Lilia's room", new BlockPos(21, 1, 7)),
        new AdultDestination("living/dining room", new BlockPos(21, 1, 11)),
        new AdultDestination("upstairs study", new BlockPos(10, 5, 6)),
        new AdultDestination("Rudeus's room through the stairhead doorway", new BlockPos(10, 5, 14)),
        new AdultDestination("parents' bedroom", new BlockPos(22, 5, 8)),
        new AdultDestination("Roxy's guest room", new BlockPos(22, 5, 14)));
    private static final ResourceLocation TEMPLATE_ID = id("greyrat_home/main_v2");
    private static final ResourceLocation POOL_ID = id("greyrat_home/start");
    private static final ResourceLocation STRUCTURE_ID = id("greyrat_home");
    private static final ResourceLocation SET_ID = id("greyrat_homes");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }

    private record Placement(StructureTemplate template, CompoundTag raw, BlockPos origin,
                             Rotation rotation, RoxyTutorEntity tutor, List<PathfinderMob> residents) {
        BlockPos world(BlockPos local) {
            return StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
        }
    }

    private static CompoundTag rawResource(GameTestHelper helper) {
        ResourceLocation file = id("structure/" + TEMPLATE_ID.getPath() + ".nbt");
        try (InputStream stream = helper.getLevel().getServer().getResourceManager().open(file)) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException failure) {
            throw new IllegalStateException("The delivered house resource cannot be read: " + file, failure);
        }
    }

    private static BoundingBox arenaBox(GameTestHelper helper) {
        return BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 0, 1)),
            helper.absolutePos(new BlockPos(ARENA_SIZE - 2, ARENA_HEIGHT - 1, ARENA_SIZE - 2)));
    }

    private static AABB arenaArea(GameTestHelper helper) {
        return new AABB(helper.absoluteVec(new Vec3(1, 0, 1)),
            helper.absoluteVec(new Vec3(ARENA_SIZE - 1, ARENA_HEIGHT, ARENA_SIZE - 1)));
    }

    private static BlockPos localOrigin(Rotation rotation) {
        return switch (rotation) {
            case NONE -> new BlockPos(MARGIN, 0, MARGIN);
            case CLOCKWISE_90 -> new BlockPos(MARGIN + SIZE_Z - 1, 0, MARGIN);
            case CLOCKWISE_180 -> new BlockPos(MARGIN + SIZE_X - 1, 0, MARGIN + SIZE_Z - 1);
            case COUNTERCLOCKWISE_90 -> new BlockPos(MARGIN, 0, MARGIN + SIZE_X - 1);
        };
    }

    private static Vec3 expectedResidentLocal(Rotation rotation) {
        return switch (rotation) {
            case NONE -> new Vec3(MARGIN + RESIDENT.x, RESIDENT.y, MARGIN + RESIDENT.z);
            case CLOCKWISE_90 -> new Vec3(MARGIN + SIZE_Z - RESIDENT.z, RESIDENT.y, MARGIN + RESIDENT.x);
            case CLOCKWISE_180 -> new Vec3(MARGIN + SIZE_X - RESIDENT.x, RESIDENT.y, MARGIN + SIZE_Z - RESIDENT.z);
            case COUNTERCLOCKWISE_90 -> new Vec3(MARGIN + RESIDENT.z, RESIDENT.y, MARGIN + SIZE_X - RESIDENT.x);
        };
    }

    private static Placement place(GameTestHelper helper, Rotation rotation) {
        try { return placeChecked(helper, rotation); }
        catch (RuntimeException | Error failure) {
            // A failed assertion inside placement must not leak the three newly added residents
            // before the calling test receives its Placement/finally cleanup list.
            Set<String> roles = Set.of("roxy_tutor", "paul_greyrat", "zenith_greyrat", "lilia_greyrat");
            helper.getLevel().getEntitiesOfClass(PathfinderMob.class, arenaArea(helper), value -> {
                ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(value.getType());
                return !value.isRemoved() && type.getNamespace().equals(JoblessReincarnation.MOD_ID) && roles.contains(type.getPath());
            }).forEach(Entity::discard);
            throw failure;
        }
    }

    private static Placement placeChecked(GameTestHelper helper, Rotation rotation) {
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(TEMPLATE_ID)
            .orElseThrow(() -> new IllegalStateException("Missing delivered template " + TEMPLATE_ID));
        helper.assertValueEqual(template.getSize().getX(), SIZE_X, "The delivered width matches the agreed interface");
        helper.assertValueEqual(template.getSize().getY(), SIZE_Y, "The delivered height matches the agreed interface");
        helper.assertValueEqual(template.getSize().getZ(), SIZE_Z, "The delivered depth matches the agreed interface");
        CompoundTag raw = rawResource(helper);
        ListTag entities = raw.getList("entities", Tag.TAG_COMPOUND);
        Set<String> residentIds = Set.of("jobless_reincarnation:roxy_tutor", "jobless_reincarnation:paul_greyrat",
            "jobless_reincarnation:zenith_greyrat", "jobless_reincarnation:lilia_greyrat");
        helper.assertValueEqual(entities.size(), residentIds.size(), "The house template contains exactly four approved residents");
        Set<String> foundIds = new HashSet<>();
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag residentNbt = entities.getCompound(index).getCompound("nbt");
            String type = residentNbt.getString("id");
            helper.assertTrue(residentIds.contains(type) && foundIds.add(type), "Each formal resident type occurs exactly once: " + type);
            for (String forbidden : List.of("UUID", "JR_TutorAnchor", "JR_HomeAnchor", "Passengers", "Motion", "NoAI")) {
                helper.assertFalse(residentNbt.contains(forbidden), "Reusable resident data has no stale " + forbidden);
            }
            for (String key : residentNbt.getAllKeys()) {
                helper.assertFalse(key.contains("Anchor") || key.contains("Conversation"), "No resident has a baked home or transient conversation");
            }
        }
        StructureTemplatePool pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL)
            .getOptional(POOL_ID).orElseThrow(() -> new IllegalStateException("Missing registered pool " + POOL_ID));
        StructurePoolElement element = pool.getRandomTemplate(RandomSource.create(7015L));
        helper.assertTrue(element instanceof SinglePoolElement, "The registered start pool selects a normal SinglePoolElement");
        BlockPos origin = helper.absolutePos(localOrigin(rotation));
        BoundingBox bounds = element.getBoundingBox(level.getStructureManager(), origin, rotation);
        BoundingBox arena = arenaBox(helper);
        helper.assertTrue(arena.isInside(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()))
            && arena.isInside(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ())), "Every rotated house block stays within the isolated arena");
        helper.assertTrue(element.place(level.getStructureManager(), level, level.structureManager(),
            level.getChunkSource().getGenerator(), origin, origin, rotation, arena, RandomSource.create(7016L),
            LiquidSettings.IGNORE_WATERLOGGING, false), "The registered pool places the delivered template");
        List<PathfinderMob> residents = level.getEntitiesOfClass(PathfinderMob.class, arenaArea(helper),
            value -> !value.isRemoved() && residentIds.contains(BuiltInRegistries.ENTITY_TYPE.getKey(value.getType()).toString()));
        helper.assertValueEqual(residents.size(), 4, "The actual house placement produces four formal residents");
        helper.assertValueEqual(residents.stream().map(Entity::getUUID).distinct().count(), 4L, "All four resident identities are distinct");
        for (String type : residentIds) {
            helper.assertValueEqual(residents.stream().filter(value -> BuiltInRegistries.ENTITY_TYPE.getKey(value.getType()).toString().equals(type)).count(),
                1L, "The placed house has one resident of " + type);
        }
        List<RoxyTutorEntity> tutors = level.getEntitiesOfClass(RoxyTutorEntity.class, arenaArea(helper), value -> !value.isRemoved());
        helper.assertValueEqual(tutors.size(), 1, "The actual house placement produces exactly one formal tutor");
        RoxyTutorEntity tutor = tutors.getFirst();
        Vec3 expected = helper.absoluteVec(expectedResidentLocal(rotation));
        helper.assertTrue(tutor.position().distanceToSqr(expected) < 1.0E-12, "The house resident lands at its transformed courtyard teaching point");
        helper.assertValueEqual(tutor.getTutorAnchor(), GlobalPos.of(level.dimension(), BlockPos.containing(expected)),
            "Actual house support and clearance permit an anchor at the courtyard teaching point");
        helper.assertFalse(tutor.isNoAi(), "The actual house resident's AI is enabled");
        helper.assertTrue(tutor.isPersistenceRequired(), "The actual house resident is persistent");
        helper.assertTrue(level.noCollision(tutor, tutor.getBoundingBox()), "The actual resident does not intersect architecture");
        helper.assertTrue(level.getBlockCollisions(tutor, tutor.getBoundingBox().move(0, -0.05, 0)).iterator().hasNext(),
            "The actual resident has solid support immediately on placement");
        return new Placement(template, raw, origin, rotation, tutor, residents);
    }

    private static void checkPaletteAndPlacedBlocks(GameTestHelper helper, Placement placed) {
        ListTag palette = placed.raw.getList("palette", Tag.TAG_COMPOUND);
        helper.assertTrue(!palette.isEmpty(), "The delivered template has a block palette");
        for (int index = 0; index < palette.size(); index++) {
            CompoundTag entry = palette.getCompound(index);
            ResourceLocation blockId = ResourceLocation.parse(entry.getString("Name"));
            helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(blockId), "Every palette id exists in the actual registry: " + blockId);
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            CompoundTag properties = entry.getCompound("Properties");
            for (String name : properties.getAllKeys()) {
                var property = block.getStateDefinition().getProperty(name);
                helper.assertTrue(property != null, "The registered block declares property " + blockId + "[" + name + "]");
                helper.assertTrue(property.getValue(properties.getString(name)).isPresent(),
                    "The block property value is valid: " + blockId + "[" + name + "=" + properties.getString(name) + "]");
            }
        }
        ListTag blocks = placed.raw.getList("blocks", Tag.TAG_COMPOUND);
        int solidEntries = 0, blockEntities = 0;
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag entry = blocks.getCompound(index);
            int paletteIndex = entry.getInt("state");
            helper.assertTrue(paletteIndex >= 0 && paletteIndex < palette.size(), "Every block has a valid palette index");
            ListTag coordinates = entry.getList("pos", Tag.TAG_INT);
            BlockPos local = new BlockPos(coordinates.getInt(0), coordinates.getInt(1), coordinates.getInt(2));
            helper.assertTrue(local.getX() >= 0 && local.getX() < SIZE_X && local.getY() >= 0 && local.getY() < SIZE_Y
                && local.getZ() >= 0 && local.getZ() < SIZE_Z, "No delivered block lies outside the template size");
            Block expectedBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(palette.getCompound(paletteIndex).getString("Name")));
            BlockPos world = placed.world(local);
            BlockState state = helper.getLevel().getBlockState(world);
            helper.assertTrue(state.is(expectedBlock), "The engine placed the declared block at " + local + ": " + state);
            helper.assertTrue(state.canSurvive(helper.getLevel(), world),
                "The placed block has its required support and valid environment at " + local + ": " + state);
            if (!state.isAir()) solidEntries++;
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = helper.getLevel().getBlockEntity(world);
                helper.assertTrue(blockEntity != null, "The engine created the declared block entity at " + local);
                if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                    String declaredType = entry.getCompound("nbt").getString("id");
                    if (!declaredType.isBlank()) {
                        helper.assertValueEqual(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString(), declaredType,
                            "The delivered block entity type loads correctly at " + local);
                    }
                }
                blockEntities++;
            }
        }
        helper.assertTrue(solidEntries > 500, "The resource contains the full house architecture, not an empty stand-in");
        helper.assertTrue(blockEntities > 0, "The actual house contains functional block entities");
    }

    private static void checkFourStepStairGeometry(GameTestHelper helper, Placement placed) {
        // Path.canReach can include jumping: it did not catch v3's final full-block rise.
        // Check four actual rising collision profiles separately. Real W-key traversal remains separate evidence.
        double previousSurface = 1.0;
        for (int step = 0; step < 4; step++) {
            BlockPos local = new BlockPos(15, step + 1, 13 - step);
            BlockPos world = placed.world(local);
            BlockState state = helper.getLevel().getBlockState(world);
            helper.assertTrue(state.is(Blocks.OAK_STAIRS), "All four risers, including the upper landing, are real stairs at " + local);
            helper.assertValueEqual(state.getValue(StairBlock.HALF), Half.BOTTOM, "Every staircase block uses its lower half at " + local);
            helper.assertValueEqual(state.getValue(StairBlock.FACING), placed.rotation.rotate(Direction.NORTH),
                "Every step rises consistently toward the rotated upper landing at " + local);
            // Keep the 0.6-wide standing body behind the next taller tread; a centered lower-half
            // sample at 0.75 would artificially embed its leading 0.05 blocks in the next riser.
            for (double localZ : new double[] {0.85, 0.35}) {
                Vec3 sample = StructureTemplate.transform(new Vec3(15.5, step + 1, local.getZ() + localZ),
                    Mirror.NONE, placed.rotation, BlockPos.ZERO).add(Vec3.atLowerCornerOf(placed.origin));
                double x = sample.x - world.getX(), z = sample.z - world.getZ();
                double shapeTop = Double.NEGATIVE_INFINITY;
                for (AABB part : state.getCollisionShape(helper.getLevel(), world).toAabbs()) {
                    if (x > part.minX && x < part.maxX && z > part.minZ && z < part.maxZ) shapeTop = Math.max(shapeTop, part.maxY);
                }
                double expectedTop = localZ > 0.5 ? 0.5 : 1.0;
                helper.assertTrue(Math.abs(shapeTop - expectedTop) < 1.0E-9,
                    "Each step has an actual half-height then full-height collision surface at " + local);
                double surface = step + 1 + shapeTop;
                helper.assertTrue(surface >= previousSurface && surface - previousSurface <= 0.5 + 1.0E-9,
                    "Each successive stair surface rises at most half a block, rather than a jump-sized full block");
                previousSurface = surface;
                double feet = placed.origin.getY() + surface;
                AABB standingPlayer = new AABB(sample.x - 0.3, feet, sample.z - 0.3,
                    sample.x + 0.3, feet + 1.8, sample.z + 0.3);
                helper.assertFalse(helper.getLevel().getBlockCollisions(null, standingPlayer).iterator().hasNext(),
                    "A standing player has actual block clearance on both halves of the stair at " + local);
            }
        }
        helper.assertTrue(Math.abs(previousSurface - UPPER_HALL.getY()) < 1.0E-9,
            "The fourth step ends flush with the second-floor walking surface");
    }

    private static void rotationCase(GameTestHelper helper, Rotation rotation) {
        Placement placed = place(helper, rotation);
        try {
            checkPaletteAndPlacedBlocks(helper, placed);
            checkFourStepStairGeometry(helper, placed);
        }
        finally { placed.residents.forEach(Entity::discard); }
        helper.succeed();
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void registeredHousePlacesUnrotatedWithLiveResident(GameTestHelper helper) {
        rotationCase(helper, Rotation.NONE);
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void registeredHousePlacesClockwiseWithLiveResident(GameTestHelper helper) {
        rotationCase(helper, Rotation.CLOCKWISE_90);
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void registeredHousePlacesHalfTurnWithLiveResident(GameTestHelper helper) {
        rotationCase(helper, Rotation.CLOCKWISE_180);
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void registeredHousePlacesCounterclockwiseWithLiveResident(GameTestHelper helper) {
        rotationCase(helper, Rotation.COUNTERCLOCKWISE_90);
    }

    private static void assertBookShelf(GameTestHelper helper, Placement placed, BlockPos position, Direction facing,
                                        String[] keys, String[] fallbacks) {
        BlockPos world = placed.world(position);
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(world);
        helper.assertTrue(blockEntity instanceof ChiseledBookShelfBlockEntity, "The expected family bookshelf exists at " + position);
        ChiseledBookShelfBlockEntity shelf = (ChiseledBookShelfBlockEntity)blockEntity;
        BlockState state = helper.getLevel().getBlockState(world);
        helper.assertValueEqual(state.getValue(BlockStateProperties.HORIZONTAL_FACING), facing, "The expected bookshelf has its accessible facing at " + position);
        for (int slot = 0; slot < 6; slot++) {
            ItemStack book = shelf.getItem(slot);
            boolean occupied = slot < keys.length;
            helper.assertValueEqual(state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot)), occupied,
                "The bookshelf's visual slot state matches its real inventory at " + position + " slot " + slot);
            if (!occupied) {
                helper.assertTrue(book.isEmpty(), "Unused family bookshelf slots remain empty at " + position + " slot " + slot);
                continue;
            }
            helper.assertTrue(book.is(Items.BOOK), "Each named family volume remains a normal vanilla book, not a spell reward");
            helper.assertValueEqual(book.getCount(), 1, "Each occupied shelf slot contains one book");
            Component name = book.get(DataComponents.ITEM_NAME);
            helper.assertTrue(name != null && name.getContents() instanceof TranslatableContents,
                "The ordinary book has its own translatable display name");
            TranslatableContents translated = (TranslatableContents)name.getContents();
            helper.assertValueEqual(translated.getKey(), "item.jobless_reincarnation.greyrat_home." + keys[slot],
                "The book keeps the intended title and slot at " + position + " slot " + slot);
            helper.assertValueEqual(translated.getFallback(), fallbacks[slot],
                "The book retains its readable Chinese fallback even before localization loads");
        }
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void familyFiveBookCollectionAndBirthdayDictionaryRemainSeparateOrdinaryBooks(GameTestHelper helper) {
        Placement placed = place(helper, Rotation.NONE);
        int books = 0, shelves = 0;
        try {
            assertBookShelf(helper, placed, new BlockPos(8, 5, 4), Direction.SOUTH,
                new String[] {"world_travels", "fittoa_monsters", "magic_textbook", "legend_of_perugius", "three_swordsmen"},
                new String[] {"行遍世界", "菲托亚的魔物生态、弱点", "魔术教科书", "佩尔基乌斯的传说", "三剑士与迷宫"});
            assertBookShelf(helper, placed, new BlockPos(13, 5, 13), Direction.WEST,
                new String[] {"plant_dictionary"}, new String[] {"植物辞典"});
            for (BlockPos local : BlockPos.betweenClosed(0, 0, 0, SIZE_X - 1, SIZE_Y - 1, SIZE_Z - 1)) {
                BlockEntity blockEntity = helper.getLevel().getBlockEntity(placed.world(local));
                if (blockEntity instanceof ChiseledBookShelfBlockEntity) shelves++;
                if (!(blockEntity instanceof Container container)) continue;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (stack.isEmpty()) continue;
                    helper.assertTrue(blockEntity instanceof ChiseledBookShelfBlockEntity,
                        "H01 containers outside the two specified family bookshelves remain empty");
                    helper.assertTrue(stack.is(Items.BOOK), "All household books are normal vanilla books, not spell tomes or other rewards");
                    books += stack.getCount();
                }
            }
            helper.assertValueEqual(shelves, 2, "The family collection and birthday dictionary use their two distinct shelves");
            helper.assertValueEqual(books, 6, "The five original family books plus the later birthday dictionary load exactly once");
        } finally { placed.residents.forEach(Entity::discard); }
        helper.succeed();
    }

    private static Path requirePath(GameTestHelper helper, RoxyTutorEntity tutor, BlockPos destination, String description) {
        Path path = tutor.getNavigation().createPath(destination, 0);
        helper.assertTrue(path != null && path.canReach(), description + " has a complete actual navigation path");
        helper.assertValueEqual(path.getEndNode().asBlockPos(), destination, description + " reaches the intended floor position");
        return path;
    }

    private static void openDoor(GameTestHelper helper, Placement placed, BlockPos local) {
        BlockPos world = placed.world(local);
        BlockState lower = helper.getLevel().getBlockState(world);
        Block expectedDoor = local.equals(BACK_DOOR) ? Blocks.SPRUCE_DOOR : Blocks.OAK_DOOR;
        helper.assertTrue(lower.is(expectedDoor) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER,
            "The specified entrance uses its agreed door material and lower half");
        helper.assertFalse(lower.getValue(DoorBlock.OPEN), "The delivered entrance starts closed");
        BlockState upper = helper.getLevel().getBlockState(world.above());
        helper.assertTrue(upper.is(expectedDoor) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
            "The specified entrance has a matching upper door half");
        ((DoorBlock)lower.getBlock()).setOpen(null, helper.getLevel(), lower, world, true);
        helper.assertTrue(helper.getLevel().getBlockState(world).getValue(DoorBlock.OPEN), "The entrance can be opened through the normal door method");
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void courtyardPathsStayInsideTheExistingTutorHomeAndClosedFrontDoor(GameTestHelper helper) {
        Placement placed = place(helper, Rotation.NONE);
        try {
            placed.tutor.setOnGround(true);
            Vec3 homeCenter = Vec3.atBottomCenterOf(placed.tutor.getTutorAnchor().pos());
            for (BlockPos target : List.of(new BlockPos(8, 1, 28), new BlockPos(15, 1, 28),
                new BlockPos(12, 1, 22), new BlockPos(12, 1, 32))) {
                Path path = requirePath(helper, placed.tutor, placed.world(target), "Courtyard point " + target);
                for (int index = 0; index < path.getNodeCount(); index++) {
                    helper.assertTrue(path.getEntityPosAtNode(placed.tutor, index).distanceToSqr(homeCenter)
                        <= RoxyTutorEntity.HOME_RADIUS * RoxyTutorEntity.HOME_RADIUS,
                        "The actual courtyard route fits the unchanged eight-block home restriction");
                }
            }
            Path blocked = placed.tutor.getNavigation().createPath(placed.world(LOWER_HALL), 0);
            helper.assertTrue(blocked == null || !blocked.canReach(), "The closed front door blocks a tutor who cannot open doors");
            // v2 places the front entrance outside her eight-block home; adult access is tested separately.
        } finally { placed.residents.forEach(Entity::discard); }
        helper.succeed();
    }

    @GameTest(template = ARENA, batch = BATCH)
    public static void openedEntrancesAndStairsAdmitAnAdultNavigationProbe(GameTestHelper helper) {
        Placement placed = place(helper, Rotation.NONE);
        Villager probe = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(probe != null, "A normal adult navigation probe can be created");
        try {
            openDoor(helper, placed, FRONT_DOOR);
            openDoor(helper, placed, BACK_DOOR);
            // An adult villager (1.95 blocks tall) verifies clearance beyond Roxy's smaller body.
            probe.setNoAi(true);
            BlockPos hallFloor = placed.world(LOWER_HALL);
            BlockState hallSurface = helper.getLevel().getBlockState(hallFloor);
            double carpetHeight = hallSurface.getBlock() instanceof CarpetBlock
                ? hallSurface.getCollisionShape(helper.getLevel(), hallFloor).max(Direction.Axis.Y) : 0.0;
            // Spawn on the actual carpet top, not embedded in its 1/16-block collision shape.
            probe.moveTo(Vec3.atBottomCenterOf(hallFloor).add(0.0, carpetHeight, 0.0));
            probe.setOnGround(true);
            helper.assertTrue(helper.getLevel().addFreshEntity(probe), "The adult probe joins inside the actual lower hallway");
            helper.assertTrue(helper.getLevel().noCollision(probe, probe.getBoundingBox()), "The lower hallway has adult body clearance");
            for (BlockPos witness : List.of(UPPER_HALL, BACK_OUTSIDE, new BlockPos(16, 1, 25),
                new BlockPos(15, 2, 12), new BlockPos(15, 3, 11), new BlockPos(15, 4, 10), new BlockPos(15, 5, 9))) {
                Path diagnostic = probe.getNavigation().createPath(placed.world(witness), 0);
                List<BlockPos> nodes = new ArrayList<>();
                if (diagnostic != null) for (int i = 0; i < diagnostic.getNodeCount(); i++) {
                    nodes.add(diagnostic.getNodePos(i).subtract(placed.origin));
                }
                AABB bodyAtWitness = probe.getBoundingBox().move(Vec3.atBottomCenterOf(placed.world(witness)).subtract(probe.position()));
                JoblessReincarnation.LOGGER.info("[JR_H01_PATH] target={} reaches={} nodes={} clearance={} dimensions={}",
                    witness, diagnostic != null && diagnostic.canReach(), nodes,
                    !helper.getLevel().getBlockCollisions(probe, bodyAtWitness).iterator().hasNext(), probe.getDimensions(probe.getPose()));
            }
            for (AdultDestination destination : ADULT_DESTINATIONS) {
                BlockPos target = destination.position;
                Path path = probe.getNavigation().createPath(placed.world(target), 0);
                helper.assertTrue(path != null && path.canReach(), "Adult navigation reaches " + destination.room + " at " + target + " through the actual house");
                helper.assertValueEqual(path.getEndNode().asBlockPos(), placed.world(target), "The route ends on the intended floor");
                if (target.equals(UPPER_HALL)) {
                    helper.assertTrue(path.getNodeCount() >= 5, "The upper floor requires a real staircase route");
                    helper.assertTrue(path.getEndNode().y - path.getNode(0).y >= 3, "The route actually climbs to the upper storey");
                    for (int index = 1; index < path.getNodeCount(); index++) {
                        helper.assertTrue(Math.abs(path.getNode(index).y - path.getNode(index - 1).y) <= 1,
                            "The staircase route has no multi-block vertical jump");
                    }
                }
            }
        } finally {
            probe.discard();
            placed.residents.forEach(Entity::discard);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void occupiedFloorsAndCourtyardHaveActualBlockLight(GameTestHelper helper) {
        Placement placed = place(helper, Rotation.NONE);
        List<BlockPos> samples = new ArrayList<>();
        // Measure actual spawn-supporting walking cells on inhabited floors and in the fenced yard.
        // This does not claim a roof-wide natural-generation spawn audit or rely on the number of lamps.
        for (int y : new int[] {1, 5}) {
            int minZ = 4, maxZ = y == 1 ? 32 : 16;
            int minX = y == 1 ? 3 : 7, maxX = y == 1 ? 29 : 25;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                BlockPos world = placed.world(new BlockPos(x, y, z));
                if (y == 1 && z < 17 && (x < 6 || x > 26)) continue;
                if (!helper.getLevel().getBlockState(world.below()).isValidSpawn(helper.getLevel(), world.below(), EntityType.ZOMBIE)) continue;
                AABB body = new AABB(world.getX() + 0.2, world.getY(), world.getZ() + 0.2,
                    world.getX() + 0.8, world.getY() + 1.8, world.getZ() + 0.8);
                if (!helper.getLevel().getBlockCollisions(null, body).iterator().hasNext()) samples.add(world.immutable());
            }
        }
        helper.assertTrue(samples.size() >= 80, "The lighting check samples actual unobstructed occupied floors and courtyard cells");
        helper.succeedWhen(() -> {
            helper.getLevel().getChunkSource().getLightEngine().tryScheduleUpdate();
            for (BlockPos position : samples) {
                helper.assertTrue(helper.getLevel().getBrightness(LightLayer.BLOCK, position) > 0,
                    "Actual propagated block light must reach the walkable cell " + position + " (not skylight)");
            }
            placed.residents.forEach(Entity::discard);
        });
    }

    /** Air the outside cannot reach is a dark cell mobs could spawn in. Doors, panes and fences count as open; stairs and slabs as closed. */
    @GameTest(template = ARENA, batch = BATCH)
    public static void theRoofHasNoSealedDarkCells(GameTestHelper helper) {
        Placement placed = place(helper, Rotation.NONE);
        try {
            boolean[][][] reached = new boolean[SIZE_X][SIZE_Y][SIZE_Z];
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            for (BlockPos local : BlockPos.betweenClosed(0, 0, 0, SIZE_X - 1, SIZE_Y - 1, SIZE_Z - 1)) {
                boolean edge = local.getX() == 0 || local.getX() == SIZE_X - 1 || local.getY() == SIZE_Y - 1 || local.getZ() == 0 || local.getZ() == SIZE_Z - 1;
                if (edge && open(helper, placed, local) && !reached[local.getX()][local.getY()][local.getZ()]) {
                    reached[local.getX()][local.getY()][local.getZ()] = true;
                    queue.add(local.immutable());
                }
            }
            while (!queue.isEmpty()) {
                BlockPos at = queue.poll();
                for (Direction direction : Direction.values()) {
                    BlockPos next = at.relative(direction);
                    if (next.getX() < 0 || next.getY() < 0 || next.getZ() < 0 || next.getX() >= SIZE_X || next.getY() >= SIZE_Y || next.getZ() >= SIZE_Z) continue;
                    if (reached[next.getX()][next.getY()][next.getZ()] || !open(helper, placed, next)) continue;
                    reached[next.getX()][next.getY()][next.getZ()] = true;
                    queue.add(next);
                }
            }
            for (BlockPos local : BlockPos.betweenClosed(0, 0, 0, SIZE_X - 1, SIZE_Y - 1, SIZE_Z - 1)) {
                if (helper.getLevel().getBlockState(placed.world(local)).isAir()) {
                    helper.assertTrue(reached[local.getX()][local.getY()][local.getZ()], "No sealed air cell in the house at " + local);
                }
            }
        } finally { placed.residents.forEach(Entity::discard); }
        helper.succeed();
    }

    private static boolean open(GameTestHelper helper, Placement placed, BlockPos local) {
        BlockPos world = placed.world(local);
        BlockState state = helper.getLevel().getBlockState(world);
        if (state.getBlock() instanceof StairBlock || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) return false;
        return !state.isCollisionShapeFullBlock(helper.getLevel(), world);
    }
    @GameTest(template = "empty", batch = BATCH)
    public static void theHomeRefusesGroundTooRoughForALevelYard(GameTestHelper helper) {
        GreyratHomeStructure.Limits limits = new GreyratHomeStructure.Limits(14, 10, 20);
        BoundingBox home = new BoundingBox(100, 60, 200, 132, 79, 238);
        var flat = GreyratHomeStructure.assess((x, z) -> 70, home, limits);
        helper.assertTrue(flat.accepted() && flat.groundY() == 70 && flat.footprintRelief() == 0 && flat.bufferRelief() == 0, "Flat ground is accepted at its own height");
        // Half the footprint up a step: the sampled corner could be either height, the median is one of them, and the rise itself is allowed.
        var step = GreyratHomeStructure.assess((x, z) -> x < 116 ? 70 : 80, home, limits);
        helper.assertTrue(step.accepted() && step.footprintRelief() == 10, "A ten-block rise under the home is still acceptable");
        var slope = GreyratHomeStructure.assess((x, z) -> 70 + (x - 100), home, limits);
        helper.assertFalse(slope.accepted(), "A hillside rising one block per block is refused");
        helper.assertValueEqual(slope.groundY(), 86, "The reference height is the median of the footprint, not one corner");
        var cliffBeside = GreyratHomeStructure.assess((x, z) -> x <= 132 ? 70 : 95, home, limits);
        helper.assertTrue(!cliffBeside.accepted() && cliffBeside.footprintRelief() == 0, "A cliff just outside a level footprint is refused too");
        var lakeside = GreyratHomeStructure.assess((x, z) -> x < 116 ? 63 : 68, home, limits);
        helper.assertTrue(lakeside.accepted(), "Water counts at its surface, so a home by the water stays allowed");
        helper.succeed();
    }

    /** Real overworld noise at known sites of seed 42, without generating a world: the mountainside and the steep hill go, flat ground and the coast stay. */
    @GameTest(template = "empty", batch = BATCH)
    public static void realSeed42TerrainKeepsLevelSitesAndRefusesMountainsides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        long seed = 42L;
        var source = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source, settings);
        var noise = net.minecraft.world.level.levelgen.RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
        GreyratHomeStructure home = (GreyratHomeStructure)registries.registryOrThrow(Registries.STRUCTURE).getOptional(STRUCTURE_ID).orElseThrow();
        record Site(int x, int z, boolean kept) { }
        for (Site site : List.of(new Site(1392, 4224, false), new Site(-2800, 672, false), new Site(4000, -3744, false),
                new Site(1456, 2192, true), new Site(736, 288, true), new Site(640, -1792, true), new Site(4032, -5456, true))) {
            var context = new Structure.GenerationContext(registries, generator, source, noise, level.getStructureManager(), seed,
                new net.minecraft.world.level.ChunkPos(site.x() >> 4, site.z() >> 4), level, biome -> true);
            helper.assertValueEqual(home.inspect(context).stub().isPresent(), site.kept(), "The terrain decision at " + site.x() + "," + site.z());
        }
        // Good ground on the new grid, two chunks from a home seed 42 really had before: an upgraded save may hold that one, so this site stays empty.
        var beside = new Structure.GenerationContext(registries, generator, source, noise, level.getStructureManager(), seed,
            new net.minecraft.world.level.ChunkPos(2608 >> 4, -3536 >> 4), level, biome -> true);
        helper.assertTrue(home.inspect(beside).stub().isPresent(), "The ground beside the old home should be fine on its own");
        helper.assertTrue(home.findGenerationPoint(beside).isEmpty(), "A new home should keep off the old grid");
        helper.succeed();
    }

    /** The old grid in the code has to be the one old saves really used: these are starts seen in worlds generated before the change. */
    @GameTest(template = "empty", batch = BATCH)
    public static void theOldGridMatchesRealOldHomesAndClearsThem(GameTestHelper helper) {
        long seed = 42L;
        for (int[] start : new int[][] {{1392, 4224}, {-2800, 672}, {4000, -3744}, {1456, 2192}, {736, 288}, {640, -1792}, {4032, -5456}, {2624, -3568}}) {
            var old = new net.minecraft.world.level.ChunkPos(start[0] >> 4, start[1] >> 4);
            helper.assertValueEqual(GreyratHomeStructure.OLD_GRID.getPotentialStructureChunk(seed, old.x, old.z), old, "The old grid at " + start[0] + "," + start[1]);
            int clearance = GreyratHomeStructure.OLD_GRID_CLEARANCE;
            helper.assertTrue(GreyratHomeStructure.nearOldGrid(seed, old), "The old start itself");
            helper.assertTrue(GreyratHomeStructure.nearOldGrid(seed, new net.minecraft.world.level.ChunkPos(old.x + clearance, old.z - clearance)), "The corner of the clearance");
        }
        // The clearance ends one chunk further out; for this seed no other old spot is near this one.
        var spot = GreyratHomeStructure.OLD_GRID.getPotentialStructureChunk(seed, 640, -240);
        helper.assertTrue(GreyratHomeStructure.nearOldGrid(seed, new net.minecraft.world.level.ChunkPos(spot.x + GreyratHomeStructure.OLD_GRID_CLEARANCE, spot.z)), "The edge of the clearance");
        helper.assertFalse(GreyratHomeStructure.nearOldGrid(seed, new net.minecraft.world.level.ChunkPos(spot.x + GreyratHomeStructure.OLD_GRID_CLEARANCE + 1, spot.z)), "Just past the clearance");
        helper.succeed();
    }
    /** Homes already generated keep the old piece: saved structure starts name this template, so it has to stay loadable. */
    @GameTest(template = "empty", batch = BATCH)
    public static void theFirstHouseTemplateStaysForWorldsThatAlreadyPlacedIt(GameTestHelper helper) {
        StructureTemplate first = helper.getLevel().getStructureManager().get(id("greyrat_home/main"))
            .orElseThrow(() -> new IllegalStateException("The first house template must stay in the mod"));
        helper.assertValueEqual(first.getSize(), new BlockPos(25, 16, 31), "Worlds that already placed the first house find the same template");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void worldgenRegistriesLinkTheDeliveredPoolAndDefaultPlainsBiomes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Structure structure = registries.registryOrThrow(Registries.STRUCTURE).getOptional(STRUCTURE_ID)
            .orElseThrow(() -> new IllegalStateException("Missing registered house structure"));
        helper.assertTrue(structure instanceof GreyratHomeStructure, "The house uses its own terrain-checked structure type");
        helper.assertValueEqual(((GreyratHomeStructure)structure).limits(), new GreyratHomeStructure.Limits(14, 10, 20), "The registered house uses the agreed terrain limits");
        // Placed after trees have grown, so the template's air clears whatever grew in the yard and the fields.
        helper.assertValueEqual(structure.step(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION, "The house is placed after vegetation");
        helper.assertValueEqual(structure.terrainAdaptation(), TerrainAdjustment.BEARD_THIN, "Terrain adaptation matches the house interface");
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonObject encoded = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow().getAsJsonObject();
        helper.assertValueEqual(encoded.get("start_pool").getAsString(), POOL_ID.toString(), "The actual decoded structure links the delivered start pool");
        helper.assertValueEqual(encoded.get("biomes").getAsString(), "#jobless_reincarnation:has_structure/greyrat_home",
            "The structure uses the extensible house biome tag");
        helper.assertTrue(encoded.has("max_buffer_relief"), "The decoded structure round-trips its terrain limits");
        var biomes = registries.registryOrThrow(Registries.BIOME);
        var tag = TagKey.create(Registries.BIOME, id("has_structure/greyrat_home"));
        var tagged = biomes.getTag(tag).orElseThrow(() -> new IllegalStateException("The house biome tag is empty or missing"));
        for (var biomeKey : List.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS)) {
            var biome = biomes.getHolderOrThrow(biomeKey);
            helper.assertTrue(tagged.contains(biome) && structure.biomes().contains(biome), "Default plains biome is eligible: " + biomeKey.location());
        }
        StructureTemplatePool pool = registries.registryOrThrow(Registries.TEMPLATE_POOL).getOptional(POOL_ID).orElseThrow();
        helper.assertTrue(pool.size() > 0, "The actual start pool contains at least one selectable entry");
        JsonObject poolJson = StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, pool).getOrThrow().getAsJsonObject();
        helper.assertValueEqual(poolJson.getAsJsonArray("elements").size(), 1, "The start pool has the single agreed house entry");
        JsonObject element = poolJson.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element");
        helper.assertValueEqual(element.get("location").getAsString(), TEMPLATE_ID.toString(), "The decoded pool points to the delivered large template");
        helper.assertValueEqual(element.get("projection").getAsString(), "rigid", "The rigid house does not individually project interior floors");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).getOptional(id("greyrat_homes")).isEmpty(), "V01 no longer scatters separate homes");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).getOptional(id("sylphiette_meetings")).isEmpty(), "V01 no longer scatters separate tree encounters");
        helper.assertTrue(registries.registryOrThrow(Registries.STRUCTURE_SET).containsKey(id("buena_village")), "Both scenes are now provided by the single village");
        helper.succeed();
    }

    private HouseStructureGameTests() { }
}
