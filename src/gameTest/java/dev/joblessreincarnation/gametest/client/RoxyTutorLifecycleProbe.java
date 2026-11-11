package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.RoxyDisplayEntity;
import dev.joblessreincarnation.gametest.RoxyTutorPreview;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Explicit automated lifecycle evidence, never human-play evidence.
 * Uses the ordinary command connection, disconnect flow and public WorldOpenFlows.openWorld API.
 */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class RoxyTutorLifecycleProbe {
    public static final String PROPERTY="jobless_reincarnation.roxyTutorLifecycleProbe";
    private enum Phase { FIRST_SCENE, COMMAND_REMOVAL, STILL_PLAYING, FIRST_TITLE, SECOND_SCENE, SECOND_TITLE, FAIL_CLEANUP }
    private static Phase phase=Phase.FIRST_SCENE;
    private static boolean captured,originalPause,operating,done;
    private static long deadline;
    private static int age,liveTicks;
    private static long liveGameTime;
    private static UUID firstNpc,secondNpc;
    private static ClientLevel firstLevel;
    private static IntegratedServer firstServer,closingServer;
    private static String worldFolder,failure;

    private static boolean enabled(){
        return Boolean.getBoolean(PROPERTY)&&RoxyTutorPreview.enabled()&&RoxyTutorPreview.mode().equals("manual");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event){
        if(!enabled()||done||operating)return;
        Minecraft c=Minecraft.getInstance();
        if(!captured){
            originalPause=c.options.pauseOnLostFocus;
            captured=true;
            c.options.pauseOnLostFocus=false;
            enter(Phase.FIRST_SCENE,180);
            LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] BEGIN automated native lifecycle probe; manual scene mode");
        }
        if(System.nanoTime()>deadline&&phase!=Phase.FAIL_CLEANUP)fail("Timeout in "+phase);
        age++;
        try{
            if(phase==Phase.FAIL_CLEANUP){
                failAndExit(c);
                return;
            }
            if(c.getSingleplayerServer()!=null&&RoxyTutorPreview.WORLD.equals(c.getSingleplayerServer().getWorldData().getLevelName())
                    &&c.screen instanceof PauseScreen)c.setScreen(null);
            switch(phase){
                case FIRST_SCENE -> {
                    RoxyDisplayEntity npc=sceneNpc(c);
                    if(npc==null)return;
                    firstServer=c.getSingleplayerServer();
                    require(firstServer!=null&&!firstServer.isPublished(),"Expected private integrated server");
                    require(RoxyTutorPreview.WORLD.equals(firstServer.getWorldData().getLevelName()),"Expected isolated test world");
                    worldFolder=firstServer.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
                    firstLevel=c.level;
                    firstNpc=npc.getUUID();
                    require(c.getConnection()!=null,"Command connection is ready");
                    enter(Phase.COMMAND_REMOVAL,45);
                    c.getConnection().sendCommand("jr_tutor end");
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] FIRST_SCENE npc={} sent native command jr_tutor end",firstNpc);
                }
                case COMMAND_REMOVAL -> {
                    require(c.level==firstLevel&&c.player!=null&&c.getSingleplayerServer()==firstServer,
                            "Manual end disconnected or replaced the running world");
                    if(find(c,firstNpc)!=null)return;
                    liveGameTime=c.level.getGameTime();
                    liveTicks=0;
                    enter(Phase.STILL_PLAYING,30);
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] COMMAND_REMOVAL_PASS npc={} disappeared",firstNpc);
                }
                case STILL_PLAYING -> {
                    require(c.level==firstLevel&&c.player!=null&&c.getSingleplayerServer()==firstServer,
                            "Manual end did not leave the same world running");
                    require(find(c,firstNpc)==null,"Ended NPC reappeared");
                    liveTicks++;
                    if(liveTicks<40||c.level.getGameTime()-liveGameTime<40)return;
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] MANUAL_END_PASS same client level stayed playable clientTicks={} gameTicks={}",
                            liveTicks,c.level.getGameTime()-liveGameTime);
                    enter(Phase.FIRST_TITLE,60);
                    disconnectNormally(c);
                }
                case FIRST_TITLE -> {
                    if(!atStoppedTitle(c)||age<20)return;
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] FIRST_DISCONNECT_PASS old server fully shut down; reopening {}",worldFolder);
                    enter(Phase.SECOND_SCENE,180);
                    operating=true;
                    try{
                        c.createWorldOpenFlows().openWorld(worldFolder,()->fail("WorldOpenFlows could not reopen "+worldFolder));
                    }finally{operating=false;}
                }
                case SECOND_SCENE -> {
                    RoxyDisplayEntity npc=sceneNpc(c);
                    if(npc==null)return;
                    require(c.level!=firstLevel,"Re-entry must create a new client level");
                    require(c.getSingleplayerServer()!=null&&c.getSingleplayerServer()!=firstServer,
                            "Re-entry must create a new integrated server");
                    secondNpc=npc.getUUID();
                    require(!secondNpc.equals(firstNpc),"Re-entry must prepare a new NPC UUID");
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] REENTRY_PASS firstNpc={} secondNpc={} new scene prepared",firstNpc,secondNpc);
                    // No end command here: the normal disconnect must exercise participant-logout restoration.
                    enter(Phase.SECOND_TITLE,60);
                    disconnectNormally(c);
                }
                case SECOND_TITLE -> {
                    if(!atStoppedTitle(c)||age<40)return;
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] ACTIVE_LOGOUT_PASS title reached and second server fully shut down");
                    restore(c);
                    done=true;
                    LogUtils.getLogger().info("[JR_TUTOR_LIFECYCLE] ALL_PASS automated command-end/same-world-40-ticks/re-entry/new-UUID/active-logout; firstNpc={} secondNpc={}",firstNpc,secondNpc);
                    c.stop();
                }
                default -> throw new IllegalStateException("Unexpected lifecycle phase "+phase);
            }
        }catch(Exception ex){
            LogUtils.getLogger().error("[JR_TUTOR_LIFECYCLE] Failure in "+phase,ex);
            fail(ex.toString());
        }
    }

    private static RoxyDisplayEntity sceneNpc(Minecraft c){
        if(c.level==null||c.player==null||c.getSingleplayerServer()==null)return null;
        require(RoxyTutorPreview.WORLD.equals(c.getSingleplayerServer().getWorldData().getLevelName()),
                "Probe is restricted to the private test world");
        RoxyDisplayEntity found=null;
        for(var entity:c.level.entitiesForRendering()){
            if(entity instanceof RoxyDisplayEntity npc&&!npc.isRemoved()
                    &&npc.position().distanceToSqr(RoxyTutorPreview.NPC_POS)<1.0){
                require(found==null,"Expected exactly one scene NPC");
                found=npc;
            }
        }
        return found;
    }

    private static RoxyDisplayEntity find(Minecraft c,UUID uuid){
        if(c.level==null)return null;
        for(var entity:c.level.entitiesForRendering())
            if(entity instanceof RoxyDisplayEntity npc&&npc.getUUID().equals(uuid)&&!npc.isRemoved())return npc;
        return null;
    }

    private static void disconnectNormally(Minecraft c){
        closingServer=c.getSingleplayerServer();
        operating=true;
        try{
            if(c.level!=null)c.level.disconnect();
            c.disconnect(new TitleScreen());
            c.setScreen(new TitleScreen());
        }finally{operating=false;}
    }

    private static boolean atStoppedTitle(Minecraft c){
        // isShutdown is the completion condition used by Minecraft.disconnect itself; isStopped is too early.
        return c.level==null&&c.player==null&&c.getConnection()==null&&c.getSingleplayerServer()==null
                &&(closingServer==null||closingServer.isShutdown())&&c.screen instanceof TitleScreen;
    }

    private static void enter(Phase next,int timeoutSeconds){
        phase=next;age=0;deadline=System.nanoTime()+timeoutSeconds*1_000_000_000L;
    }

    private static void fail(String reason){
        if(failure==null)failure=reason;
        enter(Phase.FAIL_CLEANUP,60);
        LogUtils.getLogger().error("[JR_TUTOR_LIFECYCLE] FAIL {}",failure);
    }

    private static void failAndExit(Minecraft c){
        operating=true;
        try{
            // Use vanilla teardown even on timeout so the integrated server performs its normal save.
            if(c.level!=null||c.getConnection()!=null||c.getSingleplayerServer()!=null)disconnectNormally(c);
        }catch(Exception ex){
            LogUtils.getLogger().error("[JR_TUTOR_LIFECYCLE] Normal disconnect failed; client shutdown will finish teardown",ex);
        }finally{
            restore(c);
            done=true;
            operating=false;
            LogUtils.getLogger().error("[JR_TUTOR_LIFECYCLE] FAIL_EXIT normal disconnect/save requested; {}",failure);
            c.stop();
        }
    }

    private static void restore(Minecraft c){
        if(captured){c.options.pauseOnLostFocus=originalPause;captured=false;}
    }

    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private RoxyTutorLifecycleProbe(){}
}
