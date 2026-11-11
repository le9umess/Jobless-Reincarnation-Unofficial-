package dev.joblessreincarnation.worldgen.buena;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** One Overworld record. Absence in an existing world never opts that world into a new village. */
public final class BuenaVillageData extends SavedData {
    public static final String NAME="jobless_buena_village";
    public static final Factory<BuenaVillageData> FACTORY=new Factory<>(BuenaVillageData::new,BuenaVillageData::load);
    public static final int FORMAT=1;
    private boolean eligible;
    private @Nullable BuenaVillagePlan plan;
    private final Set<Long> chunks=new HashSet<>();
    private final Map<String,UUID> residents=new TreeMap<>();
    private @Nullable CompoundTag unreadable;
    private String note="not_eligible";
    private int attempts;
    private long planningMillis;
    private @Nullable BlockPos spawnReference,initialSpawn;
    private boolean pairedCreation;

    public static @Nullable BuenaVillageData get(ServerLevel level){return level.getDataStorage().get(FACTORY,NAME);}
    public static BuenaVillageData fresh(ServerLevel level){
        BuenaVillageData data=level.getDataStorage().computeIfAbsent(FACTORY,NAME);
        if(data.compatible()&&!data.eligible){data.eligible=true;data.note="pending";data.setDirty();}
        return data;
    }
    public boolean compatible(){return unreadable==null;}
    public boolean eligible(){return eligible&&compatible();}
    public @Nullable BuenaVillagePlan plan(){return plan;}
    public String note(){return note;}
    public int attempts(){return attempts;}
    public long planningMillis(){return planningMillis;}
    public int generatedChunks(){return chunks.size();}
    public boolean complete(){return plan!=null&&chunks.size()==plan.chunkCount();}
    public Map<String,UUID> residents(){return Map.copyOf(residents);}
    public @Nullable BlockPos initialSpawn(){return initialSpawn;}
    public boolean requiresInitialPair(){return pairedCreation;}
    public void requireInitialPair(){if(!pairedCreation){pairedCreation=true;setDirty();}}
    public void recordInitialSpawn(BlockPos reference,BlockPos spawn){
        if(plan==null||plan.spawnX!=spawn.getX()||plan.spawnZ!=spawn.getZ())throw new IllegalStateException("Initial spawn does not match village reservation");
        if(initialSpawn!=null&&!initialSpawn.equals(spawn))throw new IllegalStateException("Initial spawn is already fixed");
        spawnReference=reference.immutable();initialSpawn=spawn.immutable();pairedCreation=true;setDirty();
    }

    public void reserve(BuenaVillagePlan next,int attempts,long millis){
        if(!eligible())throw new IllegalStateException("World is not eligible for a village");
        if(plan!=null){if(!plan.id.equals(next.id))throw new IllegalStateException("A world already has its village");return;}
        plan=next;this.attempts=attempts;planningMillis=millis;note="reserved";setDirty();
    }
    public void noSite(int attempts,long millis,String reason){
        if(plan!=null||!eligible())return;this.attempts=attempts;planningMillis=millis;note=reason;setDirty();
    }
    public void chunkFinished(UUID village,ChunkPos chunk){
        if(plan==null||!plan.id.equals(village)||!plan.ownsChunk(chunk))return;
        if(chunks.add(chunk.toLong())){note=complete()?"generated":"generating";setDirty();}
    }
    public void residentSeen(UUID village,String role,UUID entity){
        if(plan==null||!plan.id.equals(village)||!plan.residentUuid(role).equals(entity))return;
        if(!entity.equals(residents.putIfAbsent(role,entity)))setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        if(unreadable!=null)return unreadable.copy();
        tag.putInt("format",FORMAT);tag.putBoolean("eligible",eligible);tag.putString("note",note);
        tag.putBoolean("paired_creation",pairedCreation);
        tag.putInt("attempts",attempts);tag.putLong("planning_millis",planningMillis);
        if(plan!=null)tag.put("plan",plan.save());
        if(initialSpawn!=null){tag.putIntArray("initial_spawn",new int[]{initialSpawn.getX(),initialSpawn.getY(),initialSpawn.getZ()});
            tag.putIntArray("spawn_reference",new int[]{spawnReference.getX(),spawnReference.getY(),spawnReference.getZ()});}
        tag.putLongArray("generated_chunks",chunks.stream().mapToLong(Long::longValue).sorted().toArray());
        CompoundTag people=new CompoundTag();residents.forEach(people::putUUID);tag.put("residents",people);
        return tag;
    }
    public static BuenaVillageData load(CompoundTag tag,HolderLookup.Provider registries){
        BuenaVillageData data=new BuenaVillageData();
        try {
            if(tag.getInt("format")!=FORMAT)throw new IllegalArgumentException("Unknown village format");
            data.eligible=tag.getBoolean("eligible");data.note=tag.getString("note");
            data.pairedCreation=tag.getBoolean("paired_creation");
            data.attempts=tag.getInt("attempts");data.planningMillis=tag.getLong("planning_millis");
            if(tag.contains("plan",Tag.TAG_COMPOUND))data.plan=BuenaVillagePlan.load(tag.getCompound("plan"));
            if(tag.contains("initial_spawn")){
                int[] spawn=tag.getIntArray("initial_spawn"),reference=tag.getIntArray("spawn_reference");
                if(spawn.length!=3||reference.length!=3||data.plan==null||data.plan.spawnX!=spawn[0]||data.plan.spawnZ!=spawn[2])throw new IllegalArgumentException("Invalid initial village spawn");
                data.initialSpawn=new BlockPos(spawn[0],spawn[1],spawn[2]);data.spawnReference=new BlockPos(reference[0],reference[1],reference[2]);
                data.pairedCreation=true;
            }
            for(long c:tag.getLongArray("generated_chunks")){
                if(data.plan==null||!data.plan.ownsChunk(new ChunkPos(c)))throw new IllegalArgumentException("Invalid completed village chunk");
                data.chunks.add(c);
            }
            CompoundTag people=tag.getCompound("residents");
            for(String role:people.getAllKeys()){
                UUID id=people.getUUID(role);
                if(data.plan==null||!data.plan.residentUuid(role).equals(id))throw new IllegalArgumentException("Invalid resident identity");
                data.residents.put(role,id);
            }
        }catch(RuntimeException invalid){
            // Keep the original bytes in memory; never turn an unknown/newer record into a second fresh village.
            data.eligible=false;data.plan=null;data.chunks.clear();data.residents.clear();
            data.note="unsupported_or_invalid_data";data.unreadable=tag.copy();
        }
        return data;
    }
}
