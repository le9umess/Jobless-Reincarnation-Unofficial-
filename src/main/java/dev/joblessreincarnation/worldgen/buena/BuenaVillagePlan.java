package dev.joblessreincarnation.worldgen.buena;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import static dev.joblessreincarnation.worldgen.buena.BuenaVillageLayout.*;

/** The site and terrain are chosen once, before any part is built, and saved verbatim for later chunks/restarts. */
public final class BuenaVillagePlan {
    public static final int FORMAT = 1, MAX_EARTHWORK = 6;
    public record Column(int ground,boolean dry) { }
    @FunctionalInterface public interface Terrain { Column at(int x,int z); }
    private record Pad(Rect box,int y) { }
    public final UUID id;
    public final int originX,originZ,spawnX,spawnZ;
    public final String layoutHash;
    private final int[] heights,natural,river;
    private final Map<String,Integer> levels;
    private final int minY,maxY;

    private BuenaVillagePlan(UUID id,int x,int z,int sx,int sz,String hash,int[] heights,int[] natural,int[] river,Map<String,Integer> levels) {
        if(heights.length!=WIDTH*WIDTH||natural.length!=heights.length||river.length!=176)throw new IllegalArgumentException("Village height map size");
        this.id=id;originX=x;originZ=z;spawnX=sx;spawnZ=sz;layoutHash=hash;
        this.heights=heights.clone();this.natural=natural.clone();this.river=river.clone();this.levels=Map.copyOf(levels);
        minY=Arrays.stream(heights).min().orElseThrow();maxY=Arrays.stream(heights).max().orElseThrow();
        if(minY < -2048 || maxY > 4096)throw new IllegalArgumentException("Invalid saved village heights");
    }
    public int height(int x,int z){return heights[index(x,z)];}
    public int originalHeight(int x,int z){return natural[index(x,z)];}
    public int riverHeight(int z){return river[Math.clamp(z,0,175)];}
    public int partHeight(String key){Integer y=levels.get(key);if(y==null)throw new IllegalArgumentException("Unknown saved village part "+key);return y;}
    public boolean hasPart(String key){return levels.containsKey(key);}
    public boolean matches(BuenaVillageLayout layout){
        if(!layoutHash.equals(layout.hash)||!layout.byKey.keySet().containsAll(levels.keySet()))return false;
        for(Part p:layout.parts)if(p.kind()!=Kind.TREE&&!levels.containsKey(p.key()))return false;
        return levels.values().stream().allMatch(y->y>=minY&&y<=maxY);
    }
    public ChunkPos anchor(){return new ChunkPos(Math.floorDiv(originX+88,16),Math.floorDiv(originZ+88,16));}
    public BlockPos centre(){return new BlockPos(originX+88,height(88,88)+1,originZ+88);}
    public BoundingBox bounds(){return new BoundingBox(originX+MIN,Math.min(minY,Arrays.stream(natural).min().orElse(minY))-6,originZ+MIN,originX+MAX,maxY+32,originZ+MAX);}
    public UUID residentUuid(String role){return UUID.nameUUIDFromBytes((id+"/"+role).getBytes(StandardCharsets.UTF_8));}
    public BuenaVillagePlan withSpawn(BlockPos spawn){
        return new BuenaVillagePlan(id,originX,originZ,spawn.getX(),spawn.getZ(),layoutHash,heights,natural,river,levels);
    }
    public boolean ownsChunk(ChunkPos pos){return pos.x>=Math.floorDiv(originX+MIN,16)&&pos.x<=Math.floorDiv(originX+MAX,16)
            &&pos.z>=Math.floorDiv(originZ+MIN,16)&&pos.z<=Math.floorDiv(originZ+MAX,16);}
    public int chunkCount(){return (Math.floorDiv(originX+MAX,16)-Math.floorDiv(originX+MIN,16)+1)
            *(Math.floorDiv(originZ+MAX,16)-Math.floorDiv(originZ+MIN,16)+1);}
    public boolean clearOfSpawn(int minimum){
        int dx=Math.max(0,Math.max(originX+MIN-spawnX,spawnX-(originX+MAX)));
        int dz=Math.max(0,Math.max(originZ+MIN-spawnZ,spawnZ-(originZ+MAX)));
        return (long)dx*dx+(long)dz*dz>=(long)minimum*minimum;
    }

    public static @Nullable BuenaVillagePlan assess(long seed,int originX,int originZ,BlockPos spawn,BuenaVillageLayout layout,Terrain terrain,int minBuild,int maxBuild) {
        return assess(seed,originX,originZ,spawn,layout,terrain,minBuild,maxBuild,reason->{});
    }
    public static @Nullable BuenaVillagePlan assess(long seed,int originX,int originZ,BlockPos spawn,BuenaVillageLayout layout,Terrain terrain,int minBuild,int maxBuild,Consumer<String> rejected) {
        int[] natural=new int[WIDTH*WIDTH];boolean[] critical=new boolean[natural.length];
        // Exact noise columns for buildings, plots, routes and water. The untouched land between them uses a coarse map.
        for(Part part:layout.parts)if(part.kind()!=Kind.TREE)fill(critical,part.rect());
        Rect homeAndPractice=new Rect(72,22,123,60);fill(critical,homeAndPractice);fill(critical,layout.mud);
        // Cheap footprint samples reject ponds and steep pads before asking the noise generator for every column.
        List<Rect> footprints=new ArrayList<>();footprints.add(homeAndPractice);
        for(Part part:layout.parts)if(part.kind()!=Kind.TREE)footprints.add(part.rect());
        for(Rect footprint:footprints){
            int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
            int nx=(footprint.x1()-footprint.x0()+7)/8,nz=(footprint.z1()-footprint.z0()+7)/8;
            for(int gz=0;gz<=nz;gz++)for(int gx=0;gx<=nx;gx++){
                int x=footprint.x0()+Math.min(gx*8,footprint.x1()-footprint.x0());
                int z=footprint.z0()+Math.min(gz*8,footprint.z1()-footprint.z0());
                Column c=terrain.at(originX+x,originZ+z);
                if(!c.dry())return reject(rejected,"sampled_pad_water");
                lo=Math.min(lo,c.ground());hi=Math.max(hi,c.ground());
                if(hi-lo>10)return reject(rejected,"sampled_pad_relief");
            }
            if(hi-lo>10)return reject(rejected,"sampled_pad_relief");
        }
        for(int z=MIN;z<=MAX;z++)for(int x=MIN;x<=MAX;x++)
            if(layout.road(x,z)||layout.berm(x,z)||(z>=0&&z<176&&Math.abs(x-layout.riverX(z))<=3))critical[index(x,z)]=true;
        // Check the exact construction pads before spending work on the entire village/fringe map.
        // The coarse eight-block grid can miss a stream or a sharp crest between its sample points.
        // This uses precisely the same dry/range conditions as the full-map and addPad checks below.
        for(Rect footprint:footprints){
            int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
            for(int z=footprint.z0();z<=footprint.z1();z++)for(int x=footprint.x0();x<=footprint.x1();x++){
                Column c=terrain.at(originX+x,originZ+z);
                if(!c.dry())return reject(rejected,"construction_over_water");
                lo=Math.min(lo,c.ground());hi=Math.max(hi,c.ground());
                if(hi-lo>10)return reject(rejected,"exact_pad_relief");
            }
        }
        int[][] samples=new int[25][25];
        for(int z=0;z<25;z++)for(int x=0;x<25;x++){
            Column c=terrain.at(originX+MIN+x*8,originZ+MIN+z*8);
            // Water in an untouched fringe is not a reason to reject the whole village. Every construction column
            // and every authored tree root is checked separately below and must still be dry.
            if(c.ground()<minBuild+3||c.ground()+32>=maxBuild)return reject(rejected,"grid_height");
            samples[z][x]=c.ground();
        }
        for(int z=MIN;z<=MAX;z++)for(int x=MIN;x<=MAX;x++){
            int i=index(x,z);
            if(critical[i]){
                Column c=terrain.at(originX+x,originZ+z);if(!c.dry())return reject(rejected,"construction_over_water");natural[i]=c.ground();
            }else{
                int gx=(x-MIN)/8,gz=(z-MIN)/8,fx=(x-MIN)%8,fz=(z-MIN)%8;
                natural[i]=(int)Math.round((samples[gz][gx]*(8-fx)*(8-fz)+samples[gz][gx+1]*fx*(8-fz)
                        +samples[gz+1][gx]*(8-fx)*fz+samples[gz+1][gx+1]*fx*fz)/64.0);
            }
        }
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(int i=0;i<natural.length;i++)if(critical[i]){low=Math.min(low,natural[i]);high=Math.max(high,natural[i]);}
        if(high-low>32)return reject(rejected,"construction_relief");
        List<Pad> pads=new ArrayList<>();Map<String,Integer> levels=new LinkedHashMap<>();
        Integer homeY=addPad(homeAndPractice,natural,pads);if(homeY==null)return reject(rejected,"home_practice_relief");levels.put("greyrat_home",homeY);
        for(Part part:layout.parts)if(part.kind()!=Kind.TREE&&!part.key().equals("greyrat_home")){
            Integer y=addPad(part.rect(),natural,pads);if(y==null)return reject(rejected,"pad_fit_"+part.key());levels.put(part.key(),y);
        }
        int[] upper=new int[natural.length],negativeLower=new int[natural.length];Arrays.fill(upper,100000);Arrays.fill(negativeLower,100000);
        for(Pad p:pads)for(int z=p.box.z0();z<=p.box.z1();z++)for(int x=p.box.x0();x<=p.box.x1();x++){
            int i=index(x,z);upper[i]=Math.min(upper[i],p.y);negativeLower[i]=Math.min(negativeLower[i],-p.y);
        }
        int[] seeds=natural.clone();for(int i=0;i<seeds.length;i++)if(!critical[i])seeds[i]=100000;
        upper=envelope(upper);negativeLower=envelope(negativeLower);int[] smooth=envelope(seeds),height=new int[natural.length];
        for(int i=0;i<height.length;i++){
            if(!critical[i]){height[i]=natural[i];continue;}
            int lower=-negativeLower[i];if(lower>upper[i])return reject(rejected,"inconsistent_pad_heights");
            height[i]=Math.clamp(smooth[i],lower,upper[i]);if(critical[i]&&Math.abs(height[i]-natural[i])>MAX_EARTHWORK)return reject(rejected,"excess_earthwork");
        }
        for(Part part:layout.parts)if(part.kind()==Kind.TREE){
            int x=part.x()+part.width()/2,z=part.z()+part.depth()/2;
            Column c=terrain.at(originX+x,originZ+z);
            // Trees adapt to a forest floor rather than forcing an entire settlement to move for one pond/cliff.
            boolean supported=c.dry();
            for(int dz:new int[]{-2,2})for(int dx:new int[]{-2,2}){
                Column corner=terrain.at(originX+x+dx,originZ+z+dz);
                supported&=corner.dry()&&Math.abs(corner.ground()-c.ground())<=6;
            }
            if(!supported)continue;
            natural[index(x,z)]=c.ground();height[index(x,z)]=c.ground();levels.put(part.key(),c.ground());
        }
        int[] river=new int[176];int mill=levels.get("watermill");
        int[] riverLow=new int[22],riverHigh=new int[22],desired=new int[22];
        Arrays.fill(riverLow,-100000);Arrays.fill(riverHigh,100000);
        for(int z=0;z<176;z++)for(int dx=-3;dx<=3;dx++){
            int i=index(layout.riverX(z)+dx,z),g=z/8;
            riverLow[g]=Math.max(riverLow[g],Math.max(-negativeLower[i],natural[i]-MAX_EARTHWORK));
            int cap=Math.min(upper[i],natural[i]+MAX_EARTHWORK);
            riverHigh[g]=Math.min(riverHigh[g],cap);
            // A bank at a transition is as high as either adjacent water level.
            if(Math.abs(dx)>=2){
                if(z%8==0&&g>0)riverHigh[g-1]=Math.min(riverHigh[g-1],cap);
                if(z%8==7&&g<21)riverHigh[g+1]=Math.min(riverHigh[g+1],cap);
            }
        }
        for(int g=0;g<22;g++){
            desired[g]=height[index(layout.riverX(g*8+4),g*8+4)];
            if(g>=10&&g<=12){riverLow[g]=Math.max(riverLow[g],mill);riverHigh[g]=Math.min(riverHigh[g],mill);}
        }
        int[] levelsByReach=new int[22];
        for(int g=0;g<22;g++){
            int lo=-100000,hi=100000,target=100000;
            for(int other=0;other<22;other++){
                int d=Math.abs(g-other);lo=Math.max(lo,riverLow[other]-d);hi=Math.min(hi,riverHigh[other]+d);target=Math.min(target,desired[other]+d);
            }
            if(lo>hi)return reject(rejected,"river_profile");
            levelsByReach[g]=Math.clamp(target,lo,hi);
        }
        for(int z=0;z<176;z++)river[z]=levelsByReach[z/8];
        // River banks are fixed-height pads too. Reconcile adjoining paths with them before any chunk is built.
        for(int z=-1;z<=176;z++){
            int rz=Math.clamp(z,0,175);
            int bank=Math.max(river[rz],Math.max(river[Math.max(0,rz-1)],river[Math.min(175,rz+1)]));
            for(int dx=-3;dx<=3;dx++){
                int x=layout.riverX(rz)+dx,i=index(x,z),y=Math.abs(dx)<=1&&z>=0&&z<176?river[rz]:bank;
                critical[i]=true;upper[i]=Math.min(upper[i],y);negativeLower[i]=Math.min(negativeLower[i],-y);
            }
        }
        upper=envelope(upper);negativeLower=envelope(negativeLower);
        for(int i=0;i<height.length;i++)if(critical[i]){
            int lower=-negativeLower[i];if(lower>upper[i])return reject(rejected,"river_pad_conflict");
            height[i]=Math.clamp(smooth[i],lower,upper[i]);
            if(Math.abs(height[i]-natural[i])>MAX_EARTHWORK)return reject(rejected,"river_bank_earthwork");
        }
        UUID id=UUID.nameUUIDFromBytes(("jobless:buena:v01/"+seed+"/"+originX+"/"+originZ).getBytes(StandardCharsets.UTF_8));
        return new BuenaVillagePlan(id,originX,originZ,spawn.getX(),spawn.getZ(),layout.hash,height,natural,river,levels);
    }
    private static @Nullable BuenaVillagePlan reject(Consumer<String> rejected,String reason){rejected.accept(reason);return null;}
    private static void fill(boolean[] grid,Rect box){for(int z=box.z0();z<=box.z1();z++)for(int x=box.x0();x<=box.x1();x++)grid[index(x,z)]=true;}
    private static @Nullable Integer addPad(Rect box,int[] natural,List<Pad> pads){
        int[] values=new int[(box.x1()-box.x0()+1)*(box.z1()-box.z0()+1)];int n=0;
        for(int z=box.z0();z<=box.z1();z++)for(int x=box.x0();x<=box.x1();x++)values[n++]=natural[index(x,z)];
        Arrays.sort(values);if(values[values.length-1]-values[0]>10)return null;
        int lo=values[values.length-1]-MAX_EARTHWORK,hi=values[0]+MAX_EARTHWORK;
        for(Pad p:pads){int distance=box.distance(p.box);lo=Math.max(lo,p.y-distance);hi=Math.min(hi,p.y+distance);}
        if(lo>hi)return null;int y=Math.clamp(values[values.length/2],lo,hi);pads.add(new Pad(box,y));return y;
    }
    /** Eight-neighbour distance transform, including the diagonal steps of a one-block-wide path. */
    private static int[] envelope(int[] input){
        int[] a=input.clone();
        for(int z=0;z<WIDTH;z++)for(int x=0;x<WIDTH;x++){
            int i=z*WIDTH+x;if(x>0)a[i]=Math.min(a[i],a[i-1]+1);if(z>0)a[i]=Math.min(a[i],a[i-WIDTH]+1);
            if(z>0&&x>0)a[i]=Math.min(a[i],a[i-WIDTH-1]+1);if(z>0&&x+1<WIDTH)a[i]=Math.min(a[i],a[i-WIDTH+1]+1);
        }
        for(int z=WIDTH-1;z>=0;z--)for(int x=WIDTH-1;x>=0;x--){
            int i=z*WIDTH+x;if(x+1<WIDTH)a[i]=Math.min(a[i],a[i+1]+1);if(z+1<WIDTH)a[i]=Math.min(a[i],a[i+WIDTH]+1);
            if(z+1<WIDTH&&x>0)a[i]=Math.min(a[i],a[i+WIDTH-1]+1);if(z+1<WIDTH&&x+1<WIDTH)a[i]=Math.min(a[i],a[i+WIDTH+1]+1);
        }
        return a;
    }
    public CompoundTag save(){
        CompoundTag t=new CompoundTag();t.putInt("format",FORMAT);t.putUUID("id",id);t.putInt("x",originX);t.putInt("z",originZ);
        t.putInt("spawn_x",spawnX);t.putInt("spawn_z",spawnZ);t.putString("layout_hash",layoutHash);
        t.putIntArray("heights",heights.clone());t.putIntArray("natural",natural.clone());t.putIntArray("river",river.clone());
        CompoundTag parts=new CompoundTag();levels.forEach(parts::putInt);t.put("parts",parts);return t;
    }
    public static BuenaVillagePlan load(CompoundTag t){
        if(t.getInt("format")!=FORMAT)throw new IllegalArgumentException("Unsupported village plan format");
        Map<String,Integer> parts=new HashMap<>();CompoundTag p=t.getCompound("parts");for(String key:p.getAllKeys())parts.put(key,p.getInt(key));
        return new BuenaVillagePlan(t.getUUID("id"),t.getInt("x"),t.getInt("z"),t.getInt("spawn_x"),t.getInt("spawn_z"),t.getString("layout_hash"),
                t.getIntArray("heights"),t.getIntArray("natural"),t.getIntArray("river"),parts);
    }
}
