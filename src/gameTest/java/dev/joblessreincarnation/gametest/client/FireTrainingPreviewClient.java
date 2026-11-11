package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.*;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.glfw.GLFW;
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
public final class FireTrainingPreviewClient {
    private static FireTrainingPreview.State pending;
    private static final NativeUseInput USE_INPUT = new NativeUseInput();
    private static int ticks,age,nextConnect=100,startedAt=-1,lastRelease=-1000,finishDelay=-1,captureAt=-1;
    private static boolean captured,originalPause,originalHide,done,acked,useDown,captureRequested,captureDone;
    private static int originalScale;private static CameraType originalCamera;
    private static int focusWaitStarted=-1,focusRequestAt=-1,focusReadyAt=-1,focusRequests;
    private static net.minecraft.client.multiplayer.ClientLevel seenLevel;
    private static final List<String> messages=new ArrayList<>();
    private static String stage="";private static final AtomicInteger screenshots=new AtomicInteger();
    private static volatile String imageFailure;
    private static boolean eligible(){return FireTrainingPreview.enabled()&&!done;}
    private static boolean automaticSession(){
        if(!eligible())return false;
        Minecraft c=Minecraft.getInstance();
        if(c.level==null)return false;
        if(pending!=null)return c.level==seenLevel&&pending.phase()!=FireTrainingPreview.FINISH;
        // Keep only the opted-in host fixture ticking while it prepares the first scene packet.
        var server=c.getSingleplayerServer();
        return finishDelay<0&&server!=null&&!server.isPublished()&&FireTrainingPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(automaticSession()&&e.isPaused())e.setCanceled(true);}
    @SubscribeEvent public static void noMenu(ScreenEvent.Opening e){if(automaticSession()&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System e){
        if(!eligible()||pending==null||e.isOverlay()||!isTutor(e.getMessage()))return;
        Minecraft c=Minecraft.getInstance();String actual=findStage(e.getMessage());
        messages.add(c.level==null?e.getMessage().getString():Component.Serializer.toJson(e.getMessage(),c.level.registryAccess()));stage=actual;
        LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] PRIVATE_CHAT role={} phase={} stage={} component={} text={}",FireTrainingPreview.mode(),pending.phase(),actual,messages.getLast(),e.getMessage().getString());
        if(e.getMessage().getString().contains("message.jobless_reincarnation."))imageFailure="Packaged course language resource not loaded";
        boolean query=pending.action()==FireTrainingPreview.QUERY||pending.action()==FireTrainingPreview.HOLD_QUERY;
        if(!query)fail("Private tutor reply arrived during a non-query action");
        else if(!actual.equals(pending.expected()))fail("A private reply used the wrong course or personal stage");
    }
    private static boolean isTutor(Component component){
        return component.getContents() instanceof TranslatableContents contents
                &&(contents.getKey().equals("message.jobless_reincarnation.tutor.speaker")
                ||contents.getKey().equals("message.jobless_reincarnation.fire_tutor.speaker"));
    }
    private static String findStage(Component component){
        if(component.getContents() instanceof TranslatableContents contents){
            String key=contents.getKey(),fire="message.jobless_reincarnation.fire_tutor.rule.",water="message.jobless_reincarnation.tutor.rule.";
            if(key.startsWith(fire))return "FIRE:"+key.substring(fire.length()).toUpperCase(Locale.ROOT);
            if(key.startsWith(water))return "WATER:"+key.substring(water.length()).toUpperCase(Locale.ROOT);
        }
        for(Component child:component.getSiblings()){String found=findStage(child);if(!found.isEmpty())return found;}
        return "";
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre e){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();FireTrainingPreview.clientReceiver=FireTrainingPreviewClient::receive;ticks++;
        if(c.level!=seenLevel){
            if(pending!=null||captured||finishDelay>=0){clearSession(c);LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] DETACHED: inputs/options restored; ready for a new scene");}
            seenLevel=c.level;
        }
        if(finishDelay>=0){if(--finishDelay>0)return;done=true;restore(c);if(c.level!=null)c.level.disconnect();c.disconnect(new TitleScreen());LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] EXIT role={} screenshots={}",FireTrainingPreview.mode(),screenshots.get());c.stop();return;}
        if(FireTrainingPreview.observer()&&c.player==null&&c.getOverlay()==null&&ticks>=nextConnect&&(c.screen instanceof TitleScreen||c.screen instanceof DisconnectedScreen)){
            nextConnect=ticks+200;String address="127.0.0.1:"+FireTrainingPreview.PORT;ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("JR isolated tutor",address,ServerData.Type.OTHER),false,null);return;}
        if(c.player==null||c.level==null||pending==null)return;
        if(!captured){originalPause=c.options.pauseOnLostFocus;originalHide=c.options.hideGui;originalScale=c.options.guiScale().get();originalCamera=c.options.getCameraType();captured=true;c.options.pauseOnLostFocus=false;c.options.hideGui=false;c.options.guiScale().set(2);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();c.mouseHandler.releaseMouse();}
        if(c.getOverlay()!=null)return;
        if(c.screen!=null){if(c.screen instanceof PauseScreen)c.setScreen(null);else{if(++age>200)fail("Unexpected screen blocks real input");return;}}
        if(++age>1800||imageFailure!=null){fail(imageFailure==null?"Phase timed out "+pending.phase():imageFailure);return;}
        c.options.keyUp.setDown(false);c.options.keyDown.setDown(false);c.options.keyLeft.setDown(false);c.options.keyRight.setDown(false);c.options.keyJump.setDown(false);c.options.keyShift.setDown(false);
        if(acked){USE_INPUT.release(c);return;}
        try{
            Entity npc=find(c,pending.npc());int action=pending.action();
            if(action==FireTrainingPreview.REMOVED){require(npc==null,"Removed NPC remains on client");if(age>=20)complete("Exact NPC disappearance observed");return;}
            if(npc==null)return;
            if(!correctItem(c,pending.item()))return;
            if(action==FireTrainingPreview.QUERY||action==FireTrainingPreview.HOLD_QUERY){
                aim(c,npc.getEyePosition());c.gameRenderer.pick(1F);
                if(!(c.hitResult instanceof EntityHitResult hit)||!hit.getEntity().getUUID().equals(pending.npc())){if(age>160)throw new IllegalStateException("Real crosshair did not acquire tutor");return;}
                if(startedAt<0&&age>=4){if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);startedAt=age;useDown=true;}
                if(startedAt>=0){if(age-startedAt>=(action==FireTrainingPreview.HOLD_QUERY?45:1))useDown=false;
                    int settle=action==FireTrainingPreview.HOLD_QUERY?62:16;
                    if(age-startedAt>=settle){require(!c.player.isUsingItem(),"NPC click fell through to staff/book use");require(messages.size()==1&&stage.equals(pending.expected()),"One press must produce exactly one private Component with the expected stage");
                        if(!captureRequested){capture(c);return;}
                        if(imageFailure!=null)throw new IllegalStateException(imageFailure);
                        if(!captureDone){require(age-captureAt<=240,"Native screenshot save callback timed out");return;}
                        if(age-startedAt>=settle+8)complete("Native entity ray + synthetic raw PRESS/RELEASE + vanilla KeyMapping + one private SystemChat; native screenshot saved");}
                }
            }else if(action==FireTrainingPreview.STUDY){
                aim(c,c.player.position().add(0,1.5,8));c.gameRenderer.pick(1F);require(!(c.hitResult instanceof EntityHitResult hit&&hit.getEntity()==npc),"Study must aim away from NPC");
                if(age>=12&&startedAt<0){if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);startedAt=age;useDown=true;}
                if(FireMagic.isLearned(c.player)){useDown=false;if(lastRelease<0)lastRelease=age;if(age-lastRelease>=10&&!c.player.isUsingItem())complete("Real 40-tick book use learned Fire; no tutoring grant");}
            }else if(action==FireTrainingPreview.SELECT){
                aim(c,c.player.position().add(0,1.5,8));c.gameRenderer.pick(1F);useDown=false;
                if(age>=12&&startedAt<0){
                    USE_INPUT.release(c);
                    if(!selectionFocus(c))return;
                    require(c.player.hasData(ModAttachments.CASTING),"Selection attachment must be synchronized before native G input");
                    require(c.player.getData(ModAttachments.CASTING).selectedSpell().equals(WaterMagic.SPELL),"Native selection starts from water");
                    require(FireMagic.isLearned(c.player)&&WaterMagic.isLearned(c.player),"Both learned spells are synchronized before cycling");
                    KeyMapping cycle=Arrays.stream(c.options.keyMappings).filter(key->key.getName().equals("key.jobless_reincarnation.cycle_spell")).findFirst().orElseThrow(()->new IllegalStateException("Cycle spell key mapping unavailable"));
                    KeyMapping.click(cycle.getKey());startedAt=age;
                    LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] ACTUAL_CYCLE_KEY role={} mapping={} bound={} active={} nativeFocused={}",FireTrainingPreview.mode(),cycle.getName(),cycle.getKey().getName(),c.isWindowActive(),GLFW.glfwGetWindowAttrib(c.getWindow().getWindow(),GLFW.GLFW_FOCUSED)==GLFW.GLFW_TRUE);
                }
                if(startedAt>=0&&age-startedAt>=8&&c.player.hasData(ModAttachments.CASTING)
                        &&c.player.getData(ModAttachments.CASTING).selectedSpell().equals(FireMagic.SPELL)){
                    require(!c.player.isUsingItem(),"G selection must not start staff use");
                    complete("Actual cycle key input; server-authoritative casting selection synchronized as fire");
                }
            }else if(action==FireTrainingPreview.PRACTICE){
                aim(c,Vec3.atCenterOf(FireTrainingPreview.TARGET));c.gameRenderer.pick(1F);
                require(c.player.hasData(ModAttachments.CASTING)&&c.player.getData(ModAttachments.CASTING).selectedSpell().equals(FireMagic.SPELL),"Real fire practice requires synchronized fire selection");
                int casts=FireMagic.practiceCount(c.player);
                if(casts>=pending.goal()){
                    useDown=false;if(lastRelease<0)lastRelease=age;
                    if(age-lastRelease>=45&&!c.player.isUsingItem())complete("Actual native fire releases reached "+pending.goal()+" casts; server verifies target-hit distance separately");
                }else if(age>=12){
                    if(c.player.isUsingItem()){
                        if(c.player.getTicksUsingItem()>=FireMagic.chantTicks(c.player)+3){useDown=false;lastRelease=age;}
                    }else if(age-lastRelease>=8&&!c.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())){
                        if(!USE_INPUT.focusReady(c))return;USE_INPUT.press(c);useDown=true;
                    }
                }
            }else if(action==FireTrainingPreview.WAIT){useDown=false;if(age>=35){require(messages.isEmpty(),"Other player's private tutoring leaked");complete("No private tutor message in passive role");}}
            USE_INPUT.hold(c,useDown);
        }catch(Exception ex){LogUtils.getLogger().error("[JR_FIRE_TRAINING_CLIENT] FAILED",ex);fail(ex.toString());}
    }
    /** Respect the production foreground guard by asking the OS to focus this probe's own window. */
    private static boolean selectionFocus(Minecraft c){
        if(focusWaitStarted<0)focusWaitStarted=age;
        long window=c.getWindow().getWindow();
        require(window!=0L,"Native window handle unavailable for selection input");
        boolean nativeFocused=GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_FOCUSED)==GLFW.GLFW_TRUE;
        if(c.isWindowActive()&&nativeFocused){
            if(focusReadyAt<0){
                focusReadyAt=age;
                LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] WINDOW_FOCUS_READY role={} phase={} active={} nativeFocused={} requests={} waitTicks={}",
                        FireTrainingPreview.mode(),pending.phase(),c.isWindowActive(),nativeFocused,focusRequests,age-focusWaitStarted);
                return false;
            }
            // Let the real GLFW focus callback settle before the production Pre-tick handler sees a click.
            return age>focusReadyAt;
        }
        focusReadyAt=-1;
        require(age-focusWaitStarted<=160,"Own window did not gain real foreground focus; native G selection was not sent");
        if(focusRequests<2&&(focusRequestAt<0||age-focusRequestAt>=40)){
            focusRequests++;focusRequestAt=age;
            LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] WINDOW_FOCUS_REQUEST role={} phase={} attempt={} active={} nativeFocused={}",
                    FireTrainingPreview.mode(),pending.phase(),focusRequests,c.isWindowActive(),nativeFocused);
            GLFW.glfwFocusWindow(window);
        }
        return false;
    }
    private static void resetSelectionFocus(){focusWaitStarted=-1;focusRequestAt=-1;focusReadyAt=-1;focusRequests=0;}
    private static boolean correctItem(Minecraft c,int item){return item==0?c.player.getMainHandItem().isEmpty():c.player.getMainHandItem().is(item==1?ModContent.FIRE_TOME.get():item==3?ModContent.WATER_TOME.get():ModContent.TRAINING_STAFF.get());}
    private static Entity find(Minecraft c,UUID id){for(Entity entity:c.level.entitiesForRendering())if(entity.getUUID().equals(id))return entity;return null;}
    private static void aim(Minecraft c,Vec3 point){c.player.lookAt(EntityAnchorArgument.Anchor.EYES,point);}
    private static void complete(String detail){if(acked)return;acked=true;useDown=false;USE_INPUT.release(Minecraft.getInstance());PacketDistributor.sendToServer(new FireTrainingPreview.Ack(pending.phase(),true,messages.size(),stage,detail));LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] PASS role={} phase={} messages={} stage={}",FireTrainingPreview.mode(),pending.phase(),messages.size(),stage);}
    private static void fail(String detail){if(pending==null)return;acked=true;useDown=false;USE_INPUT.release(Minecraft.getInstance());PacketDistributor.sendToServer(new FireTrainingPreview.Ack(pending.phase(),false,messages.size(),stage,detail.length()>500?detail.substring(0,500):detail));}
    private static void receive(FireTrainingPreview.State state){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();
        if(c.level==null)return;
        if(c.level!=seenLevel&&(pending!=null||captured||finishDelay>=0))clearSession(c);
        seenLevel=c.level;pending=state;age=0;resetSelectionFocus();startedAt=-1;lastRelease=-1000;acked=false;useDown=false;captureRequested=false;captureDone=false;captureAt=-1;messages.clear();stage="";imageFailure=null;
        USE_INPUT.release(c);
        if(state.phase()==FireTrainingPreview.FINISH){
            LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] FINISHED passed={} {}",state.passed(),state.message());
            restore(c);finishDelay=FireTrainingPreview.observer()?20:45;
        }else{
            finishDelay=-1;
            LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] STATE role={} phase={} action={}",FireTrainingPreview.mode(),state.phase(),state.action());
        }
    }
    private static void clearSession(Minecraft c){
        restore(c);pending=null;finishDelay=-1;age=0;resetSelectionFocus();startedAt=-1;lastRelease=-1000;acked=false;useDown=false;
        captureRequested=false;captureDone=false;captureAt=-1;messages.clear();stage="";imageFailure=null;

    }
    private static void restore(Minecraft c){
        useDown=false;USE_INPUT.release(c);KeyMapping.releaseAll();KeyMapping.resetToggleKeys();
        if(!captured)return;
        c.options.pauseOnLostFocus=originalPause;c.options.hideGui=originalHide;c.options.guiScale().set(originalScale);c.options.setCameraType(originalCamera);c.resizeDisplay();captured=false;
    }
    private static void capture(Minecraft c){
        FireTrainingPreview.State shotState=pending;
        var shotLevel=c.level;
        captureRequested=true;captureDone=false;captureAt=age;
        String name="fire_training_"+FireTrainingPreview.mode()+"_phase_"+shotState.phase()+"_"+System.currentTimeMillis()+".png";
        Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),result->c.execute(()->{
            // A previous phase/world callback must never satisfy or fail the current phase.
            if(!eligible()||pending!=shotState||c.level!=shotLevel||acked)return;
            if(result.getContents() instanceof TranslatableContents t&&t.getKey().equals("screenshot.success")){
                captureDone=true;screenshots.incrementAndGet();LogUtils.getLogger().info("[JR_FIRE_TRAINING_CLIENT] SCREENSHOT {}",name);
            }else imageFailure=result.getString();
        }));
    }
    private static void require(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private FireTrainingPreviewClient(){}
}
