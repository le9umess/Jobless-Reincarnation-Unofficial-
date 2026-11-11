package dev.joblessreincarnation.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The fixed, dry, flat set of cells shared by placement, rendering and live contact checks. */
public final class MireGround {
    private static final double POSITION_EPSILON = 1.0E-7;

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
        if (!finite(base)
                || Math.abs(base.x - (Math.floor(base.x) + 0.5)) > POSITION_EPSILON
                || Math.abs(base.z - (Math.floor(base.z) + 0.5)) > POSITION_EPSILON
                || Math.abs(base.y - Math.rint(base.y)) > POSITION_EPSILON) return false;
        BlockPos center = BlockPos.containing(base);
        // Finish the non-loading checks for the entire footprint before any block-state/shape lookup.
        for (BlockPos offset : MireEntity.CELL_OFFSETS) {
            BlockPos feet = center.offset(offset);
            if (level.isOutsideBuildHeight(feet.below()) || level.isOutsideBuildHeight(feet.above())
                    || !level.getWorldBorder().isWithinBounds(new AABB(feet))
                    || !level.hasChunkAt(feet) || !level.isPositionEntityTicking(feet)) return false;
        }
        for (BlockPos offset : MireEntity.CELL_OFFSETS) {
            BlockPos feet = center.offset(offset);
            BlockPos support = feet.below();
            BlockPos head = feet.above();
            if (!level.getFluidState(support).isEmpty() || !level.getFluidState(feet).isEmpty()
                    || !level.getFluidState(head).isEmpty()
                    || level.getBlockState(feet).is(Blocks.SNOW) || level.getBlockState(head).is(Blocks.SNOW)
                    || level.getBlockState(feet).is(Blocks.POWDER_SNOW) || level.getBlockState(head).is(Blocks.POWDER_SNOW)
                    || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
                    || !Block.isFaceFull(level.getBlockState(support).getCollisionShape(level, support), Direction.UP)
                    || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
        }
        return true;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private MireGround() {}
}