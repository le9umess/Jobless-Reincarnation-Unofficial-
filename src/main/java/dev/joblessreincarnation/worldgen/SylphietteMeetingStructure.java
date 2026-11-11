package dev.joblessreincarnation.worldgen;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/** One rigid tree clearing, selected before terrain generation without loading neighbouring chunks. */
public final class SylphietteMeetingStructure extends Structure {
    // Decode bounded raw fields, validate their relationship, then construct the strongly constrained Limits.
    private record Parameters(StructureSettings settings, Holder<StructureTemplatePool> startPool,
                              int footprintRise, int bufferMargin, int bufferRise, int minBaseAboveSeaLevel) { }
    public static final MapCodec<SylphietteMeetingStructure> CODEC = RecordCodecBuilder.<Parameters>mapCodec(instance -> instance.group(
            StructureSettings.CODEC.forGetter(Parameters::settings),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(Parameters::startPool),
            Codec.intRange(0, 4).optionalFieldOf("max_footprint_rise", 3).forGetter(Parameters::footprintRise),
            Codec.intRange(1, 8).optionalFieldOf("buffer_margin", 4).forGetter(Parameters::bufferMargin),
            Codec.intRange(0, 8).optionalFieldOf("max_buffer_rise", 5).forGetter(Parameters::bufferRise),
            Codec.intRange(0, 8).optionalFieldOf("min_base_above_sea_level", 2).forGetter(Parameters::minBaseAboveSeaLevel)
    ).apply(instance, Parameters::new)).validate(value -> value.bufferRise() >= value.footprintRise()
            ? DataResult.success(value) : DataResult.error(() -> "max_buffer_rise must be at least max_footprint_rise"))
            .xmap(value -> new SylphietteMeetingStructure(value.settings(), value.startPool(), value.footprintRise(), value.bufferMargin(), value.bufferRise(), value.minBaseAboveSeaLevel()),
                    value -> new Parameters(value.modifiableStructureInfo().getOriginalStructureInfo().structureSettings(),
                            value.startPool, value.limits.footprintRise(), value.limits.bufferMargin(), value.limits.bufferRise(), value.limits.minBaseAboveSeaLevel()));
    private final Holder<StructureTemplatePool> startPool;
    private final MeetingTerrain.Limits limits;

    public enum InspectionStatus { EMPTY_START, BIOME_REJECTED, FINAL_BIOME_REJECTED, INVALID_PIECE,
        TERRAIN_REJECTED, INVALID_SHIFT, ACCEPTED }
    /** One actual attempt, with no counters or mutable global diagnostic state. */
    public record Inspection(InspectionStatus status, Optional<GenerationStub> stub,
                             @Nullable MeetingTerrain.Assessment terrain,
                             @Nullable BlockPos originalPosition, @Nullable BlockPos finalPosition) { }

    public SylphietteMeetingStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool,
                                     int footprintRise, int bufferMargin, int bufferRise, int minBaseAboveSeaLevel) {
        super(settings);
        this.startPool = startPool;
        this.limits = new MeetingTerrain.Limits(footprintRise, bufferMargin, bufferRise, minBaseAboveSeaLevel);
    }
    public MeetingTerrain.Limits terrainLimits() { return limits; }
    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        return inspect(context).stub();
    }
    /** Used by generation and independent surveys: build at most once, never generate blocks/entities. */
    public Inspection inspect(GenerationContext context) {
        BlockPos start = new BlockPos(context.chunkPos().getMinBlockX(), 0, context.chunkPos().getMinBlockZ());
        Optional<GenerationStub> attempt = JigsawPlacement.addPieces(context, startPool, Optional.empty(), 1, start,
                false, Optional.of(Heightmap.Types.WORLD_SURFACE_WG), 48, PoolAliasLookup.EMPTY,
                DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
        if (attempt.isEmpty()) return rejected(InspectionStatus.EMPTY_START, null, null, null);
        GenerationStub stub = attempt.get();
        BlockPos original = stub.position();
        if (!hasPossibleBiome(context, original)) return rejected(InspectionStatus.BIOME_REJECTED, null, original, null);
        var builder = stub.getPiecesBuilder(); // Consume the random-dependent builder exactly once.
        var pieces = builder.build().pieces();
        if (pieces.size() != 1 || !(pieces.getFirst() instanceof PoolElementStructurePiece piece)
                || piece.getElement().getProjection() != StructureTemplatePool.Projection.RIGID)
            return rejected(InspectionStatus.INVALID_PIECE, null, original, null);
        MeetingTerrain.Assessment assessment = MeetingTerrain.assess(MeetingTerrain.noiseSource(context), piece.getBoundingBox(), limits);
        if (!assessment.accepted()) return rejected(InspectionStatus.TERRAIN_REJECTED, assessment, original, null);
        int offset = assessment.baseY() - piece.getBoundingBox().minY();
        if (offset > 0 || offset < -limits.footprintRise())
            return rejected(InspectionStatus.INVALID_SHIFT, assessment, original, null);
        BlockPos moved = original.offset(0, offset, 0);
        if (!validBiome(context, moved)) return rejected(InspectionStatus.FINAL_BIOME_REJECTED, assessment, original, moved);
        piece.move(0, offset, 0);
        return new Inspection(InspectionStatus.ACCEPTED,
                Optional.of(new GenerationStub(moved, Either.right(builder))), assessment, original, moved);
    }
    private static Inspection rejected(InspectionStatus status, @Nullable MeetingTerrain.Assessment terrain,
                                       @Nullable BlockPos original, @Nullable BlockPos moved) {
        return new Inspection(status, Optional.empty(), terrain, original, moved);
    }
    private static boolean validBiome(GenerationContext context, BlockPos position) {
        return context.validBiome().test(context.chunkGenerator().getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(position.getX()), QuartPos.fromBlock(position.getY()), QuartPos.fromBlock(position.getZ()),
                context.randomState().sampler()));
    }
    private boolean hasPossibleBiome(GenerationContext context, BlockPos position) {
        // The final downward move is bounded by footprintRise. Only reject when every possible
        // final quart-height is invalid; inspect and Structure both check the actual moved stub.
        int previousQuart = Integer.MAX_VALUE;
        for (int shift = 0; shift <= limits.footprintRise(); shift++) {
            int quartY = QuartPos.fromBlock(position.getY() - shift);
            if (quartY == previousQuart) continue;
            previousQuart = quartY;
            if (validBiome(context, position.offset(0, -shift, 0))) return true;
        }
        return false;
    }
    @Override public StructureType<?> type() { return ModStructures.SYLPHIETTE_MEETING.get(); }
}
