package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import java.net.InetAddress;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Explicit opt-in private-world scene. All temporary player writes are journaled; tutoring itself is read-only. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID)
public final class RoxyTutorPreview {
    public static final String MODE="jobless_reincarnation.roxyTutorMode", WORLD="JR Magic 0.2.0 Test";
    public static final int PORT=25578, WAIT=0, QUERY=1, HOLD_QUERY=2, STUDY=3, PRACTICE=4, REMOVED=5, MANUAL=6, FINISH=99;
    public static final Vec3 NPC_POS=new Vec3(206.5,-60,194.5);
    public static final BlockPos TARGET=new BlockPos(206,-59,207);
    public static volatile Consumer<State> clientReceiver=ignored->{};
    public static String mode(){return System.getProperty(MODE,"manual");}
    public static boolean enabled(){return Boolean.getBoolean(RoxyTutorInteraction.PROPERTY);}
    public static boolean observer(){return mode().equals("observer");}
    public record State(int phase,UUID npc,boolean present,int action,String expected,int item,int goal,boolean passed,String message) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_roxy_tutor_state"));
        public static final StreamCodec<FriendlyByteBuf,State> CODEC=CustomPacketPayload.codec(State::write,State::new);
        private State(FriendlyByteBuf b){this(b.readVarInt(),b.readUUID(),b.readBoolean(),b.readVarInt(),b.readUtf(40),b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeUUID(npc);b.writeBoolean(present);b.writeVarInt(action);b.writeUtf(expected,40);b.writeVarInt(item);b.writeVarInt(goal);b.writeBoolean(passed);b.writeUtf(message,512);}
        @Override public Type<State> type(){return TYPE;}
    }
    public record Ack(int phase,boolean passed,int messages,String stage,String detail) implements CustomPacketPayload {
        public static final Type<Ack> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_roxy_tutor_ack"));
        public static final StreamCodec<FriendlyByteBuf,Ack> CODEC=CustomPacketPayload.codec(Ack::write,Ack::new);
        private Ack(FriendlyByteBuf b){this(b.readVarInt(),b.readBoolean(),b.readVarInt(),b.readUtf(40),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeBoolean(passed);b.writeVarInt(messages);b.writeUtf(stage,40);b.writeUtf(detail,512);}
        @Override public Type<Ack> type(){return TYPE;}
    }
    @EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Network {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("dev-roxy-tutor-1")
            .playToClient(State.TYPE,State.CODEC,(p,c)->clientReceiver.accept(p))
            .playToServer(Ack.TYPE,Ack.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)ack(player,p);});}
    }
    private record Saved(CompoundTag nbt,ServerLevel level,GameType mode,Vec3 pos,Vec3 motion,float yaw,float pitch,HumanoidArm arm,Set<UUID> bolts){}
    private record Check(RoxyTutorGuidance.Snapshot progress,List<ItemStack> inventory,int mana,int maximum){}
    private static final class Session {
        final MinecraftServer server;final UUID host;final ServerLevel level;
        final Map<UUID,Saved> saved=new LinkedHashMap<>();final Map<UUID,State> sent=new HashMap<>();final Map<UUID,Check> before=new HashMap<>();final Set<UUID> acks=new HashSet<>();
        final long day;final boolean spawning,regen,keepInventory,auth;
        UUID peer;RoxyDisplayEntity npc;BlockState targetBefore;boolean targetPlaced,ready,ended;int ticks,phase,age;
        Session(ServerPlayer p){server=p.getServer();host=p.getUUID();level=p.serverLevel();day=level.getDayTime();auth=server.usesAuthentication();
            spawning=server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);regen=server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);keepInventory=server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);}
    }
    private static Session session;private static boolean attempted;
    private static Saved save(ServerPlayer p){return new Saved(p.saveWithoutId(new CompoundTag()),p.serverLevel(),p.gameMode.getGameModeForPlayer(),p.position(),p.getDeltaMovement(),p.getYRot(),p.getXRot(),p.getMainArm(),
        p.serverLevel().getEntities(ModContent.WATER_BOLT.get(),b->b.getOwner()==p).stream().map(WaterBolt::getUUID).collect(java.util.stream.Collectors.toSet()));}
    private static boolean ownerContext(ServerPlayer p){MinecraftServer s=p.getServer();return s!=null&&!s.isDedicatedServer()&&!s.isPublished()&&s.isSingleplayerOwner(p.getGameProfile())&&WORLD.equals(s.getWorldData().getLevelName())&&p.level().dimension()==Level.OVERWORLD&&p.isAlive()&&!p.isSpectator();}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){
        if(!enabled()||observer()||!(e.getEntity() instanceof ServerPlayer p))return;
        if(!attempted&&ownerContext(p)){attempted=true;session=new Session(p);session.saved.put(p.getUUID(),save(p));try{journal(session,false);}catch(Exception ex){finish(session,false,ex.toString());}return;}
        Session s=session;if(s!=null&&!s.ended&&s.server==p.getServer()&&mode().equals("host")&&p.getGameProfile().getName().equals("JRObserver")&&s.peer==null){s.peer=p.getUUID();s.saved.put(s.peer,save(p));try{journal(s,false);}catch(Exception ex){finish(s,false,ex.toString());}}
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){if(enabled())e.getDispatcher().register(Commands.literal("jr_tutor").requires(s->s.hasPermission(2)).then(Commands.literal("end").executes(c->{
        if(session!=null&&session.server==c.getSource().getServer())finish(session,true,"Manual scene ended by owner command");return 1;})));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){Session s=session;if(s==null||s.ended||s.server!=e.getServer())return;
        try{ServerPlayer host=s.server.getPlayerList().getPlayer(s.host);if(host==null||!host.isAlive()||host.level()!=s.level){finish(s,false,"Owner left eligible scene");return;}
            s.ticks++;s.age++;
            if(!mode().equals("manual")&&s.ticks>6000)throw new IllegalStateException("Scene timeout");
            if(mode().equals("host")&&s.ticks==40){s.server.setUsesAuthentication(false);s.server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"),PORT);LogUtils.getLogger().info("[JR_TUTOR_PREVIEW] LOOPBACK_READY port={}",PORT);}
            if(!s.ready&&s.ticks>=80&&(!mode().equals("host")||s.peer!=null))prepare(s);
            if(!s.ready)return;
            if(s.npc!=null&&!s.npc.isRemoved())for(ServerPlayer p:players(s)){var r=RoxyTutorInteraction.receipt(p);if(r!=null&&r.tick()==s.level.getGameTime()){Vec3 delta=p.position().subtract(s.npc.position());float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);s.npc.setYRot(yaw);s.npc.setYHeadRot(yaw);s.npc.setYBodyRot(yaw);}}
            if(!mode().equals("manual")&&s.acks.size()==s.saved.size()){validate(s);LogUtils.getLogger().info("[JR_TUTOR_PREVIEW] PHASE_PASS {} participants={}",s.phase,s.saved.size());if(s.phase==10)finish(s,true,"10 native interaction/learning/practice phases passed");else phase(s,s.phase+1);}
        }catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_PREVIEW] FAILED",ex);finish(s,false,ex.toString());}}
    private static List<ServerPlayer> players(Session s){return s.saved.keySet().stream().map(id->s.server.getPlayerList().getPlayer(id)).filter(Objects::nonNull).toList();}
    private static void prepare(Session s)throws Exception{
        var area=new AABB(NPC_POS.x-0.6,NPC_POS.y,NPC_POS.z-0.6,NPC_POS.x+0.6,NPC_POS.y+2.7,NPC_POS.z+0.6);
        require(s.level.hasChunksAt(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))&&s.level.noCollision(area),"NPC site must be loaded and empty");
        require(s.level.getBlockState(BlockPos.containing(NPC_POS).below()).isFaceSturdy(s.level,BlockPos.containing(NPC_POS).below(),net.minecraft.core.Direction.UP),"NPC floor required");
        require(s.level.hasChunkAt(TARGET)&&s.level.getBlockState(TARGET).isAir()&&s.level.getBlockEntity(TARGET)==null,"Target fixture may replace only original air");
        s.targetBefore=s.level.getBlockState(TARGET);journal(s,false);s.targetPlaced=true;s.level.setBlock(TARGET,Blocks.TARGET.defaultBlockState(),Block.UPDATE_ALL);
        s.npc=NpcDisplayRegistry.spawnRoxy(s.level,NPC_POS,0);require(s.npc!=null&&!s.npc.isRemoved()&&s.level.getEntity(s.npc.getUUID())==s.npc,"Scene NPC must actually join");journal(s,false);
        s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,s.server);s.level.setDayTime(6000);
        for(ServerPlayer p:players(s)){p.closeContainer();SpellCasting.openJournal(p);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().setItem(0,ModContent.WATER_TOME.get().getDefaultInstance());p.getInventory().setItem(1,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;p.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));p.setData(ModAttachments.CASTING,new CastingData());p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);seed(p,p.getUUID().equals(s.host)?0:1);near(s,p);sync(p);}
        RoxyTutorInteraction.bind(s.level,s.npc,s.saved.keySet());s.ready=true;
        if(mode().equals("manual")){for(ServerPlayer p:players(s)){send(s,p,new State(0,s.npc.getUUID(),true,MANUAL,"",1,0,true,"Developer teaching; /jr_tutor end restores this scene"));p.sendSystemMessage(Component.literal("[开发场景] 右键洛琪希咨询；在空处研读水弹术魔法书，用初学者魔杖练习南侧靶子。结束请用 /jr_tutor end 恢复玩家快照和临时场景；自行改动的其他世界方块不会回滚。"));}}
        else phase(s,1);
    }
    private static void seed(ServerPlayer p,int state){MagicKnowledge k=new MagicKnowledge();if(state>0)k.learn(WaterMagic.SPELL);int casts=state==1?2:state>1?TrainingTrials.CAST_GOAL:0;for(int i=0;i<casts;i++)k.practice(WaterMagic.SPELL);
        p.setData(ModAttachments.KNOWLEDGE,k);p.setData(ModAttachments.TRAINING,new TrainingProgress(state==1?1:state>=3?TrainingTrials.HIT_GOAL:0,state>=4,state==5));}
    private static void near(Session s,ServerPlayer p){boolean host=p.getUUID().equals(s.host);Vec3 pos=NPC_POS.add(host?0:-2.6,0,host?2.6:0);p.teleportTo(s.level,pos.x,pos.y,pos.z,host?180:-90,12);p.setYHeadRot(p.getYRot());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}
    private static void away(Session s,ServerPlayer p,boolean target){p.teleportTo(s.level,206.5,-60,201.5,0,target?1.2F:0);p.setYHeadRot(0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}
    private static void phase(Session s,int phase)throws Exception{
        s.phase=phase;s.age=0;s.acks.clear();s.sent.clear();s.before.clear();
        if(phase==10){RoxyTutorInteraction.unbind(s.level,s.npc);s.npc.discard();for(ServerPlayer p:players(s))send(s,p,new State(phase,s.npc.getUUID(),false,REMOVED,"",2,0,true,"Exact NPC removed; stale tutor scope cleared"));return;}
        RoxyTutorInteraction.bind(s.level,s.npc,s.saved.keySet());
        for(ServerPlayer p:players(s)){
            boolean host=p.getUUID().equals(s.host);SpellCasting.openJournal(p);p.setShiftKeyDown(false);
            if(phase==7)seed(p,host?2:5);if(phase==8)seed(p,host?3:0);if(phase==9)seed(p,host?5:4);
            int action=WAIT,item=2;
            if(phase==1){action=host?HOLD_QUERY:WAIT;item=host?1:2;}
            else if(phase==2)action=!host||s.peer==null?QUERY:WAIT;
            else if(phase==3){action=host?STUDY:WAIT;item=host?1:2;}
            else if(phase==4)action=host?QUERY:WAIT;
            else if(phase==5)action=host?PRACTICE:WAIT;
            else action=QUERY;
            p.getInventory().setItem(0,item==1?ModContent.WATER_TOME.get().getDefaultInstance():ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;
            if(action==STUDY||action==PRACTICE)away(s,p,action==PRACTICE);else near(s,p);
            // Prevent natural mana recovery from being confused with conversation side effects.
            ManaData m=p.getData(ModAttachments.MANA);p.setData(ModAttachments.MANA,new ManaData(m.mana(),m.maximum(),6000,m.cooldown()));
            sync(p);s.before.put(p.getUUID(),check(p));
            String expected=action==QUERY||action==HOLD_QUERY?RoxyTutorGuidance.snapshot(p).stage().name():"";
            send(s,p,new State(phase,s.npc.getUUID(),true,action,expected,item,action==PRACTICE?TrainingTrials.CAST_GOAL:0,true,"Developer phase "+phase));
        }
        LogUtils.getLogger().info("[JR_TUTOR_PREVIEW] PHASE {}",phase);
    }
    private static Check check(ServerPlayer p){List<ItemStack> inventory=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)inventory.add(p.getInventory().getItem(i).copy());ManaData m=p.getData(ModAttachments.MANA);return new Check(RoxyTutorGuidance.snapshot(p),inventory,m.mana(),m.maximum());}
    private static boolean sameInventory(List<ItemStack> a,List<ItemStack> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;}
    private static void validate(Session s){
        for(ServerPlayer p:players(s)){
            State state=s.sent.get(p.getUUID());if(state.action()==REMOVED){require(s.npc.isRemoved()&&s.level.getEntity(s.npc.getUUID())==null,"NPC cleanup verified");continue;}
            Check old=s.before.get(p.getUUID()),now=check(p);var receipt=RoxyTutorInteraction.receipt(p);
            require(!p.isUsingItem()&&SpellCatalog.active(p)==null,"Interaction/action must settle use state");
            if(state.action()==QUERY||state.action()==HOLD_QUERY){require(receipt!=null&&receipt.stage().name().equals(state.expected()),"Authoritative guidance must match actor");require(state.action()==QUERY?receipt.delivered()==1:receipt.delivered()>=2&&receipt.delivered()<=4,"Bounded, non-duplicated private replies");}
            else require(receipt==null,"Non-actor phase sends no tutor message");
            if(state.action()==STUDY){require(now.progress.learned()&&now.progress.casts()==0&&now.mana==old.mana,"Actual study teaches without spending/practicing");}
            else if(state.action()==PRACTICE){require(now.progress.casts()==TrainingTrials.CAST_GOAL&&now.progress.hits()==TrainingTrials.HIT_GOAL&&now.progress.charged()&&!now.progress.claimed(),"Real five releases and three distant hits including full shaping");require(now.mana<old.mana&&now.mana>=0,"Real casts pay normally");}
            else {require(now.progress.equals(old.progress)&&now.mana==old.mana&&now.maximum==old.maximum&&sameInventory(now.inventory,old.inventory),"Private teaching does not change personal progress, mana or inventory");}
            require(p.getInventory().countItem(ModContent.TRAINING_RECORD.get())==0,"Tutor never issues a reward");
        }
    }
    private static void send(Session s,ServerPlayer p,State state){s.sent.put(p.getUUID(),state);PacketDistributor.sendToPlayer(p,state);}
    private static void ack(ServerPlayer p,Ack a){Session s=session;if(s==null||s.ended||s.server!=p.getServer()||a.phase()!=s.phase||!s.saved.containsKey(p.getUUID()))return;
        State sent=s.sent.get(p.getUUID());if(sent==null)return;
        if(!a.passed()){finish(s,false,"Client "+p.getGameProfile().getName()+": "+a.detail());return;}
        boolean talks=sent.action()==QUERY||sent.action()==HOLD_QUERY;
        if(talks){var r=RoxyTutorInteraction.receipt(p);if(r==null||a.messages()!=r.delivered()||!a.stage().equals(sent.expected())){finish(s,false,"Private native chat receipt mismatch");return;}}
        else if(a.messages()!=0){finish(s,false,"Private tutoring leaked into non-actor phase");return;}
        s.acks.add(p.getUUID());LogUtils.getLogger().info("[JR_TUTOR_PREVIEW] ACK player={} phase={} messages={} stage={} {}",p.getGameProfile().getName(),a.phase(),a.messages(),a.stage(),a.detail());
    }
    private static void sync(ServerPlayer p){p.syncData(ModAttachments.KNOWLEDGE);p.syncData(ModAttachments.MANA);p.syncData(ModAttachments.CASTING);p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();p.connection.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));}
    private static void journal(Session s,boolean restored)throws Exception{CompoundTag tag=new CompoundTag();tag.putBoolean("Restored",restored);tag.putString("Purpose","ROXY-A02 isolated teaching fixture, original player data before mutation");tag.putLong("DayTime",s.day);tag.putBoolean("OriginalMobSpawning",s.spawning);tag.putBoolean("OriginalNaturalRegeneration",s.regen);tag.putBoolean("OriginalKeepInventory",s.keepInventory);tag.putBoolean("OriginalAuthentication",s.auth);
        ListTag list=new ListTag();for(var entry:s.saved.entrySet()){Saved v=entry.getValue();CompoundTag p=new CompoundTag();p.putUUID("UUID",entry.getKey());p.put("Player",v.nbt.copy());p.putString("MainArm",v.arm.name());p.putString("Dimension",v.level.dimension().location().toString());list.add(p);}tag.put("Players",list);
        if(s.npc!=null)tag.putUUID("TemporaryNpc",s.npc.getUUID());if(s.targetBefore!=null){tag.put("TargetPosition",NbtUtils.writeBlockPos(TARGET));tag.put("TargetOriginal",NbtUtils.writeBlockState(s.targetBefore));}
        var destination=s.server.getWorldPath(LevelResource.ROOT).resolve("jr-roxy-tutor-recovery.nbt");
        var pending=destination.resolveSibling(destination.getFileName()+".pending");
        // Preserve the last complete recovery snapshot if writing the new journal fails.
        NbtIo.writeCompressed(tag,pending);
        try{java.nio.file.Files.move(pending,destination,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        catch(java.nio.file.AtomicMoveNotSupportedException ex){java.nio.file.Files.move(pending,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }

    @FunctionalInterface
    private interface RestoreAction { void run() throws Exception; }

    /** Each restoration unit is independent: a failed NPC or player must not strand the others. */
    private static boolean restoreStep(String part,RestoreAction action){
        try{action.run();return true;}
        catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_PREVIEW] RESTORE_FAILED "+part,ex);return false;}
    }

    private static void restorePlayerData(ServerPlayer p,Saved old){
        p.removeData(ModAttachments.KNOWLEDGE);p.removeData(ModAttachments.MANA);
        p.removeData(ModAttachments.CASTING);p.removeData(ModAttachments.TRAINING);
        p.load(old.nbt.copy());
        p.setGameMode(old.mode);require(p.gameMode.getGameModeForPlayer()==old.mode,"Original game mode restored");
        p.setMainArm(old.arm);
        if(p.hasData(ModAttachments.CASTING))p.getData(ModAttachments.CASTING).clearActive();
        p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        if(p.hasData(ModAttachments.MANA)){
            int cooldown=p.getData(ModAttachments.MANA).cooldown();
            if(cooldown>0)p.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(),cooldown);
        }
    }

    private static void finish(Session s,boolean passed,String reason){
        if(s==null||s.ended)return;
        s.ended=true;
        boolean restored=true;
        // Deliberate &= evaluates every cleanup step, even after an earlier failure.
        if(s.npc!=null){
            restored&=restoreStep("NPC binding",()->RoxyTutorInteraction.unbind(s.level,s.npc));
            restored&=restoreStep("NPC entity",()->{
                s.npc.discard();
                require(s.npc.isRemoved(),"Exact temporary NPC removed");
            });
        }
        if(s.targetPlaced)restored&=restoreStep("target",()->{
            // Reload only the exact journaled fixture chunk if the manual player walked away.
            s.level.getChunkAt(TARGET);
            s.level.setBlock(TARGET,s.targetBefore,Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS);
            require(s.level.getBlockState(TARGET).equals(s.targetBefore),"Target original state restored");
        });
        for(var entry:s.saved.entrySet()){
            UUID uuid=entry.getKey();
            ServerPlayer p=s.server.getPlayerList().getPlayer(uuid);
            if(p==null){
                restored=false;
                LogUtils.getLogger().error("[JR_TUTOR_PREVIEW] RESTORE_FAILED missing player {}",uuid);
                continue;
            }
            Saved old=entry.getValue();
            restored&=restoreStep("player "+uuid+" use",()->{p.closeContainer();SpellCasting.openJournal(p);});
            restored&=restoreStep("player "+uuid+" projectiles",()->
                s.level.getEntities(ModContent.WATER_BOLT.get(),b->b.getOwner()==p&&!old.bolts.contains(b.getUUID())).forEach(WaterBolt::discard));
            restored&=restoreStep("player "+uuid+" effects",()->{
                // LivingEntity.load merges effects; remove scene effects before loading the saved set.
                p.removeAllEffects();
                require(p.getActiveEffects().isEmpty(),"Current effects cleared before snapshot load");
            });
            restored&=restoreStep("player "+uuid+" data",()->restorePlayerData(p,old));
            restored&=restoreStep("player "+uuid+" position",()->{
                p.teleportTo(old.level,old.pos.x,old.pos.y,old.pos.z,old.yaw,old.pitch);
                p.setDeltaMovement(old.motion);
            });
            restored&=restoreStep("player "+uuid+" synchronization",()->{
                p.onUpdateAbilities();
                sync(p);
                // Loading NBT restores the effect map/attributes without sending its normal add callbacks.
                for(var effect:p.getActiveEffects())
                    p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(),effect,false));
            });
        }
        restored&=restoreStep("day time",()->s.level.setDayTime(s.day));
        restored&=restoreStep("mob spawning rule",()->s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(s.spawning,s.server));
        restored&=restoreStep("natural regeneration rule",()->s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(s.regen,s.server));
        restored&=restoreStep("keep inventory rule",()->s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(s.keepInventory,s.server));
        restored&=restoreStep("authentication",()->s.server.setUsesAuthentication(s.auth));
        final boolean snapshotRestored=restored;
        restored&=restoreStep("recovery journal",()->journal(s,snapshotRestored));
        String message=reason+" restored="+restored;
        if(message.length()>512)message=message.substring(0,512);
        State result=new State(FINISH,s.npc==null?new UUID(0,0):s.npc.getUUID(),false,WAIT,"",0,0,passed&&restored,message);
        for(ServerPlayer p:players(s)){
            // A departed connection may reject the final notice; it must not interrupt other notices.
            try{PacketDistributor.sendToPlayer(p,result);}
            catch(Exception ex){LogUtils.getLogger().warn("[JR_TUTOR_PREVIEW] Final notice unavailable for "+p.getUUID(),ex);}
        }
        LogUtils.getLogger().info("[JR_TUTOR_PREVIEW] {} phases={} players={} restored={} {}",passed&&restored?"PASS":"FAIL",s.phase,s.saved.size(),restored,reason);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(session!=null&&session.saved.containsKey(e.getEntity().getUUID()))finish(session,mode().equals("manual"),"Participant logout");}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){if(session!=null&&session.server==e.getServer())finish(session,mode().equals("manual"),"Server stop");}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){
        if(session!=null&&session.server!=e.getServer())return;
        session=null;attempted=false;
    }

    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private RoxyTutorPreview(){}
}
