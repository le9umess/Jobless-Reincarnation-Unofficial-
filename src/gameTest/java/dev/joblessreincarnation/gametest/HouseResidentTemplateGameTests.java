package dev.joblessreincarnation.gametest;

import com.mojang.datafixers.util.Either;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Actual vanilla template/pool placement with the registered tutor, using a small in-memory NBT fixture.
 * Bounding-box slices exercise entity ownership at placement boundaries, not asynchronous new-chunk generation.
 * An ordinary entity reload is distinct from deliberately placing the whole structure again.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HouseResidentTemplateGameTests {
    private static final String ANCHOR_TAG = "JR_TutorAnchor";
    private static final UUID SOURCE_UUID = UUID.fromString("76c5c0d3-2794-4dc3-9c09-38f0a5dde67d");
    private static final Vec3 LOCAL_RESIDENT = new Vec3(1.25, 1.0, 2.75);

    /** Use the real SinglePoolElement settings and place implementation without replacing either method. */
    private static final class FixturePool extends SinglePoolElement {
        FixturePool(StructureTemplate template) {
            super(Either.right(template), Holder.direct(new StructureProcessorList(List.of())),
                StructureTemplatePool.Projection.RIGID, Optional.empty());
        }
    }

    private record Fixture(CompoundTag source, CompoundTag sourceWitness,
                           StructureTemplate template, CompoundTag templateWitness, FixturePool pool) {
        void assertUnchanged(GameTestHelper helper) {
            helper.assertValueEqual(source, sourceWitness, "Placement does not rewrite the source NBT document");
            helper.assertValueEqual(template.save(new CompoundTag()), templateWitness,
                "Placement does not modify the template's resident UUID, position or other stored NBT");
        }
    }

    private static void floor(GameTestHelper helper) {
        // The test controller at 0,0,0 and adjacent GameTests remain untouched.
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static ListTag ints(int... values) {
        ListTag tag = new ListTag();
        for (int value : values) tag.add(IntTag.valueOf(value));
        return tag;
    }

    private static ListTag doubles(double... values) {
        ListTag tag = new ListTag();
        for (double value : values) tag.add(DoubleTag.valueOf(value));
        return tag;
    }

    private static Fixture fixture(GameTestHelper helper) {
        RoxyTutorEntity prototype = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
        helper.assertTrue(prototype != null && prototype.getTutorAnchor() == null,
            "An unplaced registered tutor starts without a constructor-origin home");
        CompoundTag residentNbt = new CompoundTag();
        helper.assertTrue(prototype.save(residentNbt), "The fixture serializes the actual registered tutor");
        residentNbt.putUUID("UUID", SOURCE_UUID);
        // The placement record below, not a stale serialized absolute position, must determine the home.
        residentNbt.put("Pos", doubles(12345.0, 123.0, -12345.0));
        residentNbt.remove(ANCHOR_TAG);
        residentNbt.putBoolean("NoAI", false);
        helper.assertFalse(residentNbt.contains(ANCHOR_TAG), "A reusable house template has no baked world anchor");

        CompoundTag resident = new CompoundTag();
        resident.put("pos", doubles(LOCAL_RESIDENT.x, LOCAL_RESIDENT.y, LOCAL_RESIDENT.z));
        resident.put("blockPos", ints(1, 1, 2));
        resident.put("nbt", residentNbt);
        ListTag residents = new ListTag();
        residents.add(resident);

        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag();
        palette.add(stone);
        CompoundTag markerFloor = new CompoundTag();
        markerFloor.put("pos", ints(0, 0, 0));
        markerFloor.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(markerFloor);
        CompoundTag source = new CompoundTag();
        source.put("size", ints(5, 3, 5));
        source.put("palette", palette);
        source.put("blocks", blocks);
        source.put("entities", residents);
        CompoundTag sourceWitness = source.copy();
        StructureTemplate template = new StructureTemplate();
        template.load(helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), source);
        helper.assertValueEqual(source, sourceWitness, "Template decoding preserves the input document");
        return new Fixture(source, sourceWitness, template, template.save(new CompoundTag()), new FixturePool(template));
    }

    private static AABB area(GameTestHelper helper) {
        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(1, 1, 1))),
            Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(15, 5, 15))));
    }

    private static List<RoxyTutorEntity> residents(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(RoxyTutorEntity.class, area(helper), entity -> !entity.isRemoved());
    }

    private static BoundingBox placementBox(GameTestHelper helper, int minX, int maxX) {
        return BoundingBox.fromCorners(helper.absolutePos(new BlockPos(minX, 0, 1)),
            helper.absolutePos(new BlockPos(maxX, 4, 14)));
    }

    private static void placePool(GameTestHelper helper, Fixture fixture, BlockPos localOrigin,
                                  Rotation rotation, BoundingBox slice) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(localOrigin);
        helper.assertTrue(fixture.pool.place(level.getStructureManager(), level, level.structureManager(),
            level.getChunkSource().getGenerator(), origin, origin, rotation, slice, RandomSource.create(71021L),
            LiquidSettings.IGNORE_WATERLOGGING, false), "The real SinglePoolElement accepts the fixture");
    }

    private static RoxyTutorEntity onlyResident(GameTestHelper helper) {
        List<RoxyTutorEntity> result = residents(helper);
        helper.assertValueEqual(result.size(), 1, "Exactly one live resident belongs to this isolated placement");
        return result.getFirst();
    }

    private static void assertHome(GameTestHelper helper, RoxyTutorEntity resident, Vec3 expected) {
        helper.assertTrue(resident.position().distanceToSqr(expected) < 1.0E-12,
            "The template entity lands at the expected fractional position: " + resident.position() + " versus " + expected);
        helper.assertValueEqual(resident.getTutorAnchor(), GlobalPos.of(helper.getLevel().dimension(), BlockPos.containing(expected)),
            "The home belongs to the final transformed world position");
        helper.assertValueEqual(resident.getRestrictCenter(), BlockPos.containing(expected), "AI restriction uses that new home");
        helper.assertFalse(resident.isNoAi(), "Structure residents retain their normal AI");
        helper.assertTrue(resident.isPersistenceRequired(), "The house resident uses ordinary persistent entity storage");
        helper.assertTrue(helper.getLevel().getEntity(resident.getUUID()) == resident, "The actual server entity index owns the resident");
        helper.assertFalse(resident.getUUID().equals(SOURCE_UUID), "Placement generates a new entity UUID from reusable template data");
    }

    private static void rotationCase(GameTestHelper helper, Rotation rotation, Vec3 expectedOffset) {
        floor(helper);
        Fixture fixture = fixture(helper);
        placePool(helper, fixture, new BlockPos(7, 0, 7), rotation, placementBox(helper, 1, 14));
        RoxyTutorEntity resident = onlyResident(helper);
        try {
            assertHome(helper, resident, helper.absoluteVec(new Vec3(7, 0, 7).add(expectedOffset)));
            fixture.assertUnchanged(helper);
        } finally { resident.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void poolResidentAtUnrotatedWorldPosition(GameTestHelper helper) {
        rotationCase(helper, Rotation.NONE, new Vec3(1.25, 1, 2.75));
    }

    @GameTest(template = "empty")
    public static void poolResidentAtClockwiseWorldPosition(GameTestHelper helper) {
        rotationCase(helper, Rotation.CLOCKWISE_90, new Vec3(-1.75, 1, 1.25));
    }

    @GameTest(template = "empty")
    public static void poolResidentAtHalfTurnWorldPosition(GameTestHelper helper) {
        rotationCase(helper, Rotation.CLOCKWISE_180, new Vec3(-0.25, 1, -1.75));
    }

    @GameTest(template = "empty")
    public static void poolResidentAtCounterclockwiseWorldPosition(GameTestHelper helper) {
        rotationCase(helper, Rotation.COUNTERCLOCKWISE_90, new Vec3(2.75, 1, -0.25));
    }

    @GameTest(template = "empty")
    public static void mirroredTemplatePositionsKeepTheirOwnHomes(GameTestHelper helper) {
        floor(helper);
        Fixture fixture = fixture(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(7, 0, 7));
        // Independently tabulated expected offsets: LR then FB, in NONE/CW90/180/CCW90 order.
        Vec3[][] expected = {
            {new Vec3(1.25, 1, -1.75), new Vec3(2.75, 1, 1.25),
                new Vec3(-0.25, 1, 2.75), new Vec3(-1.75, 1, -0.25)},
            {new Vec3(-0.25, 1, 2.75), new Vec3(-1.75, 1, -0.25),
                new Vec3(1.25, 1, -1.75), new Vec3(2.75, 1, 1.25)}
        };
        Mirror[] mirrors = {Mirror.LEFT_RIGHT, Mirror.FRONT_BACK};
        Rotation[] rotations = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};
        for (int mirror = 0; mirror < mirrors.length; mirror++) for (int rotation = 0; rotation < rotations.length; rotation++) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true)
                .setKnownShape(true).setMirror(mirrors[mirror]).setRotation(rotations[rotation])
                .setBoundingBox(placementBox(helper, 1, 14));
            helper.assertTrue(fixture.template.placeInWorld(helper.getLevel(), origin, origin, settings, RandomSource.create(71L), 18),
                "Vanilla StructureTemplate places the mirrored fixture");
            RoxyTutorEntity resident = onlyResident(helper);
            try { assertHome(helper, resident, Vec3.atLowerCornerOf(origin).add(expected[mirror][rotation])); }
            finally { resident.discard(); }
        }
        fixture.assertUnchanged(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void separateHomesReceiveIndependentResidentIdentities(GameTestHelper helper) {
        floor(helper);
        Fixture fixture = fixture(helper);
        placePool(helper, fixture, new BlockPos(2, 0, 2), Rotation.NONE, placementBox(helper, 1, 14));
        RoxyTutorEntity first = onlyResident(helper);
        placePool(helper, fixture, new BlockPos(9, 0, 9), Rotation.NONE, placementBox(helper, 1, 14));
        List<RoxyTutorEntity> all = residents(helper);
        try {
            helper.assertValueEqual(all.size(), 2, "A reusable pool template can populate two separate homes");
            RoxyTutorEntity second = all.stream().filter(value -> value != first).findFirst().orElseThrow();
            assertHome(helper, first, helper.absoluteVec(new Vec3(3.25, 1, 4.75)));
            assertHome(helper, second, helper.absoluteVec(new Vec3(10.25, 1, 11.75)));
            helper.assertFalse(first.getUUID().equals(second.getUUID()), "Two generated homes never share their resident UUID");
            helper.assertFalse(first.getTutorAnchor().equals(second.getTutorAnchor()), "Each resident has its own world anchor");
            fixture.assertUnchanged(helper);
        } finally { all.forEach(Entity::discard); }
        helper.succeed();
    }

    private static void splitCase(GameTestHelper helper, boolean residentSliceFirst) {
        floor(helper);
        Fixture fixture = fixture(helper);
        // The resident's feet block is x=8; its 0.6-wide body partly overlaps x=7.
        // Ownership is by the transformed entity blockPos, not intersection of the entity's AABB.
        BoundingBox emptySlice = placementBox(helper, 1, 7);
        BoundingBox residentSlice = placementBox(helper, 8, 14);
        placePool(helper, fixture, new BlockPos(7, 0, 7), Rotation.NONE,
            residentSliceFirst ? residentSlice : emptySlice);
        helper.assertValueEqual(residents(helper).size(), residentSliceFirst ? 1 : 0,
            "The first disjoint bounding-box slice only places its own resident");
        placePool(helper, fixture, new BlockPos(7, 0, 7), Rotation.NONE,
            residentSliceFirst ? emptySlice : residentSlice);
        RoxyTutorEntity resident = onlyResident(helper);
        try {
            assertHome(helper, resident, helper.absoluteVec(new Vec3(8.25, 1, 9.75)));
            fixture.assertUnchanged(helper);
        } finally { resident.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boundingBoxSlicesForwardPlaceOneResident(GameTestHelper helper) {
        splitCase(helper, false);
    }

    @GameTest(template = "empty")
    public static void boundingBoxSlicesReversePlaceOneResident(GameTestHelper helper) {
        splitCase(helper, true);
    }

    @GameTest(template = "empty")
    public static void mainThreadTemplateJoinInitializesTheNewHome(GameTestHelper helper) {
        floor(helper);
        Fixture fixture = fixture(helper);
        int[] joins = {0};
        Consumer<EntityJoinLevelEvent> observer = event -> {
            if (event.getLevel() != helper.getLevel() || !(event.getEntity() instanceof RoxyTutorEntity resident)
                || !area(helper).contains(resident.position())) return;
            helper.assertTrue(helper.getLevel().getServer().isSameThread(), "This actual placement joins on the server thread");
            helper.assertTrue(resident.getTutorAnchor() == null, "Before onAddedToLevel the template has no stale baked anchor");
            helper.assertFalse(resident.isNoAi(), "Normal AI is already enabled at the actual join event");
            joins[0]++;
        };
        NeoForge.EVENT_BUS.addListener(observer);
        try {
            placePool(helper, fixture, new BlockPos(7, 0, 7), Rotation.CLOCKWISE_90, placementBox(helper, 1, 14));
            RoxyTutorEntity resident = onlyResident(helper);
            try {
                helper.assertValueEqual(joins[0], 1, "Exactly one actual EntityJoinLevelEvent was observed");
                assertHome(helper, resident, helper.absoluteVec(new Vec3(5.25, 1, 8.25)));
                fixture.assertUnchanged(helper);
            } finally { resident.discard(); }
        } finally { NeoForge.EVENT_BUS.unregister(observer); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void ordinaryResidentSaveReloadPreservesIdentityAndHomeWithoutPlacement(GameTestHelper helper) {
        floor(helper);
        Fixture fixture = fixture(helper);
        placePool(helper, fixture, new BlockPos(7, 0, 7), Rotation.NONE, placementBox(helper, 1, 14));
        RoxyTutorEntity original = onlyResident(helper);
        UUID uuid = original.getUUID();
        GlobalPos home = original.getTutorAnchor();
        original.moveTo(helper.absoluteVec(new Vec3(11.5, 1, 12.5)));
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(original.save(saved), "The generated resident is saved through the ordinary entity path");
        helper.assertTrue(saved.contains(ANCHOR_TAG), "Ordinary entity saves include the new persistent home");
        CompoundTag savedWitness = saved.copy();
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(helper.getLevel().getEntity(uuid) == null, "The unloaded identity has left the live index");
            helper.assertValueEqual(residents(helper).size(), 0, "Unloading does not trigger a replacement resident");
            Entity loaded = EntityType.loadEntityRecursive(saved.copy(), helper.getLevel(), value -> value);
            helper.assertTrue(loaded instanceof RoxyTutorEntity, "Ordinary entity loading recreates the registered resident");
            RoxyTutorEntity restored = (RoxyTutorEntity)loaded;
            try {
                helper.assertValueEqual(restored.getUUID(), uuid, "Entity reload retains its UUID, unlike fresh template placement");
                helper.assertValueEqual(restored.getTutorAnchor(), home, "Entity reload retains home despite a different saved position");
                helper.assertTrue(helper.getLevel().addFreshEntity(restored), "The reloaded resident enters the level normally");
                helper.assertValueEqual(residents(helper).size(), 1, "Ordinary save/load produces one resident without placing the template again");
                helper.assertValueEqual(restored.getTutorAnchor(), home, "Join restores the saved home instead of reanchoring the moved resident");
                helper.assertValueEqual(restored.getRestrictCenter(), home.pos(), "Rejoined AI keeps its saved home restriction");
                helper.assertFalse(restored.isNoAi(), "The reloaded house resident retains normal AI");
                helper.assertValueEqual(saved, savedWitness, "Loading does not modify the saved entity document");
                fixture.assertUnchanged(helper);
            } finally { restored.discard(); }
            helper.succeed();
        });
    }

    private HouseResidentTemplateGameTests() { }
}
