package dev.joblessreincarnation.worldgen.buena;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.worldgen.ModStructures;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import static dev.joblessreincarnation.worldgen.buena.BuenaVillageLayout.*;

/** All writes are clipped to the current protochunk. A loaded/full chunk is never accepted as a construction target. */
public final class BuenaVillagePiece extends StructurePiece {
    public enum Step { GROUND, TEMPLATE, SURFACE, RESIDENT, FINISH }
    private final Step step;
    private final UUID village;
    private final String receipt;
    private final @Nullable BuenaVillagePlan plan;
    private final @Nullable BuenaVillageLayout layout;
    private final @Nullable ResourceLocation templateId;
    private final @Nullable StructureTemplate template;
    private final BlockPos position;
    private final Rotation rotation;
    private final boolean tree;
    private final @Nullable CompoundTag resident;
    private final @Nullable String role;
    private final @Nullable Vec3 feet;

    private BuenaVillagePiece(Step step,UUID village,String receipt,BoundingBox box,
                              @Nullable BuenaVillagePlan plan,@Nullable BuenaVillageLayout layout,
                              @Nullable ResourceLocation templateId,@Nullable StructureTemplate template,
                              BlockPos position,Rotation rotation,boolean tree,
                              @Nullable CompoundTag resident,@Nullable String role,@Nullable Vec3 feet) {
        super(ModStructures.BUENA_PIECE.get(),0,box);this.step=step;this.village=village;this.receipt=receipt;
        this.plan=plan;this.layout=layout;this.templateId=templateId;this.template=template;
        this.position=position;this.rotation=rotation;this.tree=tree;
        this.resident=resident==null?null:resident.copy();this.role=role;this.feet=feet;
    }
    public static BuenaVillagePiece terrain(Step step,BuenaVillagePlan plan,BuenaVillageLayout layout){
        if(step!=Step.GROUND&&step!=Step.SURFACE&&step!=Step.FINISH)throw new IllegalArgumentException();
        return new BuenaVillagePiece(step,plan.id,step==Step.FINISH?"complete":"_"+step.name().toLowerCase(Locale.ROOT),plan.bounds(),
                plan,layout,null,null,BlockPos.ZERO,Rotation.NONE,false,null,null,null);
    }
    public static BuenaVillagePiece template(BuenaVillagePlan plan,Part part,StructureTemplateManager manager){
        StructureTemplate template=manager.get(part.template()).orElseThrow();
        BlockPos pos=new BlockPos(plan.originX+part.x()+part.offset().getX(),plan.partHeight(part.key()),plan.originZ+part.z()+part.offset().getZ());
        var settings=new StructurePlaceSettings().setRotation(part.rotation());
        BoundingBox box=template.getBoundingBox(settings,pos);
        return new BuenaVillagePiece(Step.TEMPLATE,plan.id,part.key(),box,null,null,part.template(),template,pos,part.rotation(),part.kind()==Kind.TREE,null,null,null);
    }
    public static BuenaVillagePiece resident(BuenaVillagePlan plan,BuenaVillageLayout layout,Resident person){
        Part part=layout.byKey.get(person.part());
        Vec3 transformed=StructureTemplate.transform(person.localFeet(),Mirror.NONE,part.rotation(),BlockPos.ZERO);
        Vec3 feet=transformed.add(plan.originX+part.x()+part.offset().getX(),plan.partHeight(part.key()),plan.originZ+part.z()+part.offset().getZ());
        CompoundTag data=person.nbt();data.putUUID("UUID",plan.residentUuid(person.role()));
        BlockPos block=BlockPos.containing(feet);
        return new BuenaVillagePiece(Step.RESIDENT,plan.id,"resident_"+person.role(),new BoundingBox(block.getX(),block.getY(),block.getZ(),block.getX(),block.getY()+2,block.getZ()),
                null,null,null,null,block,part.rotation(),false,data,person.role(),feet);
    }
    public BuenaVillagePiece(StructurePieceSerializationContext context,CompoundTag tag){
        super(ModStructures.BUENA_PIECE.get(),tag);
        if(tag.getInt("BuenaFormat")!=1)throw new IllegalArgumentException("Unsupported village piece format");
        step=Step.valueOf(tag.getString("Step"));village=tag.getUUID("Village");receipt=tag.getString("Receipt");
        plan=tag.contains("Plan",Tag.TAG_COMPOUND)?BuenaVillagePlan.load(tag.getCompound("Plan")):null;
        layout=plan==null?null:BuenaVillageLayout.load(context.resourceManager(),context.structureTemplateManager());
        if(plan!=null&&(!village.equals(plan.id)||!plan.matches(layout)))throw new IllegalArgumentException("Village piece does not match its saved layout");
        templateId=tag.contains("Template")?ResourceLocation.parse(tag.getString("Template")):null;
        template=templateId==null?null:context.structureTemplateManager().get(templateId).orElseThrow();
        position=new BlockPos(tag.getInt("X"),tag.getInt("Y"),tag.getInt("Z"));
        rotation=Rotation.valueOf(tag.getString("Rotation"));tree=tag.getBoolean("Tree");
        resident=tag.contains("Resident",Tag.TAG_COMPOUND)?tag.getCompound("Resident").copy():null;
        role=resident==null?null:tag.getString("Role");
        feet=resident==null?null:new Vec3(tag.getDouble("FeetX"),tag.getDouble("FeetY"),tag.getDouble("FeetZ"));
    }
    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag){
        tag.putInt("BuenaFormat",1);tag.putString("Step",step.name());tag.putUUID("Village",village);tag.putString("Receipt",receipt);
        if(plan!=null)tag.put("Plan",plan.save());
        if(templateId!=null)tag.putString("Template",templateId.toString());
        tag.putInt("X",position.getX());tag.putInt("Y",position.getY());tag.putInt("Z",position.getZ());tag.putString("Rotation",rotation.name());tag.putBoolean("Tree",tree);
        if(resident!=null){tag.put("Resident",resident.copy());tag.putString("Role",role);tag.putDouble("FeetX",feet.x);tag.putDouble("FeetY",feet.y);tag.putDouble("FeetZ",feet.z);}
    }
    @Override public void postProcess(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,RandomSource random,BoundingBox clip,ChunkPos chunkPos,BlockPos pivot){
        if(!(level instanceof WorldGenRegion))return;
        ChunkAccess chunk=level.getChunk(chunkPos.x,chunkPos.z);
        if(!(chunk instanceof ProtoChunk)||chunk instanceof ImposterProtoChunk||chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL))return;
        if(!BuenaChunkMarks.writable(chunk,village)||BuenaChunkMarks.done(chunk,village,receipt))return;
        boolean success=switch(step){
            case GROUND -> {ground(level,clip);yield true;}
            case TEMPLATE -> placeTemplate(level,clip,random);
            case SURFACE -> {surface(level,clip);yield true;}
            case RESIDENT -> addResident(level,clip);
            case FINISH -> finish(chunk,chunkPos);
        };
        if(success)BuenaChunkMarks.mark(chunk,village,receipt);
    }
    private boolean placeTemplate(WorldGenLevel level,BoundingBox clip,RandomSource random){
        // Each invocation owns its settings; distant chunks must not race on a shared mutable bounding box.
        var settings=new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true).setKnownShape(true).setBoundingBox(clip)
                .addProcessor(tree?BlockIgnoreProcessor.STRUCTURE_AND_AIR:BlockIgnoreProcessor.STRUCTURE_BLOCK);
        return template.placeInWorld(level,position,position,settings,random,2);
    }
    private boolean addResident(WorldGenLevel level,BoundingBox clip){
        if(!clip.isInside(position))return false;
        CompoundTag data=resident.copy();
        Entity entity=EntityType.loadEntityRecursive(data,level.getLevel(),e->e);
        if(entity==null)throw new IllegalStateException("Missing authored resident "+role);
        entity.setUUID(data.getUUID("UUID"));
        entity.moveTo(feet.x,feet.y,feet.z,entity.rotate(rotation),entity.getXRot());
        entity.addTag(BuenaVillageGeneration.RESIDENT_TAG);entity.addTag(BuenaVillageGeneration.ROLE_TAG+role);entity.addTag(BuenaVillageGeneration.VILLAGE_TAG+village);
        if(entity instanceof Mob mob){mob.finalizeSpawn(level,level.getCurrentDifficultyAt(position),MobSpawnType.STRUCTURE,null);mob.setPersistenceRequired();}
        return level.addFreshEntity(entity);
    }
    private boolean finish(ChunkAccess chunk,ChunkPos at){
        if(!BuenaChunkMarks.done(chunk,village,"_ground")||!BuenaChunkMarks.done(chunk,village,"_surface"))return false;
        int x0=at.getMinBlockX()-plan.originX,z0=at.getMinBlockZ()-plan.originZ,x1=x0+15,z1=z0+15;
        for(Part p:layout.parts)if(plan.hasPart(p.key())&&p.x()<=x1&&p.x()+p.width()-1>=x0&&p.z()<=z1&&p.z()+p.depth()-1>=z0)
            if(!BuenaChunkMarks.done(chunk,village,p.key()))return false;
        for(Resident r:layout.residents){
            Part p=layout.byKey.get(r.part());Vec3 point=StructureTemplate.transform(r.localFeet(),Mirror.NONE,p.rotation(),BlockPos.ZERO)
                    .add(p.x()+p.offset().getX(),0,p.z()+p.offset().getZ());
            if(point.x>=x0&&point.x<x1+1&&point.z>=z0&&point.z<z1+1&&!BuenaChunkMarks.done(chunk,village,"resident_"+r.role()))return false;
        }
        return true;
    }
    private void ground(WorldGenLevel level,BoundingBox clip){
        int[] clear=new int[WIDTH*WIDTH];boolean[] grade=new boolean[clear.length];
        for(Part part:layout.parts){
            if(!plan.hasPart(part.key()))continue;
            for(int z=part.z();z<part.z()+part.depth();z++)for(int x=part.x();x<part.x()+part.width();x++){
                int i=index(x,z);clear[i]=Math.max(clear[i],Math.max(24,part.height()+2));if(part.kind()!=Kind.TREE)grade[i]=true;
            }
            if(part.kind()==Kind.TREE)grade[index(part.x()+part.width()/2,part.z()+part.depth()/2)]=true;
        }
        for(int z=22;z<=60;z++)for(int x=72;x<=123;x++){grade[index(x,z)]=true;clear[index(x,z)]=Math.max(clear[index(x,z)],24);}
        for(int z=MIN;z<=MAX;z++)for(int x=MIN;x<=MAX;x++){
            int i=index(x,z);boolean water=z>=-1&&z<=176&&Math.abs(x-layout.riverX(Math.clamp(z,0,175)))<=3;
            if(layout.road(x,z)||layout.berm(x,z)||layout.mud.contains(x,z)||water){grade[i]=true;clear[i]=Math.max(clear[i],24);}
        }
        each(clip,(x,z,worldX,worldZ)->{
            int i=index(x,z);
            int y=plan.height(x,z),original=plan.originalHeight(x,z);
            // A settlement can straddle a temperate forest edge. Clear native trees from its inhabited core before
            // placing the reviewed trees; keep the western woodland and all ground outside construction masks.
            if(x>=32&&x<176&&z>=0&&z<176)for(int dy=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,worldX,worldZ);dy>=level.getMinBuildHeight();dy--){
                BlockPos at=new BlockPos(worldX,dy,worldZ);BlockState old=level.getBlockState(at);
                if(old.is(BlockTags.LOGS)||old.is(BlockTags.LEAVES)||old.is(Blocks.VINE))put(level,clip,worldX,dy,worldZ,Blocks.AIR.defaultBlockState());
                else if(old.blocksMotion()||!old.getFluidState().isEmpty())break;
            }
            if(clear[i]==0)return;
            if(grade[i]){
                int bottom=Math.max(level.getMinBuildHeight()+1,Math.min(y,original)-4);
                for(int dy=bottom;dy<y;dy++)put(level,clip,worldX,dy,worldZ,Blocks.DIRT.defaultBlockState());
                put(level,clip,worldX,y,worldZ,Blocks.GRASS_BLOCK.defaultBlockState());
            }
            for(int dy=y+1;dy<=Math.min(level.getMaxBuildHeight()-1,Math.max(original+2,y+clear[i]));dy++)
                put(level,clip,worldX,dy,worldZ,Blocks.AIR.defaultBlockState());
        });
    }
    private void surface(WorldGenLevel level,BoundingBox clip){
        each(clip,(x,z,wx,wz)->{
            int y=plan.height(x,z);
            if(layout.berm(x,z))put(level,clip,wx,y,wz,Blocks.COARSE_DIRT.defaultBlockState());
            if(layout.road(x,z)&&(!inTemplate(x,z)||(x>=102&&x<=105&&z>=48&&z<=50)))
                put(level,clip,wx,y,wz,pattern(wx,wz,11,7)?Blocks.COARSE_DIRT.defaultBlockState():Blocks.DIRT_PATH.defaultBlockState());
            if(layout.sparring(x,z))put(level,clip,wx,y,wz,pattern(wx,wz,23,5)?Blocks.COARSE_DIRT.defaultBlockState():Blocks.GRASS_BLOCK.defaultBlockState());
            if(layout.mud.contains(x,z)){
                boolean edge=x==layout.mud.x0()||x==layout.mud.x1()||z==layout.mud.z0()||z==layout.mud.z1();
                boolean puddle=!edge&&pattern(wx,wz,37,11)&&plan.height(x-1,z)==y&&plan.height(x+1,z)==y&&plan.height(x,z-1)==y&&plan.height(x,z+1)==y;
                put(level,clip,wx,y,wz,edge?Blocks.COARSE_DIRT.defaultBlockState():puddle?Blocks.WATER.defaultBlockState():Blocks.MUD.defaultBlockState());
            }
            if(z>=-1&&z<=176){
                int rz=Math.clamp(z,0,175),dx=Math.abs(x-layout.riverX(rz)),waterY=plan.riverHeight(rz);
                if(dx<=1&&z>=0&&z<176){
                    put(level,clip,wx,waterY-2,wz,Blocks.CLAY.defaultBlockState());
                    put(level,clip,wx,waterY-1,wz,Blocks.WATER.defaultBlockState());
                    if(!(z>=87&&z<=97))put(level,clip,wx,waterY,wz,Blocks.WATER.defaultBlockState());
                }else if(dx<=2){
                    if(layout.byKey.get("watermill").rect().contains(x,z))return;
                    int bank=Math.max(waterY,Math.max(plan.riverHeight(rz-1),plan.riverHeight(rz+1)));
                    for(int dy=waterY-2;dy<=bank;dy++)put(level,clip,wx,dy,wz,pattern(wx,wz,53,3)?Blocks.GRAVEL.defaultBlockState():Blocks.COARSE_DIRT.defaultBlockState());
                }
            }
        });
        for(int dx:new int[]{-8,8})for(int dz:new int[]{-8,8}){
            int x=layout.sparX+dx,z=layout.sparZ+dz,y=plan.height(x,z),wx=plan.originX+x,wz=plan.originZ+z;
            for(int dy=1;dy<=3;dy++)put(level,clip,wx,y+dy,wz,Blocks.OAK_FENCE.defaultBlockState());
            put(level,clip,wx,y+4,wz,Blocks.LANTERN.defaultBlockState());
        }
    }
    private boolean pattern(int x,int z,int salt,int denominator){
        return Math.floorMod(net.minecraft.util.Mth.getSeed(x,salt,z)^village.getMostSignificantBits(),denominator)==0;
    }
    private boolean inTemplate(int x,int z){for(Part p:layout.parts)if(p.kind()!=Kind.TREE&&p.rect().contains(x,z))return true;return false;}
    private interface ColumnWriter{void write(int x,int z,int worldX,int worldZ);}
    private void each(BoundingBox clip,ColumnWriter write){
        int x0=Math.max(MIN,clip.minX()-plan.originX),x1=Math.min(MAX,clip.maxX()-plan.originX);
        int z0=Math.max(MIN,clip.minZ()-plan.originZ),z1=Math.min(MAX,clip.maxZ()-plan.originZ);
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)write.write(x,z,plan.originX+x,plan.originZ+z);
    }
    private static void put(WorldGenLevel level,BoundingBox clip,int x,int y,int z,BlockState state){
        BlockPos p=new BlockPos(x,y,z);if(clip.isInside(p)&&!level.isOutsideBuildHeight(p))level.setBlock(p,state,2);
    }
}
