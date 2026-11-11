package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.*;
import dev.joblessreincarnation.training.TrainingProgress;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Opt-in, private singleplayer fixture. Only the client key path exercises the item under test. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID)
public final class TutorInputPreview {
    public static final String WORLD="JR Magic 0.2.0 Test";
    public static final int FINISH=99;
    public static final Vec3 POSITION=new Vec3(206.5,-60,184.5);
    public static final BlockPos FLOOR=new BlockPos(206,-61,186);
    public static volatile Consumer<State> clientReceiver=ignored->{};
    public static boolean enabled(){return Boolean.getBoolean("jobless_reincarnation.tutorInputProbe");}
    public static final int PHASES=12;
    private static final TicketType<UUID> SCENE_TICKET=TicketType.create("jr_tutor_input_probe",UUID::compareTo);
    public static String name(int phase){return switch(phase){
        case 1->"unknown tome / 112-tick tutor hold";case 2->"staff / move aim to air";
        case 3->"unknown water tome / torch / move to floor";case 4->"hotbar switch / second tutor / air";
        case 5->"keyboard binding / REPEAT and duplicate PRESS";case 6->"middle mouse binding / release and new press";
        case 7->"ordinary food first / drag aim to tutor";case 8->"screen close / stale click / new press";
        case 9->"K journal / stale click / new press";case 10->"focus loss / stale click / new press";
        case 11->"rebind during hold / stale click / new press";case 12->"binding context inactive PRESS / active REPEAT / fresh PRESS";default->"finish";};}
    public static Item item(int phase){return switch(phase){case 2->ModContent.TRAINING_STAFF.get();case 3,4,6->ModContent.WATER_TOME.get();case 7->Items.BREAD;default->ModContent.FIRE_TOME.get();};}
    public static int replies(int phase){return phase==7?0:phase==12?1:phase==6||phase>=8?2:1;}
    public record State(int phase,int holdTicks,UUID npc,UUID otherNpc,boolean passed,String detail) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_tutor_input_state"));
        public static final StreamCodec<FriendlyByteBuf,State> CODEC=CustomPacketPayload.codec(State::write,State::new);
        private State(FriendlyByteBuf b){this(b.readVarInt(),b.readVarInt(),b.readUUID(),b.readUUID(),b.readBoolean(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeVarInt(holdTicks);b.writeUUID(npc);b.writeUUID(otherNpc);b.writeBoolean(passed);b.writeUtf(detail,512);}
        @Override public Type<State> type(){return TYPE;}
    }
    public record Ack(int phase,boolean passed,int overlays,int swings,int uses,int offhand,String detail) implements CustomPacketPayload {
        public static final Type<Ack> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_tutor_input_ack"));
        public static final StreamCodec<FriendlyByteBuf,Ack> CODEC=CustomPacketPayload.codec(Ack::write,Ack::new);
        private Ack(FriendlyByteBuf b){this(b.readVarInt(),b.readBoolean(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeBoolean(passed);b.writeVarInt(overlays);b.writeVarInt(swings);b.writeVarInt(uses);b.writeVarInt(offhand);b.writeUtf(detail,512);}
        @Override public Type<Ack> type(){return TYPE;}
    }
    @EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Network {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("dev-tutor-input-1")
            .playToClient(State.TYPE,State.CODEC,(p,c)->clientReceiver.accept(p))
            .playToServer(Ack.TYPE,Ack.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)ack(player,p);});}
    }
    private record Saved(CompoundTag nbt,ServerLevel level,GameType mode,Vec3 pos,Vec3 motion,float yaw,float pitch,HumanoidArm arm){}
    private static final class Session {
        final MinecraftServer server;final UUID owner;final ServerLevel level;final Saved saved;
        final long day;final boolean spawning,regen,keepInventory;
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        RoxyTutorEntity npc,otherNpc;
        final ChunkPos sceneChunk=new ChunkPos(FLOOR);final UUID ticketOwner=UUID.randomUUID();boolean ticketActive;
        List<ItemStack> inventory=List.of();int phase,ticks,age;boolean ready,ended;
        Session(ServerPlayer p){server=p.getServer();owner=p.getUUID();level=p.serverLevel();saved=new Saved(p.saveWithoutId(new CompoundTag()),level,p.gameMode.getGameModeForPlayer(),p.position(),p.getDeltaMovement(),p.getYRot(),p.getXRot(),p.getMainArm());
            day=level.getDayTime();spawning=server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);regen=server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);keepInventory=server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);}
    }
    private static Session session;private static boolean attempted;
    private static Path recovery(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("jr-tutor-input-recovery.nbt");}
    private static boolean privateOwner(ServerPlayer p){MinecraftServer s=p.getServer();return s!=null&&privatePath(s)&&!s.isDedicatedServer()&&!s.isPublished()&&s.getPlayerList().getPlayerCount()==1&&s.isSingleplayerOwner(p.getGameProfile())&&WORLD.equals(s.getWorldData().getLevelName())&&p.level().dimension()==Level.OVERWORLD&&p.isAlive()&&!p.isSpectator();}
    private static boolean privatePath(MinecraftServer server){Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();return world.getParent()!=null&&world.getParent().getFileName().toString().equals("saves")&&world.getParent().getParent()!=null&&world.getParent().getParent().getFileName().toString().equals("run-tutor-input");}
    private static boolean journalAvailable(MinecraftServer server){try{Path file=recovery(server);if(Files.notExists(file))return true;CompoundTag tag=NbtIo.readCompressed(file,NbtAccounter.create(16L*1024*1024));require(tag.contains("Restored",Tag.TAG_BYTE)&&tag.getBoolean("Restored"),"Unresolved recovery journal preserved: "+file);return true;}catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_INPUT_PREVIEW] REFUSE_START",ex);return false;}}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(!enabled()||attempted||!(e.getEntity() instanceof ServerPlayer p)||!privateOwner(p))return;attempted=true;if(!journalAvailable(p.getServer()))return;session=new Session(p);try{journal(session,false);}catch(Exception ex){finish(session,false,"Initial journal failed: "+ex);}}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){Session s=session;if(s==null||s.ended||s.server!=e.getServer())return;try{
        ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);require(p!=null&&privateOwner(p)&&p.serverLevel()==s.level,"Private owner/context lost");
        s.ticks++;s.age++;require(s.ticks<=5000,"Scene timeout");require(s.age<=600,"Phase timeout");
        if(!s.ready&&s.ticks>=60)prepare(s,p);
    }catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_INPUT_PREVIEW] FAILED",ex);finish(s,false,ex.toString());}}
    private static void prepare(Session s,ServerPlayer p)throws Exception{
        require(s.level.hasChunkAt(BlockPos.containing(POSITION))&&s.level.hasChunkAt(FLOOR),"Private test scene must already be loaded");
        BlockState floor=s.level.getBlockState(FLOOR);
        require((floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT)||floor.is(Blocks.STONE)||floor.is(Blocks.BEDROCK))&&floor.isFaceSturdy(s.level,FLOOR,Direction.UP)&&s.level.getBlockEntity(FLOOR)==null,"Plain non-interactive solid floor required");
        require(s.level.noCollision(p,p.getBoundingBox().move(POSITION.subtract(p.position()))),"Private player site must be empty");
        for(BlockPos mutable:BlockPos.betweenClosed(FLOOR.offset(-1,0,-1),FLOOR.offset(1,2,1))){BlockPos pos=mutable.immutable();require(s.level.getBlockEntity(pos)==null,"No block entities in rollback footprint");s.blocks.put(pos,s.level.getBlockState(pos));}
        require(s.level.getBlockState(FLOOR.above()).isAir(),"Torch test requires original air above floor");
        journal(s,false);
        s.level.getChunkSource().addRegionTicket(SCENE_TICKET,s.sceneChunk,3,s.ticketOwner);s.ticketActive=true;
        s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,s.server);s.level.setDayTime(6000);
        p.closeContainer();SpellCasting.openJournal(p);p.removeAllEffects();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);p.getFoodData().setExhaustion(0);
        p.teleportTo(s.level,POSITION.x,POSITION.y,POSITION.z,0,0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        s.npc=createNpc(s,POSITION.add(0,0,1.8));s.otherNpc=createNpc(s,POSITION.add(1.8,0,1.8));
        journal(s,false); // Persist exact ownership before either NPC can enter a saved chunk.
        require(s.level.addFreshEntity(s.npc),"First formal NPC joins private world");
        require(s.level.addFreshEntity(s.otherNpc),"Second formal NPC joins private world");
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_PREVIEW] EXPLICIT_SCENE private NPCs NoAI=true; native input only; facing/animation reserved for independent AI-enabled two-player check");
        s.ready=true;phase(s,p,1);
    }
    private static RoxyTutorEntity createNpc(Session s,Vec3 pos){RoxyTutorEntity npc=ModContent.ROXY_TUTOR.get().create(s.level);require(npc!=null,"Formal NPC factory");
        npc.moveTo(pos.x,pos.y,pos.z,180,0);npc.setNoAi(true);npc.setNoGravity(true);npc.setInvulnerable(true);return npc;}
    private static void phase(Session s,ServerPlayer p,int phase){s.phase=phase;s.age=0;p.stopUsingItem();p.getInventory().clearContent();
        p.getInventory().setItem(0,phase==7?new ItemStack(Items.BREAD,16):item(phase).getDefaultInstance());p.getInventory().setItem(1,ModContent.TRAINING_STAFF.get().getDefaultInstance());p.getInventory().selected=0;
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(phase==3?Items.TORCH:Items.BREAD,16));
        p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);p.getFoodData().setExhaustion(0);
        MagicKnowledge knowledge=new MagicKnowledge();if(phase==2||phase==4)knowledge.learn(SpellCatalog.FIRE_BALL.id());
        p.setData(ModAttachments.KNOWLEDGE,knowledge);p.setData(ModAttachments.CASTING,new CastingData());p.setData(ModAttachments.TRAINING,new TrainingProgress());p.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));
        p.teleportTo(s.level,POSITION.x,POSITION.y,POSITION.z,0,0);p.setDeltaMovement(Vec3.ZERO);
        sync(p);s.inventory=inventory(p);
        PacketDistributor.sendToPlayer(p,new State(phase,phase==6||phase>=8?200:112,s.npc.getUUID(),s.otherNpc.getUUID(),true,name(phase)));
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_PREVIEW] PHASE {} {}",phase,name(phase));
    }
    private static List<ItemStack> inventory(ServerPlayer p){List<ItemStack> result=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)result.add(p.getInventory().getItem(i).copy());return result;}
    private static void ack(ServerPlayer p,Ack a){Session s=session;if(!enabled()||s==null||s.ended||p.getServer()!=s.server||!p.getUUID().equals(s.owner)||a.phase()!=s.phase)return;
        if(!a.passed()){finish(s,false,"Client: "+a.detail());return;}
        try{require(a.offhand()==0,"Tutor hold fell through to offhand");require(a.overlays()==replies(s.phase),"Actual private reply count differs from new-press count");
            require(a.swings()==replies(s.phase),"Actual swing count differs from consultation count");
            require(s.phase==7?a.uses()>0:a.uses()==0,"Unexpected item use count");require(!p.isUsingItem(),"No residual item use");
            require(p.getData(ModAttachments.MANA).mana()==100,"Consultation spent mana");
            var after=inventory(p);for(int i=0;i<after.size();i++)if(s.phase!=7||i!=0)require(ItemStack.matches(s.inventory.get(i),after.get(i)),"Inventory changed at slot "+i);
            if(s.phase==7){ItemStack bread=p.getInventory().getItem(0);int eaten=16-bread.getCount();require(ItemStack.isSameItemSameComponents(s.inventory.getFirst(),bread)&&eaten>=1&&eaten<=2,"Only one or two main-hand bread items may be consumed");require(p.getFoodData().getFoodLevel()==Math.min(20,10+5*eaten),"Food gain must match the consumed main-hand bread");}
            else require(p.getFoodData().getFoodLevel()==10,"Offhand food unexpectedly consumed");
            for(var entry:s.blocks.entrySet())require(s.level.getBlockState(entry.getKey()).equals(entry.getValue()),"Unexpected block placement "+entry.getKey());
            TrainingProgress training=p.getData(ModAttachments.TRAINING);require(training.targetHits()==0&&!training.chargedTargetHit()&&!training.claimed()&&training.fireTargetHits()==0,"Consultation changed training progress");
            for(SpellCatalog spell:SpellCatalog.values()){boolean expected=spell==SpellCatalog.FIRE_BALL&&(s.phase==2||s.phase==4);require(spell.knows(p)==expected&&spell.practiceCount(p)==0,"Unexpected knowledge/practice change "+spell);}
            LogUtils.getLogger().info("[JR_TUTOR_INPUT_PREVIEW] PHASE_PASS {} privateReplies={} swings={} itemUses={} offhand={} {}",a.phase(),a.overlays(),a.swings(),a.uses(),a.offhand(),a.detail());
            if(s.phase==PHASES)finish(s,true,"12 native input phases passed; actual packet observation and server state verified");else phase(s,p,s.phase+1);
        }catch(Exception ex){finish(s,false,ex.toString());}
    }
    private static void sync(ServerPlayer p){
        // A repeated fixture value may equal ServerPlayer lastSentFood, while the client predicted hunger differently.
        // Send vanilla authoritative health/food explicitly for every phase and for full snapshot restoration.
        p.connection.send(new ClientboundSetHealthPacket(p.getHealth(),p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel()));
        p.syncData(ModAttachments.KNOWLEDGE);p.syncData(ModAttachments.MANA);p.syncData(ModAttachments.CASTING);p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();p.connection.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));}
    private static void journal(Session s,boolean restored)throws Exception{CompoundTag tag=new CompoundTag();tag.putBoolean("Restored",restored);tag.putString("Purpose","C03 private native tutor input fixture; original full player snapshot before mutation");tag.putLong("DayTime",s.day);tag.putBoolean("OriginalMobSpawning",s.spawning);tag.putBoolean("OriginalNaturalRegeneration",s.regen);tag.putBoolean("OriginalKeepInventory",s.keepInventory);
        CompoundTag saved=new CompoundTag();saved.putUUID("UUID",s.owner);saved.put("Player",s.saved.nbt.copy());saved.putString("Dimension",s.saved.level.dimension().location().toString());saved.putString("MainArm",s.saved.arm.name());ListTag players=new ListTag();players.add(saved);tag.put("Players",players);
        ListTag blocks=new ListTag();for(var entry:s.blocks.entrySet()){CompoundTag b=new CompoundTag();b.put("Position",NbtUtils.writeBlockPos(entry.getKey()));b.put("Original",NbtUtils.writeBlockState(entry.getValue()));blocks.add(b);}tag.put("Blocks",blocks);
        ListTag npcs=new ListTag();for(RoxyTutorEntity npc:new RoxyTutorEntity[]{s.npc,s.otherNpc})if(npc!=null){CompoundTag id=new CompoundTag();id.putUUID("UUID",npc.getUUID());npcs.add(id);}tag.put("CreatedNPCs",npcs);
        Path destination=recovery(s.server),pending=destination.resolveSibling(destination.getFileName()+".pending");NbtIo.writeCompressed(tag,pending);try{Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(pending,destination,StandardCopyOption.REPLACE_EXISTING);}}
    @FunctionalInterface private interface RestoreAction{void run()throws Exception;}
    private static boolean restore(String part,RestoreAction action){try{action.run();return true;}catch(Exception ex){LogUtils.getLogger().error("[JR_TUTOR_INPUT_PREVIEW] RESTORE_FAILED "+part,ex);return false;}}
    private static void finish(Session s,boolean passed,String reason){if(s==null||s.ended)return;s.ended=true;boolean restored=true;ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);
        for(RoxyTutorEntity npc:new RoxyTutorEntity[]{s.npc,s.otherNpc})if(npc!=null)restored&=restore("created NPC "+npc.getUUID(),()->{
            Entity current=s.level.getEntity(npc.getUUID());
            require(current==null||current==npc,"Created NPC identity changed; preserve recovery journal");
            if(current==npc){npc.discard();require(s.level.getEntity(npc.getUUID())==null,"Created NPC removal did not complete");}
            else require(!npc.isAddedToLevel()&&(npc.getRemovalReason()==null||npc.getRemovalReason()==Entity.RemovalReason.DISCARDED||npc.getRemovalReason()==Entity.RemovalReason.KILLED),"Created NPC may remain saved outside the scene; preserve recovery journal");
        });
        restored&=restore("precise 27-block footprint",()->{require(!s.server.isPublished()&&s.server.getPlayerList().getPlayerCount()<=1,"Unsafe concurrent scene: preserve recovery journal");for(var entry:s.blocks.entrySet()){s.level.getChunkAt(entry.getKey());if(!s.level.getBlockState(entry.getKey()).equals(entry.getValue()))s.level.setBlock(entry.getKey(),entry.getValue(),Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS);require(s.level.getBlockState(entry.getKey()).equals(entry.getValue()),"Original fixture block restore");}});
        restored&=restore("player snapshot",()->{require(p!=null,"Owner unavailable for full NBT restore");p.closeContainer();SpellCasting.openJournal(p);p.removeAllEffects();p.removeData(ModAttachments.KNOWLEDGE);p.removeData(ModAttachments.MANA);p.removeData(ModAttachments.CASTING);p.removeData(ModAttachments.TRAINING);p.load(s.saved.nbt.copy());p.setGameMode(s.saved.mode);p.setMainArm(s.saved.arm);if(p.hasData(ModAttachments.CASTING))p.getData(ModAttachments.CASTING).clearActive();p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());if(p.hasData(ModAttachments.MANA)&&p.getData(ModAttachments.MANA).cooldown()>0)p.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(),p.getData(ModAttachments.MANA).cooldown());});
        restored&=restore("position and synchronization",()->{require(p!=null,"Owner unavailable");p.teleportTo(s.saved.level,s.saved.pos.x,s.saved.pos.y,s.saved.pos.z,s.saved.yaw,s.saved.pitch);p.setDeltaMovement(s.saved.motion);p.onUpdateAbilities();sync(p);for(var effect:p.getActiveEffects())p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(),effect,false));});
        restored&=restore("day",()->s.level.setDayTime(s.day));restored&=restore("mob spawning",()->s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(s.spawning,s.server));restored&=restore("regeneration",()->s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(s.regen,s.server));restored&=restore("keep inventory",()->s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(s.keepInventory,s.server));
        if(s.ticketActive)restored&=restore("scene ticket",()->{s.level.getChunkSource().removeRegionTicket(SCENE_TICKET,s.sceneChunk,3,s.ticketOwner);s.ticketActive=false;});
        boolean stateRestored=restored;restored&=restore("journal",()->journal(s,stateRestored));String detail=reason+" restored="+restored;if(detail.length()>510)detail=detail.substring(0,510);
        if(p!=null)try{PacketDistributor.sendToPlayer(p,new State(FINISH,0,new UUID(0,0),new UUID(0,0),passed&&restored,detail));}catch(Exception ex){LogUtils.getLogger().warn("[JR_TUTOR_INPUT_PREVIEW] Final client notice unavailable",ex);}
        LogUtils.getLogger().info("[JR_TUTOR_INPUT_PREVIEW] {} phases={} restored={} {}",passed&&restored?"PASS":"FAIL",s.phase,restored,reason);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(session!=null&&session.owner.equals(e.getEntity().getUUID()))finish(session,false,"Owner logout");}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){if(session!=null&&session.server==e.getServer())finish(session,false,"Server stop");}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){if(session!=null&&session.server!=e.getServer())return;session=null;attempted=false;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private TutorInputPreview(){}
}
