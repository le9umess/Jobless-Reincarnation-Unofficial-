package dev.joblessreincarnation.worldgen;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Bounded, candidate-local reads of generator noise; never requests a live world chunk. */
public final class MeetingTerrain {
    public static final int MAX_SPAN = 32;
    private MeetingTerrain() { }

    public record Limits(int footprintRise, int bufferMargin, int bufferRise, int minBaseAboveSeaLevel) {
        public Limits {
            if (footprintRise < 0 || footprintRise > 4 || bufferMargin < 1 || bufferMargin > 8
                    || bufferRise < footprintRise || bufferRise > 8 || minBaseAboveSeaLevel < 0 || minBaseAboveSeaLevel > 8) throw new IllegalArgumentException("Invalid meeting terrain limits");
        }
    }
    public enum Reason { ACCEPTED, INVALID_BOUNDS, INVALID_HEIGHT, LOW_GROUND, FOOTPRINT_SLOPE, BUFFER_SLOPE,
        SURFACE_FLUID, FOUNDATION_FLUID, FOUNDATION_VOID }
    public record Assessment(Reason reason, int baseY, int footprintMin, int footprintMax,
                             int bufferMin, int bufferMax, int heightQueries, int columnQueries) {
        public boolean accepted() { return reason == Reason.ACCEPTED; }
    }
    /** Read-only seam also used by deterministic terrain fixtures and offline candidate surveys. */
    public interface Source {
        int height(int x, int z, Heightmap.Types type);
        NoiseColumn column(int x, int z);
        int minY();
        int maxY();
        int seaLevel();
        /** Only opt in where a full column is proven equivalent to both height APIs. */
        default boolean deriveHeightsFromColumn() { return false; }
    }
    public static Source noiseSource(Structure.GenerationContext context) {
        return new Source() {
            @Override public int height(int x, int z, Heightmap.Types type) {
                return context.chunkGenerator().getBaseHeight(x, z, type, context.heightAccessor(), context.randomState());
            }
            @Override public NoiseColumn column(int x, int z) {
                return context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState());
            }
            @Override public boolean deriveHeightsFromColumn() {
                // A subclass can change either API; it must keep its original API behavior.
                return context.chunkGenerator().getClass() == NoiseBasedChunkGenerator.class;
            }
            @Override public int minY() { return context.heightAccessor().getMinBuildHeight(); }
            @Override public int maxY() { return context.heightAccessor().getMaxBuildHeight(); }
            @Override public int seaLevel() { return context.chunkGenerator().getSeaLevel(); }
        };
    }
    public record ColumnHeights(int worldSurface, int oceanFloor) { }
    /** Same predicates, top-down order and no-match result used by vanilla iterateNoiseColumn. */
    public static ColumnHeights columnHeights(NoiseColumn column, int minY, int maxY) {
        int surface = minY, floor = minY;
        boolean foundSurface = false, foundFloor = false;
        var surfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        var floorPredicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        for (int y = maxY - 1; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (!foundSurface && surfacePredicate.test(state)) { surface = y + 1; foundSurface = true; }
            if (!foundFloor && floorPredicate.test(state)) { floor = y + 1; foundFloor = true; }
            if (foundSurface && foundFloor) break;
        }
        return new ColumnHeights(surface, floor);
    }
    public static Assessment assess(Source source, BoundingBox footprint, Limits limits) {
        return new Scan(source, footprint, limits).run();
    }
    private static final class Scan {
        private final Source source;
        private final BoundingBox box;
        private final Limits limits;
        private final long minimumBaseY;
        private final Map<Long, Integer> heights = new HashMap<>();
        private final Map<Long, NoiseColumn> columns = new HashMap<>();
        private final Map<Long, ColumnHeights> columnHeightmaps = new HashMap<>();
        private boolean reuseColumns;
        private int footprintMin = Integer.MAX_VALUE, footprintMax = Integer.MIN_VALUE;
        private int bufferMin = Integer.MAX_VALUE, bufferMax = Integer.MIN_VALUE;
        private int heightQueries, columnQueries;
        Scan(Source source, BoundingBox box, Limits limits) {
            this.source = source; this.box = box; this.limits = limits;
            this.minimumBaseY = (long) source.seaLevel() + limits.minBaseAboveSeaLevel();
        }
        Assessment finish(Reason reason) {
            return new Assessment(reason, footprintMin == Integer.MAX_VALUE ? source.minY() : footprintMin - 1,
                    footprintMin, footprintMax, bufferMin, bufferMax, heightQueries, columnQueries);
        }
        boolean inside(int x, int z) { return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ(); }
        static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
        NoiseColumn column(int x, int z) {
            return columns.computeIfAbsent(key(x, z), ignored -> { columnQueries++; return source.column(x, z); });
        }
        ColumnHeights derivedHeights(int x, int z) {
            return columnHeightmaps.computeIfAbsent(key(x, z), ignored -> {
                ColumnHeights result = columnHeights(column(x, z), source.minY(), source.maxY());
                heights.put(key(x, z), result.worldSurface());
                return result;
            });
        }
        int oceanFloor(int x, int z) {
            if (reuseColumns) return derivedHeights(x, z).oceanFloor();
            heightQueries++;
            return source.height(x, z, Heightmap.Types.OCEAN_FLOOR_WG);
        }
        int surface(int x, int z) {
            if (reuseColumns) return derivedHeights(x, z).worldSurface();
            return heights.computeIfAbsent(key(x, z), ignored -> { heightQueries++; return source.height(x, z, Heightmap.Types.WORLD_SURFACE_WG); });
        }
        Reason heightCheck(int x, int z) {
            int y = surface(x, z);
            if ((long) y < (long) source.minY() + 3 || y >= source.maxY()) return Reason.INVALID_HEIGHT;
            bufferMin = Math.min(bufferMin, y); bufferMax = Math.max(bufferMax, y);
            if (inside(x, z)) {
                footprintMin = Math.min(footprintMin, y); footprintMax = Math.max(footprintMax, y);
                // Any sampled low occupied column bounds the final minimum from above; reject early.
                // This sea-relative heuristic does not predict later carvers or local aquifer water levels.
                if ((long) footprintMin - 1 < minimumBaseY) return Reason.LOW_GROUND;
                if (footprintMax - footprintMin > limits.footprintRise()) return Reason.FOOTPRINT_SLOPE;
            }
            return bufferMax - bufferMin > limits.bufferRise() ? Reason.BUFFER_SLOPE : Reason.ACCEPTED;
        }
        Reason grid(int minX, int maxX, int minZ, int maxZ) {
            for (int i = 0; i <= 4; i++) for (int j = 0; j <= 4; j++) {
                Reason reason = heightCheck(minX + (maxX - minX) * i / 4, minZ + (maxZ - minZ) * j / 4);
                if (reason != Reason.ACCEPTED) return reason;
            }
            return Reason.ACCEPTED;
        }
        Assessment run() {
            int margin = limits.bufferMargin();
            // Reject before allocating/sampling, including overflowing data-pack supplied coordinates.
            if (limits.bufferRise() < limits.footprintRise() || box.getXSpan() < 7 || box.getXSpan() > MAX_SPAN || box.getZSpan() < 7 || box.getZSpan() > MAX_SPAN
                    || box.getYSpan() < 1 || box.getYSpan() > 64
                    || box.minX() < Integer.MIN_VALUE + margin || box.maxX() >= Integer.MAX_VALUE - margin
                    || box.minZ() < Integer.MIN_VALUE + margin || box.maxZ() >= Integer.MAX_VALUE - margin) return finish(Reason.INVALID_BOUNDS);
            int minX = box.minX() - margin, maxX = box.maxX() + margin;
            int minZ = box.minZ() - margin, maxZ = box.maxZ() + margin;
            Reason reason = grid(box.minX(), box.maxX(), box.minZ(), box.maxZ());
            if (reason != Reason.ACCEPTED) return finish(reason);
            reason = grid(minX, maxX, minZ, maxZ);
            if (reason != Reason.ACCEPTED) return finish(reason);
            // Exact vanilla noise columns serve both heightmaps and the later support checks.
            // Coarse checks above still use the cheaper stopping-height API.
            reuseColumns = source.deriveHeightsFromColumn();
            // Full resolution catches one-column pits and streams that corner sampling misses.
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                reason = heightCheck(x, z);
                if (reason != Reason.ACCEPTED) return finish(reason);
                if (oceanFloor(x, z) != surface(x, z)) return finish(Reason.SURFACE_FLUID);
            }
            int base = footprintMin - 1;
            if ((long) base + box.getYSpan() > source.maxY()) return finish(Reason.INVALID_HEIGHT);
            for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
                NoiseColumn column = column(x, z);
                BlockState top = column.getBlock(surface(x, z) - 1);
                if (!top.getFluidState().isEmpty()) return finish(Reason.SURFACE_FLUID);
                for (int y = base - 2; y <= base - 1; y++) {
                    BlockState support = column.getBlock(y);
                    if (!support.getFluidState().isEmpty()) return finish(Reason.FOUNDATION_FLUID);
                    if (support.isAir() || !support.blocksMotion()) return finish(Reason.FOUNDATION_VOID);
                }
            }
            return finish(Reason.ACCEPTED);
        }
    }
}
