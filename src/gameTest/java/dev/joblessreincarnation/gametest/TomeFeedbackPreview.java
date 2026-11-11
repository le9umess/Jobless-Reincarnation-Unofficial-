package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.*;
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
public final class TomeFeedbackPreview {
    public static final String WORLD="JR Magic 0.2.0 Test";
    public static final int FINISH=99;
    public static final Vec3 POSITION=new Vec3(206.5,-60,184.5);
    public static final BlockPos FLOOR=new BlockPos(206,-61,186);
    public static volatile Consumer<State> clientReceiver=ignored->{};
    public static boolean enabled(){return Boolean.getBoolean("jobless_reincarnation.tomeFeedbackProbe");}
    public static SpellCatalog spell(int phase){return switch(phase){case 2,7->SpellCatalog.WATER_BOLT;case 3->SpellCatalog.EARTH_WALL;case 4->SpellCatalog.AIR_BURST;case 5->SpellCatalog.HEALING;default->SpellCatalog.FIRE_BALL;};}
    public static Item tome(int phase){return switch(spell(phase)){case WATER_BOLT->ModContent.WATER_TOME.get();case EARTH_WALL->ModContent.EARTH_TOME.get();case AIR_BURST->ModContent.AIR_TOME.get();case HEALING->ModContent.HEALING_TOME.get();case FIRE_BALL->ModContent.FIRE_TOME.get();case WATER_SHIELD->ModContent.WATER_SHIELD_TOME.get();case MIRE->ModContent.MIRE_TOME.get();case STONE_CANNON->ModContent.STONE_CANNON_TOME.get();};}
    public static String knownKey(int phase){return "message.jobless_reincarnation."+switch(spell(phase)){case WATER_BOLT->"already_learned";case EARTH_WALL->"earth_already_learned";case AIR_BURST->"air_already_learned";case HEALING->"healing_already_learned";case FIRE_BALL->"fire_already_learned";case WATER_SHIELD->"water_shield.already_learned";case MIRE->"mire.already_learned";case STONE_CANNON->"stone_cannon.already_learned";};}
    public record State(int phase,int holdTicks,boolean floor,boolean passed,String detail) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_tome_feedback_state"));
        public static final StreamCodec<FriendlyByteBuf,State> CODEC=CustomPacketPayload.codec(State::write,State::new);
        private State(FriendlyByteBuf b){this(b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readBoolean(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeVarInt(holdTicks);b.writeBoolean(floor);b.writeBoolean(passed);b.writeUtf(detail,512);}
        @Override public Type<State> type(){return TYPE;}
    }
    public record Ack(int phase,boolean passed,int overlays,int swings,int uses,int offhand,String detail) implements CustomPacketPayload {
        public static final Type<Ack> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"dev_tome_feedback_ack"));
        public static final StreamCodec<FriendlyByteBuf,Ack> CODEC=CustomPacketPayload.codec(Ack::write,Ack::new);
        private Ack(FriendlyByteBuf b){this(b.readVarInt(),b.readBoolean(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(512));}
        private void write(FriendlyByteBuf b){b.writeVarInt(phase);b.writeBoolean(passed);b.writeVarInt(overlays);b.writeVarInt(swings);b.writeVarInt(uses);b.writeVarInt(offhand);b.writeUtf(detail,512);}
        @Override public Type<Ack> type(){return TYPE;}
    }
    @EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Network {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("dev-tome-feedback-1")
            .playToClient(State.TYPE,State.CODEC,(p,c)->clientReceiver.accept(p))
            .playToServer(Ack.TYPE,Ack.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)ack(player,p);});}
    }
    private record Saved(CompoundTag nbt,ServerLevel level,GameType mode,Vec3 pos,Vec3 motion,float yaw,float pitch,HumanoidArm arm){}
    private static final class Session {
        final MinecraftServer server;final UUID owner;final ServerLevel level;final Saved saved;
        final long day;final boolean spawning,regen,keepInventory;
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        List<ItemStack> inventory=List.of();int phase,ticks,age;boolean ready,ended;
        Session(ServerPlayer p){server=p.getServer();owner=p.getUUID();level=p.serverLevel();saved=new Saved(p.saveWithoutId(new CompoundTag()),level,p.gameMode.getGameModeForPlayer(),p.position(),p.getDeltaMovement(),p.getYRot(),p.getXRot(),p.getMainArm());
            day=level.getDayTime();spawning=server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);regen=server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);keepInventory=server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);}
    }
    private static Session session;private static boolean attempted;
    private static Path recovery(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("jr-tome-feedback-recovery.nbt");}
    private static boolean privateOwner(ServerPlayer p){MinecraftServer s=p.getServer();return s!=null&&privatePath(s)&&!s.isDedicatedServer()&&!s.isPublished()&&s.getPlayerList().getPlayerCount()==1&&s.isSingleplayerOwner(p.getGameProfile())&&WORLD.equals(s.getWorldData().getLevelName())&&p.level().dimension()==Level.OVERWORLD&&p.isAlive()&&!p.isSpectator();}
    private static boolean privatePath(MinecraftServer server){Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();return world.getParent()!=null&&world.getParent().getFileName().toString().equals("saves")&&world.getParent().getParent()!=null&&world.getParent().getParent().getFileName().toString().equals("run-tome-feedback");}
    private static boolean journalAvailable(MinecraftServer server){try{Path file=recovery(server);if(Files.notExists(file))return true;CompoundTag tag=NbtIo.readCompressed(file,NbtAccounter.create(16L*1024*1024));require(tag.contains("Restored",Tag.TAG_BYTE)&&tag.getBoolean("Restored"),"Unresolved recovery journal preserved: "+file);return true;}catch(Exception ex){LogUtils.getLogger().error("[JR_TOME_FEEDBACK_PREVIEW] REFUSE_START",ex);return false;}}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(!enabled()||attempted||!(e.getEntity() instanceof ServerPlayer p)||!privateOwner(p))return;attempted=true;if(!journalAvailable(p.getServer()))return;session=new Session(p);try{journal(session,false);}catch(Exception ex){finish(session,false,"Initial journal failed: "+ex);}}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){Session s=session;if(s==null||s.ended||s.server!=e.getServer())return;try{
        ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);require(p!=null&&privateOwner(p)&&p.serverLevel()==s.level,"Private owner/context lost");
        s.ticks++;s.age++;require(s.ticks<=3000,"Scene timeout");require(s.age<=600,"Phase timeout");
        if(!s.ready&&s.ticks>=60)prepare(s,p);
    }catch(Exception ex){LogUtils.getLogger().error("[JR_TOME_FEEDBACK_PREVIEW] FAILED",ex);finish(s,false,ex.toString());}}
    private static void prepare(Session s,ServerPlayer p)throws Exception{
        require(s.level.hasChunkAt(BlockPos.containing(POSITION))&&s.level.hasChunkAt(FLOOR),"Private test scene must already be loaded");
        BlockState floor=s.level.getBlockState(FLOOR);
        require((floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT)||floor.is(Blocks.STONE)||floor.is(Blocks.BEDROCK))&&floor.isFaceSturdy(s.level,FLOOR,Direction.UP)&&s.level.getBlockEntity(FLOOR)==null,"Plain non-interactive solid floor required");
        require(s.level.noCollision(p,p.getBoundingBox().move(POSITION.subtract(p.position()))),"Private player site must be empty");
        for(BlockPos mutable:BlockPos.betweenClosed(FLOOR.offset(-1,0,-1),FLOOR.offset(1,2,1))){BlockPos pos=mutable.immutable();require(s.level.getBlockEntity(pos)==null,"No block entities in rollback footprint");s.blocks.put(pos,s.level.getBlockState(pos));}
        require(s.level.getBlockState(FLOOR.above()).isAir(),"Torch test requires original air above floor");
        journal(s,false);
        s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,s.server);s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,s.server);s.level.setDayTime(6000);
        p.closeContainer();SpellCasting.openJournal(p);p.removeAllEffects();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);p.getFoodData().setExhaustion(0);
        MagicKnowledge knowledge=new MagicKnowledge();for(SpellCatalog spell:SpellCatalog.values())if(spell!=SpellCatalog.FIRE_BALL)knowledge.learn(spell.id());
        p.setData(ModAttachments.KNOWLEDGE,knowledge);p.setData(ModAttachments.CASTING,new CastingData());p.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));
        p.teleportTo(s.level,POSITION.x,POSITION.y,POSITION.z,0,0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        LogUtils.getLogger().info("[JR_TOME_FEEDBACK_PREVIEW] EXPLICIT_SEED water/earth/air/healing learned; FIRE remains unknown; food=10; no practice/rewards granted");
        s.ready=true;phase(s,p,1);
    }
    private static void phase(Session s,ServerPlayer p,int phase){s.phase=phase;s.age=0;p.stopUsingItem();p.getInventory().setItem(0,tome(phase).getDefaultInstance());p.getInventory().selected=0;
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,phase==3?new ItemStack(Items.TORCH,16):new ItemStack(Items.BREAD,16));
        sync(p);s.inventory=inventory(p);
        PacketDistributor.sendToPlayer(p,new State(phase,phase==1?120:112,phase==3,true,"Native hold, phase "+phase));
        LogUtils.getLogger().info("[JR_TOME_FEEDBACK_PREVIEW] PHASE {} spell={} offhand={} floor={}",phase,spell(phase),p.getOffhandItem(),phase==3);
    }
    private static List<ItemStack> inventory(ServerPlayer p){List<ItemStack> result=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)result.add(p.getInventory().getItem(i).copy());return result;}
    private static void ack(ServerPlayer p,Ack a){Session s=session;if(!enabled()||s==null||s.ended||p.getServer()!=s.server||!p.getUUID().equals(s.owner)||a.phase()!=s.phase)return;
        if(!a.passed()){finish(s,false,"Client: "+a.detail());return;}
        try{require(a.offhand()==0,"Suppressed main hand fell through to offhand");require(a.overlays()>=1&&a.overlays()<=5,"Overlay count outside bounded window");require(a.swings()>=1&&a.swings()<=5,"Swing count outside bounded window");require(a.uses()>=10,"Native repeated use must actually reach the server");
            require(FireMagic.isLearned(p),"Real fire study was not committed");require(!p.isUsingItem(),"No residual item use after hold release");require(p.getFoodData().getFoodLevel()==10,"Offhand food must remain uneaten");
            var after=inventory(p);for(int i=0;i<after.size();i++)require(ItemStack.matches(s.inventory.get(i),after.get(i)),"Inventory changed at slot "+i);
            for(var entry:s.blocks.entrySet())require(s.level.getBlockState(entry.getKey()).equals(entry.getValue()),"Offhand torch unexpectedly placed at "+entry.getKey());
            for(SpellCatalog spell:SpellCatalog.values())require(spell.knows(p)&&spell.practiceCount(p)==0,"Study fixture must not practice or erase another spell");
            LogUtils.getLogger().info("[JR_TOME_FEEDBACK_PREVIEW] PHASE_PASS {} overlays={} swings={} usePackets={} offhand={} {}",a.phase(),a.overlays(),a.swings(),a.uses(),a.offhand(),a.detail());
            if(s.phase==7)finish(s,true,"7 native feedback phases passed; fire naturally learned, four spells explicitly seeded");else phase(s,p,s.phase+1);
        }catch(Exception ex){finish(s,false,ex.toString());}
    }
    private static void sync(ServerPlayer p){p.syncData(ModAttachments.KNOWLEDGE);p.syncData(ModAttachments.MANA);p.syncData(ModAttachments.CASTING);p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();p.connection.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));}
    private static void journal(Session s,boolean restored)throws Exception{CompoundTag tag=new CompoundTag();tag.putBoolean("Restored",restored);tag.putString("Purpose","C02 private native tome input fixture; original full player snapshot before mutation");tag.putLong("DayTime",s.day);tag.putBoolean("OriginalMobSpawning",s.spawning);tag.putBoolean("OriginalNaturalRegeneration",s.regen);tag.putBoolean("OriginalKeepInventory",s.keepInventory);
        CompoundTag saved=new CompoundTag();saved.putUUID("UUID",s.owner);saved.put("Player",s.saved.nbt.copy());saved.putString("Dimension",s.saved.level.dimension().location().toString());saved.putString("MainArm",s.saved.arm.name());ListTag players=new ListTag();players.add(saved);tag.put("Players",players);
        ListTag blocks=new ListTag();for(var entry:s.blocks.entrySet()){CompoundTag b=new CompoundTag();b.put("Position",NbtUtils.writeBlockPos(entry.getKey()));b.put("Original",NbtUtils.writeBlockState(entry.getValue()));blocks.add(b);}tag.put("Blocks",blocks);
        Path destination=recovery(s.server),pending=destination.resolveSibling(destination.getFileName()+".pending");NbtIo.writeCompressed(tag,pending);try{Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(pending,destination,StandardCopyOption.REPLACE_EXISTING);}}
    @FunctionalInterface private interface RestoreAction{void run()throws Exception;}
    private static boolean restore(String part,RestoreAction action){try{action.run();return true;}catch(Exception ex){LogUtils.getLogger().error("[JR_TOME_FEEDBACK_PREVIEW] RESTORE_FAILED "+part,ex);return false;}}
    private static void finish(Session s,boolean passed,String reason){if(s==null||s.ended)return;s.ended=true;boolean restored=true;ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);
        restored&=restore("precise 27-block footprint",()->{require(!s.server.isPublished()&&s.server.getPlayerList().getPlayerCount()<=1,"Unsafe concurrent scene: preserve recovery journal");for(var entry:s.blocks.entrySet()){s.level.getChunkAt(entry.getKey());if(!s.level.getBlockState(entry.getKey()).equals(entry.getValue()))s.level.setBlock(entry.getKey(),entry.getValue(),Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS);require(s.level.getBlockState(entry.getKey()).equals(entry.getValue()),"Original fixture block restore");}});
        restored&=restore("player snapshot",()->{require(p!=null,"Owner unavailable for full NBT restore");p.closeContainer();SpellCasting.openJournal(p);p.removeAllEffects();p.removeData(ModAttachments.KNOWLEDGE);p.removeData(ModAttachments.MANA);p.removeData(ModAttachments.CASTING);p.removeData(ModAttachments.TRAINING);p.load(s.saved.nbt.copy());p.setGameMode(s.saved.mode);p.setMainArm(s.saved.arm);if(p.hasData(ModAttachments.CASTING))p.getData(ModAttachments.CASTING).clearActive();p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());if(p.hasData(ModAttachments.MANA)&&p.getData(ModAttachments.MANA).cooldown()>0)p.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(),p.getData(ModAttachments.MANA).cooldown());});
        restored&=restore("position and synchronization",()->{require(p!=null,"Owner unavailable");p.teleportTo(s.saved.level,s.saved.pos.x,s.saved.pos.y,s.saved.pos.z,s.saved.yaw,s.saved.pitch);p.setDeltaMovement(s.saved.motion);p.onUpdateAbilities();sync(p);for(var effect:p.getActiveEffects())p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(),effect,false));});
        restored&=restore("day",()->s.level.setDayTime(s.day));restored&=restore("mob spawning",()->s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(s.spawning,s.server));restored&=restore("regeneration",()->s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(s.regen,s.server));restored&=restore("keep inventory",()->s.server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(s.keepInventory,s.server));
        boolean stateRestored=restored;restored&=restore("journal",()->journal(s,stateRestored));String detail=reason+" restored="+restored;if(detail.length()>510)detail=detail.substring(0,510);
        if(p!=null)try{PacketDistributor.sendToPlayer(p,new State(FINISH,0,false,passed&&restored,detail));}catch(Exception ex){LogUtils.getLogger().warn("[JR_TOME_FEEDBACK_PREVIEW] Final client notice unavailable",ex);}
        LogUtils.getLogger().info("[JR_TOME_FEEDBACK_PREVIEW] {} phases={} restored={} {}",passed&&restored?"PASS":"FAIL",s.phase,restored,reason);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(session!=null&&session.owner.equals(e.getEntity().getUUID()))finish(session,false,"Owner logout");}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){if(session!=null&&session.server==e.getServer())finish(session,false,"Server stop");}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){if(session!=null&&session.server!=e.getServer())return;session=null;attempted=false;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private TomeFeedbackPreview(){}
}
