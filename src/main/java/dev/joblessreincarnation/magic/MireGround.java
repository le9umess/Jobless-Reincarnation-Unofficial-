package dev.joblessreincarnation.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Samples the dry standing surfaces under the 21-cell footprint without requesting chunks. */
public final class MireGround {
    private static final double POSITION_EPSILON = 1.0E-7;

    /** A one-block relief has at most one other elevation relative to the aimed cell. */
    public record Surface(int step, int steppedCells) {
        public int offsetAt(int dx, int dz) {
            return (steppedCells & cellBit(dx, dz)) == 0 ? 0 : step;
        }
    }

    private static int cellBit(int dx, int dz) {
        return 1 << ((dx + 2) * 5 + dz + 2);
    }

    /**
     * A top-face hit within the aiming range selects the centre cell. The whole footprint is then checked without
     * requesting an absent chunk. Empty-collision plants are allowed; carpets, snow layers and fluids are not.
     */
    @Nullable
    public static Vec3 findBase(ServerLevel level, ServerPlayer player) {
        if (player.serverLevel() != level) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(MireSpell.RANGE));
        if (!finite(eye) || !finite(end)) return null;
        BlockPos unloaded = BlockGetter.traverseBlocks(eye, end, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        if (unloaded != null) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP || hit.isInside()) return null;
        BlockPos feet = hit.getBlockPos().above();
        Vec3 base = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        return isValid(level, base) ? base : null;
    }

    /** Checks every one of the 21 displayed cells; the four absent corner cells are deliberately not consulted. */
    public static boolean isValid(ServerLevel level, Vec3 base) {
        return sample(level, base) != null;
    }

    @Nullable
    static Surface sample(Level level, Vec3 base) {
        if (!finite(base)
                || Math.abs(base.x - (Math.floor(base.x) + 0.5)) > POSITION_EPSILON
                || Math.abs(base.z - (Math.floor(base.z) + 0.5)) > POSITION_EPSILON
                || Math.abs(base.y - Math.rint(base.y)) > POSITION_EPSILON) return null;
        BlockPos center = BlockPos.containing(base);
        // Finish the non-loading checks for the entire footprint before any block-state/shape lookup.
        for (BlockPos offset : MireEntity.CELL_OFFSETS) {
            BlockPos feet = center.offset(offset);
            if (!level.getWorldBorder().isWithinBounds(new AABB(feet)) || !level.hasChunkAt(feet)
                    || level instanceof ServerLevel server && !server.isPositionEntityTicking(feet)) return null;
        }
        int step = 0, steppedCells = 0;
        for (BlockPos offset : MireEntity.CELL_OFFSETS) {
            BlockPos feet = center.offset(offset);
            if (canStand(level, feet)) continue;
            // The centre is the aimed top face. Mixing +1 and -1 elsewhere would span two blocks.
            if (offset.equals(BlockPos.ZERO)) return null;
            if (step == 0) {
                if (canStand(level, feet.above())) step = 1;
                else if (canStand(level, feet.below())) step = -1;
                else return null;
            } else if (!canStand(level, feet.above(step))) return null;
            steppedCells |= cellBit(offset.getX(), offset.getZ());
        }
        return new Surface(step, steppedCells);
    }

    private static boolean canStand(Level level, BlockPos feet) {
        BlockPos support = feet.below();
        if (level.isOutsideBuildHeight(support) || level.isOutsideBuildHeight(feet.above())) return false;
        var floor = level.getBlockState(support);
        return floor.getFluidState().isEmpty() && !floor.is(Blocks.SNOW) && !floor.is(Blocks.POWDER_SNOW)
                && floor.isFaceSturdy(level, support, Direction.UP)
                && Block.isFaceFull(floor.getCollisionShape(level, support), Direction.UP)
                && clear(level, feet) && clear(level, feet.above());
    }

    private static boolean clear(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !state.is(Blocks.SNOW) && !state.is(Blocks.POWDER_SNOW)
                && state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private MireGround() {}
}
