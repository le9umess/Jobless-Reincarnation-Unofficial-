package dev.joblessreincarnation.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.*;
import dev.joblessreincarnation.gametest.FireProbe;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModContent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Actual vanilla input routes and real journal widgets; no attachment assignment on the client. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class FireProbeClient {
    private static final String[] NAMES={"study_reusable_book_and_art","G_fire_seventh_then_wrap","fixed_long_hold_release","early_release","sneak_cancel","K_ready_cancel","unknown_fire_readonly","fire_journal_select_bilingual","H_rejected_for_fire","left_hand_focus_and_cancel"};
    private static final AtomicInteger saved=new AtomicInteger();
    private static final AtomicReference<String> imageFailure=new AtomicReference<>();
    private static int caseId,phase,age,total,delay=12,originalScale;
    private static boolean formingCaptured;
    private static boolean done,options,finishRequested,useDown,clientPass,originalGui,originalToggle;
    private static String originalLanguage,reason;
    private static CameraType originalCamera;
    private static MinecraftServer server;
    private static CompletableFuture<Void> reload;
    private static boolean privateWorld(){Minecraft c=Minecraft.getInstance();return FireProbe.enabled()&&!done&&c.getSingleplayerServer()!=null&&!c.getSingleplayerServer().isPublished()&&c.player!=null&&FireProbe.WORLD.equals(c.getSingleplayerServer().getWorldData().getLevelName());}
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(e.isPaused()&&privateWorld())e.setCanceled(true);}
    @SubscribeEvent public static void noMenu(ScreenEvent.Opening e){if(privateWorld()&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre e){
        if(!FireProbe.enabled()||done)return;Minecraft c=Minecraft.getInstance();server=c.getSingleplayerServer();if(server==null)return;
        FireProbe.Receipt r=FireProbe.receipt(server);if(r==null)return;
        if(r.restored()){if(--delay>0)return;done=true;restoreOptions(c);if(c.level!=null)c.level.disconnect();c.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_FIRE_PROBE_CLIENT] DISCONNECTED passed={} images={} {}",r.passed(),saved.get(),r.message());c.stop();return;}
        try{
            if(!r.ready()||c.player==null||c.level==null)return;
            if(!options){originalLanguage=c.getLanguageManager().getSelected();originalScale=c.options.guiScale().get();originalGui=c.options.hideGui;originalToggle=c.options.toggleCrouch().get();originalCamera=c.options.getCameraType();options=true;
                c.options.hideGui=false;c.options.guiScale().set(2);c.options.toggleCrouch().set(false);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();}
            if(++total>4500||imageFailure.get()!=null){finish(c,false,imageFailure.get()==null?"250s timeout":"Image failure: "+imageFailure.get());return;}
            if(reload!=null){if(!reload.isDone()||c.getOverlay()!=null)return;reload.join();reload=null;age=0;}
            if(phase==999){if(!finishRequested){finishRequested=true;server.execute(()->FireProbe.finish(server,clientPass,reason));}return;}
            if(finishRequested||c.getOverlay()!=null||!c.isWindowActive())return;
            if(++age>400)throw new IllegalStateException("Stalled case="+caseId+" phase="+phase);
            if(phase==900){if(r.caseId()!=caseId)return;if(r.result().equals("FAIL"))throw new IllegalStateException("Server rejected "+NAMES[caseId]);if(!r.result().equals("PASS"))return;
                LogUtils.getLogger().info("[JR_FIRE_PROBE_CLIENT] CASE_PASS {} {}",caseId,NAMES[caseId]);close(c);useDown=false;c.options.keyUse.setDown(false);c.options.keyShift.setDown(false);SpellKeys.DISMISS.setDown(false);
                if(++caseId==NAMES.length){finish(c,true,"10 native fire input/focus scenarios, including short-lived particles and one-shot SoundEngine cue; synthetic inputs");return;}
                c.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(),false); // Clear the previous fixture selection toast before changing server setup.
                FireFocusObservation.reset();int next=caseId;server.execute(()->FireProbe.prepare(server,next));step(0);return;}
            if(r.caseId()!=caseId||!r.result().equals("READY"))return;
            switch(caseId){
                case 0->study(c,r);case 1->cycle(c,r);case 2,3,4,5->cast(c,r);case 6,7->journal(c,r);case 8->dismiss(c,r);case 9->leftFocus(c,r);
                default->throw new IllegalStateException("Invalid case");
            }
            c.options.keyUse.setDown(useDown);
        }catch(Exception failure){LogUtils.getLogger().error("[JR_FIRE_PROBE_CLIENT] FAILED",failure);finish(c,false,failure.toString());}
    }
    private static void study(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<10||!c.player.getMainHandItem().is(ModContent.FIRE_TOME.get()))return;language(c,"zh_cn");c.setScreen(new InventoryScreen(c.player));step(1);}
        case 1->{if(age>=10){capture(c,"inventory");step(2);}}
        case 2->{if(saved.get()<1)return;close(c);step(3);}
        case 3->{if(age>=10){capture(c,"tome_first_person");step(4);}}
        case 4->{if(saved.get()<2)return;freshUse(c);step(5);}
        case 5->{if(!r.learned()||!FireMagic.isLearned(c.player))return;useDown=false;step(6);}
        case 6->{if(age>=8)check();}
        default->throw new IllegalStateException("Study phase");
    }}
    private static void cycle(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<8||SpellCatalog.selected(c.player)!=SpellCatalog.HEALING)return;KeyMapping.click(SpellKeys.CYCLE.getKey());step(1);}
        case 1->{if(r.selected()!=SpellCatalog.FIRE_BALL||SpellCatalog.selected(c.player)!=SpellCatalog.FIRE_BALL)return;KeyMapping.click(SpellKeys.CYCLE.getKey());step(2);}
        case 2->{if(r.selected()!=SpellCatalog.MIRE||SpellCatalog.selected(c.player)!=SpellCatalog.MIRE)return;KeyMapping.click(SpellKeys.CYCLE.getKey());step(3);}
        case 3->{if(r.selected()==SpellCatalog.WATER_BOLT&&SpellCatalog.selected(c.player)==SpellCatalog.WATER_BOLT&&age>=8)check();}
        default->throw new IllegalStateException("Cycle phase");
    }}
    private static void cast(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<8||SpellCatalog.selected(c.player)!=SpellCatalog.FIRE_BALL)return;freshUse(c);step(1);}
        case 1->{
            if(caseId==2&&!formingCaptured&&r.useTicks()>=6&&r.useTicks()<FireMagic.chantTicks(c.player)) {
                require(FireFocusObservation.created()>0,"Actual partial-chant particle provider ran");
                capture(c,"forming");formingCaptured=true;
            }
            if(caseId==3){if(age<3)return;useDown=false;step(2);return;}
            if(!r.using()||r.active()!=SpellCatalog.FIRE_BALL||r.useTicks()<FireMagic.chantTicks(c.player)+(caseId==2?40:2))return;
            if(caseId==2){require(FireFocusObservation.largestProgress()==1F&&FireFocusObservation.readySounds()==1,"Ready particle plus exactly one SoundEngine cue during long hold");capture(c,"ready_hud");useDown=false;}
            else if(caseId==4){c.options.keyShift.setDown(true);useDown=false;}
            else {KeyMapping.click(SpellKeys.JOURNAL.getKey());useDown=false;}
            step(2);
        }
        case 2->{if(age<12)return;useDown=false;
            if(caseId==4)c.options.keyShift.setDown(false);
            if(caseId==5)require(c.screen instanceof MagicJournalScreen,"K opened actual journal");
            if(caseId==2&&r.spawned()!=1)return;step(3);
        }
        case 3->{if(age>=6){
            require(FireFocusObservation.alive()==0,"Focus particles expired after release/cancel");
            require(FireFocusObservation.readySounds()==(caseId==3?0:1),"Early cancellation is silent; ready use gets one cue");
            LogUtils.getLogger().info("[JR_FIRE_FOCUS_CLIENT] PASS case={} particles={} readySounds={} liveAfterStop={}",caseId,FireFocusObservation.created(),FireFocusObservation.readySounds(),FireFocusObservation.alive());check();
        }}
        default->throw new IllegalStateException("Cast phase");
    }}
    private static void journal(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<8)return;KeyMapping.click(SpellKeys.JOURNAL.getKey());step(1);}
        case 1->{if(!(c.screen instanceof MagicJournalScreen s)||age<8)return;browseRow(s,SpellCatalog.FIRE_BALL);step(2);}
        case 2->{if(age<8)return;MagicJournalScreen s=screen(c);require(s.browsedSpell()==SpellCatalog.FIRE_BALL,"Visible fire row browses fire");
            var nav=s.navigationBounds();int previousBottom=Integer.MIN_VALUE;
            for(SpellCatalog spell:SpellCatalog.values()){var b=s.spellBounds(spell);require(b.height()>=23&&b.y()>previousBottom,"Every row retains readable height and separation, including clipped rows");previousBottom=b.y()+b.height();}
            var fire=s.spellBounds(SpellCatalog.FIRE_BALL);
            require(fire.y()>=nav.y()&&fire.y()+fire.height()<=nav.y()+nav.height()&&nav.y()+nav.height()<s.selectionBounds().y(),"Scrolled fire is fully visible above the independent footer");
            if(caseId==6){require(!widget(s,s.selectionBounds()).active,"Unknown fire disabled");capture(c,"fire_unknown_zh_cn");step(8);}
            else{require(widget(s,s.selectionBounds()).active,"Known fire selectable");click(s,s.selectionBounds());step(3);}
        }
        case 3->{if(r.selected()!=SpellCatalog.FIRE_BALL||SpellCatalog.selected(c.player)!=SpellCatalog.FIRE_BALL||age<8)return;capture(c,"fire_learned_zh_cn");step(4);}
        case 4->{if(saved.get()<6)return;language(c,"en_us");step(5);}
        case 5->{if(age<10)return;capture(c,"fire_learned_en_us");step(6);}
        case 6->{if(saved.get()<7)return;language(c,"zh_cn");step(8);}
        case 8->{if(age>=12)check();}
        default->throw new IllegalStateException("Journal phase");
    }}
    private static void dismiss(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<8)return;SpellKeys.DISMISS.setDown(true);KeyMapping.click(SpellKeys.DISMISS.getKey());step(1);}
        case 1->{if(age<25)return;SpellKeys.DISMISS.setDown(false);step(2);}
        case 2->{if(age>=8)check();}
        default->throw new IllegalStateException("Dismiss phase");
    }}
    private static void leftFocus(Minecraft c,FireProbe.Receipt r){switch(phase){
        case 0->{if(age<8||c.player.getMainArm()!=net.minecraft.world.entity.HumanoidArm.LEFT)return;freshUse(c);step(1);}
        case 1->{if(!r.using()||r.useTicks()<FireMagic.chantTicks(c.player)+8)return;
            require(FireFocusObservation.largestProgress()==1F&&FireFocusObservation.readySounds()==1,"Left-hand ready focus and single cue received");
            capture(c,"left_hand_ready");c.options.keyShift.setDown(true);useDown=false;step(2);}
        case 2->{if(age<16)return;c.options.keyShift.setDown(false);require(FireFocusObservation.alive()==0,"Left-hand cancellation expires particles");check();}
        default->throw new IllegalStateException("Left focus phase");
    }}
    private static void freshUse(Minecraft c){var key=c.options.keyUse.getKey();
        if(key.getType()==InputConstants.Type.MOUSE)NeoForge.EVENT_BUS.post(new InputEvent.MouseButton.Post(key.getValue(),GLFW.GLFW_PRESS,0));
        else NeoForge.EVENT_BUS.post(new InputEvent.Key(key.getValue(),0,GLFW.GLFW_PRESS,0));
        KeyMapping.click(key);useDown=true;
    }
    private static MagicJournalScreen screen(Minecraft c){if(c.screen instanceof MagicJournalScreen s)return s;throw new IllegalStateException("Missing actual journal");}
    private static AbstractWidget widget(Screen s,MagicJournalScreen.Bounds b){return s.children().stream().filter(v->v instanceof AbstractWidget).map(v->(AbstractWidget)v).filter(w->w.getX()==b.x()&&w.getY()==b.y()&&w.getWidth()==b.width()&&w.getHeight()==b.height()).findFirst().orElseThrow();}
    private static void click(Screen s,MagicJournalScreen.Bounds b){s.mouseClicked(b.x()+b.width()/2D,b.y()+b.height()/2D,0);s.mouseReleased(b.x()+b.width()/2D,b.y()+b.height()/2D,0);}
    private static void browseRow(MagicJournalScreen screen,SpellCatalog spell){
        for(int attempt=0;attempt<SpellCatalog.values().length+2;attempt++){
            var row=screen.spellBounds(spell);var nav=screen.navigationBounds();
            if(row.y()>=nav.y()&&row.y()+row.height()<=nav.y()+nav.height()){click(screen,row);return;}
            screen.mouseScrolled(nav.x()+nav.width()/2D,nav.y()+nav.height()/2D,0,row.y()<nav.y()?1:-1);
        }
        throw new IllegalStateException("Wheel could not expose "+spell);
    }
    private static void close(Minecraft c){if(c.screen!=null)c.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);}
    private static void check(){int i=caseId;server.execute(()->FireProbe.check(server,i));step(900);}
    private static void step(int value){phase=value;age=0;}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void language(Minecraft c,String code){if(c.getLanguageManager().getSelected().equals(code))return;c.options.languageCode=code;c.getLanguageManager().setSelected(code);reload=c.reloadResourcePacks();}
    private static void capture(Minecraft c,String view){String name="fire_focus_probe_013_"+view+"_"+System.currentTimeMillis()+".png";
        Screenshot.grab(c.gameDirectory,name,c.getMainRenderTarget(),message->{if(message.getContents() instanceof TranslatableContents t&&t.getKey().equals("screenshot.success")){saved.incrementAndGet();LogUtils.getLogger().info("[JR_FIRE_PROBE_CLIENT] SCREENSHOT_SAVED {}",name);}else imageFailure.set(message.getString());});}
    private static void restoreOptions(Minecraft c){if(!options)return;c.options.keyUse.setDown(false);c.options.keyShift.setDown(false);SpellKeys.DISMISS.setDown(false);c.options.hideGui=originalGui;c.options.guiScale().set(originalScale);c.options.toggleCrouch().set(originalToggle);c.options.setCameraType(originalCamera);c.resizeDisplay();c.options.languageCode=originalLanguage;c.getLanguageManager().setSelected(originalLanguage);}
    private static void finish(Minecraft c,boolean passed,String message){if(phase==999||finishRequested)return;useDown=false;close(c);clientPass=passed;reason=message;restoreOptions(c);reload=c.reloadResourcePacks();step(999);}
    private FireProbeClient(){}
}
