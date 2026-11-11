package dev.joblessreincarnation.gametest;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.worldgen.MeetingTerrain;
import dev.joblessreincarnation.worldgen.SylphietteMeetingStructure;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Terrain rejection checks use explicit noise columns; these tests never load candidate world chunks. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MeetingTerrainGameTests {
    private static final String ARENA = "house_h01_empty", BATCH = "meetingTerrainH02";
    private static final MeetingTerrain.Limits LIMITS = new MeetingTerrain.Limits(3, 4, 5, 2);
    private static final BoundingBox BOX = new BoundingBox(0, 63, 0, 22, 78, 22);
    private static final class Terrain implements MeetingTerrain.Source {
        IntBinaryOperator top = (x, z) -> 64;
        final Set<Long> water = new HashSet<>();
        final Map<BlockPos, BlockState> overrides = new HashMap<>();
        int reads;
        int sea = 61;
        @Override public int seaLevel() { return sea; }
        boolean derive;
        @Override public boolean deriveHeightsFromColumn() { return derive; }
        static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
        @Override public int height(int x, int z, Heightmap.Types type) {
            reads++;
            return top.applyAsInt(x, z) - (type == Heightmap.Types.OCEAN_FLOOR_WG && water.contains(key(x, z)) ? 1 : 0);
        }
        @Override public NoiseColumn column(int x, int z) {
            reads++;
            int surface = top.applyAsInt(x, z);
            BlockState[] cells = new BlockState[maxY() - minY()];
            for (int y = minY(); y < maxY(); y++) {
                BlockState state = y < surface ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                if (y == surface - 1 && water.contains(key(x, z))) state = Blocks.WATER.defaultBlockState();
                cells[y - minY()] = overrides.getOrDefault(new BlockPos(x, y, z), state);
            }
            return new NoiseColumn(minY(), cells);
        }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    }
    private static MeetingTerrain.Assessment assess(Terrain terrain) { return MeetingTerrain.assess(terrain, BOX, LIMITS); }
    private static void reason(GameTestHelper helper, MeetingTerrain.Assessment result, MeetingTerrain.Reason expected) {
        helper.assertValueEqual(result.reason(), expected, "Terrain assessment has the intended reason");
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void dryFlatAndInclusiveSlopeLimitsKeepLowestSupportedBase(GameTestHelper helper) {
        Terrain flat = new Terrain();
        var first = assess(flat);
        reason(helper, first, MeetingTerrain.Reason.ACCEPTED);
        helper.assertValueEqual(first.baseY(), 63, "First-free height 64 means occupied support y63");
        helper.assertTrue(first.heightQueries() <= 2000 && first.columnQueries() == 529, "Accepted candidate has bounded cached reads");
        helper.assertValueEqual(flat.reads, first.heightQueries() + first.columnQueries(), "Reported read counts are actual source calls");
        Terrain edge = new Terrain();
        edge.top = (x, z) -> x > 22 ? 69 : x >= 11 ? 67 : 64;
        var second = assess(edge);
        reason(helper, second, MeetingTerrain.Reason.ACCEPTED);
        helper.assertValueEqual(second.baseY(), 63, "Three-block incline is based on the lowest occupied column, never lifted above it");
        helper.assertValueEqual(second.footprintMax() - second.footprintMin(), 3, "Footprint upper bound is inclusive");
        helper.assertValueEqual(second.bufferMax() - second.bufferMin(), 5, "Buffer upper bound is inclusive");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void steepCandidateAndSteepSurroundingsAreRejectedBeforeColumnScans(GameTestHelper helper) {
        Terrain steep = new Terrain(); steep.top = (x, z) -> x > 11 ? 68 : 64;
        var result = assess(steep); reason(helper, result, MeetingTerrain.Reason.FOOTPRINT_SLOPE);
        helper.assertTrue(result.heightQueries() <= 25 && result.columnQueries() == 0, "Coarse checks cheaply reject a broad slope");
        Terrain nearbyCliff = new Terrain(); nearbyCliff.top = (x, z) -> x > 22 ? 70 : 64;
        result = assess(nearbyCliff); reason(helper, result, MeetingTerrain.Reason.BUFFER_SLOPE);
        helper.assertValueEqual(result.columnQueries(), 0, "A cliff just outside the template is still rejected before full columns");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void denseScanFindsSingleColumnPitAndNarrowBufferWater(GameTestHelper helper) {
        Terrain pit = new Terrain(); pit.top = (x, z) -> x == 1 && z == 1 ? 64 : 68;
        var result = assess(pit); reason(helper, result, MeetingTerrain.Reason.FOOTPRINT_SLOPE);
        helper.assertTrue(result.heightQueries() > 50, "A one-column pit between coarse samples is found by the dense pass");
        Terrain stream = new Terrain(); stream.water.add(Terrain.key(-3, 1));
        reason(helper, assess(stream), MeetingTerrain.Reason.SURFACE_FLUID);
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void bothFoundationLayersAndCoordinateBoundsAreValidated(GameTestHelper helper) {
        Terrain gap = new Terrain(); gap.overrides.put(new BlockPos(1, 62, 1), Blocks.AIR.defaultBlockState());
        reason(helper, assess(gap), MeetingTerrain.Reason.FOUNDATION_VOID);
        Terrain wet = new Terrain(); wet.overrides.put(new BlockPos(1, 62, 1), Blocks.WATER.defaultBlockState());
        reason(helper, assess(wet), MeetingTerrain.Reason.FOUNDATION_FLUID);
        Terrain baseGap = new Terrain(); baseGap.overrides.put(new BlockPos(1, 63, 1), Blocks.AIR.defaultBlockState());
        reason(helper, assess(baseGap), MeetingTerrain.Reason.ACCEPTED); // Authored y0 replaces this slot; support is below it.
        Terrain huge = new Terrain();
        reason(helper, MeetingTerrain.assess(huge, new BoundingBox(0, 63, 0, 10000, 78, 10000), LIMITS), MeetingTerrain.Reason.INVALID_BOUNDS);
        reason(helper, MeetingTerrain.assess(huge, new BoundingBox(Integer.MAX_VALUE - 26, 63, 0, Integer.MAX_VALUE - 4, 78, 22), LIMITS), MeetingTerrain.Reason.INVALID_BOUNDS);
        helper.assertValueEqual(huge.reads, 0, "Oversized/overflowing footprints are rejected without source reads");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void rotatedNegativeFootprintsStillDetectTheirInteriorHazard(GameTestHelper helper) {
        BlockPos origin = new BlockPos(-103, 63, -117);
        for (Rotation rotation : Rotation.values()) {
            BlockPos opposite = StructureTemplate.transform(new BlockPos(22, 15, 22), Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
            BoundingBox box = BoundingBox.fromCorners(origin, opposite);
            Terrain terrain = new Terrain();
            reason(helper, MeetingTerrain.assess(terrain, box, LIMITS), MeetingTerrain.Reason.ACCEPTED);
            BlockPos hazard = StructureTemplate.transform(new BlockPos(1, 0, 1), Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
            terrain.top = (x, z) -> x == hazard.getX() && z == hazard.getZ() ? 64 : 68;
            reason(helper, MeetingTerrain.assess(terrain, box, LIMITS), MeetingTerrain.Reason.FOOTPRINT_SLOPE);
        }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void registeredCodecRetainsLimitsAndRejectsUnboundedOrInvertedSettings(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        Structure structure = registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "sylphiette_meeting_gentle"));
        helper.assertTrue(structure instanceof SylphietteMeetingStructure, "Registered landmark uses the filtered structure type");
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonObject json = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow().getAsJsonObject();
        helper.assertValueEqual(json.get("type").getAsString(), "jobless_reincarnation:sylphiette_meeting", "Custom type is registered");
        helper.assertValueEqual(structure.terrainAdaptation(), TerrainAdjustment.NONE, "The sea-relative candidate does not reshape surrounding density");
        Structure decoded = Structure.DIRECT_CODEC.parse(ops, json).getOrThrow();
        helper.assertValueEqual(((SylphietteMeetingStructure) decoded).terrainLimits(), LIMITS, "Bounded terrain parameters survive registry codec round trip");
        for (String field : new String[] {"max_footprint_rise", "buffer_margin", "max_buffer_rise", "min_base_above_sea_level"}) {
            JsonObject invalid = json.deepCopy(); invalid.addProperty(field, 10000);
            helper.assertTrue(Structure.DIRECT_CODEC.parse(ops, invalid).error().isPresent(), "Unbounded parameter rejected: " + field);
        }
        JsonObject inverted = json.deepCopy(); inverted.addProperty("max_buffer_rise", 1);
        try {
            helper.assertTrue(Structure.DIRECT_CODEC.parse(ops, inverted).error().isPresent(), "Buffer rise cannot be smaller than footprint rise");
        } catch (IllegalArgumentException exception) {
            helper.fail("Invalid cross-field JSON must return DataResult.error rather than throw from Limits: " + exception.getMessage());
        }
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void denseColumnsAreReusedWithoutLosingWaterChecksOrChangingCustomSources(GameTestHelper helper) {
        Terrain fallback = new Terrain();
        fallback.top = (x, z) -> x > 22 ? 69 : x >= 11 ? 67 : 64;
        Terrain cached = new Terrain(); cached.top = fallback.top; cached.derive = true;
        var oldResult = assess(fallback); var newResult = assess(cached);
        reason(helper, oldResult, MeetingTerrain.Reason.ACCEPTED);
        reason(helper, newResult, oldResult.reason());
        helper.assertValueEqual(newResult.baseY(), oldResult.baseY(), "Column reuse preserves the occupied base");
        helper.assertValueEqual(newResult.footprintMax(), oldResult.footprintMax(), "Column reuse preserves footprint heights");
        helper.assertValueEqual(newResult.bufferMax(), oldResult.bufferMax(), "Column reuse preserves buffer heights");
        helper.assertTrue(newResult.heightQueries() <= 50, "Dense vanilla reads do not repeat height API work after the coarse pass");
        helper.assertValueEqual(newResult.columnQueries(), 961, "Every one of the 31x31 columns is read once, including the buffer");
        helper.assertValueEqual(cached.reads, newResult.heightQueries() + newResult.columnQueries(), "Counts remain actual source calls, not derived height lookups");
        helper.assertValueEqual(oldResult.heightQueries(), 1922, "Opt-out source still uses both original height APIs over every column");
        helper.assertValueEqual(oldResult.columnQueries(), 529, "Opt-out source still obtains all foundation columns");
        Terrain stream = new Terrain(); stream.derive = true; stream.water.add(Terrain.key(-3, 1));
        reason(helper, assess(stream), MeetingTerrain.Reason.SURFACE_FLUID);
        Terrain lowerGap = new Terrain(); lowerGap.derive = true;
        lowerGap.overrides.put(new BlockPos(1, 62, 1), Blocks.AIR.defaultBlockState());
        reason(helper, assess(lowerGap), MeetingTerrain.Reason.FOUNDATION_VOID);
        // A nonstandard source may deliberately disagree between height and column APIs.
        // Without opt-in it must continue using its own heights instead of silently reinterpreting them.
        Terrain custom = new Terrain(); custom.overrides.put(new BlockPos(1, 65, 1), Blocks.STONE.defaultBlockState());
        var customResult = assess(custom); reason(helper, customResult, MeetingTerrain.Reason.ACCEPTED);
        helper.assertValueEqual(customResult.footprintMax(), 64, "Opt-out source keeps its own height API despite a taller column state");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void vanillaNoiseColumnsMatchBothHeightPredicatesAndSubclassesOptOut(GameTestHelper helper) {
        var level = helper.getLevel(); var registries = level.registryAccess();
        var settings = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomeSource = new FixedBiomeSource(registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var generator = new NoiseBasedChunkGenerator(biomeSource, settings);
        var random = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), 42L);
        for (int[] point : new int[][] {{0, 0}, {1, -1}, {-600, 395}, {-2355, -1316}}) {
            NoiseColumn column = generator.getBaseColumn(point[0], point[1], level, random);
            var heights = MeetingTerrain.columnHeights(column, level.getMinBuildHeight(), level.getMaxBuildHeight());
            helper.assertValueEqual(heights.worldSurface(), generator.getBaseHeight(point[0], point[1], Heightmap.Types.WORLD_SURFACE_WG, level, random),
                    "World-surface height derived from a real vanilla noise column matches its API");
            helper.assertValueEqual(heights.oceanFloor(), generator.getBaseHeight(point[0], point[1], Heightmap.Types.OCEAN_FLOOR_WG, level, random),
                    "Ocean-floor height derived from a real vanilla noise column matches its API");
        }
        var context = new Structure.GenerationContext(registries, generator, biomeSource, random, level.getStructureManager(),
                42L, new ChunkPos(0, 0), level, biome -> true);
        helper.assertTrue(MeetingTerrain.noiseSource(context).deriveHeightsFromColumn(), "Exact vanilla generator enables proven column derivation");
        helper.assertValueEqual(MeetingTerrain.noiseSource(context).seaLevel(), generator.getSeaLevel(), "Sea-level reference comes from the generator settings");
        var subclass = new NoiseBasedChunkGenerator(biomeSource, settings) { };
        var subclassContext = new Structure.GenerationContext(registries, subclass, biomeSource, random, level.getStructureManager(),
                42L, new ChunkPos(0, 0), level, biome -> true);
        helper.assertFalse(MeetingTerrain.noiseSource(subclassContext).deriveHeightsFromColumn(), "A potentially overridden generator subclass keeps its APIs");
        BlockState[] empty = {Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()};
        var emptyHeights = MeetingTerrain.columnHeights(new NoiseColumn(10, empty), 10, 12);
        helper.assertValueEqual(emptyHeights.worldSurface(), 10, "Missing opaque surface returns min build height");
        helper.assertValueEqual(emptyHeights.oceanFloor(), 10, "Missing motion-blocking floor returns min build height");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void seaRelativeMinimumIsInclusiveAndDensePassFindsUnsampledLowColumn(GameTestHelper helper) {
        Terrain boundary = new Terrain();
        reason(helper, assess(boundary), MeetingTerrain.Reason.ACCEPTED); // occupied63 == sea61 + margin2
        Terrain higherSea = new Terrain(); higherSea.sea = 62;
        var low = assess(higherSea); reason(helper, low, MeetingTerrain.Reason.LOW_GROUND);
        helper.assertTrue(low.heightQueries() <= 25 && low.columnQueries() == 0, "Coarse low ground rejection avoids dense column work");
        Terrain lowerSea = new Terrain(); lowerSea.sea = 50;
        reason(helper, assess(lowerSea), MeetingTerrain.Reason.ACCEPTED);
        Terrain overflow = new Terrain(); overflow.sea = Integer.MAX_VALUE;
        reason(helper, assess(overflow), MeetingTerrain.Reason.LOW_GROUND);
        Terrain narrow = new Terrain(); narrow.derive = true;
        narrow.top = (x, z) -> x == 1 && z == 1 ? 63 : 64;
        var found = assess(narrow); reason(helper, found, MeetingTerrain.Reason.LOW_GROUND);
        helper.assertTrue(found.columnQueries() > 0, "Full dense coverage finds a low column missed by the coarse grid");
        helper.succeed();
    }
    @GameTest(template = ARENA, batch = BATCH)
    public static void secondLayerBelowTemplateRejectsAirWaterAndOutOfRangeSupport(GameTestHelper helper) {
        Terrain gap = new Terrain(); gap.derive = true;
        gap.overrides.put(new BlockPos(1, 61, 1), Blocks.AIR.defaultBlockState());
        reason(helper, assess(gap), MeetingTerrain.Reason.FOUNDATION_VOID);
        Terrain wet = new Terrain(); wet.derive = true;
        wet.overrides.put(new BlockPos(1, 61, 1), Blocks.WATER.defaultBlockState());
        reason(helper, assess(wet), MeetingTerrain.Reason.FOUNDATION_FLUID);
        Terrain lowest = new Terrain(); lowest.sea = -64; lowest.top = (x, z) -> -61;
        reason(helper, assess(lowest), MeetingTerrain.Reason.ACCEPTED); // base -62, support -63 and minY -64
        Terrain outside = new Terrain(); outside.sea = -64; outside.top = (x, z) -> -62;
        reason(helper, assess(outside), MeetingTerrain.Reason.INVALID_HEIGHT);
        helper.succeed();
    }
}
