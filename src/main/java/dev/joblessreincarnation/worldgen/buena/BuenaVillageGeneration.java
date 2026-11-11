package dev.joblessreincarnation.worldgen.buena;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import static dev.joblessreincarnation.worldgen.buena.BuenaVillageLayout.*;

/** Main-thread reservation, immutable worldgen input, and main-thread bookkeeping are deliberately separate. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID)
public final class BuenaVillageGeneration {
    public static final String RESIDENT_TAG="jr_buena", ROLE_TAG="jr_buena_role=", VILLAGE_TAG="jr_buena_id=";
    public static final TagKey<Biome> BIOMES=TagKey.create(Registries.BIOME,id("has_structure/buena_village"));
    public record Handle(MinecraftServer server,BuenaVillageLayout layout,BuenaVillagePlan plan){}
    private static final Map<RandomState,Handle> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<RandomState,SiteSearch> SEARCHES=new ConcurrentHashMap<>();
    public static final long SEARCH_LIMIT_MILLIS=90000;
    public record SearchProgress(long elapsedMillis,int checked,boolean preparingSpawn){}
    public static @Nullable SearchProgress progress(MinecraftServer server){
        for(var job:SEARCHES.values())if(job.level.getServer()==server)
            return new SearchProgress((System.nanoTime()-job.started)/1_000_000,job.checked,job.preparingSpawn);
        return null;
    }
    private static final class SearchDeadline extends RuntimeException {}
    private static final class SiteSearch {
        final ServerLevel level;final BuenaVillageLayout layout;final BlockPos spawn;
        final boolean pairInitialSpawn;
        volatile boolean cancelled;
        final long started=System.nanoTime();
        volatile int checked;
        volatile boolean preparingSpawn;
        final Map<String,java.util.concurrent.atomic.LongAdder> timings=new ConcurrentHashMap<>();
        SiteSearch(ServerLevel level,BuenaVillageLayout layout){this(level,layout,level.getSharedSpawnPos(),false);}
        SiteSearch(ServerLevel level,BuenaVillageLayout layout,BlockPos reference,boolean pair){
            this.level=level;this.layout=layout;spawn=reference.immutable();pairInitialSpawn=pair;
        }
        void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
        void checkTime(){check();if((System.nanoTime()-started)/1_000_000>=SEARCH_LIMIT_MILLIS)throw new SearchDeadline();}
    }
    public static @Nullable Handle active(RandomState state){return ACTIVE.get(state);}

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void freshWorld(LevelEvent.CreateSpawnPosition event){
        if(!(event.getLevel() instanceof ServerLevel level)||level.dimension()!=Level.OVERWORLD
                ||level.getServer() instanceof GameTestServer||!level.getServer().getWorldData().worldGenOptions().generateStructures())return;
        var data=BuenaVillageData.fresh(level);
        if(!data.eligible())return;
        data.requireInitialPair();
        var layout=BuenaVillageLayout.load(level.getServer().getResourceManager(),level.getStructureManager());
        var guide=new ChunkPos(level.getChunkSource().randomState().sampler().findSpawnPosition());
        var reference=new BlockPos(guide.getMiddleBlockX(),level.getChunkSource().getGenerator().getSeaLevel(),guide.getMiddleBlockZ());
        if(data.plan()==null){
            var job=new SiteSearch(level,layout,reference,true);
            JoblessReincarnation.LOGGER.info("Choosing Buena village terrain and its new-world initial spawn; noise reference {},{}",reference.getX(),reference.getZ());
            var ready=launchSearch(job,data);
            // World creation, before spawn preparation and before the first player is admitted.
            level.getServer().managedBlock(ready::isDone);ready.join();
        }else if(data.plan().matches(layout)){
            // A crash after saving the pair but before saving level.dat must resume it, not survey another village.
            remember(level,layout,data.plan());
        }else return;
        if(data.plan()==null||data.initialSpawn()==null){
            JoblessReincarnation.LOGGER.error("No safe Buena village/spawn pair was found; vanilla spawn creation is left in control ({})",data.note());
            return;
        }
        var spawn=data.initialSpawn();
        event.getSettings().setSpawn(spawn,BuenaVillageSpawn.facingVillage(data.plan(),spawn));
        // Vanilla's ten-block random offset can choose a neighbouring canopy. This applies only at world creation;
        // saved player positions, beds, and later administrator changes to the rule remain under vanilla control.
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS).set(0,level.getServer());
        if(level.getServer().getWorldData().worldGenOptions().generateBonusChest())
            level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(net.minecraft.data.worldgen.features.MiscOverworldFeatures.BONUS_CHEST)
                    .ifPresent(feature->feature.value().place(level,level.getChunkSource().getGenerator(),level.random,spawn));
        level.getDataStorage().save();event.setCanceled(true);
        JoblessReincarnation.LOGGER.info("Buena initial spawn fixed at {}; village distance {} blocks",spawn,
                Math.round(Math.hypot(spawn.getX()-data.plan().centre().getX(),spawn.getZ()-data.plan().centre().getZ())));
    }
    @SubscribeEvent public static void loadedWorld(LevelEvent.Load event){
        if(!(event.getLevel() instanceof ServerLevel level)||level.dimension()!=Level.OVERWORLD||level.getServer() instanceof GameTestServer)return;
        // Forced chunks may resume during prepareLevels, before ServerStartedEvent. Restore the decoration guard first.
        var data=BuenaVillageData.get(level);if(data==null||data.plan()==null||!data.eligible())return;
        var layout=BuenaVillageLayout.load(level.getServer().getResourceManager(),level.getStructureManager());
        if(data.plan().matches(layout))remember(level,layout,data.plan());
    }
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(event.getServer() instanceof GameTestServer)return;
        ServerLevel level=event.getServer().overworld();
        BuenaVillageData data=BuenaVillageData.get(level);
        if(data==null)return;
        if(!data.compatible()){JoblessReincarnation.LOGGER.error("Buena village data is newer or invalid; it has not been reset.");return;}
        if(!data.eligible())return;
        if(!level.getServer().getWorldData().worldGenOptions().generateStructures())return;
        try{
            BuenaVillageLayout layout=BuenaVillageLayout.load(level.getServer().getResourceManager(),level.getStructureManager());
            if(data.plan()==null){
                // Never silently substitute the old far-away-village policy after a paired creation fails.
                if(!data.requiresInitialPair())beginSearch(level,data,layout);
                return;
            }
            if(!data.plan().matches(layout))throw new IllegalStateException("Saved village layout differs from its versioned resource");
            remember(level,layout,data.plan());
            level.getDataStorage().save();
            JoblessReincarnation.LOGGER.info("Buena village reserved at {} (initial spawn {},{}), {} chunks; status {}",
                    data.plan().centre(),data.plan().spawnX,data.plan().spawnZ,data.plan().chunkCount(),data.note());
        }catch(Exception e){JoblessReincarnation.LOGGER.error("Buena village was not generated; existing terrain and saved data were left in place",e);}
    }
    public static void remember(ServerLevel level,BuenaVillageLayout layout,BuenaVillagePlan plan){
        if(level.dimension()!=Level.OVERWORLD)throw new IllegalArgumentException("Buena belongs to the Overworld");
        ACTIVE.compute(level.getChunkSource().randomState(),(key,old)->{
            if(old!=null&&!old.plan.id.equals(plan.id))throw new IllegalStateException("Second village reservation");
            return new Handle(level.getServer(),layout,plan);
        });
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){
        ACTIVE.entrySet().removeIf(e->e.getValue().server==event.getServer());
        SEARCHES.entrySet().removeIf(e->{if(e.getValue().level.getServer()!=event.getServer())return false;e.getValue().cancelled=true;return true;});
    }

    private static void beginSearch(ServerLevel level,BuenaVillageData data,BuenaVillageLayout layout){
        launchSearch(new SiteSearch(level,layout),data);
    }
    private static CompletableFuture<Void> launchSearch(SiteSearch job,BuenaVillageData data){
        var random=job.level.getChunkSource().randomState();
        if(SEARCHES.putIfAbsent(random,job)!=null)return CompletableFuture.completedFuture(null);
        data.noSite(0,0,"searching");
        return CompletableFuture.runAsync(()->{
            try{choose(job);}
            catch(SearchDeadline expired){
                noSite(job,job.checked,(System.nanoTime()-job.started)/1_000_000,"search_time_budget");
                JoblessReincarnation.LOGGER.warn("Buena site search reached its {} ms budget after {} candidates; keeping vanilla spawn",SEARCH_LIMIT_MILLIS,job.checked);
            }
            catch(CancellationException ignored){}
            catch(Exception e){JoblessReincarnation.LOGGER.error("Buena site survey failed without changing terrain",e);}
            finally{SEARCHES.remove(random,job);}
        },Util.backgroundExecutor());
    }
    private static void choose(SiteSearch job){
        ServerLevel level=job.level;BuenaVillageLayout layout=job.layout;
        long start=System.nanoTime();int attempts=0;Map<String,Integer> rejected=new TreeMap<>();
        BlockPos spawn=job.spawn;ChunkPos spawnChunk=new ChunkPos(spawn);
        NoiseTerrain hintTerrain=new NoiseTerrain(level,job);
        Map<String,List<BoundingBox>> nearbyStructures=new HashMap<>();
        List<ChunkPos> sites=job.pairInitialSpawn?creationCandidates(level.getSeed()):candidates(level.getSeed());
        if(job.pairInitialSpawn&&hintTerrain.generator.getClass()==net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class){
            List<ChunkPos> preferred=new ArrayList<>(),later=new ArrayList<>();
            for(var offset:sites){job.checkTime();var at=new ChunkPos(spawnChunk.x+offset.x,spawnChunk.z+offset.z);
                boolean suitable=true;
                for(int[] point:new int[][]{{0,0},{8,-39},{-41,-7}})
                    suitable&=hintTerrain.generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(at.getMiddleBlockX()+point[0]),
                            QuartPos.fromBlock(hintTerrain.generator.getSeaLevel()+1),QuartPos.fromBlock(at.getMiddleBlockZ()+point[1]),hintTerrain.random.sampler()).is(BIOMES);
                (suitable?preferred:later).add(offset);
            }
            // A climate hint is only a priority. The original fine-grid coverage and every exact
            // surface/biome/water/earthwork check remain; unusual vertical biomes are not discarded.
            preferred.addAll(later);sites=preferred;
        }
        int workers=hintTerrain.generator.getClass()==net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class
                &&hintTerrain.generator.getBiomeSource().getClass()==net.minecraft.world.level.biome.MultiNoiseBiomeSource.class
                ?Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-2)):1;
        ThreadLocal<NoiseTerrain> lanes=ThreadLocal.withInitial(()->new NoiseTerrain(level,job));
        ExecutorService pool=Executors.newFixedThreadPool(workers,r->{Thread t=new Thread(r,"Buena surface survey");t.setDaemon(true);return t;});
        try{
        for(int batch=0;batch<sites.size();batch+=workers*16){
            job.checkTime();List<CompletableFuture<Survey>> tasks=new ArrayList<>();
            for(int lane=0;lane<workers*16&&batch+lane<sites.size();lane++){
                var offset=sites.get(batch+lane);
                tasks.add(CompletableFuture.supplyAsync(()->{
                    var terrain=lanes.get();var chunk=new ChunkPos(spawnChunk.x+offset.x,spawnChunk.z+offset.z);
                    int x=chunk.getMiddleBlockX()-88,z=chunk.getMiddleBlockZ()-88;
                    if(terrain.cache.size()>62500)terrain.cache.clear();
                    String reason=!job.pairInitialSpawn&&!clearOfSpawn(x,z,spawn,100)?"spawn_clearance":measured(job,"coarse",()->coarseSite(level,terrain,x,z));
                    if(reason!=null)return new Survey(chunk,x,z,null,List.of(),reason);
                    String[] failure={"plan_rejected"};
                    var plan=measured(job,"plan",()->BuenaVillagePlan.assess(level.getSeed(),x,z,spawn,layout,terrain,level.getMinBuildHeight(),level.getMaxBuildHeight(),r->failure[0]=r));
                    if(plan==null)return new Survey(chunk,x,z,null,List.of(),failure[0]);
                    var choices=job.pairInitialSpawn?measured(job,"approach",()->BuenaVillageSpawn.candidates(plan,terrain)):List.<ChunkPos>of();
                    return new Survey(chunk,x,z,plan,choices,job.pairInitialSpawn&&choices.isEmpty()?"no_dry_spawn_approach":null);
                },pool));
            }
            // Consume immutable results in candidate order, not completion order. Workers only
            // sample noise; structure checks, real chunks, reservation and saved data remain serial.
            for(var task:tasks){
                var scan=awaitSurvey(task);job.checkTime();attempts++;job.checked=attempts;
                var chunk=scan.chunk();int x=scan.x(),z=scan.z();
                if(scan.reason()!=null){count(rejected,scan.reason());continue;}
                var plan=scan.plan();var spawnChoices=scan.choices();
                if(measured(job,"structures",()->nearOtherStructure(level,chunk,x,z,nearbyStructures))){count(rejected,"structure_clearance");continue;}
                int count=attempts;long millis=(System.nanoTime()-start)/1_000_000;
                String reservationFailure=onMain(job,()->{
                    var data=BuenaVillageData.get(level);
                    if(data==null||!data.eligible()||data.plan()!=null)return "reservation_conflict";
                    if(!withinBorder(level,x,z))return "world_border";
                    if(!unbuilt(level,plan))return "built_footprint";
                    job.preparingSpawn=true;
                    BlockPos initial;
                    // Once a site has passed the survey, finish its bounded safe-spawn choices.
                    // Do not discard a valid village merely because native chunk preparation crosses the survey deadline.
                    try{initial=job.pairInitialSpawn?BuenaVillageSpawn.resolve(level,plan,spawnChoices,job::check):null;}
                    finally{job.preparingSpawn=false;}
                    if(job.pairInitialSpawn&&initial==null)return "no_safe_spawn";
                    if(job.pairInitialSpawn&&!unbuilt(level,plan))return "spawn_requested_village_anchor";
                    var chosen=initial==null?plan:plan.withSpawn(initial);
                    // This final check, reservation, active handle and save are one server-thread operation.
                    data.reserve(chosen,count,millis);
                    if(initial!=null)data.recordInitialSpawn(job.spawn,initial);
                    remember(level,layout,chosen);level.getDataStorage().save();
                    JoblessReincarnation.LOGGER.info("Buena village reserved at {} (initial spawn {},{}), {} chunks; status {}",
                            chosen.centre(),chosen.spawnX,chosen.spawnZ,chosen.chunkCount(),data.note());
                    return null;
                });
                if(reservationFailure!=null){count(rejected,reservationFailure);continue;}
                JoblessReincarnation.LOGGER.info("Buena search accepted candidate {} after {} ms; prior rejections {}",attempts,(System.nanoTime()-start)/1_000_000,rejected);
                return;
        }
        }
        noSite(job,attempts,(System.nanoTime()-start)/1_000_000,"no_suitable_unbuilt_site");
        JoblessReincarnation.LOGGER.warn("No suitable Buena village site found within the search radius; no village was placed. Rejections: {}",rejected);
            }finally{pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                Map<String,Long> times=new TreeMap<>();job.timings.forEach((k,v)->times.put(k,v.sum()/1_000_000));
                JoblessReincarnation.LOGGER.info("Buena survey work times in ms (worker stages summed): {}",times);}
    }
    private record Survey(ChunkPos chunk,int x,int z,BuenaVillagePlan plan,List<ChunkPos> choices,String reason){}
    private static <T>T measured(SiteSearch job,String stage,Supplier<T> work){long start=System.nanoTime();try{return work.get();}
        finally{job.timings.computeIfAbsent(stage,k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-start);}}
    private static Survey awaitSurvey(CompletableFuture<Survey> task){
        try{return task.join();}catch(CompletionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    private static void noSite(SiteSearch job,int attempts,long millis,String reason){
        onMain(job,()->{var data=BuenaVillageData.get(job.level);if(data!=null)data.noSite(attempts,millis,reason);return true;});
    }
    private static <T>T onMain(SiteSearch job,Supplier<T> action){
        if(job.level.getServer().isSameThread()){job.check();return action.get();}
        var result=new CompletableFuture<T>();
        job.level.getServer().execute(()->{
            try{job.check();result.complete(action.get());}catch(Exception e){result.completeExceptionally(e);}
        });
        for(;;){
            job.check();
            try{return result.get(100,TimeUnit.MILLISECONDS);}
            catch(TimeoutException waiting){}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CancellationException();}
            catch(ExecutionException failed){if(failed.getCause() instanceof SearchDeadline expired)throw expired;throw new IllegalStateException(failed.getCause());}
        }
    }
    /** Dense near-spawn coverage, thinning farther out; unlike a few radial rays this does not skip whole valleys. */
    public static List<ChunkPos> candidates(long seed){
        List<ChunkPos> out=new ArrayList<>();
        for(int z=-256;z<=256;z++)for(int x=-256;x<=256;x++){
            int d=x*x+z*z;if(d<144||d>256*256)continue;
            int ring=Math.max(Math.abs(x),Math.abs(z)),step=ring<=24?2:ring<=128?4:8;
            if(Math.floorMod(x,step)==0&&Math.floorMod(z,step)==0)out.add(new ChunkPos(x,z));
        }
        out.sort(Comparator.<ChunkPos>comparingInt(p->p.x*p.x+p.z*p.z).thenComparingLong(p->mix(p.toLong()^seed)));
        return List.copyOf(out);
    }
    /** Fresh worlds can select their spawn beside suitable terrain, including land farther from the vanilla noise hint. */
    public static List<ChunkPos> creationCandidates(long seed){
        List<ChunkPos> out=new ArrayList<>(candidates(seed));
        for(int z=-12;z<=12;z+=2)for(int x=-12;x<=12;x+=2)if(x*x+z*z<144)out.add(new ChunkPos(x,z));
        for(int z=-1024;z<=1024;z+=16)for(int x=-1024;x<=1024;x+=16){
            int d=x*x+z*z;if(d>256*256&&d<=1024*1024)out.add(new ChunkPos(x,z));
        }
        out.sort(Comparator.<ChunkPos>comparingInt(p->p.x*p.x+p.z*p.z).thenComparingLong(p->mix(p.toLong()^seed)));
        return List.copyOf(out);
    }
    private static long mix(long value){value^=value>>>33;value*=0xff51afd7ed558ccdL;value^=value>>>33;return value;}
    private static void count(Map<String,Integer> counts,String key){counts.merge(key,1,Integer::sum);}
    public static boolean clearOfSpawn(int x,int z,BlockPos spawn,int distance){
        int dx=Math.max(0,Math.max(x+MIN-spawn.getX(),spawn.getX()-(x+MAX)));
        int dz=Math.max(0,Math.max(z+MIN-spawn.getZ(),spawn.getZ()-(z+MAX)));
        return (long)dx*dx+(long)dz*dz>=(long)distance*distance;
    }
    private static boolean withinBorder(ServerLevel level,int x,int z){
        return level.getWorldBorder().isWithinBounds(new BlockPos(x+MIN,0,z+MIN))&&level.getWorldBorder().isWithinBounds(new BlockPos(x+MAX,0,z+MAX));
    }
    private static boolean nearOtherStructure(ServerLevel level,ChunkPos at,int originX,int originZ,Map<String,List<BoundingBox>> cache){
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        var generator=level.getChunkSource().getGenerator();
        var state=level.getChunkSource().getGeneratorState();
        for(var entry:Map.of("villages",16,"pillager_outposts",11,"ruined_portals",10).entrySet()){
            var holder=registry.getHolder(ResourceKey.create(Registries.STRUCTURE_SET,ResourceLocation.withDefaultNamespace(entry.getKey())));
            if(holder.isEmpty())continue;
            var set=holder.get().value();
            if(!(set.placement() instanceof RandomSpreadStructurePlacement placement)){
                if(state.hasStructureChunkInRange(holder.get(),at.x,at.z,entry.getValue()))return true;
                continue;
            }
            int spacing=placement.spacing(),radius=entry.getValue();
            for(int rz=Math.floorDiv(at.z-radius,spacing);rz<=Math.floorDiv(at.z+radius,spacing);rz++)
                for(int rx=Math.floorDiv(at.x-radius,spacing);rx<=Math.floorDiv(at.x+radius,spacing);rx++){
                    ChunkPos start=placement.getPotentialStructureChunk(level.getSeed(),rx*spacing,rz*spacing);
                    if(Math.abs(start.x-at.x)>radius||Math.abs(start.z-at.z)>radius||!placement.isStructureChunk(state,start.x,start.z))continue;
                    String key=entry.getKey()+"/"+start.toLong();
                    List<BoundingBox> boxes=cache.computeIfAbsent(key,unused->{
                        List<BoundingBox> found=new ArrayList<>();
                        // Native start construction evaluates biome/terrain and builds piece bounds, but places no blocks,
                        // requests no chunks and does not register a real structure start.
                        for(var choice:set.structures()){
                            var structure=choice.structure().value();
                            var candidate=structure.generate(level.registryAccess(),generator,generator.getBiomeSource(),
                                    level.getChunkSource().randomState(),level.getStructureManager(),level.getSeed(),start,0,level,structure.biomes()::contains);
                            if(candidate.isValid())found.add(candidate.getBoundingBox().inflatedBy(16));
                        }
                        return List.copyOf(found);
                    });
                    for(var box:boxes)if(box.intersects(originX+MIN,originZ+MIN,originX+MAX,originZ+MAX))return true;
                }
        }
        return false;
    }
    private static @Nullable String coarseSite(ServerLevel level,NoiseTerrain terrain,int x,int z){
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,dry=0,temperate=0,checked=0;
        // Reject unsuitable key locations before the more expensive region scan.
        for(int[] point:new int[][]{{88,88},{96,49},{47,81}}){
            int px=x+point[0],pz=z+point[1];var c=terrain.at(px,pz);
            if(!c.dry())return "wet_landmark";
            if(!terrain.generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(px),QuartPos.fromBlock(c.ground()+1),QuartPos.fromBlock(pz),terrain.random.sampler()).is(BIOMES))
                return "non_temperate_landmark";
        }
        for(int rz=0;rz<5;rz++)for(int rx=0;rx<5;rx++){
            int px=x+rx*44,pz=z+rz*44;var c=terrain.at(px,pz);
            if(c.dry())dry++;min=Math.min(min,c.ground());max=Math.max(max,c.ground());
            var biome=terrain.generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(px),QuartPos.fromBlock(c.ground()),QuartPos.fromBlock(pz),terrain.random.sampler());
            if(biome.is(BIOMES))temperate++;
            checked++;
            // These failures cannot be repaired by later samples. Avoid evaluating the remainder.
            if(checked-dry>4)return "mostly_wet";
            if(checked-temperate>7)return "non_temperate_region";
            if(max-min>32)return "overall_relief";
        }
        if(dry<21)return "mostly_wet";
        if(temperate<18)return "non_temperate_region";
        if(max-min>32)return "overall_relief";
        return null;
    }
    /** Disk reads and existing holders only. No candidate chunk is generated as a side effect of validation. */
    private static boolean unbuilt(ServerLevel level,BuenaVillagePlan plan){
        var box=plan.bounds();var map=level.getChunkSource().chunkMap;
        for(int z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZ(),16);z++)
            for(int x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxX(),16);x++){
                ChunkPos at=new ChunkPos(x,z);var holder=map.getVisibleChunkIfPresent(at.toLong());
                ChunkAccess loaded=holder==null?null:holder.getLatestChunk();
                if(loaded!=null&&loaded.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))return false;
                var saved=map.read(at).join();
                if(saved.isPresent()){
                    ResourceLocation key=ResourceLocation.tryParse(saved.get().getString("Status"));
                    if(key==null||!BuiltInRegistries.CHUNK_STATUS.containsKey(key)||BuiltInRegistries.CHUNK_STATUS.get(key).isOrAfter(ChunkStatus.FEATURES))return false;
                }
            }
        ChunkPos anchor=plan.anchor();
        return level.getChunkSource().getChunk(anchor.x,anchor.z,ChunkStatus.EMPTY,false)==null&&map.read(anchor).join().isEmpty();
    }
    private static final class NoiseTerrain implements BuenaVillagePlan.Terrain {
        final ServerLevel level;final ChunkGenerator generator;final RandomState random;
        final SiteSearch job;
        final BuenaVillagePlan.Terrain surface;
        final Map<Long,BuenaVillagePlan.Column> cache=new HashMap<>();
        NoiseTerrain(ServerLevel level,SiteSearch job){
            this.level=level;this.job=job;generator=level.getChunkSource().getGenerator();random=level.getChunkSource().randomState();
            surface=BuenaSurfaceSampler.create(generator,level,random);
        }
        @Override public BuenaVillagePlan.Column at(int x,int z){
            job.checkTime();
            return cache.computeIfAbsent(ChunkPos.asLong(x,z),key->surface.at(x,z));
        }
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event){
        if(!(event.getLevel() instanceof ServerLevel level)||level.dimension()!=Level.OVERWORLD)return;
        UUID village=BuenaChunkMarks.completedVillage(event.getChunk());if(village==null)return;
        ChunkPos at=event.getChunk().getPos();
        level.getServer().execute(()->{BuenaVillageData data=BuenaVillageData.get(level);if(data!=null)data.chunkFinished(village,at);});
    }
    @SubscribeEvent public static void residentJoined(EntityJoinLevelEvent event){
        if(!(event.getLevel() instanceof ServerLevel level)||level.dimension()!=Level.OVERWORLD)return;
        Entity entity=event.getEntity();
        if(!(entity instanceof FamilyResidentEntity||entity instanceof RoxyTutorEntity||entity instanceof SylphietteEntity)||!entity.getTags().contains(RESIDENT_TAG))return;
        String role=null;UUID village=null;
        try{
            for(String tag:entity.getTags()){
                if(tag.startsWith(ROLE_TAG))role=tag.substring(ROLE_TAG.length());
                if(tag.startsWith(VILLAGE_TAG))village=UUID.fromString(tag.substring(VILLAGE_TAG.length()));
            }
        }catch(IllegalArgumentException invalid){return;}
        if(role==null||village==null||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals(role))return;
        String savedRole=role;UUID savedVillage=village,id=entity.getUUID();
        level.getServer().execute(()->{BuenaVillageData data=BuenaVillageData.get(level);if(data!=null)data.residentSeen(savedVillage,savedRole,id);});
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("jr_buena").requires(s->s.hasPermission(2)).executes(c->{
            BuenaVillageData data=BuenaVillageData.get(c.getSource().getServer().overworld());
            String text=data==null?"Buena: this world has no new-world reservation."
                    :data.plan()==null?"Buena: "+data.note()+" ("+data.attempts()+" candidates)"
                    :"Buena: "+data.plan().centre()+"; "+data.generatedChunks()+"/"+data.plan().chunkCount()+" chunks; "+data.residents().size()+" resident receipts; "+data.note();
            c.getSource().sendSuccess(()->Component.literal(text),false);return data!=null&&data.plan()!=null?1:0;
        }));
    }
    private BuenaVillageGeneration(){}
}
