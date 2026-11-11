package dev.joblessreincarnation.worldgen.buena;

import com.google.gson.*;
import dev.joblessreincarnation.JoblessReincarnation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;

/** Immutable coordinates from the reviewed handoff. Reading a layout never changes a world or an entity. */
public final class BuenaVillageLayout {
    public static final ResourceLocation RESOURCE = id("buena_village/v01/layout.json");
    private static final Map<ResourceManager,BuenaVillageLayout> CACHE=new WeakHashMap<>();
    public static final int MIN = -8, WIDTH = 192, MAX = MIN + WIDTH - 1;
    public enum Kind { BUILDING, FIELD, TREE }
    public record Rect(int x0, int z0, int x1, int z1) {
        public boolean contains(int x,int z){return x>=x0&&x<=x1&&z>=z0&&z<=z1;}
        public int distance(Rect other){return Math.max(Math.max(0,Math.max(x0-other.x1,other.x0-x1)),Math.max(0,Math.max(z0-other.z1,other.z0-z1)));}
    }
    public record Part(String key,ResourceLocation template,int x,int z,int width,int height,int depth,
                       Rotation rotation,BlockPos offset,Kind kind) {
        public Rect rect(){return new Rect(x,z,x+width-1,z+depth-1);}
    }
    public record Resident(String role,String part,Vec3 localFeet,float yaw,CompoundTag nbt) {
        public Resident { nbt=nbt.copy(); }
        @Override public CompoundTag nbt(){return nbt.copy();}
    }
    public final List<Part> parts;
    public final List<Resident> residents;
    public final Map<String,Part> byKey;
    public final String hash;
    private final boolean[] roads=new boolean[WIDTH*WIDTH];
    private final boolean[] berms=new boolean[WIDTH*WIDTH];
    public final Rect mud;
    public final int sparX,sparZ,sparRadius;

    public static ResourceLocation id(String value){return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,value);}
    public static int index(int x,int z){
        if(x<MIN||x>MAX||z<MIN||z>MAX)throw new IllegalArgumentException("Village coordinate outside reserved area: "+x+","+z);
        return (z-MIN)*WIDTH+x-MIN;
    }
    public static boolean inside(int x,int z){return x>=MIN&&x<=MAX&&z>=MIN&&z<=MAX;}

    public static synchronized BuenaVillageLayout load(ResourceManager resources,StructureTemplateManager templates) {
        BuenaVillageLayout cached=CACHE.get(resources);if(cached!=null)return cached;
        try(var stream=resources.open(RESOURCE)) {
            byte[] bytes=stream.readAllBytes();
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonObject json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            BuenaVillageLayout layout=new BuenaVillageLayout(json,hash,resources,templates);CACHE.put(resources,layout);return layout;
        }catch(Exception e){throw new IllegalStateException("Cannot load frozen Buena village layout",e);}
    }
    private BuenaVillageLayout(JsonObject json,String hash,ResourceManager resources,StructureTemplateManager templates)throws Exception {
        if(json.get("schema").getAsInt()!=1||!json.get("layoutVersion").getAsString().equals("v01"))throw new IllegalArgumentException("Unsupported village layout");
        this.hash=hash;
        List<Part> pieces=new ArrayList<>();List<Resident> people=new ArrayList<>();Map<String,Part> keys=new LinkedHashMap<>();
        for(JsonElement element:json.getAsJsonArray("pieces")) {
            JsonObject p=element.getAsJsonObject();JsonArray expected=p.getAsJsonArray("size");
            Part part=part(p.get("id").getAsString(),p.get("template").getAsString(),p.get("x").getAsInt(),p.get("z").getAsInt(),p.get("rotation").getAsInt(),Kind.BUILDING,templates);
            if(part.width()!=expected.get(0).getAsInt()||part.height()!=expected.get(1).getAsInt()||part.depth()!=expected.get(2).getAsInt())throw new IllegalArgumentException("Rotated footprint differs: "+part.key());
            pieces.add(part);addResidents(resources,part,people);
        }
        for(JsonElement element:json.getAsJsonArray("fields")) {
            JsonObject f=element.getAsJsonObject();JsonArray origin=f.getAsJsonArray("origin");
            for(JsonElement cell:f.getAsJsonArray("plots")) {
                JsonArray at=cell.getAsJsonArray();int x=at.get(0).getAsInt(),z=at.get(1).getAsInt();
                pieces.add(part(f.get("id").getAsString()+"_"+x+"_"+z,f.get("plot").getAsString(),origin.get(0).getAsInt()+10*x,origin.get(1).getAsInt()+10*z,0,Kind.FIELD,templates));
            }
        }
        int tree=0;
        for(JsonElement element:json.getAsJsonArray("trees")) {
            JsonObject t=element.getAsJsonObject();pieces.add(part("tree_"+tree++,t.get("template").getAsString(),t.get("x").getAsInt(),t.get("z").getAsInt(),0,Kind.TREE,templates));
        }
        for(Part p:pieces){if(keys.put(p.key(),p)!=null)throw new IllegalArgumentException("Repeated village part "+p.key());index(p.x(),p.z());index(p.x()+p.width()-1,p.z()+p.depth()-1);}
        Set<String> roles=new HashSet<>();for(Resident r:people)if(!roles.add(r.role()))throw new IllegalArgumentException("Duplicate resident role "+r.role());
        if(!roles.equals(Set.of("roxy_tutor","paul_greyrat","zenith_greyrat","lilia_greyrat","sylphiette")))throw new IllegalArgumentException("V01 requires exactly the existing five residents");
        parts=List.copyOf(pieces);residents=List.copyOf(people);byKey=Map.copyOf(keys);
        for(Part p:parts)if(p.kind()==Kind.FIELD)
            for(int z=p.z()-1;z<=p.z()+p.depth();z++)for(int x=p.x()-1;x<=p.x()+p.width();x++)
                if(inside(x,z)&&!p.rect().contains(x,z))berms[index(x,z)]=true;
        for(Part p:parts)if(p.kind()!=Kind.TREE)
            for(int z=p.z();z<p.z()+p.depth();z++)for(int x=p.x();x<p.x()+p.width();x++)berms[index(x,z)]=false;
        JsonObject spar=json.getAsJsonObject("sparringGround");sparX=spar.getAsJsonArray("center").get(0).getAsInt();sparZ=spar.getAsJsonArray("center").get(1).getAsInt();sparRadius=spar.get("radius").getAsInt();
        if(sparRadius!=8)throw new IllegalArgumentException("Unexpected practice ground");
        JsonObject m=json.getAsJsonObject("mudField");int mx=m.get("x").getAsInt(),mz=m.get("z").getAsInt();mud=new Rect(mx,mz,mx+m.get("w").getAsInt()-1,mz+m.get("h").getAsInt()-1);
        for(JsonElement element:json.getAsJsonArray("roads")) {
            JsonObject road=element.getAsJsonObject();int width=road.get("width").getAsInt();if(width<1||width>3)throw new IllegalArgumentException("Road width");
            JsonArray points=road.getAsJsonArray("points");
            for(int i=1;i<points.size();i++)line(points.get(i-1).getAsJsonArray(),points.get(i).getAsJsonArray(),width);
        }
    }
    private static Part part(String key,String resource,int x,int z,int turn,Kind kind,StructureTemplateManager templates) {
        ResourceLocation id=ResourceLocation.parse(resource);var template=templates.get(id).orElseThrow(()->new IllegalArgumentException("Missing village template "+id));
        if(turn<0||turn>3)throw new IllegalArgumentException("Invalid quarter turn");
        Rotation rotation=switch(turn){case 0->Rotation.NONE;case 1->Rotation.CLOCKWISE_90;case 2->Rotation.CLOCKWISE_180;default->Rotation.COUNTERCLOCKWISE_90;};
        var size=template.getSize();int w=size.getX(),d=size.getZ();
        BlockPos offset=switch(turn){case 1->new BlockPos(d-1,0,0);case 2->new BlockPos(w-1,0,d-1);case 3->new BlockPos(0,0,w-1);default->BlockPos.ZERO;};
        return new Part(key,id,x,z,turn%2==0?w:d,size.getY(),turn%2==0?d:w,rotation,offset,kind);
    }
    private static void addResidents(ResourceManager resources,Part part,List<Resident> people)throws Exception {
        try(var stream=resources.open(ResourceLocation.fromNamespaceAndPath(part.template().getNamespace(),"structure/"+part.template().getPath()+".nbt"))) {
            CompoundTag raw=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap());ListTag entities=raw.getList("entities",Tag.TAG_COMPOUND);
            for(int i=0;i<entities.size();i++) {
                CompoundTag entity=entities.getCompound(i),nbt=entity.getCompound("nbt");ResourceLocation type=ResourceLocation.parse(nbt.getString("id"));
                if(!type.getNamespace().equals(JoblessReincarnation.MOD_ID))throw new IllegalArgumentException("Unexpected resident type "+type);
                ListTag feet=entity.getList("pos",Tag.TAG_DOUBLE);Vec3 local=new Vec3(feet.getDouble(0),feet.getDouble(1),feet.getDouble(2));
                if(!Double.isFinite(local.x)||!Double.isFinite(local.y)||!Double.isFinite(local.z))throw new IllegalArgumentException("Invalid resident position");
                if(nbt.hasUUID("UUID")||nbt.contains("JR_HomeAnchor"))throw new IllegalArgumentException("Baked editing-world identity");
                people.add(new Resident(type.getPath(),part.key(),local,nbt.getList("Rotation",Tag.TAG_FLOAT).getFloat(0),nbt));
            }
        }
    }
    private void line(JsonArray a,JsonArray b,int width) {
        int x=a.get(0).getAsInt(),z=a.get(1).getAsInt(),endX=b.get(0).getAsInt(),endZ=b.get(1).getAsInt();
        int dx=Math.abs(endX-x),dz=Math.abs(endZ-z),sx=x<endX?1:-1,sz=z<endZ?1:-1,error=dx-dz;
        for(;;){for(int ox=-(width/2);ox<width-width/2;ox++)for(int oz=-(width/2);oz<width-width/2;oz++)if(inside(x+ox,z+oz))roads[index(x+ox,z+oz)]=true;
            if(x==endX&&z==endZ)break;int twice=2*error;if(twice>-dz){error-=dz;x+=sx;}if(twice<dx){error+=dx;z+=sz;}}
    }
    public boolean sparring(int x,int z){int dx=x-sparX,dz=z-sparZ;return dx*dx+dz*dz<=sparRadius*sparRadius;}
    public boolean road(int x,int z){return inside(x,z)&&roads[index(x,z)];}
    public boolean berm(int x,int z){return inside(x,z)&&berms[index(x,z)];}
    public int riverX(int z){return z>=87&&z<=97?141:(int)Math.round(146+6*Math.sin(z/22.0));}
}
