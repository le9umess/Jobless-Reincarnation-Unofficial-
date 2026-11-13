package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.worldgen.buena.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 0.1.1 site search: the frozen S1 order (neighbours and their cap), no wall clock, shifted origins, the one-time
 * no-village notice and the terminal states. All BuenaVillageData records here are synthetic (built from NBT in the
 * test), not copies of real worlds; real 0.1.0 worlds are opened on a dedicated server instead.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuenaSiteSearchGameTests {
    private static final Runnable GO=()->{};
    private static BuenaSiteOrder.Site grid(int x,int z){return new BuenaSiteOrder.Site(x,z,"grid",-1,1);}
    private static int indexOf(BuenaSiteOrder order,int x,int z){
        for(int i=0;i<order.size();i++)if(order.get(i).x()==x&&order.get(i).z()==z)return i;
        return -1;
    }

    @GameTest(template="empty")
    public static void siteOrderRefinesGridSitesOnlyAfterLatePlanningFailures(GameTestHelper h){
        var order=new BuenaSiteOrder();
        order.add(grid(-16,-48));order.add(grid(48,-48));
        h.assertTrue(order.rejected(0,"overall_relief",1,GO).isEmpty()&&order.size()==2,"A coarse failure is not refined");
        var near=order.rejected(0,"exact_pad_relief",1,GO);
        h.assertValueEqual(near.size(),80,"Eight-block steps within 32 blocks, the origin itself excluded");
        h.assertValueEqual(order.size(),82,"Inserted, not appended");
        h.assertTrue(order.get(1)==near.get(0)&&order.get(80)==near.get(79),"Neighbours come right after their parent");
        h.assertTrue(order.get(81).tag().equals("grid")&&order.get(81).x()==48,"...and before the next grid site");
        h.assertTrue(near.stream().allMatch(s->s.tag().equals("s1")&&s.parent()==1),"Each neighbour names its parent's consumption number");
        // Frozen order: distance from the parent, then x, then z.
        int[][] first={{-8,0},{0,-8},{0,8},{8,0},{-8,-8},{-8,8},{8,-8},{8,8},{-16,0},{0,-16},{0,16},{16,0}};
        for(int i=0;i<first.length;i++)
            h.assertTrue(near.get(i).x()==-16+first[i][0]&&near.get(i).z()==-48+first[i][1],"Frozen order at neighbour "+(i+1));
        h.assertTrue(near.get(79).x()==-16+32&&near.get(79).z()==-48+32,"The far corner comes last");
        h.assertTrue(near.stream().allMatch(s->Math.abs(s.x()+16)<=32&&Math.abs(s.z()+48)<=32&&Math.floorMod(s.x()+16,8)==0&&Math.floorMod(s.z()+48,8)==0),
                "Real block offsets on the 8-block grid, not chunk positions");
        h.assertTrue(near.stream().anyMatch(s->Math.floorMod(s.x(),16)!=0),"Offsets that a chunk position could not express are kept");
        h.assertTrue(order.rejected(1,"excess_earthwork",2,GO).isEmpty(),"A neighbour never refines again");
        h.assertTrue(BuenaSiteOrder.deep("pad_fit_wheat_east_0_0")&&BuenaSiteOrder.deep("river_profile")&&BuenaSiteOrder.deep("exact_pad_relief")
                &&!BuenaSiteOrder.deep("sampled_pad_relief")&&!BuenaSiteOrder.deep("overall_relief")&&!BuenaSiteOrder.deep("structure_clearance"),
                "Late planning failures only; a real structure overlap is not moved around");
        var shifted=BuenaSiteOrder.grid(new ChunkPos(0,0),new ChunkPos(24,-3),1);
        h.assertTrue(shifted.x()==24*16+8-88&&shifted.z()==-3*16+8-88&&shifted.chunk().equals(new ChunkPos(24,-3)),
                "A grid site is the 0.1.0 origin, chunk middle minus 88");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void siteOrderKeepsGridTurnsAndStopsAtTheCap(GameTestHelper h){
        var order=new BuenaSiteOrder();
        // A slice of the near grid: origins 32 blocks apart, as the 0.1.0 grid has within 24 chunks of the reference.
        for(int z=-64;z<=64;z+=32)for(int x=-64;x<=96;x+=32)order.add(grid(x,z));
        int p1=indexOf(order,0,0);
        var a=order.rejected(p1,"exact_pad_relief",7,GO);
        h.assertValueEqual(a.size(),72,"Grid origins inside the neighbourhood are already seen and keep their own turn");
        h.assertTrue(a.stream().noneMatch(s->Math.floorMod(s.x(),32)==0&&Math.floorMod(s.z(),32)==0),"No grid origin is pulled forward");
        int p2=indexOf(order,32,0);
        h.assertValueEqual(p2,p1+73,"The next grid site follows the inserted neighbours");
        var b=order.rejected(p2,"river_profile",80,GO);
        int[][] expected={{40,0},{40,-8},{40,8},{48,0},{40,-16},{40,16},{48,-8},{48,8}};
        h.assertValueEqual(BuenaSiteOrder.CAP,96,"The frozen cap");
        h.assertValueEqual(b.size(),Math.min(33,BuenaSiteOrder.CAP-72),"Only the second parent's unseen neighbours, up to what the cap leaves");
        for(int i=0;i<Math.min(expected.length,b.size());i++)
            h.assertTrue(b.get(i).x()==expected[i][0]&&b.get(i).z()==expected[i][1]&&b.get(i).parent()==80,"Unseen neighbours, nearest the parent first, at "+(i+1));
        var c=order.rejected(indexOf(order,64,0),"excess_earthwork",90,GO);
        h.assertValueEqual(c.size(),Math.max(0,Math.min(33,BuenaSiteOrder.CAP-72-b.size())),"The third parent gets only what is left of the cap");
        if(!c.isEmpty())h.assertTrue(c.get(0).x()==72&&c.get(0).z()==0,"...as a prefix of its own order");
        h.assertValueEqual(order.refined(),Math.min(BuenaSiteOrder.CAP,72+33+33),"Never more than the cap in one search");
        h.assertTrue(order.refined()<BuenaSiteOrder.CAP||order.rejected(indexOf(order,-32,0),"excess_earthwork",95,GO).isEmpty(),"Nothing more after the cap; the grid stays as it was");
        Set<Long> keys=new HashSet<>();for(int i=0;i<order.size();i++)h.assertTrue(keys.add(order.get(i).key()),"Origin listed twice at "+i);
        h.assertTrue(!order.add(grid(32,0)),"A listed origin cannot be added again");
        var far=new BuenaSiteOrder();far.add(grid(4000,0));
        h.assertValueEqual(far.rejected(0,"exact_pad_relief",1,GO).size(),80,"No distance gate: a far parent is refined too");
        var off=new BuenaSiteOrder(false);off.add(grid(0,0));
        h.assertTrue(off.rejected(0,"excess_earthwork",1,GO).isEmpty()&&off.size()==1,"The test entry without neighbours keeps the 0.1.0 order");
        var stopped=new BuenaSiteOrder();stopped.add(grid(0,0));boolean cancelled=false;
        try{stopped.rejected(0,"river_profile",1,()->{throw new CancellationException();});}catch(CancellationException expectedStop){cancelled=true;}
        h.assertTrue(cancelled&&stopped.size()==1,"Enumerating neighbours stops on cancellation");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void searchStopsOnWorkNeverOnTheClockAndShipsNoSwitches(GameTestHelper h){
        boolean limit=true,deadline=true;
        try{BuenaVillageGeneration.class.getDeclaredField("SEARCH_LIMIT_MILLIS");}catch(NoSuchFieldException gone){limit=false;}
        try{Class.forName("dev.joblessreincarnation.worldgen.buena.BuenaVillageGeneration$SearchDeadline");}catch(ClassNotFoundException gone){deadline=false;}
        h.assertTrue(!limit&&!deadline,"No wall-clock budget or deadline remains in the site search");
        h.assertValueEqual(BuenaVillageGeneration.SLOW_PATH_SAMPLE_CAP,250_000L,"The slow path stops on a count of column requests");
        h.assertTrue(Arrays.stream(BuenaVillageGeneration.SearchProgress.class.getRecordComponents()).anyMatch(c->c.getName().equals("total")),
                "The loading screen can show checked / total");
        Set<String> methods=new HashSet<>();for(var m:BuenaVillageGeneration.class.getDeclaredMethods())methods.add(m.getName());
        for(String gone:new String[]{"retry","retryRefusal","needsNoVillageHint","announcesLaterVillage","announce","whereFrom","allowedBiome","waterWouldFreeze","beginSearch"})
            h.assertTrue(!methods.contains(gone),"0.1.1 ships no "+gone+": old worlds behave as in 0.1.0");
        boolean padRelief=true;
        try{BuenaVillagePlan.class.getDeclaredField("PAD_RELIEF");}catch(NoSuchFieldException gone){padRelief=false;}
        h.assertTrue(!padRelief,"The pad relief limit is the fixed 0.1.0 value");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void noSiteNoticeComesOnceAndOnlyAfterAFailedNewWorld(GameTestHelper h){
        var server=h.getLevel().getServer();
        h.assertTrue(!BuenaVillageGeneration.takeNoSiteHint(server),"Nothing is shown unless this run's new world just failed (old saves, reopened worlds)");
        BuenaVillageGeneration.armNoSiteHintForTest(server);
        h.assertTrue(BuenaVillageGeneration.takeNoSiteHint(server),"The first player after a failed new-world search is told");
        h.assertTrue(!BuenaVillageGeneration.takeNoSiteHint(server),"...only once");
        h.succeed();
    }
    private static BuenaVillageLayout layout(GameTestHelper h){return BuenaVillageLayout.load(h.getLevel().getServer().getResourceManager(),h.getLevel().getStructureManager());}
    private static BuenaVillagePlan flatAt(GameTestHelper h,int x,int z){
        var plan=BuenaVillagePlan.assess(42,x,z,BlockPos.ZERO,layout(h),(px,pz)->new BuenaVillagePlan.Column(64,true),-64,320);
        h.assertTrue(plan!=null,"A flat dry site is valid at "+x+","+z);return plan;
    }
    @GameTest(template="empty")
    public static void shiftedOriginsKeepTheWholeFootprintAnchorAndResidents(GameTestHelper h){
        var l=layout(h);var context=StructurePieceSerializationContext.fromLevel(h.getLevel());
        // 0.1.0 grid origins are chunk middle minus 88, so origin = 0 (mod 16) and origin-8 = 8 (mod 16): 13 x 13
        // chunks. An 8-block move aligns that axis with the chunk grid: 12 chunks on it.
        int[][] cases={{176,-80,169},{168,-88,144},{168,-80,156},{176,-88,156}};
        for(int[] c:cases){
            var plan=flatAt(h,c[0],c[1]);var box=plan.bounds();
            h.assertValueEqual(plan.chunkCount(),c[2],"Chunk count for origin "+c[0]+","+c[1]);
            int owned=0;
            for(int cz=Math.floorDiv(box.minZ(),16)-1;cz<=Math.floorDiv(box.maxZ(),16)+1;cz++)for(int cx=Math.floorDiv(box.minX(),16)-1;cx<=Math.floorDiv(box.maxX(),16)+1;cx++){
                boolean inside=cx>=Math.floorDiv(box.minX(),16)&&cx<=Math.floorDiv(box.maxX(),16)&&cz>=Math.floorDiv(box.minZ(),16)&&cz<=Math.floorDiv(box.maxZ(),16);
                h.assertTrue(plan.ownsChunk(new ChunkPos(cx,cz))==inside,"Ownership follows the real bounds at "+cx+","+cz);
                if(inside)owned++;
            }
            h.assertValueEqual(owned,c[2],"Every owned chunk is counted");
            h.assertTrue(plan.anchor().equals(new ChunkPos(plan.centre())),"The anchor is the centre's chunk, derived from the real origin");
            h.assertTrue(plan.ownsChunk(plan.anchor()),"The anchor is inside the reservation");
            for(var person:l.residents){
                var piece=BuenaVillagePiece.resident(plan,l,person);
                h.assertTrue(box.isInside(piece.getBoundingBox().getCenter()),"Resident "+person.role()+" is placed inside the reservation");
            }
            var terrain=BuenaVillagePiece.terrain(BuenaVillagePiece.Step.GROUND,plan,l);
            CompoundTag tag=terrain.createTag(context);
            h.assertTrue(new BuenaVillagePiece(context,tag).createTag(context).equals(tag),"A shifted plan's terrain piece round-trips");
            var restored=BuenaVillagePlan.load(plan.save());
            h.assertTrue(restored.save().equals(plan.save())&&restored.originX==c[0]&&restored.originZ==c[1],"The saved plan keeps the exact origin");
        }
        h.succeed();
    }
    private static BuenaVillageData record(GameTestHelper h,String note){return record(h,note,true);}
    private static BuenaVillageData record(GameTestHelper h,String note,boolean paired){
        // Synthetic: the fields a 0.1.0 world writes (format 1, eligible, paired creation, a note, no plan).
        CompoundTag t=new CompoundTag();t.putInt("format",1);t.putBoolean("eligible",true);t.putString("note",note);t.putBoolean("paired_creation",paired);
        t.putInt("attempts",3266);t.putLong("planning_millis",90002);
        return BuenaVillageData.load(t,h.getLevel().registryAccess());
    }
    @GameTest(template="empty")
    public static void serverStartLeavesWorldsWithoutAPlanAsTheyAre(GameTestHelper h){
        var start=BuenaVillageGeneration.OnStart.NOTHING;
        h.assertTrue(BuenaVillageGeneration.onStart(record(h,"pending",false),true)==start,"An unpaired record without a plan (from before 0.1.0) is not searched");
        for(String note:new String[]{"search_time_budget","no_suitable_unbuilt_site","search_work_cap","searching","pending"})
            h.assertTrue(BuenaVillageGeneration.onStart(record(h,note),true)==start,"A paired record without a plan ("+note+") is left as it is");
        var plan=flatAt(h,176,-80);var spawn=plan.centre().offset(0,0,288);
        var saved=record(h,"pending");saved.reserve(plan.withSpawn(spawn),1,2);saved.recordInitialSpawn(BlockPos.ZERO,spawn);
        h.assertTrue(BuenaVillageGeneration.onStart(saved,true)==BuenaVillageGeneration.OnStart.RESUME,"A saved village resumes");
        h.assertTrue(BuenaVillageGeneration.onStart(saved,false)==start&&BuenaVillageGeneration.onStart(null,true)==start,"Not without structures; nothing without a record");
        CompoundTag unknown=new CompoundTag();unknown.putInt("format",99);
        h.assertTrue(BuenaVillageGeneration.onStart(BuenaVillageData.load(unknown,h.getLevel().registryAccess()),true)==BuenaVillageGeneration.OnStart.INCOMPATIBLE,
                "An unknown record is reported and left alone");
        h.succeed();
    }
    private static int notices(ServerPlayer player){
        var channel=(EmbeddedChannel)player.connection.getConnection().channel();
        int n=0;
        for(Object m:channel.outboundMessages())
            if(m instanceof ClientboundSystemChatPacket chat&&chat.content().getContents() instanceof TranslatableContents key
                    &&key.getKey().equals("message.jobless_reincarnation.buena.no_site"))n++;
        return n;
    }
    /** A real ServerPlayer with a real game connection whose packets stay in an embedded channel; not in any level list. */
    private static ServerPlayer probePlayer(GameTestHelper h,String name){
        var server=h.getLevel().getServer();
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
        var player=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server,connection,player,cookie);
        return player;
    }
    @GameTest(template="empty")
    public static void noSiteNoticeReachesTheFirstLoginOnly(GameTestHelper h){
        var server=h.getLevel().getServer();
        BuenaVillageGeneration.armNoSiteHintForTest(server);
        // The login handler itself. A full mock login cannot run here: Epic Fight's login handler refuses to send its
        // payload over an unnegotiated connection. Real logins are checked on a dedicated server with a client.
        var first=probePlayer(h,"jr-notice-first");var second=probePlayer(h,"jr-notice-second");
        BuenaVillageGeneration.firstPlayerAfterNoSite(new PlayerEvent.PlayerLoggedInEvent(first));
        BuenaVillageGeneration.firstPlayerAfterNoSite(new PlayerEvent.PlayerLoggedInEvent(second));
        h.assertValueEqual(notices(first),1,"The first player to log in after a failed new-world search is told once");
        h.assertValueEqual(notices(second),0,"The next login is not told");
        h.assertTrue(!BuenaVillageGeneration.takeNoSiteHint(server),"Nothing is left to show");
        boolean subscribed=false;
        try{subscribed=BuenaVillageGeneration.class.getMethod("firstPlayerAfterNoSite",PlayerEvent.PlayerLoggedInEvent.class).isAnnotationPresent(SubscribeEvent.class);}
        catch(NoSuchMethodException missing){}
        h.assertTrue(subscribed,"The handler listens to the login event");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void terminalSearchStatesAreSavedAndStayWithoutAPlan(GameTestHelper h){
        for(String note:new String[]{"no_suitable_unbuilt_site","search_work_cap"}){
            var data=record(h,"searching");data.noSite(18857,104000,note);
            var back=BuenaVillageData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(back.note().equals(note)&&back.plan()==null&&back.eligible()&&back.requiresInitialPair()&&back.attempts()==18857,"Terminal state "+note+" survives a save");
        }
        h.succeed();
    }
}
