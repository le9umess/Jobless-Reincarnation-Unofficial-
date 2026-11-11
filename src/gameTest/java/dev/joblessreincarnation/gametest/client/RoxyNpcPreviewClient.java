package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.*;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModContent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Actual vanilla key-use and actual private SystemChat receipt. Never calls the tutor service. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class RoxyNpcPreviewClient {
    private static RoxyNpcPreview.State pending;
    private static final NativeUseInput USE_INPUT = new NativeUseInput();
    private static int ticks,age,nextConnect=100,startedAt=-1,lastRelease=-1000,finishDelay=-1,captureAt=-1;
    private static boolean captured,originalPause,originalHide,done,acked,useDown,captureRequested,captureDone;
    private static int originalScale;private static CameraType originalCamera;
    private static net.minecraft.client.multiplayer.ClientLevel seenLevel;
    private static final List<String> messages=new ArrayList<>();
    private static String stage="";private static final AtomicInteger screenshots=new AtomicInteger();
    private static volatile String imageFailure;
    private static boolean eligible(){return RoxyNpcPreview.enabled()&&!done;}
    private static boolean automaticSession(){
        if(!eligible()||RoxyNpcPreview.mode().equals("manual"))return false;
        Minecraft c=Minecraft.getInstance();
        if(c.level==null)return false;
        if(pending!=null)return c.level==seenLevel&&pending.phase()!=RoxyNpcPreview.FINISH&&pending.action()!=RoxyNpcPreview.MANUAL;
        // Keep only the opted-in host fixture ticking while it prepares the first scene packet.
        var server=c.getSingleplayerServer();
        return finishDelay<0&&server!=null&&!server.isPublished()&&RoxyNpcPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(automaticSession()&&e.isPaused())e.setCanceled(true);}
    @SubscribeEvent public static void noMenu(ScreenEvent.Opening e){if(automaticSession()&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System e){if(!eligible()||pending==null||e.isOverlay()||!isTutor(e.getMessage()))return;
        Minecraft c=Minecraft.getInstance();String actual=findStage(e.getMessage());messages.add(c.level==null?e.getMessage().getString():Component.Serializer.toJson(e.getMessage(),c.level.registryAccess()));stage=actual;
        LogUtils.getLogger().info("[JR_NPC_CLIENT] PRIVATE_CHAT role={} phase={} stage={} component={} text={}",RoxyNpcPreview.mode(),pending.phase(),actual,messages.getLast(),e.getMessage().getString());
        if(e.getMessage().getString().contains("message.jobless_reincarnation.tutor"))imageFailure="Development language resource not loaded";
        if(acked&&pending.action()!=RoxyNpcPreview.QUERY&&pending.action()!=RoxyNpcPreview.HOLD_QUERY&&pending.action()!=RoxyNpcPreview.MANUAL)fail("Tutor reply arrived in non-actor phase");
    }
    private static boolean isTutor(Component c){return c.getContents() instanceof TranslatableContents t&&t.getKey().equals("message.jobless_reincarnation.tutor.speaker");}
    private static String findStage(Component c){if(c.getContents() instanceof TranslatableContents t){String key=t.getKey();String prefix="message.jobless_reincarnation.tutor.rule.";if(key.startsWith(prefix)&&!key.endsWith(".marker"))return key.substring(prefix.length()).toUpperCase(Locale.ROOT);}for(Component child:c.getSiblings()){String found=findStage(child);if(!found.isEmpty())return found;}return "";}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre e){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();RoxyNpcPreview.clientReceiver=RoxyNpcPreviewClient::receive;ticks++;
        if(c.level!=seenLevel){
            if(pending!=null||captured||finishDelay>=0){clearSession(c);LogUtils.getLogger().info("[JR_NPC_CLIENT] DETACHED: inputs/options restored; ready for a new scene");}
            seenLevel=c.level;
        }
        if(finishDelay>=0){if(--finishDelay>0)return;done=true;restore(c);if(c.level!=null)c.level.disconnect();c.disconnect(new TitleScreen());LogUtils.getLogger().info("[JR_NPC_CLIENT] EXIT role={} screenshots={}",RoxyNpcPreview.mode(),screenshots.get());c.stop();return;}
        if(RoxyNpcPreview.observer()&&c.player==null&&c.getOverlay()==null&&ticks>=nextConnect&&(c.screen instanceof TitleScreen||c.screen instanceof DisconnectedScreen)){
            nextConnect=ticks+200;String address="127.0.0.1:"+RoxyNpcPreview.PORT;ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("JR isolated tutor",address,ServerData.Type.OTHER),false,null);return;}
        if(c.player==null||c.level==null||pending==null)return;
        if(pending.action()==RoxyNpcPreview.MANUAL)return;
        if(!captured){originalPause=c.options.pauseOnLostFocus;originalHide=c.options.hideGui;originalScale=c.options.guiScale().get();originalCamera=c.options.getCameraType();captured=true;c.options.pauseOnLostFocus=false;c.options.hideGui=false;c.options.guiScale().set(2);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();c.mouseHandler.releaseMouse();}
        if(c.getOverlay()!=null)return;
        if(c.screen!=null){if(c.screen instanceof PauseScreen)c.setScreen(null);else{if(++age>200)fail("Unexpected screen blocks real input");return;}}
        if(++age>1800||imageFailure!=null){fail(imageFailure==null?"Phase timed out "+pending.phase():imageFailure);return;}
        c.options.keyUp.setDown(false);c.options.keyDown.setDown(false);c.options.keyLeft.setDown(false);c.options.keyRight.setDown(false);c.options.keyJump.setDown(false);c.options.keyShift.setDown(false);
        if(acked){USE_INPUT.release(c);return;}
        try{
            Entity npc=find(c,pending.npc());int action=pending.action();
            if(action==RoxyNpcPreview.REMOVED){require(npc==null,"Removed NPC remains on client");if(age>=20)complete("Exact NPC disappearance observed");return;}
            if(npc==null)return;
            if(!correctItem(c,pending.item()))return;
            if(action==RoxyNpcPreview.QUERY||action==RoxyNpcPreview.HOLD_QUERY){
                aim(c,npc.getEyePosition());c.gameRenderer.pick(1F);
                if(!(c.hitResult instanceof EntityHitResult hit)||!hit.getEntity().getUUID().equals(pending.npc())){if(age>160)throw new IllegalStateException("Real crosshair did not acquire tutor");return;}
                if(startedAt<0&&age>=4){if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);startedAt=age;useDown=true;}
                if(startedAt>=0){if(age-startedAt>=(action==RoxyNpcPreview.HOLD_QUERY?45:1))useDown=false;
                    int settle=action==RoxyNpcPreview.HOLD_QUERY?62:16;
                    if(age-startedAt>=settle){require(!c.player.isUsingItem(),"NPC click fell through to staff/book use");require(messages.size()==1&&stage.equals(pending.expected()),"One press must produce exactly one private Component with the expected stage");
                        if(!captureRequested){capture(c);return;}
                        if(imageFailure!=null)throw new IllegalStateException(imageFailure);
                        if(!captureDone){require(age-captureAt<=240,"Native screenshot save callback timed out");return;}
                        if(age-startedAt>=settle+8)complete("Native entity ray + synthetic raw PRESS/RELEASE + vanilla KeyMapping + one private SystemChat; native screenshot saved");}
                }
            }else if(action==RoxyNpcPreview.STUDY){
                aim(c,c.player.position().add(0,1.5,8));c.gameRenderer.pick(1F);require(!(c.hitResult instanceof EntityHitResult hit&&hit.getEntity()==npc),"Study must aim away from NPC");
                if(age>=12&&startedAt<0){if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);startedAt=age;useDown=true;}
                if(WaterMagic.isLearned(c.player)){useDown=false;if(lastRelease<0)lastRelease=age;if(age-lastRelease>=10&&!c.player.isUsingItem())complete("Real 40-tick book use learned Water; no tutoring grant");}
            }else if(action==RoxyNpcPreview.PRACTICE){
                aim(c,Vec3.atCenterOf(RoxyNpcPreview.TARGET));c.gameRenderer.pick(1F);
                int casts=WaterMagic.practiceCount(c.player);
                if(casts>=pending.goal()){useDown=false;if(lastRelease<0)lastRelease=age;if(age-lastRelease>=45&&!c.player.isUsingItem())complete("Real staff releases reached five casts; server separately checks real target impacts");}
                else if(age>=12){
                    if(c.player.isUsingItem()){
                        int threshold=WaterMagic.chantTicks(c.player)+(casts==0?MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt()+3:3);
                        if(c.player.getTicksUsingItem()>=threshold){useDown=false;lastRelease=age;}
                    }else if(age-lastRelease>=8&&!c.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())){if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);useDown=true;}
                }
            }else if(action==RoxyNpcPreview.WAIT){useDown=false;if(age>=35){require(messages.isEmpty(),"Other player's private tutoring leaked");complete("No private tutor message in passive role");}}
            USE_INPUT.hold(c,useDown);
        }catch(Exception ex){LogUtils.getLogger().error("[JR_NPC_CLIENT] FAILED",ex);fail(ex.toString());}
    }
    private static boolean correctItem(Minecraft c,int item){return item==0?c.player.getMainHandItem().isEmpty():c.player.getMainHandItem().is(item==1?ModContent.WATER_TOME.get():ModContent.TRAINING_STAFF.get());}
    private static Entity find(Minecraft c,UUID id){for(Entity entity:c.level.entitiesForRendering())if(entity.getUUID().equals(id))return entity;return null;}
    private static void aim(Minecraft c,Vec3 point){c.player.lookAt(EntityAnchorArgument.Anchor.EYES,point);}
    private static void complete(String detail){if(acked)return;acked=true;useDown=false;USE_INPUT.release(Minecraft.getInstance());PacketDistributor.sendToServer(new RoxyNpcPreview.Ack(pending.phase(),true,messages.size(),stage,detail));LogUtils.getLogger().info("[JR_NPC_CLIENT] PASS role={} phase={} messages={} stage={}",RoxyNpcPreview.mode(),pending.phase(),messages.size(),stage);}
    private static void fail(String detail){if(pending==null)return;acked=true;useDown=false;USE_INPUT.release(Minecraft.getInstance());PacketDistributor.sendToServer(new RoxyNpcPreview.Ack(pending.phase(),false,messages.size(),stage,detail.length()>500?detail.substring(0,500):detail));}
    private static void receive(RoxyNpcPreview.State state){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();
        if(c.level==null)return;
        if(c.level!=seenLevel&&(pending!=null||captured||finishDelay>=0))clearSession(c);
        seenLevel=c.level;pending=state;age=0;startedAt=-1;lastRelease=-1000;acked=false;useDown=false;captureRequested=false;captureDone=false;captureAt=-1;messages.clear();stage="";imageFailure=null;
        USE_INPUT.release(c);
        if(state.phase()==RoxyNpcPreview.FINISH){
            LogUtils.getLogger().info("[JR_NPC_CLIENT] FINISHED passed={} {}",state.passed(),state.message());
            if(RoxyNpcPreview.mode().equals("manual")){
                clearSession(c);
                LogUtils.getLogger().info("[JR_NPC_CLIENT] MANUAL_RESTORED: game remains open; ready for a new scene");
            }else{restore(c);finishDelay=RoxyNpcPreview.observer()?20:45;}
        }else{
            finishDelay=-1;
            LogUtils.getLogger().info("[JR_NPC_CLIENT] STATE role={} phase={} action={}",RoxyNpcPreview.mode(),state.phase(),state.action());
        }
    }
    private static void clearSession(Minecraft c){
        restore(c);pending=null;finishDelay=-1;age=0;startedAt=-1;lastRelease=-1000;acked=false;useDown=false;
        captureRequested=false;captureDone=false;captureAt=-1;messages.clear();stage="";imageFailure=null;

    }
    private static void restore(Minecraft c){
        useDown=false;USE_INPUT.release(c);KeyMapping.releaseAll();KeyMapping.resetToggleKeys();
        if(!captured)return;
        c.options.pauseOnLostFocus=originalPause;c.options.hideGui=originalHide;c.options.guiScale().set(originalScale);c.options.setCameraType(originalCamera);c.resizeDisplay();captured=false;
    }
    private static void capture(Minecraft c){
        RoxyNpcPreview.State shotState=pending;
        var shotLevel=c.level;
        captureRequested=true;captureDone=false;captureAt=age;
        String name="roxy_npc_"+RoxyNpcPreview.mode()+"_phase_"+shotState.phase()+"_"+System.currentTimeMillis()+".png";
        Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),result->c.execute(()->{
            // A previous phase/world callback must never satisfy or fail the current phase.
            if(!eligible()||pending!=shotState||c.level!=shotLevel||acked)return;
            if(result.getContents() instanceof TranslatableContents t&&t.getKey().equals("screenshot.success")){
                captureDone=true;screenshots.incrementAndGet();LogUtils.getLogger().info("[JR_NPC_CLIENT] SCREENSHOT {}",name);
            }else imageFailure=result.getString();
        }));
    }
    private static void require(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private RoxyNpcPreviewClient(){}
}
