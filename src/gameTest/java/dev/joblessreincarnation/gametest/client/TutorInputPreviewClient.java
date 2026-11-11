package dev.joblessreincarnation.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.MagicJournalScreen;
import dev.joblessreincarnation.client.SpellKeys;
import dev.joblessreincarnation.gametest.TutorInputPreview;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.channel.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Synthetic raw input through the normal event bus; real vanilla packets and received private chat are observed. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class TutorInputPreviewClient {
    private static final String HANDLER="jr_tutor_input_probe";
    private static final NativeUseInput USE_INPUT=new NativeUseInput();
    private static TutorInputPreview.State pending;
    private static net.minecraft.client.multiplayer.ClientLevel seenLevel;
    private static Channel channel;
    private static volatile boolean observerReady;
    private static volatile Trace trace;
    private static int ticks,age,start=-1,finishDelay=-1,originalScale;
    private static boolean captured,originalPause,originalHide,originalActive,done,acked,configured,captureRequested,captureDone;
    private static CameraType originalCamera;
    private static InputConstants.Key originalUse,originalPick,originalJournal,originalAttack;
    private static KeyModifier originalUseModifier,originalJournalModifier,originalAttackModifier;
    private static IKeyConflictContext originalUseContext;
    private static MutableUseContext mutableContext;
    private static final class MutableUseContext implements IKeyConflictContext {
        boolean active;
        @Override public boolean isActive(){return active;}
        @Override public boolean conflicts(IKeyConflictContext other){return this==other;}
    }
    private static String failure;
    private static final List<Integer> replyTicks=new ArrayList<>();
    private static final AtomicInteger screenshots=new AtomicInteger();
    private static final class Trace {
        final int phase;
        volatile String inputSnapshot="No client tick snapshot yet";
        String lastFoodSample="";
        final AtomicInteger swings=new AtomicInteger(),uses=new AtomicInteger(),useOn=new AtomicInteger(),offhand=new AtomicInteger();
        final AtomicInteger interactAt=new AtomicInteger(),interact=new AtomicInteger(),attacks=new AtomicInteger();
        Trace(int phase){this.phase=phase;}
    }
    private static final class ProbeScreen extends Screen {
        ProbeScreen(){super(Component.literal("C03 input boundary fixture"));}
        @Override public boolean isPauseScreen(){return false;}
    }
    private static boolean eligible(){return TutorInputPreview.enabled()&&!done;}
    private static boolean automaticSession(){if(!eligible())return false;Minecraft c=Minecraft.getInstance();var s=c.getSingleplayerServer();return c.level!=null&&s!=null&&!s.isPublished()&&TutorInputPreview.WORLD.equals(s.getWorldData().getLevelName())&&(pending==null||pending.phase()!=TutorInputPreview.FINISH);}
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(automaticSession()&&e.isPaused())e.setCanceled(true);}
    @SubscribeEvent public static void noMenu(ScreenEvent.Opening e){if(automaticSession()&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System e){
        if(!eligible()||pending==null||!(e.getMessage().getContents() instanceof TranslatableContents t))return;
        String key=t.getKey();
        if(e.isOverlay()){
            if(key.startsWith("message.jobless_reincarnation.")&&(key.contains("learned")||key.contains("study")))failure="Unexpected learning/study overlay: "+key;
            return;
        }
        if(!key.equals("message.jobless_reincarnation.tutor.speaker")&&!key.equals("message.jobless_reincarnation.fire_tutor.speaker"))return;
        if(acked||start<0||pending.phase()==TutorInputPreview.FINISH){failure="Private reply outside active input trace";return;}
        replyTicks.add(ticks);
        if(e.getMessage().getString().contains("message.jobless_reincarnation."))failure="Packaged private tutor language missing";
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] PRIVATE_CHAT phase={} clientTick={} key={} text={}",pending.phase(),ticks,key,e.getMessage().getString());
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre event){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();TutorInputPreview.clientReceiver=TutorInputPreviewClient::receive;ticks++;
        if(c.level!=seenLevel){if(pending!=null||captured||finishDelay>=0)clear(c);seenLevel=c.level;}
        if(finishDelay>=0){c.mouseHandler.releaseMouse();USE_INPUT.release(c);if(--finishDelay>0)return;done=true;restore(c);removeObserver();if(c.level!=null)c.level.disconnect();c.disconnect(new TitleScreen());LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] EXIT screenshots={}",screenshots.get());c.stop();return;}
        if(c.player==null||c.level==null||pending==null)return;
        if(!captured)captureOptions(c);
        if(++age>550||failure!=null){fail(failure==null?"Native phase timed out":failure);return;}
        if(acked){USE_INPUT.release(c);return;}
        try{
            installObserver(c);if(!observerReady||c.getOverlay()!=null)return;
            if(!configured){configure(c,pending.phase());configured=true;return;}
            c.options.keyUp.setDown(false);c.options.keyDown.setDown(false);c.options.keyLeft.setDown(false);c.options.keyRight.setDown(false);c.options.keyJump.setDown(false);c.options.keyShift.setDown(false);
            if(start<0){
                if(c.screen!=null||age<12||!initialSync(c)||!USE_INPUT.focusReady(c))return;
                aimForPhase(c,0);trace=new Trace(pending.phase());start=ticks;
                if(pending.phase()==12){
                    InputConstants.Key key=c.options.keyUse.getKey();
                    require(mutableContext!=null&&!mutableContext.active&&!c.options.keyUse.isDown(),"Inactive binding must begin without logical use");
                    NeoForge.EVENT_BUS.post(new InputEvent.Key(key.getValue(),GLFW.glfwGetKeyScancode(key.getValue()),GLFW.GLFW_PRESS,0));
                    LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] INACTIVE_CONTEXT_PRESS raw only; no KeyMapping.set/click and no interaction before active REPEAT");
                }else {
                    if(pending.phase()==7)LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] FOOD_START_READY food={} saturation={} canEat={}; fixture sends authoritative health/food before each phase; normal input requires canEat",c.player.getFoodData().getFoodLevel(),c.player.getFoodData().getSaturationLevel(),c.player.canEat(false));
                    USE_INPUT.press(c);
                }
                LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] START phase={} name={} holdTicks={} key={} input=synthetic_raw_events_plus_vanilla_KeyMapping",pending.phase(),TutorInputPreview.name(pending.phase()),pending.holdTicks(),c.options.keyUse.getKey().getName());
            }
            int elapsed=ticks-start,phase=pending.phase();
            boundaryActions(c,phase,elapsed);
            aimForPhase(c,elapsed);
            sampleInput(c,elapsed);
            boolean held=elapsed<pending.holdTicks()&&!(phase==6&&elapsed>=80&&elapsed<100)&&!(phase>=8&&elapsed>=100&&elapsed<112)&&!(phase==12&&elapsed<30);
            USE_INPUT.hold(c,held);
            if(c.player.isUsingItem())require(phase==7&&c.player.getUsedItemHand()==InteractionHand.MAIN_HAND,"Tutor hold started book, staff or offhand use");
            if(phase!=7&&elapsed==24)require(replyTicks.size()==(phase==12?0:1),"Unexpected reply count before any boundary action");
            if((phase==6||phase>=8)&&elapsed==96)require(replyTicks.size()==(phase==12?0:1),"A stale hold or context transition produced an unauthorized reply");
            if(screenshotPhase(phase)&&elapsed>=pending.holdTicks()+4&&!captureRequested)capture(c);
            if(elapsed>=pending.holdTicks()+16){
                require(!c.player.isUsingItem(),"Release left an item in use");
                if(screenshotPhase(phase))require(captureDone,"Native screenshot callback not completed");
                Trace result=trace;require(result!=null&&result.phase==phase,"Packet observer phase lost");
                int expected=TutorInputPreview.replies(phase);
                require(replyTicks.size()==expected,"Private replies="+replyTicks.size()+", expected="+expected);
                require(result.interactAt.get()==expected,"Actual interactAt packets="+result.interactAt.get()+", expected="+expected);
                require(result.swings.get()==expected,"Actual swings="+result.swings.get()+", expected="+expected);
                require(result.interact.get()==0&&result.attacks.get()==0,"Unexpected normal entity interaction or attack packet");
                require(result.offhand.get()==0&&result.useOn.get()==0,"Hold leaked into offhand or block use packet");
                require(phase==7?result.uses.get()>0:result.uses.get()==0,"Unexpected real item-use packet count="+result.uses.get());
                complete(result,"hold="+pending.holdTicks()+"; replyClientTicks="+replyTicks+"; interactAt="+result.interactAt.get()+"; normalInteract="+result.interact.get()+"; attack="+result.attacks.get()+"; useOn="+result.useOn.get()+"; server verifies inventory/knowledge/mana/food/blocks");
            }
        }catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_INPUT_CLIENT] FAILED",ex);fail(ex.toString());}
    }
    private static boolean initialSync(Minecraft c){
        if(c.player.getInventory().selected!=0||!c.player.getMainHandItem().is(TutorInputPreview.item(pending.phase())))return false;
        if(pending.phase()==7&&!c.player.canEat(false))return false; // Wait for the fixture health/food packet, never fake the hunger value.
        if(!c.player.getOffhandItem().is(pending.phase()==3?Items.TORCH:Items.BREAD)||c.player.getOffhandItem().getCount()!=16)return false;
        if(!c.player.hasData(ModAttachments.KNOWLEDGE)||!c.player.hasData(ModAttachments.MANA)||!c.player.hasData(ModAttachments.CASTING))return false;
        for(SpellCatalog spell:SpellCatalog.values())if(spell.knows(c.player)!=(spell==SpellCatalog.FIRE_BALL&&(pending.phase()==2||pending.phase()==4)))return false;
        return find(c,pending.npc())!=null&&find(c,pending.otherNpc())!=null;
    }
    private static void boundaryActions(Minecraft c,int phase,int elapsed){
        if(phase==4&&elapsed==40){c.player.getInventory().selected=1;LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] HOTBAR_SWITCH phase=4 slot=1; ordinary gameMode tick sends carried-item update");}
        if(phase==5&&elapsed==20)rawKey(c.options.keyUse,GLFW.GLFW_REPEAT,false);
        if(phase==5&&elapsed==40)rawKey(c.options.keyUse,GLFW.GLFW_PRESS,true);
        if(phase==6&&elapsed==80)USE_INPUT.release(c);
        if(phase==6&&elapsed==100){require(USE_INPUT.focusReady(c),"No actual focus for second middle-mouse press");USE_INPUT.press(c);}
        if(phase==8&&elapsed==30)c.setScreen(new ProbeScreen());
        if(phase==9&&elapsed==30)rawKey(SpellKeys.JOURNAL,GLFW.GLFW_PRESS,true);
        if(phase==9&&elapsed==31)rawKey(SpellKeys.JOURNAL,GLFW.GLFW_RELEASE,false);
        if(phase==9&&elapsed==33)require(c.screen instanceof MagicJournalScreen,"Actual K mapping did not open MagicJournalScreen");
        if((phase==8||phase==9)&&elapsed==40){require(c.screen!=null,"Expected screen before close");closeFixtureScreen(c);}
        if(phase==10&&elapsed>=30&&elapsed<40){c.setWindowActive(false);if(elapsed==30)LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SIMULATED_FOCUS_CALLBACK false; fixture calls Minecraft callback, not a human OS focus change");}
        if(phase==10&&elapsed==40){c.setWindowActive(true);LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SIMULATED_FOCUS_CALLBACK true");}
        if(phase==11&&elapsed==40){bindUse(c,InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] REBIND_WITHOUT_RAW_PRESS middle mouse");}
        if(phase>=8&&phase<=11&&elapsed==48){KeyMapping.click(c.options.keyUse.getKey());LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] STALE_LOGICAL_CLICK phase={} without raw PRESS",phase);}
        if(phase==12&&elapsed==30){
            require(c.options.keyUse.getKeyConflictContext()==mutableContext,"Context identity must remain unchanged during activation");
            require(trace!=null&&trace.interactAt.get()==0&&trace.uses.get()==0&&replyTicks.isEmpty(),"Inactive PRESS must not produce logical use before REPEAT");
            mutableContext.active=true;rawKey(c.options.keyUse,GLFW.GLFW_REPEAT,true);
            LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SAME_CONTEXT_ACTIVATED plus synthetic REPEAT; tests context eligibility, not physical Shift state");
        }
        if(phase>=8&&elapsed==100){USE_INPUT.release(c);if(phase==12)rawKey(c.options.keyUse,GLFW.GLFW_RELEASE,false);}
        if(phase>=8&&elapsed==112){require(c.screen==null&&USE_INPUT.focusReady(c),"No valid foreground context for fresh post-boundary press");USE_INPUT.press(c);}
    }
    private static void aimForPhase(Minecraft c,int elapsed){
        int phase=pending.phase();
        if((phase==2&&elapsed>=40)||(phase==4&&elapsed>=80)||(phase==7&&elapsed<44)){
            c.player.lookAt(EntityAnchorArgument.Anchor.EYES,c.player.position().add(0,6,-8));c.gameRenderer.pick(1F);
            require(c.hitResult==null||c.hitResult.getType()==HitResult.Type.MISS,"Air test must actually miss entities and blocks");return;
        }
        if(phase==3&&elapsed>=40){
            Vec3 point=new Vec3(TutorInputPreview.FLOOR.getX()+0.96,TutorInputPreview.FLOOR.getY()+0.999,TutorInputPreview.FLOOR.getZ()+0.06);
            c.player.lookAt(EntityAnchorArgument.Anchor.EYES,point);c.gameRenderer.pick(1F);
            require(c.hitResult instanceof BlockHitResult hit&&hit.getBlockPos().equals(TutorInputPreview.FLOOR)&&hit.getDirection()==Direction.UP,"Floor test must hit the exact unobstructed floor top");return;
        }
        UUID id=phase==4&&elapsed>=40?pending.otherNpc():pending.npc();Entity npc=find(c,id);require(npc!=null,"Formal tutor missing from scene");
        c.player.lookAt(EntityAnchorArgument.Anchor.EYES,npc.getEyePosition());c.gameRenderer.pick(1F);
        require(c.hitResult instanceof EntityHitResult hit&&hit.getEntity().getUUID().equals(id),"Real crosshair failed to acquire intended formal tutor");
    }
    private static Entity find(Minecraft c,UUID id){for(Entity entity:c.level.entitiesForRendering())if(entity.getUUID().equals(id))return entity;return null;}
    private static void rawKey(KeyMapping mapping,int action,boolean click){
        InputConstants.Key key=mapping.getKey();require(key.getType()==InputConstants.Type.KEYSYM,"Keyboard phase requires a keysym mapping");
        KeyMapping.set(key,action!=GLFW.GLFW_RELEASE);if(click)KeyMapping.click(key);
        NeoForge.EVENT_BUS.post(new InputEvent.Key(key.getValue(),GLFW.glfwGetKeyScancode(key.getValue()),action,0));
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SYNTHETIC_RAW_KEY phase={} key={} action={} click={}",pending.phase(),key.getName(),action,click);
    }
    private static void bindUse(Minecraft c,InputConstants.Key key){c.options.keyUse.setKeyModifierAndCode(KeyModifier.NONE,key);KeyMapping.resetMapping();}
    private static void configure(Minecraft c,int phase){
        USE_INPUT.release(c);int discardedAttackClicks=0;while(c.options.keyAttack.consumeClick())discardedAttackClicks++;KeyMapping.releaseAll();KeyMapping.resetToggleKeys();
        mutableContext=phase==12?new MutableUseContext():null;
        c.options.keyUse.setKeyConflictContext(phase==12?mutableContext:originalUseContext);
        c.options.keyAttack.setDown(false);c.options.keyAttack.setKeyModifierAndCode(KeyModifier.NONE,InputConstants.UNKNOWN);
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] ATTACK_ISOLATED phase={} originalKey={} queuedBeforePhase={}; no simultaneous human input; actual attack packets remain counted",phase,originalAttack.getName(),discardedAttackClicks);
        c.options.keyPickItem.setKey(InputConstants.UNKNOWN);SpellKeys.JOURNAL.setKeyModifierAndCode(KeyModifier.NONE,InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
        bindUse(c,phase==5||phase==12?InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R):InputConstants.Type.MOUSE.getOrCreate(phase==6?GLFW.GLFW_MOUSE_BUTTON_MIDDLE:GLFW.GLFW_MOUSE_BUTTON_RIGHT));
    }
    /** Passive observation: every original packet and promise is forwarded unchanged. */
    private static void installObserver(Minecraft c){Channel current=c.getConnection().getConnection().channel();if(channel==current)return;removeObserver();channel=current;observerReady=false;
        current.eventLoop().execute(()->{if(current.pipeline().get(HANDLER)!=null)current.pipeline().remove(HANDLER);current.pipeline().addLast(HANDLER,new ChannelOutboundHandlerAdapter(){@Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise)throws Exception{
            Trace t=trace;if(t!=null){
                if(packet instanceof ServerboundInteractPacket p)p.dispatch(new ServerboundInteractPacket.Handler(){
                    @Override public void onInteraction(InteractionHand hand){t.interact.incrementAndGet();offhand(t,hand);packetSample(t,"INTERACT",hand);}
                    @Override public void onInteraction(InteractionHand hand,Vec3 point){t.interactAt.incrementAndGet();offhand(t,hand);packetSample(t,"INTERACT_AT",hand);}
                    @Override public void onAttack(){t.attacks.incrementAndGet();LogUtils.getLogger().error("[JR_TUTOR_INPUT_CLIENT] ATTACK_PACKET phase={} {}",t.phase,t.inputSnapshot);}
                });
                else if(packet instanceof ServerboundSwingPacket p){t.swings.incrementAndGet();offhand(t,p.getHand());packetSample(t,"SWING",p.getHand());}
                else if(packet instanceof ServerboundUseItemPacket p){t.uses.incrementAndGet();offhand(t,p.getHand());packetSample(t,"USE_ITEM",p.getHand());}
                else if(packet instanceof ServerboundUseItemOnPacket p){t.useOn.incrementAndGet();offhand(t,p.getHand());packetSample(t,"USE_ON "+p.getHitResult().getBlockPos(),p.getHand());}
            }super.write(context,packet,promise);
        }});observerReady=true;LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] PASSIVE_PACKET_OBSERVER_READY memoryConnection={}",c.getConnection().getConnection().isMemoryConnection());});
    }
    /** World/player access stays on the client tick; Netty reads only this immutable String. */
    private static void sampleInput(Minecraft c,int elapsed){
        Trace t=trace;if(t==null)return;
        String target=c.hitResult instanceof EntityHitResult hit?"ENTITY:"+hit.getEntity().getType()+":"+hit.getEntity().getUUID():c.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK?"BLOCK:"+hit.getBlockPos():"MISS";
        String food="food="+c.player.getFoodData().getFoodLevel()+" main="+c.player.getMainHandItem()+" offhand="+c.player.getOffhandItem()+" using="+c.player.isUsingItem()+(c.player.isUsingItem()?"/"+c.player.getUsedItemHand():"");
        t.inputSnapshot="clientTick="+ticks+" elapsed="+elapsed+" target="+target+" "+food+" useTicks="+c.player.getTicksUsingItem();
        if(t.phase==7&&!food.equals(t.lastFoodSample)){
            t.lastFoodSample=food;
            LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] FOOD_TICK phase=7 {}",t.inputSnapshot);
        }
    }
    private static void packetSample(Trace t,String kind,InteractionHand hand){
        if(t.phase==7)LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] FOOD_PACKET kind={} hand={} {}",kind,hand,t.inputSnapshot);
        else if(kind.equals("SWING"))LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SWING_PACKET phase={} hand={} {}",t.phase,hand,t.inputSnapshot);
    }
    private static String traceSummary(Trace t){return t==null?"trace=null":"interactAt="+t.interactAt.get()+" normalInteract="+t.interact.get()+" attacks="+t.attacks.get()+" swings="+t.swings.get()+" uses="+t.uses.get()+" useOn="+t.useOn.get()+" offhand="+t.offhand.get()+" "+t.inputSnapshot;}
    private static void offhand(Trace t,InteractionHand hand){if(hand==InteractionHand.OFF_HAND)t.offhand.incrementAndGet();}
    private static void removeObserver(){Channel old=channel;channel=null;observerReady=false;trace=null;if(old!=null)old.eventLoop().execute(()->{if(old.pipeline().get(HANDLER)!=null)old.pipeline().remove(HANDLER);});}
    private static void receive(TutorInputPreview.State state){
        if(!eligible())return;Minecraft c=Minecraft.getInstance();if(c.level==null)return;if(c.level!=seenLevel&&(pending!=null||captured))clear(c);
        USE_INPUT.release(c);seenLevel=c.level;pending=state;age=0;start=-1;acked=false;configured=false;trace=null;captureRequested=false;captureDone=false;failure=null;replyTicks.clear();
        if(state.phase()==TutorInputPreview.FINISH){LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] FINISHED passed={} {}",state.passed(),state.detail());restore(c);removeObserver();finishDelay=35;}
        else LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] STATE phase={} hold={} {}",state.phase(),state.holdTicks(),state.detail());
    }
    private static void complete(Trace result,String detail){if(acked)return;acked=true;USE_INPUT.release(Minecraft.getInstance());
        PacketDistributor.sendToServer(new TutorInputPreview.Ack(pending.phase(),true,replyTicks.size(),result.swings.get(),result.uses.get(),result.offhand.get(),shortText(detail)));
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] PASS phase={} privateReplies={} interactAt={} normalInteract={} attack={} swings={} uses={} useOn={} offhand={} {}",pending.phase(),replyTicks.size(),result.interactAt.get(),result.interact.get(),result.attacks.get(),result.swings.get(),result.uses.get(),result.useOn.get(),result.offhand.get(),detail);trace=null;
    }
    private static void fail(String detail){if(pending==null||acked||pending.phase()==TutorInputPreview.FINISH)return;acked=true;Minecraft c=Minecraft.getInstance();USE_INPUT.release(c);closeFixtureScreen(c);if(pending.phase()==10)c.setWindowActive(true);
        Trace t=trace;trace=null;LogUtils.getLogger().error("[JR_TUTOR_INPUT_CLIENT] FAILURE phase={} age={} elapsed={} replies={} trace={} detail={}",pending.phase(),age,start<0?-1:ticks-start,replyTicks.size(),traceSummary(t),detail);
        PacketDistributor.sendToServer(new TutorInputPreview.Ack(pending.phase(),false,replyTicks.size(),t==null?0:t.swings.get(),t==null?0:t.uses.get(),t==null?0:t.offhand.get(),shortText(detail)));
    }
    private static String shortText(String text){return text.length()>500?text.substring(0,500):text;}
    private static boolean screenshotPhase(int phase){return phase==1||phase==6||phase==9;}
    private static void capture(Minecraft c){var state=pending;var level=c.level;captureRequested=true;String name="tutor_input_phase_"+state.phase()+"_"+System.currentTimeMillis()+".png";
        Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),message->c.execute(()->{if(!eligible()||pending!=state||c.level!=level||acked)return;if(message.getContents() instanceof TranslatableContents t&&t.getKey().equals("screenshot.success")){captureDone=true;screenshots.incrementAndGet();LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] SCREENSHOT {}",name);}else failure="Screenshot save failed: "+message.getString();}));
    }
    @SubscribeEvent public static void originalAttackMouse(InputEvent.MouseButton.Post event){
        if(!eligible()||pending==null||originalAttack==null||originalAttack.getType()!=InputConstants.Type.MOUSE||event.getButton()!=originalAttack.getValue())return;
        Minecraft c=Minecraft.getInstance();
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_CLIENT] ORIGINAL_ATTACK_MOUSE_CALLBACK phase={} clientTick={} action={} screen={} attackNowBound={}; this fixture never emits the original attack button",pending.phase(),ticks,event.getAction(),c.screen==null?"none":c.screen.getClass().getSimpleName(),c.options.keyAttack.getKey().getName());
    }
    private static void closeFixtureScreen(Minecraft c){
        if(c.screen instanceof ProbeScreen||c.screen instanceof MagicJournalScreen){c.setScreen(null);c.mouseHandler.releaseMouse();}
    }
    private static void captureOptions(Minecraft c){originalPause=c.options.pauseOnLostFocus;originalHide=c.options.hideGui;originalScale=c.options.guiScale().get();originalCamera=c.options.getCameraType();originalActive=c.isWindowActive();originalUse=c.options.keyUse.getKey();originalPick=c.options.keyPickItem.getKey();originalJournal=SpellKeys.JOURNAL.getKey();originalAttack=c.options.keyAttack.getKey();originalAttackModifier=c.options.keyAttack.getKeyModifier();originalUseContext=c.options.keyUse.getKeyConflictContext();originalUseModifier=c.options.keyUse.getKeyModifier();originalJournalModifier=SpellKeys.JOURNAL.getKeyModifier();captured=true;
        c.options.pauseOnLostFocus=false;c.options.hideGui=false;c.options.guiScale().set(2);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();c.mouseHandler.releaseMouse();
    }
    private static void restore(Minecraft c){c.mouseHandler.releaseMouse();USE_INPUT.release(c);KeyMapping.releaseAll();KeyMapping.resetToggleKeys();if(!captured)return;
        closeFixtureScreen(c);
        c.options.keyAttack.setKeyModifierAndCode(originalAttackModifier,originalAttack);c.options.keyUse.setKeyConflictContext(originalUseContext);mutableContext=null;c.options.keyUse.setKeyModifierAndCode(originalUseModifier,originalUse);c.options.keyPickItem.setKey(originalPick);SpellKeys.JOURNAL.setKeyModifierAndCode(originalJournalModifier,originalJournal);KeyMapping.resetMapping();c.options.pauseOnLostFocus=originalPause;c.options.hideGui=originalHide;c.options.guiScale().set(originalScale);c.options.setCameraType(originalCamera);c.setWindowActive(originalActive);c.resizeDisplay();captured=false;
    }
    private static void clear(Minecraft c){restore(c);removeObserver();pending=null;finishDelay=-1;age=0;start=-1;acked=false;configured=false;failure=null;replyTicks.clear();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private TutorInputPreviewClient(){}
}
