package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.TomeFeedbackPreview;
import dev.joblessreincarnation.magic.FireMagic;
import io.netty.channel.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native KeyMapping.use -> vanilla client item prediction -> real integrated-server packets. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class TomeFeedbackPreviewClient {
    private static final String HANDLER="jr_tome_feedback_probe";
    private static TomeFeedbackPreview.State pending;
    private static net.minecraft.client.multiplayer.ClientLevel seenLevel;
    private static Channel channel;
    private static volatile boolean observerReady;
    private static volatile Trace trace;
    private static volatile int ticks;
    private static int age,start=-1,finishDelay=-1,successTick=-1,knownTick=-1,originalScale;
    private static boolean captured,originalPause,originalHide,done,acked,captureRequested,captureDone;
    private static CameraType originalCamera;
    private static String failure;
    private static final List<Integer> knownTicks=new ArrayList<>();
    private static final AtomicInteger screenshots=new AtomicInteger();
    private static int learnedCount;
    private static final class Trace {
        final int phase;final AtomicInteger swings=new AtomicInteger(),uses=new AtomicInteger(),useOn=new AtomicInteger(),offhand=new AtomicInteger();
        Trace(int phase){this.phase=phase;}
    }
    private static boolean eligible(){return TomeFeedbackPreview.enabled()&&!done;}
    private static boolean automaticSession(){if(!eligible())return false;Minecraft c=Minecraft.getInstance();var s=c.getSingleplayerServer();return c.level!=null&&s!=null&&!s.isPublished()&&TomeFeedbackPreview.WORLD.equals(s.getWorldData().getLevelName())&&(pending==null||pending.phase()!=TomeFeedbackPreview.FINISH);}
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(automaticSession()&&e.isPaused())e.setCanceled(true);}
    @SubscribeEvent public static void noMenu(ScreenEvent.Opening e){if(automaticSession()&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System e){if(!eligible()||pending==null||!e.isOverlay()||!(e.getMessage().getContents() instanceof TranslatableContents t))return;
        String key=t.getKey();boolean known=key.endsWith("already_learned"),learned=key.equals("message.jobless_reincarnation.learned_fire_ball");if(!known&&!learned)return;
        if(acked||pending.phase()==TomeFeedbackPreview.FINISH){failure="Late tome overlay crossed a completed phase";return;}
        LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] OVERLAY phase={} clientTick={} key={} text={}",pending.phase(),ticks,key,e.getMessage().getString());
        if(e.getMessage().getString().contains("message.jobless_reincarnation."))failure="Packaged language resources missing";
        if(learned){if(pending.phase()!=1)failure="Unexpected learning overlay for already-known fixture";learnedCount++;successTick=ticks;}
        else{if(!key.equals(TomeFeedbackPreview.knownKey(pending.phase())))failure="Wrong tome private feedback";knownTicks.add(ticks);if(knownTick<0)knownTick=ticks;}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre e){if(!eligible())return;Minecraft c=Minecraft.getInstance();TomeFeedbackPreview.clientReceiver=TomeFeedbackPreviewClient::receive;ticks++;
        if(c.level!=seenLevel){if(pending!=null||captured||finishDelay>=0)clear(c);seenLevel=c.level;}
        if(finishDelay>=0){if(--finishDelay>0)return;done=true;restore(c);removeObserver();if(c.level!=null)c.level.disconnect();c.disconnect(new TitleScreen());LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] EXIT screenshots={}",screenshots.get());c.stop();return;}
        if(c.player==null||c.level==null||pending==null)return;
        if(!captured){originalPause=c.options.pauseOnLostFocus;originalHide=c.options.hideGui;originalScale=c.options.guiScale().get();originalCamera=c.options.getCameraType();captured=true;c.options.pauseOnLostFocus=false;c.options.hideGui=false;c.options.guiScale().set(2);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();c.mouseHandler.releaseMouse();}
        if(c.getOverlay()!=null)return;
        if(c.screen!=null){if(c.screen instanceof PauseScreen)c.setScreen(null);else{if(++age>200)fail("Unexpected screen blocks native input");return;}}
        if(++age>600||failure!=null){fail(failure==null?"Native phase timed out":failure);return;}
        c.options.keyUp.setDown(false);c.options.keyDown.setDown(false);c.options.keyLeft.setDown(false);c.options.keyRight.setDown(false);c.options.keyJump.setDown(false);c.options.keyShift.setDown(false);
        if(acked){c.options.keyUse.setDown(false);return;}
        try{
            installObserver(c);if(!observerReady)return;
            if(!c.player.getMainHandItem().is(TomeFeedbackPreview.tome(pending.phase())))return;
            if(pending.phase()>1&&!TomeFeedbackPreview.spell(pending.phase()).knows(c.player))return;
            Vec3 point=pending.floor()?Vec3.atCenterOf(TomeFeedbackPreview.FLOOR).add(0,0.49,0):c.player.position().add(0,3,8);
            c.player.lookAt(EntityAnchorArgument.Anchor.EYES,point);c.gameRenderer.pick(1F);
            if(pending.floor())require(c.hitResult instanceof BlockHitResult hit&&hit.getBlockPos().equals(TomeFeedbackPreview.FLOOR)&&hit.getDirection()==net.minecraft.core.Direction.UP,"Real crosshair must hit exact test floor top");
            else require(c.hitResult==null||c.hitResult.getType()==HitResult.Type.MISS,"Air-use scene must not hit an entity/block");
            if(start<0&&age>=12){if(pending.phase()==1)require(!FireMagic.isLearned(c.player),"Fire must be initially unknown on client");trace=new Trace(pending.phase());start=ticks;KeyMapping.click(c.options.keyUse.getKey());LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] NATIVE_HOLD_START phase={} ticks={} hand=MAIN_HAND offhand={}",pending.phase(),pending.holdTicks(),c.player.getOffhandItem());}
            if(start<0)return;
            int elapsed=ticks-start;c.options.keyUse.setDown(elapsed<pending.holdTicks());
            if(c.player.isUsingItem())require(c.player.getUsedItemHand()==InteractionHand.MAIN_HAND&&pending.phase()==1,"Main-hand suppression started offhand/known-book use");
            if(pending.phase()>1&&knownTick<0&&elapsed>16)throw new IllegalStateException("Different known tome did not promptly send its own feedback");
            if(!captureRequested&&(pending.phase()==1?successTick>=0&&ticks-successTick>=8:knownTick>=0&&ticks-knownTick>=8))capture(c);
            if(elapsed>=pending.holdTicks()+16){
                require(!c.player.isUsingItem(),"Release must leave no item in use");require(FireMagic.isLearned(c.player),"Actual fire study sync missing");
                require(captureDone,"Actual screenshot save callback not completed");
                Trace result=trace;require(result!=null&&result.phase==pending.phase(),"Packet observer phase lost");
                require(result.offhand.get()==0,"Suppressed main hand fell through to offhand packet");
                require(result.uses.get()>=10,"Held native use did not generate repeated real UseItem packets");
                require(result.swings.get()>=1&&result.swings.get()<=5&&result.swings.get()*3<result.uses.get(),"Client swing feedback was not substantially throttled");
                require(knownTicks.size()>=1&&knownTicks.size()<=4&&knownTicks.size()*3<result.uses.get(),"Server overlay feedback was not substantially throttled");
                for(int i=1;i<knownTicks.size();i++)require(knownTicks.get(i)-knownTicks.get(i-1)>=30,"Overlay interval below tolerant native threshold (server exact boundary is unit-tested)");
                if(pending.phase()==1){require(learnedCount==1&&successTick>=start+30,"Exactly one real study success overlay required");require(knownTick-successTick>=30,"Study success was immediately overwritten by known-book feedback");}
                else require(learnedCount==0&&knownTick-start<=16,"Known tome first feedback missing/late");
                complete(result,"Native hold="+pending.holdTicks()+"; overlayClientTicks="+knownTicks+"; learnedTick="+successTick+"; usesOn="+result.useOn.get()+"; screenshot saved; inventory/food/blocks checked on server");
            }
        }catch(Exception ex){LogUtils.getLogger().error("[JR_TOME_FEEDBACK_CLIENT] FAILED",ex);fail(ex.toString());}
    }
    /** Passive observation only: forwards every original packet and promise unchanged. */
    private static void installObserver(Minecraft c){Channel current=c.getConnection().getConnection().channel();if(channel==current)return;removeObserver();channel=current;observerReady=false;
        current.eventLoop().execute(()->{if(current.pipeline().get(HANDLER)!=null)current.pipeline().remove(HANDLER);current.pipeline().addLast(HANDLER,new ChannelOutboundHandlerAdapter(){@Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise)throws Exception{
            Trace t=trace;if(t!=null){if(packet instanceof ServerboundSwingPacket p){t.swings.incrementAndGet();if(p.getHand()==InteractionHand.OFF_HAND)t.offhand.incrementAndGet();}
                else if(packet instanceof ServerboundUseItemPacket p){t.uses.incrementAndGet();if(p.getHand()==InteractionHand.OFF_HAND)t.offhand.incrementAndGet();}
                else if(packet instanceof ServerboundUseItemOnPacket p){t.useOn.incrementAndGet();if(p.getHand()==InteractionHand.OFF_HAND)t.offhand.incrementAndGet();}}
            super.write(context,packet,promise);
        }});observerReady=true;LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] PACKET_OBSERVER_READY memoryConnection={}",c.getConnection().getConnection().isMemoryConnection());});}
    private static void removeObserver(){Channel old=channel;channel=null;observerReady=false;trace=null;if(old!=null)old.eventLoop().execute(()->{if(old.pipeline().get(HANDLER)!=null)old.pipeline().remove(HANDLER);});}
    private static void receive(TomeFeedbackPreview.State state){if(!eligible())return;Minecraft c=Minecraft.getInstance();if(c.level==null)return;if(c.level!=seenLevel&&(pending!=null||captured))clear(c);seenLevel=c.level;pending=state;age=0;start=-1;successTick=-1;knownTick=-1;learnedCount=0;knownTicks.clear();acked=false;trace=null;captureRequested=false;captureDone=false;failure=null;c.options.keyUse.setDown(false);
        if(state.phase()==TomeFeedbackPreview.FINISH){LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] FINISHED passed={} {}",state.passed(),state.detail());restore(c);removeObserver();finishDelay=35;}else LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] STATE phase={} hold={} floor={}",state.phase(),state.holdTicks(),state.floor());}
    private static void complete(Trace result,String detail){if(acked)return;acked=true;Minecraft.getInstance().options.keyUse.setDown(false);int overlays=knownTicks.size()+learnedCount;
        PacketDistributor.sendToServer(new TomeFeedbackPreview.Ack(pending.phase(),true,overlays,result.swings.get(),result.uses.get(),result.offhand.get(),shortText(detail)));
        LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] PASS phase={} overlays={} swings={} usePackets={} useOnPackets={} offhand={} {}",pending.phase(),overlays,result.swings.get(),result.uses.get(),result.useOn.get(),result.offhand.get(),detail);trace=null;}
    private static void fail(String detail){if(pending==null||acked)return;acked=true;Minecraft.getInstance().options.keyUse.setDown(false);Trace t=trace;trace=null;PacketDistributor.sendToServer(new TomeFeedbackPreview.Ack(pending.phase(),false,knownTicks.size()+learnedCount,t==null?0:t.swings.get(),t==null?0:t.uses.get(),t==null?0:t.offhand.get(),shortText(detail)));}
    private static String shortText(String text){return text.length()>500?text.substring(0,500):text;}
    private static void capture(Minecraft c){var state=pending;var level=c.level;captureRequested=true;String name="tome_feedback_phase_"+state.phase()+"_"+System.currentTimeMillis()+".png";Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),message->c.execute(()->{if(!eligible()||pending!=state||c.level!=level||acked)return;if(message.getContents() instanceof TranslatableContents t&&t.getKey().equals("screenshot.success")){captureDone=true;screenshots.incrementAndGet();LogUtils.getLogger().info("[JR_TOME_FEEDBACK_CLIENT] SCREENSHOT {}",name);}else failure="Screenshot save failed: "+message.getString();}));}
    private static void restore(Minecraft c){KeyMapping.releaseAll();KeyMapping.resetToggleKeys();if(!captured)return;c.options.pauseOnLostFocus=originalPause;c.options.hideGui=originalHide;c.options.guiScale().set(originalScale);c.options.setCameraType(originalCamera);c.resizeDisplay();captured=false;}
    private static void clear(Minecraft c){restore(c);removeObserver();pending=null;finishDelay=-1;age=0;start=-1;acked=false;failure=null;knownTicks.clear();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private TomeFeedbackPreviewClient(){}
}
