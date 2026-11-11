package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.worldgen.buena.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static dev.joblessreincarnation.worldgen.buena.BuenaVillageLayout.*;

/** Native resource, planning and persistence tests. Natural generation and real-client evidence remain separate. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuenaVillageGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void independentWorkerSamplersMatchNativeColumns(GameTestHelper h)throws Exception{
        var level=h.getLevel();var registries=level.registryAccess();
        var settings=registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var biome=new net.minecraft.world.level.biome.FixedBiomeSource(registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biome,settings);
        var random=net.minecraft.world.level.levelgen.RandomState.create(settings.value(),registries.lookupOrThrow(Registries.NOISE),212245322697743731L);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<java.util.concurrent.Future<List<BuenaVillagePlan.Column>>>();
            for(int worker=0;worker<2;worker++){final int origin=worker==0?-4092:99;
                tasks.add(pool.submit(()->{var sampler=BuenaSurfaceSampler.create(generator,level,random);var result=new ArrayList<BuenaVillagePlan.Column>();
                    for(int z=0;z<8;z++)for(int x=0;x<8;x++)result.add(sampler.at(origin+x,8964+z));return result;}));}
            for(int worker=0;worker<2;worker++){int origin=worker==0?-4092:99;var result=tasks.get(worker).get(20,java.util.concurrent.TimeUnit.SECONDS);
                for(int z=0;z<8;z++)for(int x=0;x<8;x++){
                    var column=generator.getBaseColumn(origin+x,8964+z,level,random);int y=level.getMaxBuildHeight()-1;
                    while(y>level.getMinBuildHeight()&&column.getBlock(y).isAir())y--;
                    h.assertTrue(result.get(z*8+x).equals(new BuenaVillagePlan.Column(y,column.getBlock(y).blocksMotion()&&column.getBlock(y).getFluidState().isEmpty())),"Independent worker caches preserve native results at cell boundaries");
                }
            }
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void creationSurveyRetainsDeterministicDenseCoverage(GameTestHelper h){
        var list=BuenaVillageGeneration.creationCandidates(12345);
        h.assertValueEqual(new HashSet<>(list).size(),list.size(),"No candidate is scanned twice");
        h.assertTrue(list.equals(BuenaVillageGeneration.creationCandidates(12345)),"Ordering remains deterministic");
        h.assertTrue(list.containsAll(BuenaVillageGeneration.candidates(12345))
                &&list.contains(new ChunkPos(2,0))&&list.contains(new ChunkPos(512,0)),"Near valleys and distant regions remain available");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void spawnResolutionHonoursCancellationBeforeRequestingAChunk(GameTestHelper h){
        boolean stopped=false;try{BuenaVillageSpawn.resolve(h.getLevel(),flat(h),List.of(new ChunkPos(900000,900000)),()->{throw new java.util.concurrent.CancellationException();});}
        catch(java.util.concurrent.CancellationException expected){stopped=true;}
        h.assertTrue(stopped,"An expired search cannot start another expensive spawn chunk");h.succeed();
    }
    private static BuenaVillageLayout layout(GameTestHelper h){return BuenaVillageLayout.load(h.getLevel().getServer().getResourceManager(),h.getLevel().getStructureManager());}
    private static BuenaVillagePlan flat(GameTestHelper h){
        BuenaVillagePlan p=BuenaVillagePlan.assess(42,176,-88,BlockPos.ZERO,layout(h),(x,z)->new BuenaVillagePlan.Column(64,true),-64,320);
        h.assertTrue(p!=null,"A flat, dry site is valid");return p;
    }
    @GameTest(template="empty")
    public static void frozenLayoutHasFiveRolesAndRotatedDeliveredParts(GameTestHelper h){
        var l=layout(h);
        h.assertValueEqual(l.parts.stream().filter(p->p.kind()==Kind.BUILDING).count(),18L,"Eighteen authored buildings/landmarks");
        h.assertValueEqual(l.parts.stream().filter(p->p.kind()==Kind.FIELD).count(),36L,"Thirty-six retained field plots");
        h.assertValueEqual(l.parts.stream().filter(p->p.kind()==Kind.TREE).count(),85L,"The frozen forest and solitary trees");
        h.assertValueEqual(l.residents.size(),5,"Only the existing five residents");
        h.assertTrue(l.parts.stream().anyMatch(p->p.x()<0),"The reserved bounds include the trees outside the core 176-square");
        h.assertTrue(l.byKey.get("house_04").width()==13&&l.byKey.get("house_04").depth()==15,"Quarter-turned stone house has its delivered footprint");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void flatVillageKeepsPracticeGroundAtHomeHeight(GameTestHelper h){
        var l=layout(h);var p=flat(h);int floor=p.partHeight("greyrat_home");
        for(int z=40;z<=58;z++)for(int x=105;x<=123;x++)if(l.sparring(x,z))h.assertTrue(p.height(x,z)==floor,"Practice ring shares the actual house floor");
        h.assertTrue(p.clearOfSpawn(100)&&p.anchor().getMiddleBlockX()==p.originX+88,"Reserved area clears spawn and has one reproducible anchor");
        h.assertTrue(!BuenaVillageGeneration.clearOfSpawn(-88,-88,BlockPos.ZERO,100),"A village around spawn is rejected");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void gentleTerrainKeepsDistinctFloorsAndWalkableRoadGradients(GameTestHelper h){
        var l=layout(h);var p=BuenaVillagePlan.assess(7,176,-88,BlockPos.ZERO,l,(x,z)->new BuenaVillagePlan.Column(64+Math.floorDiv(x-176,32)+Math.floorDiv(z+88,48),true),-64,320);
        h.assertTrue(p!=null,"Gentle terrain is accepted without making the whole village a flat slab");
        h.assertTrue(p.partHeight("house_08")!=p.partHeight("greyrat_home"),"Distant buildings keep different local floor heights");
        for(int z=MIN+1;z<MAX;z++)for(int x=MIN+1;x<MAX;x++)if(l.road(x,z)||l.berm(x,z))for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
            if(l.road(x+dx,z+dz)||l.berm(x+dx,z+dz))h.assertTrue(Math.abs(p.height(x,z)-p.height(x+dx,z+dz))<=1,"Orthogonal and diagonal road/berm steps rise at most one block");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void steepWetAndTooShallowSitesAreRejected(GameTestHelper h){
        var l=layout(h);
        h.assertTrue(BuenaVillagePlan.assess(1,176,-88,BlockPos.ZERO,l,(x,z)->new BuenaVillagePlan.Column(x,true),-64,320)==null,"An extreme hillside is not terraced into a village");
        h.assertTrue(BuenaVillagePlan.assess(1,176,-88,BlockPos.ZERO,l,(x,z)->new BuenaVillagePlan.Column(63,false),-64,320)==null,"An ocean is not filled");
        h.assertTrue(BuenaVillagePlan.assess(1,176,-88,BlockPos.ZERO,l,(x,z)->new BuenaVillagePlan.Column(-63,true),-64,320)==null,"The two-block channel cannot cut through the world bottom");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void savedPlanAndUnknownDataNeverCreateASecondVillage(GameTestHelper h){
        var p=flat(h);var restored=BuenaVillagePlan.load(p.save());
        h.assertTrue(restored.id.equals(p.id)&&restored.save().equals(p.save()),"Plan heights, location and identity round-trip exactly");
        CompoundTag initial=new CompoundTag();initial.putInt("format",1);initial.putBoolean("eligible",true);
        var data=BuenaVillageData.load(initial,h.getLevel().registryAccess());data.reserve(p,1,2);
        var different=BuenaVillagePlan.assess(99,432,-88,BlockPos.ZERO,layout(h),(x,z)->new BuenaVillagePlan.Column(64,true),-64,320);
        boolean rejected=false;try{data.reserve(different,2,3);}catch(IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected&&data.plan().id.equals(p.id),"A second candidate cannot replace the saved village");
        CompoundTag unknown=new CompoundTag();unknown.putInt("format",99);unknown.putString("future","keep this");
        var incompatible=BuenaVillageData.load(unknown,h.getLevel().registryAccess());
        h.assertTrue(!incompatible.eligible()&&incompatible.plan()==null&&incompatible.save(new CompoundTag(),h.getLevel().registryAccess()).equals(unknown),"Unknown data is preserved and generation is disabled");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void untouchedFringeWaterDoesNotBecomeArtificialLand(GameTestHelper h){
        var l=layout(h);
        var p=BuenaVillagePlan.assess(2,176,-88,BlockPos.ZERO,l,(x,z)->{
            boolean fringe=x-176>178;
            return new BuenaVillagePlan.Column(fringe?30:64,!fringe);
        },-64,320);
        h.assertTrue(p!=null,"A deep pond entirely outside the construction area does not reject the village");
        h.assertTrue(p.height(183,50)==p.originalHeight(183,50),"Untouched fringe retains its original terrain profile");
        h.assertTrue(p.matches(l),"The stored plan still contains every required building and field");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void oneFloodedConstructionColumnIsStillRejected(GameTestHelper h){
        var l=layout(h);
        var p=BuenaVillagePlan.assess(2,176,-88,BlockPos.ZERO,l,(x,z)->
                new BuenaVillagePlan.Column(64,!(x-176==77&&z+88==27)),-64,320);
        h.assertTrue(p==null,"A single water column under the house cannot be hidden by sparse sampling");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void vegetationClearanceIsLocalToTheReservedSettlement(GameTestHelper h){
        var p=flat(h);
        h.assertTrue(!BuenaVegetation.Clearance.permits(p,p.originX+80,p.originZ+80),"Native decoration is excluded from the inhabited core");
        h.assertTrue(BuenaVegetation.Clearance.permits(p,p.originX-40,p.originZ+80),"The western natural woodland outside clearance remains available");
        h.assertTrue(BuenaVegetation.Clearance.permits(p,p.originX+512,p.originZ+80),"Other villages and distant terrain are not cleared");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void riverBanksUseTheSameSavedGradeAsTheirWalkingSurfaces(GameTestHelper h){
        var l=layout(h);var p=BuenaVillagePlan.assess(7,176,-88,BlockPos.ZERO,l,(x,z)->new BuenaVillagePlan.Column(64+Math.floorDiv(z+88,32),true),-64,320);
        h.assertTrue(p!=null,"A mild longitudinal river slope fits");
        for(int z=0;z<176;z++){
            int bank=Math.max(p.riverHeight(z),Math.max(p.riverHeight(z-1),p.riverHeight(z+1)));
            h.assertTrue(p.height(l.riverX(z)+3,z)==bank,"Outer bank is planned at its actual placed height");
            h.assertTrue(p.height(l.riverX(z),z)==p.riverHeight(z),"Channel and profile share their height");
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void capturedNativeTerrainFitsRiverBesideTheBarn(GameTestHelper h)throws Exception{
        try(var stream=BuenaVillageGameTests.class.getResourceAsStream("/buena-seed42-height.json")){
            h.assertTrue(stream!=null,"Captured natural-world regression input exists");
            var data=com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            int ox=data.get("x").getAsInt(),oz=data.get("z").getAsInt();var values=data.getAsJsonArray("natural");
            var l=layout(h);
            var p=BuenaVillagePlan.assess(42,ox,oz,new BlockPos(-32,64,0),l,(x,z)->{
                int lx=Math.clamp(x-ox,MIN,MAX),lz=Math.clamp(z-oz,MIN,MAX);
                return new BuenaVillagePlan.Column(values.get(index(lx,lz)).getAsInt(),true);
            },-64,320);
            h.assertTrue(p!=null,"River adapts its reaches to the actual nearby barn and field instead of cutting a ledge through them");
            h.assertTrue(p.height(141,75)==p.partHeight("house_06"),"Overlapping bank at barn edge preserves the saved barn floor");
            h.succeed();
        }
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void earlySurfaceSamplingExactlyMatchesRealNativeColumns(GameTestHelper h){
        var level=h.getLevel();var registries=level.registryAccess();
        var settings=registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var biome=new net.minecraft.world.level.biome.FixedBiomeSource(registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biome,settings);
        int dry=0,wet=0;
        for(long seed:new long[]{42,12345}){
            var random=net.minecraft.world.level.levelgen.RandomState.create(settings.value(),registries.lookupOrThrow(Registries.NOISE),seed);
            var fast=BuenaSurfaceSampler.create(generator,level,random);
            for(int z=-2048;z<=2048;z+=512)for(int x=-2048;x<=2048;x+=512){
                var full=generator.getBaseColumn(x,z,level,random);var actual=fast.at(x,z);
                int y=level.getMaxBuildHeight()-1;while(y>level.getMinBuildHeight()&&full.getBlock(y).isAir())y--;
                boolean expected=full.getBlock(y).blocksMotion()&&full.getBlock(y).getFluidState().isEmpty();
                h.assertTrue(actual.ground()==y&&actual.dry()==expected,"Native early-stop matches full column at "+seed+"/"+x+"/"+z);
                if(expected)dry++;else wet++;
            }
            for(int[] start:new int[][]{{-2,-2},{102,-900},{-1400,1200}})for(int dz=0;dz<8;dz++)for(int dx=0;dx<8;dx++){
                int x=start[0]+dx,z=start[1]+dz;var full=generator.getBaseColumn(x,z,level,random);var actual=fast.at(x,z);
                int y=level.getMaxBuildHeight()-1;while(y>level.getMinBuildHeight()&&full.getBlock(y).isAir())y--;
                h.assertTrue(actual.ground()==y&&actual.dry()==(full.getBlock(y).blocksMotion()&&full.getBlock(y).getFluidState().isEmpty()),
                        "Reusing native cell corners preserves each offset, including negative cell boundaries");
            }
        }
        h.assertTrue(dry>0&&wet>0,"Regression includes real land and real water");
        var custom=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biome,settings){};
        var random=net.minecraft.world.level.levelgen.RandomState.create(settings.value(),registries.lookupOrThrow(Registries.NOISE),42);
        h.assertTrue(!(BuenaSurfaceSampler.create(custom,level,random) instanceof BuenaSurfaceSampler),"An overridden generator retains its own public API");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void newWorldSpawnChoicesStayOutsideTheVillageAndRefuseWaterApproaches(GameTestHelper h){
        var p=flat(h);
        var choices=BuenaVillageSpawn.candidates(p,(x,z)->new BuenaVillagePlan.Column(64,true));
        h.assertTrue(!choices.isEmpty(),"A flat dry countryside offers initial spawn choices");
        for(var chunk:choices)h.assertTrue(BuenaVillageSpawn.suitableDistance(p,chunk.getMiddleBlockPosition(65)),"Each spawn chunk centre is 240-320 blocks away with a clear village edge");
        h.assertTrue(BuenaVillageSpawn.candidates(p,(x,z)->new BuenaVillagePlan.Column(63,false)).isEmpty(),"The player is not assigned an ocean approach");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void initialSpawnIsSavedOnceAndDoesNotRewriteTheVillageIdentity(GameTestHelper h){
        var original=flat(h);BlockPos spawn=original.centre().offset(0,0,288);
        var paired=original.withSpawn(spawn);
        h.assertTrue(paired.id.equals(original.id)&&paired.originX==original.originX&&paired.originZ==original.originZ,"Choosing the player's start does not relocate or duplicate the village");
        CompoundTag seed=new CompoundTag();seed.putInt("format",1);seed.putBoolean("eligible",true);
        var data=BuenaVillageData.load(seed,h.getLevel().registryAccess());data.reserve(paired,1,2);data.recordInitialSpawn(BlockPos.ZERO,spawn);
        var restored=BuenaVillageData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(spawn.equals(restored.initialSpawn())&&restored.plan().clearOfSpawn(100)&&restored.requiresInitialPair(),"The agreed new-world spawn policy is persistent");
        boolean rejected=false;try{restored.recordInitialSpawn(BlockPos.ZERO,spawn.offset(1,0,0));}catch(IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected&&spawn.equals(restored.initialSpawn()),"Later respawns cannot replace this creation record");
        h.succeed();
    }
    @GameTest(template="empty",skyAccess=true)
    public static void spawnStandingChecksRefuseCanopiesAndBlockedHeads(GameTestHelper h){
        var feet=h.absolutePos(new BlockPos(1,2,1));h.getLevel().setBlock(feet.below(),Blocks.GRASS_BLOCK.defaultBlockState(),2);
        h.getLevel().setBlock(feet,Blocks.AIR.defaultBlockState(),2);h.getLevel().setBlock(feet.above(),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(BuenaVillageSpawn.safeStandingPlace(h.getLevel(),feet),"Clear dry standing space is usable: feet="+feet
                +" motionHeight="+h.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,feet.getX(),feet.getZ())
                +" top="+h.getLevel().getBlockState(new BlockPos(feet.getX(),h.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,feet.getX(),feet.getZ())-1,feet.getZ())));
        h.getLevel().setBlock(feet.above(5),Blocks.OAK_LEAVES.defaultBlockState(),2);
        h.assertTrue(!BuenaVillageSpawn.safeStandingPlace(h.getLevel(),feet),"Vanilla's height lookup must not lift this start onto an overhead canopy");
        h.getLevel().setBlock(feet.above(5),Blocks.AIR.defaultBlockState(),2);
        h.getLevel().setBlock(feet.below(),Blocks.OAK_LEAVES.defaultBlockState(),2);
        h.assertTrue(!BuenaVillageSpawn.safeStandingPlace(h.getLevel(),feet),"A treetop is not selected as the world start");
        h.getLevel().setBlock(feet.below(),Blocks.GRASS_BLOCK.defaultBlockState(),2);h.getLevel().setBlock(feet.above(),Blocks.STONE.defaultBlockState(),2);
        h.assertTrue(!BuenaVillageSpawn.safeStandingPlace(h.getLevel(),feet),"The player's head needs real space");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void nativeSeed42VillageHasAnInitialSpawnDespiteNaturalDetours(GameTestHelper h){
        var level=h.getLevel();var registries=level.registryAccess();
        var settings=registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var biome=new net.minecraft.world.level.biome.FixedBiomeSource(registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biome,settings);
        var random=net.minecraft.world.level.levelgen.RandomState.create(settings.value(),registries.lookupOrThrow(Registries.NOISE),42);
        var terrain=BuenaSurfaceSampler.create(generator,level,random);
        var plan=BuenaVillagePlan.assess(42,16,-912,BlockPos.ZERO,layout(h),terrain,-64,320);
        h.assertTrue(plan!=null,"The already observed real seed42 village terrain remains suitable");
        h.assertTrue(!BuenaVillageSpawn.candidates(plan,terrain).isEmpty(),"Safe nearby starts do not require a perfectly straight level walk");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void generatedChunkAndResidentReceiptsAreIdempotent(GameTestHelper h){
        var p=flat(h);CompoundTag initial=new CompoundTag();initial.putInt("format",1);initial.putBoolean("eligible",true);
        var data=BuenaVillageData.load(initial,h.getLevel().registryAccess());data.reserve(p,1,2);
        for(int i=0;i<3;i++){data.chunkFinished(p.id,p.anchor());data.residentSeen(p.id,"paul_greyrat",p.residentUuid("paul_greyrat"));}
        h.assertTrue(data.generatedChunks()==1&&data.residents().size()==1,"Repeated load/join events cannot add copies");
        data.residentSeen(p.id,"roxy_tutor",UUID.randomUUID());h.assertTrue(data.residents().size()==1,"A different identity cannot claim a resident slot");
        var back=BuenaVillageData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(back.generatedChunks()==1&&back.residents().equals(data.residents()),"Receipts survive persistence without testing current entity presence");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void protochunkReceiptsRejectASecondOwner(GameTestHelper h){
        var c=new ProtoChunk(new ChunkPos(8,9),UpgradeData.EMPTY,h.getLevel(),h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        UUID id=UUID.randomUUID();BuenaChunkMarks.mark(c,id,"resident_paul_greyrat");BuenaChunkMarks.mark(c,id,"resident_paul_greyrat");
        h.assertTrue(BuenaChunkMarks.done(c,id,"resident_paul_greyrat")&&!BuenaChunkMarks.writable(c,UUID.randomUUID()),"A chunk records one village's spawn fact");
        BuenaChunkMarks.mark(c,id,"complete");h.assertTrue(id.equals(BuenaChunkMarks.completedVillage(c)),"Completion has an explicit stored identity");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void villagePiecesRoundTripAndCannotBuildIntoFullChunks(GameTestHelper h){
        var p=flat(h);var l=layout(h);var context=StructurePieceSerializationContext.fromLevel(h.getLevel());
        var piece=BuenaVillagePiece.terrain(BuenaVillagePiece.Step.GROUND,p,l);
        CompoundTag tag=piece.createTag(context);var loaded=new BuenaVillagePiece(context,tag);
        h.assertTrue(loaded.createTag(context).equals(tag),"A deferred terrain piece retains its own frozen profile");
        BlockPos witness=h.absolutePos(BlockPos.ZERO);h.getLevel().setBlock(witness,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
        loaded.postProcess(h.getLevel(),h.getLevel().structureManager(),h.getLevel().getChunkSource().getGenerator(),RandomSource.create(1),
                new BoundingBox(witness.getX()-5,-64,witness.getZ()-5,witness.getX()+5,319,witness.getZ()+5),new ChunkPos(witness),witness);
        h.assertTrue(h.getLevel().getBlockState(witness).is(Blocks.DIAMOND_BLOCK),"A direct command/test call against FULL terrain writes nothing");
        h.succeed();
    }

    private static void asset(GameTestHelper h,String key){
        try{
            var l=layout(h);var p=l.byKey.get(key);var template=h.getLevel().getStructureManager().get(p.template()).orElseThrow();
            BlockPos floor=h.absolutePos(new BlockPos(1,0,1)),origin=floor.offset(p.offset());
            for(int z=0;z<40;z++)for(int x=0;x<40;x++)h.setBlock(new BlockPos(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());
            var clip=BoundingBox.fromCorners(h.absolutePos(BlockPos.ZERO),h.absolutePos(new BlockPos(39,25,39)));
            var settings=new StructurePlaceSettings().setRotation(p.rotation()).setKnownShape(true).setIgnoreEntities(true).setBoundingBox(clip);
            var box=template.getBoundingBox(settings,origin);
            h.assertTrue(box.minX()==floor.getX()&&box.minZ()==floor.getZ()&&box.getXSpan()==p.width()&&box.getZSpan()==p.depth(),"Native rotation exactly matches the handed footprint "+key);
            h.assertTrue(template.placeInWorld(h.getLevel(),origin,origin,settings,RandomSource.create(71),2),"Native placement succeeds "+key);
            CompoundTag raw;
            try(var in=h.getLevel().getServer().getResourceManager().open(ResourceLocation.fromNamespaceAndPath(p.template().getNamespace(),"structure/"+p.template().getPath()+".nbt"))){raw=NbtIo.readCompressed(in,NbtAccounter.unlimitedHeap());}
            ListTag blocks=raw.getList("blocks",Tag.TAG_COMPOUND),palette=raw.getList("palette",Tag.TAG_COMPOUND);
            h.runAfterDelay(3,()->{
                List<String> issues=new ArrayList<>();
                for(int i=0;i<blocks.size();i++){
                    CompoundTag entry=blocks.getCompound(i);ListTag xyz=entry.getList("pos",Tag.TAG_INT);
                    BlockPos local=new BlockPos(xyz.getInt(0),xyz.getInt(1),xyz.getInt(2));
                    BlockPos at=StructureTemplate.calculateRelativePosition(settings,local).offset(origin);
                    String name=palette.getCompound(entry.getInt("state")).getString("Name");
                    var actual=h.getLevel().getBlockState(at);
                    if(!BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString().equals(name))issues.add(local+" expected "+name+" got "+actual);
                    if(!actual.isAir()&&!actual.canSurvive(h.getLevel(),at))issues.add(local+" unsupported "+actual+" above "+h.getLevel().getBlockState(at.below()));
                }
                h.assertTrue(issues.isEmpty(),"Native template stability "+key+": "+issues);h.succeed();
            });
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void villageHome(GameTestHelper h){asset(h,"greyrat_home");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void thatchHouse(GameTestHelper h){asset(h,"house_01");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void stoneHouse(GameTestHelper h){asset(h,"house_02");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void barnHouse(GameTestHelper h){asset(h,"house_06");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void hunterCabin(GameTestHelper h){asset(h,"rawls_cabin");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void tower(GameTestHelper h){asset(h,"watchtower");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void watermill(GameTestHelper h){asset(h,"watermill");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void square(GameTestHelper h){asset(h,"village_square");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void rotatedThatch(GameTestHelper h){asset(h,"house_03");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void rotatedStone(GameTestHelper h){asset(h,"house_04");}
    @GameTest(template="house_h01_empty",batch="buenaAssets",timeoutTicks=180) public static void rotatedBarn(GameTestHelper h){asset(h,"house_09");}
}
