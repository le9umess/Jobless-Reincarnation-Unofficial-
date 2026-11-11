package dev.joblessreincarnation.encounter;

import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcLoadedChunks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Finds a real, already-loaded clearing without flattening terrain or editing a player's house. */
public record PaulDuelArena(Vec3 centre, int radius) {
    // Include the field beyond the furnished courtyard and its outer crop strip, even when Paul is at the back of his stroll.
    private static final int SEARCH = 30, PATH_RANGE = 48;
    public static final int APPROACH_RANGE = 36;
    public boolean contains(Vec3 position) {
        double dx=position.x-centre.x,dz=position.z-centre.z;
        return dx*dx+dz*dz <= radius*radius && Math.abs(position.y-centre.y) <= 3;
    }
    public boolean near(Vec3 p, int horizontal, int vertical) {
        double dx=p.x-centre.x,dz=p.z-centre.z;
        return dx*dx+dz*dz <= horizontal*horizontal && Math.abs(p.y-centre.y)<=vertical;
    }
    public static @Nullable PaulDuelArena find(FamilyResidentEntity paul) {
        if (!(paul.level() instanceof ServerLevel level) || !paul.onGround() || paul.isPassenger() || paul.isVehicle()) return null;
        // createPath updates its cached target even when merely probing. An unrelated live stroll would then be
        // returned as the next candidate's "reachable" path. A confirmed invitation first ends that old navigation.
        paul.getNavigation().stop();
        // Match ground navigation's standing-cell rounding on a 15/16-height dirt path.
        BlockPos origin=BlockPos.containing(paul.getX(),paul.getY()+.5,paul.getZ()); List<BlockPos> centres=new ArrayList<>();
        for(int x=-SEARCH;x<=SEARCH;x+=3)for(int z=-SEARCH;z<=SEARCH;z+=3)
            if(x*x+z*z<=SEARCH*SEARCH)centres.add(origin.offset(x,0,z));
        centres.sort(Comparator.comparingDouble(p->p.distSqr(origin)));
        for(int radius=8;radius>=6;radius--) for(BlockPos at:centres) {
            if (!clear(level,at,radius))continue;
            Vec3 centre=Vec3.atBottomCenterOf(at);
            if (route(paul,at)!=null)return new PaulDuelArena(centre,radius);
        }
        return null;
    }
    /** Use the same bounded native search for the outward walk and the return through an open courtyard gate. */
    public static @Nullable Path route(FamilyResidentEntity paul,BlockPos target) {
        if (!(paul.level() instanceof ServerLevel level))return null;
        // PathNavigation's explicit-range overload builds a region with eight blocks of padding. Check that region
        // before asking it to search, so a practice invitation never synchronously generates missing chunks.
        int shortest=Math.max(2,(int)Math.ceil(paul.position().distanceTo(Vec3.atBottomCenterOf(target)))+1);
        for(int range=shortest;range<=PATH_RANGE;range=Math.min(PATH_RANGE,range+12)) {
            if(!NpcLoadedChunks.ready(level,new AABB(paul.blockPosition()).inflate(range+8,6,range+8)))return null;
            // WATER_BORDER is dry ground beside water, including the one-block path over the home's irrigation ditch.
            // Its vanilla cost makes this short bridge lose to a long dead end around the field. Change only this
            // synchronous search, then restore the actor's original cost; actual water and all collision checks remain.
            float bankCost=paul.getPathfindingMalus(PathType.WATER_BORDER);
            Path path;
            try {
                if(bankCost>0)paul.setPathfindingMalus(PathType.WATER_BORDER,0);
                path=paul.getNavigation().createPath(target,0,range);
            } finally { paul.setPathfindingMalus(PathType.WATER_BORDER,bankCost); }
            if(path!=null&&path.canReach()) {
                for(int i=0;i<path.getNodeCount();i++) {
                    var node=path.getNode(i);BlockPos feet=node.asBlockPos();
                    if(node.type==PathType.WATER_BORDER && (!level.getFluidState(feet).isEmpty()
                            || !level.getFluidState(feet.below()).isEmpty()
                            || level.getBlockState(feet.below()).getCollisionShape(level,feet.below()).isEmpty()))return null;
                    if(!NpcLoadedChunks.ready(level,paul.getBoundingBox().move(path.getEntityPosAtNode(paul,i).subtract(paul.position())).inflate(.1)))return null;
                }
                return path;
            }
            if(range==PATH_RANGE)break;
        }
        return null;
    }
    public @Nullable Path route(FamilyResidentEntity paul){return route(paul,BlockPos.containing(centre));}
    public static boolean clear(ServerLevel level,BlockPos at,int radius) {
        if (radius<6||radius>8||!NpcLoadedChunks.ready(level,new AABB(at).inflate(radius+1,6,radius+1)))return false;
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(x*x+z*z>radius*radius)continue;
            BlockPos feet=at.offset(x,0,z),floor=feet.below();
            if(!level.getWorldBorder().isWithinBounds(feet)||level.isOutsideBuildHeight(floor))return false;
            var ground=level.getBlockState(floor);
            if(ground.is(Blocks.FARMLAND)||ground.is(BlockTags.CROPS)||!ground.getFluidState().isEmpty()
                    ||!ground.isFaceSturdy(level,floor,Direction.UP)||ground.is(Blocks.MAGMA_BLOCK)
                    ||ground.is(Blocks.CACTUS)||ground.is(Blocks.CAMPFIRE)||ground.is(Blocks.SOUL_CAMPFIRE))return false;
            for(int y=0;y<5;y++) {
                BlockPos p=feet.above(y);var state=level.getBlockState(p);
                if(!state.getFluidState().isEmpty()||state.is(BlockTags.CROPS)||!state.getCollisionShape(level,p).isEmpty())return false;
            }
        }
        return true;
    }
}
