package dev.joblessreincarnation.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Never asks the chunk source to generate terrain. A top surface or water surface is required. */
public final class IcicleGround {
    public static boolean loaded(ServerLevel level, Vec3 from, Vec3 to) {
        if(!finite(from)||!finite(to))return false;
        return BlockGetter.traverseBlocks(from,to,level,(world,p)->world.hasChunkAt(p)?null:p.immutable(),world->null)==null;
    }
    public static boolean valid(ServerLevel level, Vec3 centre) {
        if(!Double.isFinite(centre.x)||!Double.isFinite(centre.y)||!Double.isFinite(centre.z))return false;
        BlockPos c=BlockPos.containing(centre);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
            BlockPos p=c.offset(x,0,z);
            if(level.isOutsideBuildHeight(p.below(3))||level.isOutsideBuildHeight(p.above(3))
                ||!level.getWorldBorder().isWithinBounds(p)||!level.hasChunkAt(p)||!level.isPositionEntityTicking(p))return false;
        }
        var floor=level.getBlockState(c.below());
        return !floor.getFluidState().isEmpty() || floor.isFaceSturdy(level,c.below(),Direction.UP);
    }
    public static @Nullable Vec3 findBase(ServerLevel level, ServerPlayer player) {
        Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(IcicleSpell.RANGE));
        if(!loaded(level,eye,end))return null;
        var hit=level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.SOURCE_ONLY,player));
        if(hit.getType()!=HitResult.Type.BLOCK||hit.getDirection()!=Direction.UP||hit.isInside())return null;
        Vec3 centre=Vec3.atBottomCenterOf(hit.getBlockPos().above());
        return valid(level,centre)?centre:null;
    }
    public static boolean visible(ServerLevel level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity context) {
        return loaded(level,from,to)&&level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,context)).getType()==HitResult.Type.MISS;
    }
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private IcicleGround(){}
}
