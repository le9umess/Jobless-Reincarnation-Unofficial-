package dev.joblessreincarnation.worldgen.buena;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the native noise-column iterator, stopping at the first surface instead of evaluating unused underground caves. */
public final class BuenaSurfaceSampler extends NoiseBasedChunkGenerator implements BuenaVillagePlan.Terrain {
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES=new DensityFunctions.BeardifierOrMarker(){
        @Override public double compute(DensityFunction.FunctionContext context){return 0;}
        @Override public double minValue(){return 0;}
        @Override public double maxValue(){return 0;}
    };
    private final LevelHeightAccessor height;
    private final RandomState random;
    private final NoiseGeneratorSettings settings;
    private final NoiseSettings shape;
    private final Aquifer.FluidPicker fluids;
    private final Map<Long,Cell> cells=new LinkedHashMap<>(96,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,Cell> eldest){
            if(size()<=96)return false;eldest.getValue().stopInterpolation();return true;
        }
    };
    private BuenaSurfaceSampler(NoiseBasedChunkGenerator original,LevelHeightAccessor height,RandomState random){
        super(original.getBiomeSource(),original.generatorSettings());this.height=height;this.random=random;
        settings=original.generatorSettings().value();shape=settings.noiseSettings().clampToHeightAccessor(height);
        var lava=new Aquifer.FluidStatus(-54,Blocks.LAVA.defaultBlockState());
        var sea=new Aquifer.FluidStatus(settings.seaLevel(),settings.defaultFluid());
        fluids=(x,y,z)->y<Math.min(-54,settings.seaLevel())?lava:sea;
    }
    public static BuenaVillagePlan.Terrain create(ChunkGenerator generator,LevelHeightAccessor height,RandomState random){
        // An overridden third-party generator must keep its own public column semantics.
        if(generator.getClass()==NoiseBasedChunkGenerator.class)return new BuenaSurfaceSampler((NoiseBasedChunkGenerator)generator,height,random);
        return (x,z)->{
            var column=generator.getBaseColumn(x,z,height,random);
            for(int y=height.getMaxBuildHeight()-1;y>=height.getMinBuildHeight();y--){
                var state=column.getBlock(y);
                if(!state.isAir())return new BuenaVillagePlan.Column(y,state.blocksMotion()&&state.getFluidState().isEmpty());
            }
            return new BuenaVillagePlan.Column(height.getMinBuildHeight(),false);
        };
    }
    @Override public BuenaVillagePlan.Column at(int x,int z){
        int w=shape.getCellWidth(),cx=Math.floorDiv(x,w),cz=Math.floorDiv(z,w);
        return cells.computeIfAbsent(ChunkPos.asLong(cx,cz),key->new Cell(cx*w,cz*w)).surface(x,z);
    }
    /** One native interpolation cell serves its neighbouring columns; no chunk or world block is created. */
    private final class Cell extends NoiseChunk {
        private final BuenaVillagePlan.Column[] result=new BuenaVillagePlan.Column[shape.getCellWidth()*shape.getCellWidth()];
        private final int originX,originZ;
        Cell(int x,int z){
            super(1,random,x,z,shape,NO_STRUCTURES,settings,fluids,Blender.empty());
            originX=x;originZ=z;
            initializeForFirstCellX();advanceCellX(0);
        }
        BuenaVillagePlan.Column surface(int x,int z){
            int w=shape.getCellWidth(),dx=Math.floorMod(x,w),dz=Math.floorMod(z,w),index=dz*w+dx;
            if(result[index]!=null)return result[index];
            int remaining=result.length;
            int h=shape.getCellHeight(),minCell=Math.floorDiv(shape.minY(),h),count=Math.floorDiv(shape.height(),h);
            for(int cell=count-1;cell>=0;cell--){
                // Vanilla computes the density cache for EVERY block of this cell here. Repeating
                // this vertical walk for each of its sixteen columns rebuilt the same cache 16 times.
                selectCellYZ(cell,0);
                for(int dy=h-1;dy>=0;dy--){
                    int y=(minCell+cell)*h+dy;
                    updateForY(y,(double)dy/h);
                    for(int cx=0;cx<w;cx++){
                        updateForX(originX+cx,(double)cx/w);
                        for(int cz=0;cz<w;cz++){
                            int at=cz*w+cx;if(result[at]!=null)continue;
                            updateForZ(originZ+cz,(double)cz/w);
                            BlockState block=getInterpolatedState();if(block==null)block=settings.defaultBlock();
                            if(!block.isAir()){
                                result[at]=new BuenaVillagePlan.Column(y,block.blocksMotion()&&block.getFluidState().isEmpty());
                                if(--remaining==0)return result[index];
                            }
                        }
                    }
                }
            }
            for(int at=0;at<result.length;at++)if(result[at]==null)result[at]=new BuenaVillagePlan.Column(height.getMinBuildHeight(),false);
            return result[index];
        }
    }
}
// Source provenance: TGU5dW1lc3M= / JR-0.1.0 / buena-surface-sampling.
