package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.npc.FireTutorGuidance;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import dev.joblessreincarnation.training.FireTrainingTrials;
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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
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
public final class FireTrainingPreview {
    public static final String MODE="jobless_reincarnation.fireTrainingMode", WORLD="JR Magic 0.2.0 Test";
    public static final int PORT=25578, WAIT=0, QUERY=1, HOLD_QUERY=2, STUDY=3, PRACTICE=4, REMOVED=5, SELECT=6, FINISH=99;
    public static final Vec3 NPC_POS=new Vec3(206.5,-60,184.5);
    public static final BlockPos TARGET=new BlockPos(206,-59,207);
    private static final TicketType<UUID> SCENE_TICKET=TicketType.create("jr_fire_training_probe",UUID::compareTo);
    public static volatile Consumer<State> clientReceiver=ignored->{};
    public static String mode(){return System.getProperty(MODE,"probe");}
    public static boolean enabled(){return Boolean.getBoolean("jobless_reincarnation.fireTrainingProbe");}
    public static boolean observer(){return mode().equals("observer");}
    public record State(int phase,UUID npc,boolean present,int action,String expected,int item,int goal,boolean passed,String message) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_fire_training_state"));
        public static final StreamCodec<FriendlyByteBuf,State> CODEC=CustomPacketPayload.codec(State::write,State::new);
        private State(FriendlyByteBuf b){this(b.readVarInt(),b.readUUID(),b.readBoolean(),b.readVarInt(),b.readUtf(40),b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeUUID(npc);b.writeBoolean(present);b.writeVarInt(action);b.writeUtf(expected,40);b.writeVarInt(item);b.writeVarInt(goal);b.writeBoolean(passed);b.writeUtf(message,512);}
        @Override public Type<State> type(){return TYPE;}
    }
    public record Ack(int phase,boolean passed,int messages,String stage,String detail) implements CustomPacketPayload {
        public static final Type<Ack> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_fire_training_ack"));
        public static final StreamCodec<FriendlyByteBuf,Ack> CODEC=CustomPacketPayload.codec(Ack::write,Ack::new);
        private Ack(FriendlyByteBuf b){this(b.readVarInt(),b.readBoolean(),b.readVarInt(),b.readUtf(40),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeBoolean(passed);b.writeVarInt(messages);b.writeUtf(stage,40);b.writeUtf(detail,512);}
        @Override public Type<Ack> type(){return TYPE;}
    }
    @EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Network {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("dev-fire-training-1")
            .playToClient(State.TYPE,State.CODEC,(p,c)->clientReceiver.accept(p))
            .playToServer(Ack.TYPE,Ack.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)ack(player,p);});}
    }
    private record Saved(CompoundTag nbt,ServerLevel level,GameType mode,Vec3 pos,Vec3 motion,float yaw,float pitch,HumanoidArm arm,Set<UUID> bolts,Set<UUID> fireballs){}
    private record Check(TutorGuidance.Snapshot water,FireTutorGuidance.Snapshot fire,List<ItemStack> inventory,int mana,int maximum,ResourceLocation selected){}
    private static final class Session {
        final MinecraftServer server;final UUID host;final ServerLevel level;
        final Map<UUID,Saved> saved=new LinkedHashMap<>();final Map<UUID,State> sent=new HashMap<>();final Map<UUID,Check> before=new HashMap<>();final Set<UUID> acks=new HashSet<>();final Map<UUID,Ack> responses=new HashMap<>();
        final long day;final boolean spawning,regen,keepInventory,auth;
        final UUID ticketOwner=UUID.randomUUID();final ChunkPos sceneChunk=new ChunkPos(BlockPos.containing(NPC_POS));boolean ticketActive;UUID peer;RoxyTutorEntity npc;BlockState targetBefore;boolean targetPlaced,ready,ended;int ticks,phase,age;
        Session(ServerPlayer p){server=p.getServer();host=p.getUUID();level=p.serverLevel();day=level.getDayTime();auth=server.usesAuthentication();
            spawning=server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);regen=server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);keepInventory=server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);}
    }
    private static Session session;private static boolean attempted;
    private static Saved save(ServerPlayer p){return new Saved(p.saveWithoutId(new CompoundTag()),p.serverLevel(),p.gameMode.getGameModeForPlayer(),p.position(),p.getDeltaMovement(),p.getYRot(),p.getXRot(),p.getMainArm(),
        p.serverLevel().getEntities(ModContent.WATER_BOLT.get(),b->b.getOwner()==p).stream().map(WaterBolt::getUUID).collect(java.util.stream.Collectors.toSet()),
        p.serverLevel().getEntities(ModContent.FIRE_BALL.get(),b->b.getOwner()==p).stream().map(FireBallProjectile::getUUID).collect(java.util.stream.Collectors.toSet()));}
    private static boolean ownerContext(ServerPlayer p){MinecraftServer s=p.getServer();return s!=null&&!s.isDedicatedServer()&&!s.isPublished()&&s.isSingleplayerOwner(p.getGameProfile())&&WORLD.equals(s.getWorldData().getLevelName())&&p.level().dimension()==Level.OVERWORLD&&p.isAlive()&&!p.isSpectator();}
    private static java.nio.file.Path recoveryPath(MinecraftServer server){
        return server.getWorldPath(LevelResource.ROOT).resolve("jr-fire-training-recovery.nbt");
    }
    /** Never replace the only pre-mutation snapshot left behind by an interrupted probe. */
    private static boolean recoveryAllowsStart(MinecraftServer server){
        var path=recoveryPath(server);
        try{
            // notExists is false for an indeterminate/inaccessible path: those must fail the read below.
            if(java.nio.file.Files.notExists(path))return true;
            CompoundTag previous=NbtIo.readCompressed(path,NbtAccounter.create(16L*1024*1024));
            if(!previous.contains("Restored",Tag.TAG_BYTE)||!previous.getBoolean("Restored")){
                LogUtils.getLogger().error("[JR_FIRE_TRAINING_PREVIEW] REFUSE_START unresolved recovery journal: {}. Previous snapshot preserved; no new scene or restoration was started.",path);
                return false;
            }
            LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] RECOVERY_CLEARED previous Restored=true: {}",path);
            return true;
        }catch(Exception failure){
            LogUtils.getLogger().error("[JR_FIRE_TRAINING_PREVIEW] REFUSE_START recovery journal cannot be read: "+path+". File preserved; no new scene or restoration was started.",failure);
            return false;
        }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){
        if(!enabled()||observer()||!(e.getEntity() instanceof ServerPlayer p))return;
        if(!attempted&&ownerContext(p)){
            attempted=true;
            // This guard runs before creating a session, capturing/replacing snapshots, or changing the scene.
            if(!recoveryAllowsStart(p.getServer()))return;
            session=new Session(p);session.saved.put(p.getUUID(),save(p));
            try{journal(session,false);}catch(Exception ex){finish(session,false,ex.toString());}
            return;
        }
        Session s=session;if(s!=null&&!s.ended&&s.server==p.getServer()&&mode().equals("host")&&p.getGameProfile().getName().equals("JRObserver")&&s.peer==null){s.peer=p.getUUID();s.saved.put(s.peer,save(p));try{journal(s,false);}catch(Exception ex){finish(s,false,ex.toString());}}
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){if(enabled())e.getDispatcher().register(Commands.literal("jr_fire_probe").requires(s->s.hasPermission(2)).then(Commands.literal("end").executes(c->{
        if(session!=null&&session.server==c.getSource().getServer())finish(session,false,"Probe aborted by owner command");return 1;})));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){Session s=session;if(s==null||s.ended||s.server!=e.getServer())return;
        try{ServerPlayer host=s.server.getPlayerList().getPlayer(s.host);if(host==null||!host.isAlive()||host.level()!=s.level){finish(s,false,"Owner left eligible scene");return;}
            s.ticks++;s.age++;
            if(s.ticks>6000)throw new IllegalStateException("Scene timeout");
            if(mode().equals("host")&&s.ticks==40){s.server.setUsesAuthentication(false);s.server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"),PORT);LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] LOOPBACK_READY port={}",PORT);}
            if(!s.ready&&s.ticks>=80&&(!mode().equals("host")||s.peer!=null))prepare(s);
            if(!s.ready)return;
            if(s.acks.size()==s.saved.size()){validate(s);LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] PHASE_PASS {} participants={}",s.phase,s.saved.size());if(s.phase==12)finish(s,true,"12 native fire training phases passed; phase 9 explicitly seeded");else phase(s,s.phase+1);}
        }catch(Exception ex){LogUtils.getLogger().error("[JR_FIRE_TRAINING_PREVIEW] FAILED",ex);finish(s,false,ex.toString());}}
    private static List<ServerPlayer> players(Session s){return s.saved.keySet().stream().map(id->s.server.getPlayerList().getPlayer(id)).filter(Objects::nonNull).toList();}
    private static void prepare(Session s)throws Exception{
        var area=new AABB(NPC_POS.x-0.6,NPC_POS.y,NPC_POS.z-0.6,NPC_POS.x+0.6,NPC_POS.y+2.7,NPC_POS.z+0.6);
        require(s.level.hasChunksAt(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))&&s.level.noCollision(area),"NPC site must be loaded and empty");
        require(s.level.getBlockState(BlockPos.containing(NPC_POS).below()).isFaceSturdy(s.level,BlockPos.containing(NPC_POS).below(),net.minecraft.core.Direction.UP),"NPC floor required");
        require(s.level.hasChunkAt(TARGET)&&s.level.getBlockState(TARGET).isAir()&&s.level.getBlockEntity(TARGET)==null,"Target fixture may replace only original air");
        s.targetBefore=s.level.getBlockState(TARGET);journal(s,false);
        // Keep only this already-loaded test scene resident until its saved NPC is precisely discarded.
        s.level.getChunkSource().addRegionTicket(SCENE_TICKET,s.sceneChunk,3,s.ticketOwner);s.ticketActive=true;
        s.targetPlaced=true;s.level.setBlock(TARGET,Blocks.TARGET.defaultBlockState(),Block.UPDATE_ALL);
        s.npc=ModContent.ROXY_TUTOR.get().create(s.level);require(s.npc!=null,"Formal NPC factory");s.npc.moveTo(NPC_POS.x,NPC_POS.y,NPC_POS.z,0,0);require(s.level.addFreshEntity(s.npc),"Formal NPC joins");require(s.npc.getTutorAnchor()!=null,"Formal NPC creates its own persisted anchor");require(s.npc!=null&&!s.npc.isRemoved()&&s.level.getEntity(s.npc.getUUID())==s.npc,"Scene NPC must actually join");journal(s,false);
        s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,s.server);s.level.setDayTime(6000);
        for(ServerPlayer p:players(s)){
            p.closeContainer();SpellCasting.openJournal(p);p.setGameMode(GameType.SURVIVAL);
            p.getInventory().clearContent();p.getInventory().setItem(0,ModContent.FIRE_TOME.get().getDefaultInstance());
            p.getInventory().setItem(1,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;
            p.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));p.setData(ModAttachments.CASTING,new CastingData());
            p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);seedInitial(p,p.getUUID().equals(s.host));near(s,p);sync(p);
        }
        s.ready=true;phase(s,1);
    }
    private static void seedInitial(ServerPlayer p,boolean host){
        MagicKnowledge knowledge=new MagicKnowledge();knowledge.learn(WaterMagic.SPELL);
        for(int i=0;i<(host?TrainingTrials.CAST_GOAL:2);i++)knowledge.practice(WaterMagic.SPELL);
        if(!host){knowledge.learn(FireMagic.SPELL);for(int i=0;i<2;i++)knowledge.practice(FireMagic.SPELL);}
        p.setData(ModAttachments.KNOWLEDGE,knowledge);
        p.setData(ModAttachments.TRAINING,new TrainingProgress(host?3:1,host,host,host?0:1));
    }
    /** Explicit branch fixture after the actual five releases. This is never reported as earned practice. */
    private static void seedDistanceBranch(ServerPlayer p,boolean host){
        var water=TutorGuidance.snapshot(p);
        MagicKnowledge knowledge=new MagicKnowledge();if(water.learned())knowledge.learn(WaterMagic.SPELL);
        for(int i=0;i<water.casts();i++)knowledge.practice(WaterMagic.SPELL);
        knowledge.learn(FireMagic.SPELL);for(int i=0;i<FireTrainingTrials.CAST_GOAL;i++)knowledge.practice(FireMagic.SPELL);
        p.setData(ModAttachments.KNOWLEDGE,knowledge);
        p.setData(ModAttachments.TRAINING,new TrainingProgress(water.hits(),water.charged(),water.claimed(),host?0:FireTrainingTrials.HIT_GOAL));
        LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] EXPLICIT_BRANCH_SEED player={} casts=5 hits={}",p.getGameProfile().getName(),host?0:3);
    }
    private static void near(Session s,ServerPlayer p){boolean host=p.getUUID().equals(s.host);Vec3 pos=s.npc.position().add(host?0:-2.4,0,host?2.4:0);p.teleportTo(s.level,pos.x,pos.y,pos.z,host?180:-90,12);p.setYHeadRot(p.getYRot());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}
    private static void away(Session s,ServerPlayer p,boolean close){p.teleportTo(s.level,206.5,-60,close?205.0:201.5,0,0);p.setYHeadRot(0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}
    private static void phase(Session s,int phase)throws Exception{
        s.phase=phase;s.age=0;s.acks.clear();s.responses.clear();s.sent.clear();s.before.clear();
        if(phase==12){s.npc.discard();for(ServerPlayer p:players(s))send(s,p,new State(phase,s.npc.getUUID(),false,REMOVED,"",0,0,true,"Exact temporary tutor removed"));return;}
        for(ServerPlayer p:players(s)){
            boolean host=p.getUUID().equals(s.host);SpellCasting.openJournal(p);p.setShiftKeyDown(false);
            if(phase==9)seedDistanceBranch(p,host);
            int action=WAIT,item=2,goal=0;
            switch(phase){
                case 1->{action=host?HOLD_QUERY:WAIT;item=host?1:3;}
                case 2->{action=!host||s.peer==null?QUERY:WAIT;item=host?1:3;}
                case 3->{action=host?STUDY:WAIT;item=host?1:3;}
                case 4->action=host?SELECT:WAIT;
                case 5->action=host?QUERY:WAIT;
                case 6->{action=host?PRACTICE:WAIT;goal=host?FireTrainingTrials.CAST_GOAL:0;}
                case 7->{action=QUERY;item=1;}
                case 8->{action=QUERY;item=3;}
                case 9->{action=QUERY;item=1;}
                case 10->{action=host?PRACTICE:WAIT;goal=host?FireTrainingTrials.CAST_GOAL+1:0;}
                case 11->{action=QUERY;item=host?1:3;}
                default->throw new IllegalStateException("Unknown phase "+phase);
            }
            p.getInventory().setItem(0,item==1?ModContent.FIRE_TOME.get().getDefaultInstance():item==3?ModContent.WATER_TOME.get().getDefaultInstance():ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;
            if(action==STUDY||action==PRACTICE||action==SELECT)away(s,p,phase==10&&host);else near(s,p);
            ManaData mana=p.getData(ModAttachments.MANA);p.setData(ModAttachments.MANA,new ManaData(mana.mana(),mana.maximum(),6000,mana.cooldown()));
            sync(p);s.before.put(p.getUUID(),check(p));
            String expected=action==QUERY||action==HOLD_QUERY?course(p):"";
            send(s,p,new State(phase,s.npc.getUUID(),true,action,expected,item,goal,true,"Fire training phase "+phase));
        }
        LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] PHASE {}",phase);
    }
    private static String course(ServerPlayer p){return FireTutorGuidance.isFireLesson(p)?"FIRE:"+FireTutorGuidance.snapshot(p).stage().name():"WATER:"+TutorGuidance.snapshot(p).stage().name();}
    private static Check check(ServerPlayer p){
        List<ItemStack> inventory=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)inventory.add(p.getInventory().getItem(i).copy());
        ManaData mana=p.getData(ModAttachments.MANA);
        return new Check(TutorGuidance.snapshot(p),FireTutorGuidance.snapshot(p),inventory,mana.mana(),mana.maximum(),p.getData(ModAttachments.CASTING).selectedSpell());
    }
    private static boolean sameInventory(List<ItemStack> a,List<ItemStack> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;}
    private static void validate(Session s){
        for(ServerPlayer p:players(s)){
            State state=s.sent.get(p.getUUID());
            if(state.action()==REMOVED){require(s.npc.isRemoved()&&s.level.getEntity(s.npc.getUUID())==null,"Exact NPC cleanup verified");continue;}
            Check old=s.before.get(p.getUUID()),now=check(p);var response=s.responses.get(p.getUUID());
            require(!p.isUsingItem()&&SpellCatalog.active(p)==null,"Every action settles item use and active spell");
            require(now.water.equals(old.water),"Every fire action and consultation preserves the existing water course");
            require(now.maximum==old.maximum&&sameInventory(now.inventory,old.inventory),"Actions do not replace inventory or maximum mana");
            if(state.action()==QUERY||state.action()==HOLD_QUERY){
                require(course(p).equals(state.expected()),"Server-authoritative course and stage match the actor");
                require(response!=null&&response.messages()==1,"One raw use press produces exactly one private reply, including a 45-tick hold");
            }else require(response!=null&&response.messages()==0,"Non-query actions receive no tutor messages");
            if(state.action()==STUDY){
                require(!old.fire.learned()&&now.fire.learned()&&now.fire.casts()==0&&now.fire.hits()==0&&now.mana==old.mana,"Actual book use teaches fire without spending, practice, or target hits");
            }else if(state.action()==SELECT){
                require(old.selected.equals(WaterMagic.SPELL)&&now.selected.equals(FireMagic.SPELL),"Actual G key changes water selection to learned fire");
                require(now.fire.equals(old.fire)&&now.mana==old.mana,"Cycling a spell neither practices nor spends mana");
            }else if(state.action()==PRACTICE){
                require(now.fire.casts()==state.goal(),"Exact requested number of native releases counted");
                require(now.fire.hits()==(s.phase==6?FireTrainingTrials.HIT_GOAL:0),"Distant fireballs count targets; close fireball never counts");
                require(now.mana<old.mana&&now.mana>=0,"Real survival fireballs pay mana normally");
                LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] ACTUAL_CASTS phase={} player={} casts={} hits={} mana={} previousMana={}",s.phase,p.getGameProfile().getName(),now.fire.casts(),now.fire.hits(),now.mana,old.mana);
            }else require(now.fire.equals(old.fire)&&now.mana==old.mana,"Private consultation is read-only for fire progress and mana");
            if(state.action()!=SELECT)require(now.selected.equals(old.selected),"Only the native selection phase may change selected spell");
            require(p.getInventory().countItem(ModContent.TRAINING_RECORD.get())==0,"No consultation issues a water reward");
        }
    }
    private static void send(Session s,ServerPlayer p,State state){s.sent.put(p.getUUID(),state);PacketDistributor.sendToPlayer(p,state);}
    private static void ack(ServerPlayer p,Ack a){Session s=session;if(s==null||s.ended||s.server!=p.getServer()||a.phase()!=s.phase||!s.saved.containsKey(p.getUUID()))return;
        State sent=s.sent.get(p.getUUID());if(sent==null)return;
        if(!a.passed()){finish(s,false,"Client "+p.getGameProfile().getName()+": "+a.detail());return;}
        boolean talks=sent.action()==QUERY||sent.action()==HOLD_QUERY;
        if(talks){if(a.messages()<1||a.messages()>4||!a.stage().equals(sent.expected())){finish(s,false,"Private native chat receipt mismatch");return;}}
        else if(a.messages()!=0){finish(s,false,"Private tutoring leaked into non-actor phase");return;}
        s.responses.put(p.getUUID(),a);s.acks.add(p.getUUID());LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] ACK player={} phase={} messages={} stage={} {}",p.getGameProfile().getName(),a.phase(),a.messages(),a.stage(),a.detail());
    }
    private static void sync(ServerPlayer p){p.syncData(ModAttachments.KNOWLEDGE);p.syncData(ModAttachments.MANA);p.syncData(ModAttachments.CASTING);p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();p.connection.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));}
    private static void journal(Session s,boolean restored)throws Exception{CompoundTag tag=new CompoundTag();tag.putBoolean("Restored",restored);tag.putString("Purpose","FIRE-C01 isolated native input fixture, original player data before mutation");tag.putLong("DayTime",s.day);tag.putBoolean("OriginalMobSpawning",s.spawning);tag.putBoolean("OriginalNaturalRegeneration",s.regen);tag.putBoolean("OriginalKeepInventory",s.keepInventory);tag.putBoolean("OriginalAuthentication",s.auth);
        ListTag list=new ListTag();for(var entry:s.saved.entrySet()){Saved v=entry.getValue();CompoundTag p=new CompoundTag();p.putUUID("UUID",entry.getKey());p.put("Player",v.nbt.copy());p.putString("MainArm",v.arm.name());p.putString("Dimension",v.level.dimension().location().toString());list.add(p);}tag.put("Players",list);
        if(s.npc!=null){tag.putUUID("TemporaryNpc",s.npc.getUUID());tag.put("TemporaryNpcPosition",NbtUtils.writeBlockPos(s.npc.blockPosition()));tag.putString("TemporaryNpcDimension",s.npc.level().dimension().location().toString());}if(s.targetBefore!=null){tag.put("TargetPosition",NbtUtils.writeBlockPos(TARGET));tag.put("TargetOriginal",NbtUtils.writeBlockState(s.targetBefore));}
        var destination=recoveryPath(s.server);
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
        catch(Exception ex){LogUtils.getLogger().error("[JR_FIRE_TRAINING_PREVIEW] RESTORE_FAILED "+part,ex);return false;}
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
            restored&=restoreStep("NPC entity",()->{
                boolean found=false;
                for(ServerLevel level:s.server.getAllLevels()){
                    Entity live=level.getEntity(s.npc.getUUID());
                    if(live==null)continue;
                    require(live instanceof RoxyTutorEntity,"Temporary NPC identity must remain a tutor");
                    live.discard();found=true;
                    require(level.getEntity(s.npc.getUUID())==null,"Exact temporary NPC deregistered");
                }
                // An unloaded persistent entity is still on disk; an old reference's isRemoved is not proof.
                require(found||s.npc.getRemovalReason()==Entity.RemovalReason.DISCARDED
                        ||s.npc.getRemovalReason()==Entity.RemovalReason.KILLED,
                        "Temporary NPC is not loaded; preserve recovery journal instead of claiming restored");
            });
        }
        if(s.targetPlaced)restored&=restoreStep("target",()->{
            // Reload only the exact journaled fixture chunk if the player left the scene.
            s.level.getChunkAt(TARGET);
            s.level.setBlock(TARGET,s.targetBefore,Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS);
            require(s.level.getBlockState(TARGET).equals(s.targetBefore),"Target original state restored");
        });
        for(var entry:s.saved.entrySet()){
            UUID uuid=entry.getKey();
            ServerPlayer p=s.server.getPlayerList().getPlayer(uuid);
            if(p==null){
                restored=false;
                LogUtils.getLogger().error("[JR_FIRE_TRAINING_PREVIEW] RESTORE_FAILED missing player {}",uuid);
                continue;
            }
            Saved old=entry.getValue();
            restored&=restoreStep("player "+uuid+" use",()->{p.closeContainer();SpellCasting.openJournal(p);});
            restored&=restoreStep("player "+uuid+" water projectiles",()->
                s.level.getEntities(ModContent.WATER_BOLT.get(),b->b.getOwner()==p&&!old.bolts.contains(b.getUUID())).forEach(WaterBolt::discard));
            restored&=restoreStep("player "+uuid+" fire projectiles",()->
                s.level.getEntities(ModContent.FIRE_BALL.get(),b->b.getOwner()==p&&!old.fireballs.contains(b.getUUID())).forEach(FireBallProjectile::discard));
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
        if(s.ticketActive)restored&=restoreStep("scene ticket",()->{s.level.getChunkSource().removeRegionTicket(SCENE_TICKET,s.sceneChunk,3,s.ticketOwner);s.ticketActive=false;});
        final boolean snapshotRestored=restored;
        restored&=restoreStep("recovery journal",()->journal(s,snapshotRestored));
        String message=reason+" restored="+restored;
        if(message.length()>512)message=message.substring(0,512);
        State result=new State(FINISH,s.npc==null?new UUID(0,0):s.npc.getUUID(),false,WAIT,"",0,0,passed&&restored,message);
        for(ServerPlayer p:players(s)){
            // A departed connection may reject the final notice; it must not interrupt other notices.
            try{PacketDistributor.sendToPlayer(p,result);}
            catch(Exception ex){LogUtils.getLogger().warn("[JR_FIRE_TRAINING_PREVIEW] Final notice unavailable for "+p.getUUID(),ex);}
        }
        LogUtils.getLogger().info("[JR_FIRE_TRAINING_PREVIEW] {} phases={} players={} restored={} {}",passed&&restored?"PASS":"FAIL",s.phase,s.saved.size(),restored,reason);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(session!=null&&session.saved.containsKey(e.getEntity().getUUID()))finish(session,false,"Participant logout");}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){if(session!=null&&session.server==e.getServer())finish(session,false,"Server stop");}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){
        if(session!=null&&session.server!=e.getServer())return;
        session=null;attempted=false;
    }

    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private FireTrainingPreview(){}
}
