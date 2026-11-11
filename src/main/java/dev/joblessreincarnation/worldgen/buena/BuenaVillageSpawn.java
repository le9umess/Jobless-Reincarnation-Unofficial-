package dev.joblessreincarnation.worldgen.buena;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/** New-world initial spawn only. It never handles a player's later bed, death, login or dimension change. */
public final class BuenaVillageSpawn {
    public static List<ChunkPos> candidates(BuenaVillagePlan plan,BuenaVillagePlan.Terrain terrain){
        LinkedHashSet<ChunkPos> points=new LinkedHashSet<>();
        int cx=plan.centre().getX(),cz=plan.centre().getZ();
        // Prefer the southern village road, then inspect the remaining directions without altering terrain.
        for(int radius:new int[]{288,272,304})for(int direction:new int[]{0,8,4,12,2,14,6,10,1,15,3,13,5,11,7,9}){
            double angle=direction*Math.PI/8,dx=Math.sin(angle),dz=Math.cos(angle);
            int x=(int)Math.round(cx+dx*radius),z=(int)Math.round(cz+dz*radius);
            var surface=terrain.at(x,z);if(!surface.dry())continue;
            int dry=0,samples=0,wetRun=0,longestWetRun=0,lo=surface.ground(),hi=surface.ground();
            for(int d=radius-4;d>=112;d-=4){
                var at=terrain.at((int)Math.round(cx+dx*d),(int)Math.round(cz+dz*d));
                samples++;if(at.dry()){dry++;wetRun=0;}else{wetRun++;longestWetRun=Math.max(longestWetRun,wetRun);}
                lo=Math.min(lo,at.ground());hi=Math.max(hi,at.ground());
            }
            // A natural journey may go around a rock or cross a narrow stream; it need not be one perfectly level ray.
            if(dry*5>=samples*4&&longestWetRun<=3&&hi-lo<=32)
                points.add(new ChunkPos(new BlockPos(x,surface.ground()+1,z)));
        }
        return List.copyOf(points);
    }
    public static @Nullable BlockPos resolve(ServerLevel level,BuenaVillagePlan plan,List<ChunkPos> choices){
        return resolve(level,plan,choices,()->{});
    }
    public static @Nullable BlockPos resolve(ServerLevel level,BuenaVillagePlan plan,List<ChunkPos> choices,Runnable checkpoint){
        for(var chunk:choices){
            checkpoint.run();
            BlockPos point=PlayerRespawnLogic.getSpawnPosInChunk(level,chunk);
            if(point!=null&&suitableDistance(plan,point)&&safeStandingPlace(level,point))return point.immutable();
            // The vanilla helper returns its first column, which can be a canopy. Search the already loaded chunk's ground.
            for(int z=chunk.getMinBlockZ();z<=chunk.getMaxBlockZ();z++)for(int x=chunk.getMinBlockX();x<=chunk.getMaxBlockX();x++){
                point=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                if(suitableDistance(plan,point)&&safeStandingPlace(level,point))return point;
            }
        }
        return null;
    }
    public static boolean suitableDistance(BuenaVillagePlan plan,BlockPos point){
        double distance=Math.hypot(point.getX()-plan.centre().getX(),point.getZ()-plan.centre().getZ());
        return distance>=240&&distance<=320&&BuenaVillageGeneration.clearOfSpawn(plan.originX,plan.originZ,point,100);
    }
    public static boolean safeStandingPlace(ServerLevel level,BlockPos point){
        if(!level.getWorldBorder().isWithinBounds(point))return false;
        if(level.getHeight(Heightmap.Types.MOTION_BLOCKING,point.getX(),point.getZ())!=point.getY())return false;
        var floor=level.getBlockState(point.below());
        if(!floor.getFluidState().isEmpty()||floor.is(BlockTags.LEAVES)||floor.is(BlockTags.LOGS)
                ||floor.is(Blocks.MAGMA_BLOCK)||floor.is(Blocks.CACTUS)||floor.is(Blocks.CAMPFIRE)||floor.is(Blocks.SOUL_CAMPFIRE))return false;
        return Block.isFaceFull(floor.getCollisionShape(level,point.below()),Direction.UP)
                &&level.getBlockState(point).getCollisionShape(level,point).isEmpty()
                &&level.getBlockState(point.above()).getCollisionShape(level,point.above()).isEmpty()
                &&level.getFluidState(point).isEmpty()&&level.getFluidState(point.above()).isEmpty();
    }
    public static float facingVillage(BuenaVillagePlan plan,BlockPos spawn){
        return (float)-Math.toDegrees(Math.atan2(plan.centre().getX()-spawn.getX(),plan.centre().getZ()-spawn.getZ()));
    }
    private BuenaVillageSpawn(){}
}
