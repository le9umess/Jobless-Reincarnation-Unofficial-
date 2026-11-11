package dev.joblessreincarnation.worldgen;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/**
 * The family home: one rigid house and yard. Plains can run right up a mountainside, so it refuses ground too rough to
 * carry a level yard, and sits at the middle height of the ground rather than at whatever one corner of it happens to be.
 * Water counts as ground at its surface, so a home beside a lake or the sea is still allowed.
 */
public final class GreyratHomeStructure extends Structure {
    public static final MapCodec<GreyratHomeStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            settingsCodec(instance),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(home -> home.startPool),
            Codec.intRange(0, 64).fieldOf("max_footprint_relief").forGetter(home -> home.limits.footprintRelief()),
            Codec.intRange(0, 32).fieldOf("buffer_margin").forGetter(home -> home.limits.bufferMargin()),
            Codec.intRange(0, 64).fieldOf("max_buffer_relief").forGetter(home -> home.limits.bufferRelief())
    ).apply(instance, GreyratHomeStructure::new));
    private static final int MAX_DISTANCE_FROM_CENTER = 80;
    /** Sampling every few blocks: enough to find a mountainside, and cheap, since it runs once per candidate. */
    private static final int SAMPLES_PER_SIDE = 5;
    /** Where homes were placed before the larger house. A start saved at the edge of explored ground stays, and the chunks beside it may still be new. */
    public static final RandomSpreadStructurePlacement OLD_GRID = new RandomSpreadStructurePlacement(40, 12, RandomSpreadType.LINEAR, 168397479);
    /** A home reaches at most 39 blocks from its start and an old one 31, so 5 chunks always clears them. */
    public static final int OLD_GRID_CLEARANCE = 5;

    /** Highest minus lowest ground allowed under the home, and within {@code bufferMargin} blocks around it. */
    public record Limits(int footprintRelief, int bufferMargin, int bufferRelief) { }
    public record Assessment(boolean accepted, int groundY, int footprintRelief, int bufferRelief) { }
    @FunctionalInterface public interface Heights { int at(int x, int z); }

    private final Holder<StructureTemplatePool> startPool;
    private final Limits limits;

    public GreyratHomeStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool, int footprintRelief, int bufferMargin, int bufferRelief) {
        super(settings);
        this.startPool = startPool;
        this.limits = new Limits(footprintRelief, bufferMargin, bufferRelief);
    }

    public Limits limits() { return limits; }

    public static Assessment assess(Heights heights, BoundingBox box, Limits limits) {
        int[] inside = sample(heights, box.minX(), box.maxX(), box.minZ(), box.maxZ());
        Arrays.sort(inside);
        int ground = inside[inside.length / 2], footprint = inside[inside.length - 1] - inside[0];
        if (footprint > limits.footprintRelief()) return new Assessment(false, ground, footprint, -1);
        int margin = limits.bufferMargin();
        int[] around = sample(heights, box.minX() - margin, box.maxX() + margin, box.minZ() - margin, box.maxZ() + margin);
        Arrays.sort(around);
        int buffer = Math.max(around[around.length - 1], inside[inside.length - 1]) - Math.min(around[0], inside[0]);
        return new Assessment(buffer <= limits.bufferRelief(), ground, footprint, buffer);
    }

    private static int[] sample(Heights heights, int minX, int maxX, int minZ, int maxZ) {
        int[] values = new int[SAMPLES_PER_SIDE * SAMPLES_PER_SIDE];
        for (int i = 0; i < SAMPLES_PER_SIDE; i++) for (int j = 0; j < SAMPLES_PER_SIDE; j++) {
            values[i * SAMPLES_PER_SIDE + j] = heights.at(minX + (maxX - minX) * i / (SAMPLES_PER_SIDE - 1), minZ + (maxZ - minZ) * j / (SAMPLES_PER_SIDE - 1));
        }
        return values;
    }

    /** One actual attempt, for generation and for surveys: it builds the rotated piece once and judges that same piece. */
    public record Inspection(Optional<GenerationStub> stub, Optional<Assessment> assessment, Optional<BoundingBox> box) { }

    public Inspection inspect(GenerationContext context) {
        BlockPos start = new BlockPos(context.chunkPos().getMinBlockX(), 0, context.chunkPos().getMinBlockZ());
        Optional<GenerationStub> attempt = JigsawPlacement.addPieces(context, startPool, Optional.empty(), 1, start, false,
                Optional.of(Heightmap.Types.WORLD_SURFACE_WG), MAX_DISTANCE_FROM_CENTER, PoolAliasLookup.EMPTY, DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
        if (attempt.isEmpty()) return new Inspection(Optional.empty(), Optional.empty(), Optional.empty());
        GenerationStub stub = attempt.get();
        var builder = stub.getPiecesBuilder(); // Consume the random-dependent builder exactly once.
        var pieces = builder.build().pieces();
        if (pieces.size() != 1 || !(pieces.getFirst() instanceof PoolElementStructurePiece piece)) return new Inspection(Optional.empty(), Optional.empty(), Optional.empty());
        Assessment assessment = assess((x, z) -> context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(), context.randomState()), piece.getBoundingBox(), limits);
        if (!assessment.accepted()) return new Inspection(Optional.empty(), Optional.of(assessment), Optional.of(piece.getBoundingBox()));
        // A rigid piece stands with its y=1 on the first free block, the same reference vanilla projection uses for its one sampled corner.
        int offset = assessment.groundY() - (piece.getBoundingBox().minY() + piece.getGroundLevelDelta());
        piece.move(0, offset, 0);
        return new Inspection(Optional.of(new GenerationStub(stub.position().offset(0, offset, 0), Either.right(builder))), Optional.of(assessment), Optional.of(piece.getBoundingBox()));
    }

    /** True close to any spot the old grid could have used. It does not look for a real old home: this has to give the same answer in a new world and an old one. */
    public static boolean nearOldGrid(long seed, ChunkPos at) {
        int spacing = OLD_GRID.spacing(), range = OLD_GRID_CLEARANCE;
        for (int cellX = Math.floorDiv(at.x - range, spacing); cellX <= Math.floorDiv(at.x + range, spacing); cellX++) {
            for (int cellZ = Math.floorDiv(at.z - range, spacing); cellZ <= Math.floorDiv(at.z + range, spacing); cellZ++) {
                ChunkPos old = OLD_GRID.getPotentialStructureChunk(seed, cellX * spacing, cellZ * spacing);
                if (Math.abs(old.x - at.x) <= range && Math.abs(old.z - at.z) <= range) return true;
            }
        }
        return false;
    }

    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        return nearOldGrid(context.seed(), context.chunkPos()) ? Optional.empty() : inspect(context).stub();
    }

    @Override public StructureType<?> type() { return ModStructures.GREYRAT_HOME.get(); }
}
