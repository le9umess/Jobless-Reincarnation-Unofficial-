package dev.joblessreincarnation.gametest.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.EpicCastController;
import dev.joblessreincarnation.compat.epic.RoxyEpicPatch;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.lwjgl.glfw.GLFW;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Private integrated-client fixture: synthetic raw events and vanilla key mappings, not human/PCL testing. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID, value=Dist.CLIENT)
public final class EpicV01Preview {
    private static final boolean ENABLED=Boolean.getBoolean("jobless_reincarnation.epicV01Preview");
    private static final NativeUseInput USE=new NativeUseInput();
    private static final AtomicInteger SAVED=new AtomicInteger();
    private static final JsonArray SHOTS=new JsonArray(), CHECKS=new JsonArray(), FRAMES=new JsonArray();
    private static volatile UUID actor;
    private static volatile int roxyId=-1, bolts, spent, previousMana=-1;
    private static volatile Snap state;
    private static volatile String error;
    private static boolean setupQueued,optionsSet,done,entryShot,holdShot,releaseShot,seenEntry,seenHold,seenRelease;
    private static int stage,age,ticks,captures,checksStartSpent,checksStartBolts,checksStartPractice,waitExit;
    private static final String[] STAGES={"roxy_front","roxy_three_quarter","roxy_walk","prepare_cast","full_cast","early_cancel","cycle_cancel","journal_cancel","slot_cancel","vanilla_mode_cast","finish"};
    private record Snap(int mana,int practice,int bolts,int spent,boolean using,int used,String selected,boolean visual,int chant) {}
    private static boolean active(){return ENABLED&&!done;}
    private static boolean privateWorld(Minecraft c){return active()&&c.getSingleplayerServer()!=null&&!c.getSingleplayerServer().isPublished()&&c.gameDirectory.getAbsolutePath().contains("run-epic-v01");}
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre e){if(privateWorld(Minecraft.getInstance())&&e.isPaused())e.setCanceled(true);}
    @SubscribeEvent public static void noPauseMenu(ScreenEvent.Opening e){if(privateWorld(Minecraft.getInstance())&&e.getNewScreen() instanceof PauseScreen)e.setCanceled(true);}
    @SubscribeEvent public static void joined(EntityJoinLevelEvent e){
        if(active()&&!e.getLevel().isClientSide()&&e.getEntity() instanceof WaterBolt bolt&&bolt.getOwner()!=null&&bolt.getOwner().getUUID().equals(actor))bolts++;
    }
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post e){
        if(!active()||actor==null)return;ServerPlayer p=e.getServer().getPlayerList().getPlayer(actor);if(p==null)return;
        int mana=p.getData(ModAttachments.MANA).mana();if(previousMana>=0&&mana<previousMana)spent+=previousMana-mana;previousMana=mana;
        state=new Snap(mana,p.getData(ModAttachments.KNOWLEDGE).practiceCount(WaterMagic.SPELL),bolts,spent,p.isUsingItem(),p.getTicksUsingItem(),SpellCatalog.selected(p).name(),EpicCastController.hasActiveVisual(p),SpellCatalog.WATER_BOLT.chantTicks(p));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void clientTick(ClientTickEvent.Post e){
        if(!active())return;Minecraft c=Minecraft.getInstance();ticks++;
        if(ticks>4800){fail(c,"Overall preview timeout");return;}
        if(error!=null){fail(c,error);return;}
        if(c.player==null||c.level==null||c.getSingleplayerServer()==null||c.getOverlay()!=null)return;
        if(!c.gameDirectory.getAbsolutePath().contains("run-epic-v01")){fail(c,"Refusing fixture outside private run directory");return;}
        if(!optionsSet){c.options.pauseOnLostFocus=false;c.options.guiScale().set(2);c.options.fov().set(65);c.options.setCameraType(CameraType.FIRST_PERSON);c.resizeDisplay();optionsSet=true;}
        if(c.screen!=null&&!(stage==7)){if(c.screen instanceof PauseScreen)c.setScreen(null);else return;}
        if(!setupQueued){setupQueued=true;UUID id=c.player.getUUID();c.getSingleplayerServer().execute(()->setup(c.getSingleplayerServer(),id));return;}
        if(state==null||roxyId<0||c.level.getEntity(roxyId)==null)return;
        if(age==0&&stage!=7&&!USE.focusReady(c))return;
        if(ticks%2==0)frame(c);
        try { run(c); } catch(Throwable ex){fail(c,ex.toString());}
    }
    private static void setup(MinecraftServer server,UUID id){try{
        ServerPlayer p=server.getPlayerList().getPlayer(id);var l=p.serverLevel();
        l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);
        for(int x=-4;x<=22;x++)for(int z=0;z<=28;z++){l.setBlockAndUpdate(new BlockPos(x,127,z),Blocks.GRASS_BLOCK.defaultBlockState());for(int y=128;y<=135;y++)l.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
        p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().setItem(0,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;
        p.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);p.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);p.syncData(ModAttachments.KNOWLEDGE);
        p.setData(ModAttachments.MANA,ManaData.full(100));p.syncData(ModAttachments.MANA);p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        SpellCasting.select(p,WaterMagic.SPELL);p.teleportTo(l,8.5,128,7,Set.of(),0,0);p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
        RoxyTutorEntity roxy=ModContent.ROXY_TUTOR.get().create(l);roxy.setPos(8.5,128,11);roxy.setYRot(180);roxy.yBodyRot=180;roxy.yHeadRot=180;roxy.setNoAi(true);l.addFreshEntity(roxy);roxy.reanchor();
        var patch=EpicFightCapabilities.getServerPlayerPatch(p);if(patch==null)throw new IllegalStateException("Real player has no EF patch");patch.toEpicFightMode(true);
        roxyId=roxy.getId();previousMana=p.getData(ModAttachments.MANA).mana();actor=id;
        LogUtils.getLogger().info("[JR_EPIC_V01] FIXTURE_READY actor={} roxy={} grantedWaterAndFire=true privateWorld=true",id,roxyId);
    }catch(Throwable ex){error=ex.toString();}}
    private static void server(Minecraft c,java.util.function.Consumer<ServerPlayer> action){c.getSingleplayerServer().execute(()->{try{var p=c.getSingleplayerServer().getPlayerList().getPlayer(actor);if(p!=null)action.accept(p);}catch(Throwable ex){error=ex.toString();}});}
    private static void look(Minecraft c,Vec3 point){Vec3 d=point.subtract(c.player.getEyePosition());float yaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z)),pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.sqrt(d.x*d.x+d.z*d.z)));c.player.setYRot(yaw);c.player.setXRot(pitch);c.player.yRotO=yaw;c.player.xRotO=pitch;}
    private static void resetWater(Minecraft c){server(c,p->{p.getInventory().selected=0;SpellCasting.select(p,WaterMagic.SPELL);p.inventoryMenu.broadcastChanges();});key(c,c.options.keyHotbarSlots[0],true);key(c,c.options.keyHotbarSlots[0],false);}
    private static void baseline(){checksStartSpent=state.spent();checksStartBolts=state.bolts();checksStartPractice=state.practice();}
    private static void next(Minecraft c){USE.release(c);c.options.keyUp.setDown(false);c.options.keyShift.setDown(false);age=0;stage++;LogUtils.getLogger().info("[JR_EPIC_V01] STAGE {}",STAGES[stage]);}
    private static void run(Minecraft c){
        var roxy=c.level.getEntity(roxyId);int n=age++;
        switch(stage){
        case 0 -> {look(c,roxy.getEyePosition().add(0,0.15,0));if(n==5){var patch=EpicFightCapabilities.getEntityPatch(roxy,RoxyEpicPatch.class);check(patch!=null,"roxy_has_ef_patch");}if(n==20)shot(c,"roxy_front");if(n==35)next(c);}
        case 1 -> {if(n==0)server(c,p->p.teleportTo(p.serverLevel(),11,128,8,Set.of(),35,0));look(c,roxy.getEyePosition().add(0,0.2,0));if(n==25)shot(c,"roxy_three_quarter");if(n==40)next(c);}
        case 2 -> {if(n==0)server(c,p->{var r=(RoxyTutorEntity)p.serverLevel().getEntity(roxyId);r.setNoAi(false);r.getNavigation().moveTo(13,128,13,1);});look(c,roxy.getEyePosition());if(n==15||n==28)shot(c,"roxy_walk_"+n);if(n==45)next(c);}
        case 3 -> {if(n==0){resetWater(c);server(c,p->{p.teleportTo(p.serverLevel(),6,128,6,Set.of(),0,0);EpicFightCapabilities.getServerPlayerPatch(p).setModelYRot(0,true);var r=(RoxyTutorEntity)p.serverLevel().getEntity(roxyId);r.setNoAi(true);r.getNavigation().stop();});c.options.setCameraType(CameraType.THIRD_PERSON_FRONT);}look(c,new Vec3(6,129.6,26));if(n==25){shot(c,"player_idle");next(c);}}
        case 4 -> {
            if(n==0){baseline();USE.press(c);}if(n<60)USE.hold(c,true);
            String clip=clip(c);seenEntry|=clip.contains("water_chant");seenHold|=clip.contains("water_hold");seenRelease|=clip.contains("water_release");
            if(!entryShot&&clip.contains("water_chant")&&n>=2){shot(c,"player_chant");entryShot=true;}
            if(n==22)c.options.keyUp.setDown(true);if(n==32)c.options.keyUp.setDown(false);
            if(n==30){shot(c,"player_hold_front");holdShot=true;}if(n==35)c.options.setCameraType(CameraType.THIRD_PERSON_BACK);if(n==43)shot(c,"player_hold_back");if(n==50)c.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            if(n==60)USE.release(c);
            if(!releaseShot&&clip.contains("water_release")){shot(c,"player_release_start");releaseShot=true;}
            if(n==64||n==66||n==69)shot(c,"player_release_"+n);
            if(n==88){check(state.bolts()-checksStartBolts==1,"one_projectile_for_release");check(state.practice()-checksStartPractice==1,"one_practice_for_release");check(state.spent()-checksStartSpent==WaterControl.manaCost(1),"one_full_charge_mana_payment");check(!state.visual()&&!state.using(),"server_cast_visual_ends");check(seenEntry&&seenHold&&seenRelease,"three_actual_client_phases_seen");shot(c,"player_recovered");next(c);}
        }
        case 5 -> {if(n==0){baseline();USE.press(c);}if(n<3)USE.hold(c,true);if(n==3)USE.release(c);if(n==24){canceled("early_release");next(c);}}
        case 6 -> {if(n==0){baseline();USE.press(c);}if(n<24)USE.hold(c,true);if(n==20){key(c,dev.joblessreincarnation.client.SpellKeys.CYCLE,true);key(c,dev.joblessreincarnation.client.SpellKeys.CYCLE,false);}if(n==24)USE.release(c);if(n==42){canceled("cycle_selection");check(!state.selected().equals("WATER_BOLT"),"G_changes_selected_spell");resetWater(c);}if(n==58)next(c);}
        case 7 -> {if(n==0){baseline();USE.press(c);}if(n<24)USE.hold(c,true);if(n==20){key(c,dev.joblessreincarnation.client.SpellKeys.JOURNAL,true);key(c,dev.joblessreincarnation.client.SpellKeys.JOURNAL,false);}if(n==24)USE.release(c);if(n==32){check(c.screen instanceof dev.joblessreincarnation.client.MagicJournalScreen,"K_opens_real_journal");shot(c,"journal_after_cancel");canceled("journal_open");c.setScreen(null);}if(n==48)next(c);}
        case 8 -> {if(n==0){baseline();USE.press(c);}if(n<24)USE.hold(c,true);if(n==20){key(c,c.options.keyHotbarSlots[1],true);key(c,c.options.keyHotbarSlots[1],false);}if(n==24)USE.release(c);if(n==40){canceled("slot_change");resetWater(c);}if(n==58)next(c);}
        case 9 -> {if(n==0)server(c,p->EpicFightCapabilities.getServerPlayerPatch(p).toVanillaMode(true));if(n==18){baseline();USE.press(c);}if(n>18&&n<70)USE.hold(c,true);if(n==55)shot(c,"vanilla_mode_held");if(n==70)USE.release(c);if(n==92){check(state.bolts()-checksStartBolts==1,"vanilla_mode_still_casts_once");check(state.practice()-checksStartPractice==1,"vanilla_mode_practice_once");check(state.spent()-checksStartSpent==WaterControl.manaCost(1),"vanilla_mode_payment_once");check(!state.visual(),"vanilla_mode_no_retained_cast");next(c);}}
        case 10 -> {if(SAVED.get()!=captures){if(n>200)throw new IllegalStateException("Screenshots not saved");return;}if(n>=15){write(c,"result.json",result(true,null));LogUtils.getLogger().info("[JR_EPIC_V01] VERIFIED checks={} screenshots={} frames={}",CHECKS.size(),captures,FRAMES.size());done=true;c.stop();}}
        default -> throw new IllegalStateException("Unknown preview stage");
        }
    }
    private static void canceled(String cause){check(state.bolts()==checksStartBolts,cause+"_no_projectile");check(state.spent()==checksStartSpent,cause+"_no_payment");check(state.practice()==checksStartPractice,cause+"_no_practice");check(!state.using()&&!state.visual(),cause+"_no_active_cast");}
    private static void key(Minecraft c,KeyMapping mapping,boolean down){var k=mapping.getKey();KeyMapping.set(k,down);if(down)KeyMapping.click(k);int code=k.getType()==InputConstants.Type.KEYSYM?k.getValue():GLFW.GLFW_KEY_UNKNOWN;NeoForge.EVENT_BUS.post(new InputEvent.Key(code,code<0?k.getValue():GLFW.glfwGetKeyScancode(code),down?GLFW.GLFW_PRESS:GLFW.GLFW_RELEASE,0));}
    private static String clip(Minecraft c){var patch=EpicFightCapabilities.getLocalPlayerPatch(c.player);if(patch==null)return "missing";var layer=patch.getClientAnimator().getCompositeLayer(Layer.Priority.HIGH);if(layer.isOff())return "off";return layer.animationPlayer.getRealAnimation().toString();}
    private static void frame(Minecraft c){JsonObject j=new JsonObject();j.addProperty("tick",ticks);j.addProperty("stage",STAGES[stage]);j.addProperty("age",age);j.addProperty("clip",clip(c));j.addProperty("using",c.player.isUsingItem());j.addProperty("mode",EpicFightCapabilities.getLocalPlayerPatch(c.player).getPlayerMode().name());j.add("server",new GsonBuilder().create().toJsonTree(state));FRAMES.add(j);}
    private static void check(boolean condition,String name){JsonObject j=new JsonObject();j.addProperty("name",name);j.addProperty("passed",condition);j.addProperty("tick",ticks);CHECKS.add(j);if(!condition)throw new IllegalStateException("Check failed: "+name+" state="+state);}
    private static void shot(Minecraft c,String label){String filename=String.format("epic_v01_%02d_%s.png",++captures,label);JsonObject j=new JsonObject();j.addProperty("file",filename);j.addProperty("tick",ticks);j.addProperty("clip",clip(c));var layer=EpicFightCapabilities.getLocalPlayerPatch(c.player).getClientAnimator().getCompositeLayer(Layer.Priority.HIGH);float elapsed=layer.animationPlayer.getElapsedTime();if(Float.isFinite(elapsed))j.addProperty("elapsed",elapsed);else j.add("elapsed",com.google.gson.JsonNull.INSTANCE);j.addProperty("link",layer.animationPlayer.getAnimation().get().isLinkAnimation());j.addProperty("camera",c.options.getCameraType().name());j.add("server",new GsonBuilder().create().toJsonTree(state));SHOTS.add(j);Screenshot.grab(c.gameDirectory,filename,c.getMainRenderTarget(),message->{if(message.getContents() instanceof TranslatableContents text&&text.getKey().equals("screenshot.success"))SAVED.incrementAndGet();else error="Screenshot failure: "+message.getString();});LogUtils.getLogger().info("[JR_EPIC_V01] SHOT {} clip={}",filename,clip(c));}
    private static JsonObject result(boolean passed,String why){JsonObject j=new JsonObject();j.addProperty("passed",passed);j.addProperty("failure",why);j.addProperty("inputKind","private synthetic raw NeoForge events plus vanilla KeyMapping; not manual/PCL");j.addProperty("fixture","copied world; granted known Water/Fire; EF mode set via API; does not claim learning or R-key coverage");j.addProperty("screenshotsRequested",captures);j.addProperty("screenshotsSaved",SAVED.get());j.add("checks",CHECKS);j.add("screenshots",SHOTS);j.add("frames",FRAMES);return j;}
    private static void write(Minecraft c,String filename,JsonObject value){try{Path p=c.gameDirectory.toPath().resolve("v01-evidence");Files.createDirectories(p);Files.writeString(p.resolve(filename),new GsonBuilder().setPrettyPrinting().create().toJson(value));}catch(Exception ex){LogUtils.getLogger().error("[JR_EPIC_V01] Cannot save evidence",ex);}}
    private static void fail(Minecraft c,String why){if(done)return;USE.release(c);c.options.keyUp.setDown(false);write(c,"failure.json",result(false,why));LogUtils.getLogger().error("[JR_EPIC_V01] FAILED {}",why);done=true;c.stop();}
    private EpicV01Preview(){}
}
