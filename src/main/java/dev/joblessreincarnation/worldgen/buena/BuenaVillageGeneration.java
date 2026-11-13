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
    /** checked and total grow as S1 inserts neighbours; elapsed is shown on the loading screen and never decides anything. */
    public record SearchProgress(long elapsedMillis,int checked,int total,boolean preparingSpawn){}
    public static @Nullable SearchProgress progress(MinecraftServer server){
        for(var job:SEARCHES.values())if(job.level.getServer()==server)
            return new SearchProgress((System.nanoTime()-job.started)/1_000_000,job.checked,job.total,job.preparingSpawn);
        return null;
    }
    /**
     * Two diagnostic switches, read once from JVM system properties. Neither changes which site is chosen.
     *   -Djobless.buena.workers=N   a fixed number of survey workers instead of the automatic choice (1 vs 4 checks)
     *   -Djobless.buena.trace=FILE  one CSV line per consumed site, in order; off by default, and then nothing is
     *                               recorded per site
     */
    static final class Diagnostics {
        static final int WORKERS=Integer.getInteger("jobless.buena.workers",0);
        static final @Nullable String TRACE=System.getProperty("jobless.buena.trace");
        private Diagnostics(){}
    }
    /** The new-world search: the village site and the world's initial spawn are chosen together. */
    private static final class SiteSearch {
        final ServerLevel level;final BuenaVillageLayout layout;final BlockPos spawn;
        volatile boolean cancelled;
        final long started=System.nanoTime();
        volatile int checked,total;
        volatile boolean preparingSpawn;
        final Map<String,java.util.concurrent.atomic.LongAdder> timings=new ConcurrentHashMap<>();
        SiteSearch(ServerLevel level,BuenaVillageLayout layout,BlockPos reference){
            this.level=level;this.layout=layout;spawn=reference.immutable();
        }
        /** Cancellation only: a server stop or an interrupt. No wall clock ends a search. */
        void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
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
        SiteSearch job=null;
        if(data.plan()==null){
            job=new SiteSearch(level,layout,reference);
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
            if(job!=null&&!job.cancelled&&data.plan()==null){
                // Only here, right after this new world's own search came back empty: memory only, nothing saved.
                NO_SITE_HINT.add(level.getServer());
                JoblessReincarnation.LOGGER.info("Buena: this new world has no village; the first player to join is told once");
            }
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
    /** What a server start does with the saved record. Never a search: only a new world's creation searches. */
    public enum OnStart{NOTHING,INCOMPATIBLE,RESUME}
    public static OnStart onStart(@Nullable BuenaVillageData data,boolean structures){
        if(data==null)return OnStart.NOTHING;
        if(!data.compatible())return OnStart.INCOMPATIBLE;
        if(!data.eligible()||!structures)return OnStart.NOTHING;
        // No plan: a failed or interrupted creation, or an unpaired record from before 0.1.0. Left exactly as it is.
        return data.plan()==null?OnStart.NOTHING:OnStart.RESUME;
    }
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(event.getServer() instanceof GameTestServer)return;
        ServerLevel level=event.getServer().overworld();
        BuenaVillageData data=BuenaVillageData.get(level);
        switch(onStart(data,level.getServer().getWorldData().worldGenOptions().generateStructures())){
            case NOTHING->{return;}
            case INCOMPATIBLE->{JoblessReincarnation.LOGGER.error("Buena village data is newer or invalid; it has not been reset.");return;}
            case RESUME->{}
        }
        try{
            BuenaVillageLayout layout=BuenaVillageLayout.load(level.getServer().getResourceManager(),level.getStructureManager());
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
        NO_SITE_HINT.remove(event.getServer());
    }
    /**
     * Servers whose new world was just created without a village because its own site search found none. Memory only:
     * the first player to join afterwards is told once; a restart, a reopened world or any older save never shows it.
     */
    private static final Set<MinecraftServer> NO_SITE_HINT=ConcurrentHashMap.newKeySet();
    /** Takes the one-time no-village notice for this server: true once, then false. */
    public static boolean takeNoSiteHint(MinecraftServer server){return NO_SITE_HINT.remove(server);}
    /** Tests only: arms the notice as a failed new-world search does. */
    public static void armNoSiteHintForTest(MinecraftServer server){NO_SITE_HINT.add(server);}
    @SubscribeEvent public static void firstPlayerAfterNoSite(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)||!takeNoSiteHint(player.server))return;
        player.sendSystemMessage(Component.translatableWithFallback("message.jobless_reincarnation.buena.no_site",
                "No suitable place for Buena Village was found in this world. You can create a new world with a different seed."));
        JoblessReincarnation.LOGGER.info("Buena: told {} once that this new world has no village",player.getGameProfile().getName());
    }

    private static CompletableFuture<Void> launchSearch(SiteSearch job,BuenaVillageData data){
        var random=job.level.getChunkSource().randomState();
        if(SEARCHES.putIfAbsent(random,job)!=null)return CompletableFuture.completedFuture(null);
        data.noSite(0,0,"searching");
        return CompletableFuture.runAsync(()->{
            try{choose(job);}
            catch(CancellationException ignored){}
            catch(Exception e){JoblessReincarnation.LOGGER.error("Buena site survey failed without changing terrain",e);}
            finally{SEARCHES.remove(random,job);}
        },Util.backgroundExecutor());
    }
    /**
     * The slow path's work cap: logical terrain-column requests (every Terrain.at call a survey makes, counted before
     * the per-worker cache, so neither the cache nor the worker count changes it), summed over the surveys in the fixed
     * consumption order and checked only after a site has been fully handled (including any neighbours it adds). The
     * vanilla fast path has no cap and walks the whole order.
     */
    public static final long SLOW_PATH_SAMPLE_CAP=250_000;
    /**
     * The deterministic survey. Sites come from BuenaSiteOrder (the 0.1.0 grid, climate hint first on the vanilla
     * generator, with S1 neighbours inserted after late planning failures near the reference). Results are consumed
     * strictly in that order and no clock decides anything. Workers only sample noise; structure checks, real chunks,
     * the reservation and the saved data stay serial.
     */
    private static void choose(SiteSearch job){
        ServerLevel level=job.level;BuenaVillageLayout layout=job.layout;
        long start=System.nanoTime();int attempts=0;Map<String,Integer> rejected=new TreeMap<>();
        BlockPos spawn=job.spawn;ChunkPos spawnChunk=new ChunkPos(spawn);
        NoiseTerrain hintTerrain=new NoiseTerrain(level,job);
        Map<String,List<BoundingBox>> nearbyStructures=new HashMap<>();
        boolean vanillaGenerator=hintTerrain.generator.getClass()==net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class;
        boolean fastPath=vanillaGenerator&&hintTerrain.generator.getBiomeSource().getClass()==net.minecraft.world.level.biome.MultiNoiseBiomeSource.class;
        List<ChunkPos> grid=creationCandidates(level.getSeed());
        int preferredCount=-1;long hintStart=System.nanoTime();
        if(vanillaGenerator){
            List<ChunkPos> preferred=new ArrayList<>(),later=new ArrayList<>();
            for(var offset:grid){job.check();var at=new ChunkPos(spawnChunk.x+offset.x,spawnChunk.z+offset.z);
                boolean suitable=true;
                for(int[] point:new int[][]{{0,0},{8,-39},{-41,-7}})
                    suitable&=hintTerrain.generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(at.getMiddleBlockX()+point[0]),
                            QuartPos.fromBlock(hintTerrain.generator.getSeaLevel()+1),QuartPos.fromBlock(at.getMiddleBlockZ()+point[1]),hintTerrain.random.sampler()).is(BIOMES);
                (suitable?preferred:later).add(offset);
            }
            // A climate hint is only a priority. The original fine-grid coverage and every exact
            // surface/biome/water/earthwork check remain; unusual vertical biomes are not discarded.
            preferredCount=preferred.size();preferred.addAll(later);grid=preferred;
        }
        long hintMillis=(System.nanoTime()-hintStart)/1_000_000;
        BuenaSiteOrder order=new BuenaSiteOrder();
        for(int i=0;i<grid.size();i++){
            if((i&1023)==0)job.check();
            order.add(BuenaSiteOrder.grid(spawnChunk,grid.get(i),preferredCount<0?-1:i<preferredCount?1:0));
        }
        job.total=order.size();
        int automatic=fastPath?Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-2)):1;
        int workers=Diagnostics.WORKERS>0?Diagnostics.WORKERS:automatic;
        JoblessReincarnation.LOGGER.info("Buena survey setup: {} grid sites ({} first by the climate hint, ordered in {} ms); neighbours {} blocks apart within {} of a parent, at most {} per search; {} survey workers (automatic {}); {} path{}; generator {}, biome source {}",
                order.size(),preferredCount,hintMillis,BuenaSiteOrder.STEP,BuenaSiteOrder.RADIUS,BuenaSiteOrder.CAP,workers,automatic,fastPath?"fast":"slow",
                fastPath?" (whole order)":" (stops after "+SLOW_PATH_SAMPLE_CAP+" column requests)",hintTerrain.generator.getClass().getName(),
                hintTerrain.generator.getBiomeSource().getClass().getName());
        ThreadLocal<NoiseTerrain> lanes=ThreadLocal.withInitial(()->new NoiseTerrain(level,job));
        ExecutorService pool=Executors.newFixedThreadPool(workers,r->{Thread t=new Thread(r,"Buena surface survey");t.setDaemon(true);return t;});
        long samples=0;Trace trace=Trace.open();
        java.util.function.Function<BuenaSiteOrder.Site,CompletableFuture<Survey>> survey=site->CompletableFuture.supplyAsync(()->{
            var terrain=lanes.get();var chunk=site.chunk();int x=site.x(),z=site.z();
            if(terrain.cache.size()>62500)terrain.cache.clear();
            var cost=new Cost(terrain);
            String reason=measured(job,"coarse",()->coarseSite(level,terrain,x,z),cost,0);
            if(reason!=null)return new Survey(chunk,x,z,null,List.of(),reason,cost.done(terrain));
            String[] failure={"plan_rejected"};
            var plan=measured(job,"plan",()->BuenaVillagePlan.assess(level.getSeed(),x,z,spawn,layout,terrain,level.getMinBuildHeight(),level.getMaxBuildHeight(),r->failure[0]=r),cost,1);
            if(plan==null)return new Survey(chunk,x,z,null,List.of(),failure[0],cost.done(terrain));
            var choices=measured(job,"approach",()->BuenaVillageSpawn.candidates(plan,terrain),cost,2);
            return new Survey(chunk,x,z,plan,choices,choices.isEmpty()?"no_dry_spawn_approach":null,cost.done(terrain));
        },pool);
        List<CompletableFuture<Survey>> futures=new ArrayList<>(Collections.nCopies(order.size(),(CompletableFuture<Survey>)null));
        int window=workers*16;
        try{
        for(int i=0;i<order.size();i++){
            job.check();
            for(int j=i;j<order.size()&&j<i+window;j++)if(futures.get(j)==null)futures.set(j,survey.apply(order.get(j)));
            long waitStart=trace.on()?System.nanoTime():0;
            // Consume immutable results in candidate order, not completion order.
            var scan=awaitSurvey(futures.get(i));
            futures.set(i,CompletableFuture.completedFuture(null));
            attempts++;job.checked=attempts;samples+=scan.cost().samples();
            var site=order.get(i);
            Trace.Line line=null;
            if(trace.on()){long consumed=System.nanoTime();var centre=site.chunk();
                line=new Trace.Line(attempts,centre.x-spawnChunk.x,centre.z-spawnChunk.z,site.group(),site.x(),site.z(),site.tag(),site.parent(),scan.cost(),consumed-waitStart,samples,consumed);}
            String reason=scan.reason();
            if(reason==null){
                var chunk=scan.chunk();int x=scan.x(),z=scan.z();var plan=scan.plan();var spawnChoices=scan.choices();
                long structuresStart=System.nanoTime();
                boolean crowded=measured(job,"structures",()->nearOtherStructure(level,chunk,x,z,nearbyStructures));
                if(line!=null)line.structures=System.nanoTime()-structuresStart;
                if(crowded)reason="structure_clearance";
                else{
                    int count=attempts;long millis=(System.nanoTime()-start)/1_000_000;long mainStart=System.nanoTime();
                    reason=onMain(job,()->{
                        var data=BuenaVillageData.get(level);
                        if(data==null||!data.eligible()||data.plan()!=null)return "reservation_conflict";
                        if(!withinBorder(level,x,z))return "world_border";
                        if(!unbuilt(level,plan))return "built_footprint";
                        job.preparingSpawn=true;
                        BlockPos initial;
                        // Once a site has passed the survey, finish its bounded safe-spawn choices.
                        try{initial=BuenaVillageSpawn.resolve(level,plan,spawnChoices,job::check);}
                        finally{job.preparingSpawn=false;}
                        if(initial==null)return "no_safe_spawn";
                        if(!unbuilt(level,plan))return "spawn_requested_village_anchor";
                        var chosen=plan.withSpawn(initial);
                        // This final check, reservation, active handle and save are one server-thread operation.
                        data.reserve(chosen,count,millis);
                        data.recordInitialSpawn(job.spawn,initial);
                        remember(level,layout,chosen);level.getDataStorage().save();
                        JoblessReincarnation.LOGGER.info("Buena village reserved at {} (initial spawn {},{}), {} chunks; status {}",
                                chosen.centre(),chosen.spawnX,chosen.spawnZ,chosen.chunkCount(),data.note());
                        return null;
                    });
                    if(line!=null)line.main=System.nanoTime()-mainStart;
                }
            }
            if(reason==null){
                trace.write(line,"accepted",start);
                JoblessReincarnation.LOGGER.info("Buena search accepted candidate {} ({} at origin {},{}; centre {} blocks from the reference) after {} ms; prior rejections {}",
                        attempts,site.tag(),site.x(),site.z(),Math.round(Math.hypot(site.x()+88-spawn.getX(),site.z()+88-spawn.getZ())),(System.nanoTime()-start)/1_000_000,rejected);
                return;
            }
            count(rejected,reason);trace.write(line,reason,start);
            var extra=order.rejected(i,reason,attempts,job::check);
            if(!extra.isEmpty()){futures.addAll(i+1,Collections.nCopies(extra.size(),(CompletableFuture<Survey>)null));job.total=order.size();}
            if(!fastPath&&samples>=SLOW_PATH_SAMPLE_CAP){
                noSite(job,attempts,(System.nanoTime()-start)/1_000_000,"search_work_cap");
                JoblessReincarnation.LOGGER.warn("Buena site search stopped at its slow-path work cap of {} column requests after {} sites; no village was placed. Rejections: {}",SLOW_PATH_SAMPLE_CAP,attempts,rejected);
                return;
            }
        }
        noSite(job,attempts,(System.nanoTime()-start)/1_000_000,"no_suitable_unbuilt_site");
        JoblessReincarnation.LOGGER.warn("No suitable Buena village site found among {} sites ({} of them neighbours); no village was placed. Rejections: {}",order.size(),order.refined(),rejected);
        }finally{pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            Map<String,Long> times=new TreeMap<>();job.timings.forEach((k,v)->times.put(k,v.sum()/1_000_000));
            JoblessReincarnation.LOGGER.info("Buena survey work times in ms (worker stages summed): {}",times);
            // Every outcome: 0.1.0 logged this tally only on success or after the whole list.
            JoblessReincarnation.LOGGER.info("Buena survey consumed {} of {} sites ({} neighbours inserted) and {} column requests in {} ms; rejections {}",
                    attempts,order.size(),order.refined(),samples,(System.nanoTime()-start)/1_000_000,rejected);
            trace.close();}
    }
    private record Survey(ChunkPos chunk,int x,int z,BuenaVillagePlan plan,List<ChunkPos> choices,String reason,Cost.Spent cost){}
    private static <T>T measured(SiteSearch job,String stage,Supplier<T> work){long start=System.nanoTime();try{return work.get();}
        finally{job.timings.computeIfAbsent(stage,k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-start);}}
    /** The same stage total, and also this candidate's own share, measured on the worker that surveyed it. */
    private static <T>T measured(SiteSearch job,String stage,Supplier<T> work,Cost cost,int slot){long start=System.nanoTime();try{return work.get();}
        finally{long spent=System.nanoTime()-start;cost.nanos[slot]+=spent;
            job.timings.computeIfAbsent(stage,k->new java.util.concurrent.atomic.LongAdder()).add(spent);}}
    /** One candidate's survey cost. Samples count every column request, so they do not depend on which lane's cache was warm. */
    private static final class Cost {
        /** The worker starts this candidate when the Cost is made and finishes it in done(); both are System.nanoTime(). */
        final long[] nanos=new long[3];final long samplesBefore,missesBefore,started=System.nanoTime();
        Cost(NoiseTerrain terrain){samplesBefore=terrain.requests;missesBefore=terrain.misses;}
        Spent done(NoiseTerrain terrain){return new Spent(nanos[0],nanos[1],nanos[2],terrain.requests-samplesBefore,terrain.misses-missesBefore,started,System.nanoTime());}
        record Spent(long coarse,long plan,long approach,long samples,long misses,long started,long finished){}
    }
    /** Optional per-candidate CSV for development measurement; nothing is recorded unless -Djobless.buena.trace is set. */
    private static final class Trace {
        static final class Line {
            final int index,dx,dz,preferred,originX,originZ,parent;final String tag;final Cost.Spent cost;final long waited,samplesTotal,consumed;
            long structures,main;
            /** dx/dz: the chunk offset of the village centre from the reference chunk; consumed: System.nanoTime() after the wait. */
            Line(int index,int dx,int dz,int preferred,int originX,int originZ,String tag,int parent,Cost.Spent cost,long waited,long samplesTotal,long consumed){
                this.index=index;this.dx=dx;this.dz=dz;this.preferred=preferred;this.originX=originX;this.originZ=originZ;this.tag=tag;this.parent=parent;
                this.cost=cost;this.waited=waited;this.samplesTotal=samplesTotal;this.consumed=consumed;
            }
        }
        /** Null when tracing is off. */
        private final java.io.Writer out;
        private Trace(java.io.Writer out){this.out=out;}
        boolean on(){return out!=null;}
        static Trace open(){
            if(Diagnostics.TRACE==null)return new Trace(null);
            try{
                var path=java.nio.file.Path.of(Diagnostics.TRACE).toAbsolutePath();
                java.nio.file.Files.createDirectories(path.getParent());
                var out=java.nio.file.Files.newBufferedWriter(path,java.nio.charset.StandardCharsets.UTF_8);
                out.write("index,dx,dz,distance_chunks,preferred,result,coarse_ms,plan_ms,approach_ms,samples,new_columns,wait_ms,structures_ms,main_ms,elapsed_ms,samples_total"
                        +",origin_x,origin_z,tag,parent,started_ms,finished_ms,consumed_ms\n");
                return new Trace(out);
            }catch(java.io.IOException e){JoblessReincarnation.LOGGER.warn("Buena survey trace not written: {}",e.toString());return new Trace(null);}
        }
        /** Does nothing when tracing is off (line is then null). */
        void write(@Nullable Line l,String result,long searchStart){
            if(out==null||l==null)return;
            try{out.write(String.format(java.util.Locale.ROOT,"%d,%d,%d,%.1f,%d,%s,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%.3f,%d,%d",
                    l.index,l.dx,l.dz,Math.sqrt((double)l.dx*l.dx+(double)l.dz*l.dz),l.preferred,result,
                    l.cost.coarse()/1e6,l.cost.plan()/1e6,l.cost.approach()/1e6,l.cost.samples(),l.cost.misses(),
                    l.waited/1e6,l.structures/1e6,l.main/1e6,(System.nanoTime()-searchStart)/1_000_000,l.samplesTotal)
                    +String.format(java.util.Locale.ROOT,",%d,%d,%s,%d,%.3f,%.3f,%.3f",l.originX,l.originZ,l.tag,l.parent,
                    (l.cost.started()-searchStart)/1e6,(l.cost.finished()-searchStart)/1e6,(l.consumed-searchStart)/1e6)+"\n");}
            catch(java.io.IOException e){JoblessReincarnation.LOGGER.warn("Buena survey trace line lost: {}",e.toString());}
        }
        void close(){if(out!=null)try{out.close();}catch(java.io.IOException ignored){}}
    }
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
            catch(ExecutionException failed){throw new IllegalStateException(failed.getCause());}
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
        /** Diagnostics only: column requests and the columns actually sampled. Each lane stays on one worker thread. */
        long requests,misses;
        NoiseTerrain(ServerLevel level,SiteSearch job){
            this.level=level;this.job=job;generator=level.getChunkSource().getGenerator();random=level.getChunkSource().randomState();
            surface=BuenaSurfaceSampler.create(generator,level,random);
        }
        @Override public BuenaVillagePlan.Column at(int x,int z){
            job.check();requests++;
            return cache.computeIfAbsent(ChunkPos.asLong(x,z),key->{misses++;return surface.at(x,z);});
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
