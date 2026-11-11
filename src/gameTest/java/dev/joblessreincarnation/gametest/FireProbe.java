package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in private copied world. Fixture writes player state only; client drives all tested input. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class FireProbe {
    public static final String PROPERTY = "jobless_reincarnation.fireProbe";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    public static final int CASE_COUNT = 10;
    public record Receipt(boolean ready, boolean restored, boolean passed, int caseId, String result,
            SpellCatalog selected, SpellCatalog active, boolean using, int useTicks, int mana,
            int practice, boolean learned, int spawned, boolean sawUse, String message) {}
    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw,
            float pitch, net.minecraft.world.entity.HumanoidArm mainArm, long dayTime, boolean regen, boolean spawning, Set<UUID> existing) {}
    private static final class Session {
        final MinecraftServer server; final UUID owner;
        Snapshot snapshot; int ticks, caseId, spawned, passed; boolean finished, sawUse;
        String result = "WAIT";
        Session(ServerPlayer p) {server=p.getServer(); owner=p.getUUID();}
    }
    private static Session session;
    private static boolean attempted;
    private static volatile MinecraftServer publishedServer;
    private static volatile Receipt published;
    public static boolean enabled() {return Boolean.getBoolean(PROPERTY);}
    public static Receipt receipt(MinecraftServer server) {return publishedServer==server?published:null;}
    private static boolean eligible(ServerPlayer p) {
        MinecraftServer s=p.getServer(); return enabled() && s!=null && !s.isDedicatedServer() && !s.isPublished()
                && s.isSingleplayerOwner(p.getGameProfile()) && WORLD.equals(s.getWorldData().getLevelName())
                && p.level().dimension()==Level.OVERWORLD && p.isAlive() && !p.isSpectator();
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if(attempted || !(e.getEntity() instanceof ServerPlayer p) || !eligible(p))return;
        attempted=true;session=new Session(p);publishedServer=session.server;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        Session s=session;if(s==null||s.finished||s.server!=e.getServer())return;
        try {
            ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);
            if(p==null||!eligible(p)){restore(s,false,"Owner context changed");return;}
            if(++s.ticks>5000){restore(s,false,"250s timeout");return;}
            if(s.ticks==80) {
                s.snapshot=new Snapshot(p.saveWithoutId(new CompoundTag()),p.serverLevel(),p.gameMode.getGameModeForPlayer(),
                        p.position(),p.getYRot(),p.getXRot(),p.getMainArm(),s.server.overworld().getDayTime(),
                        s.server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION),
                        s.server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING),
                        p.serverLevel().getEntities(ModContent.FIRE_BALL.get(),b->b.getOwner()==p).stream().map(FireBallProjectile::getUUID).collect(Collectors.toSet()));
                journal(s,false);p.closeContainer();p.setGameMode(GameType.SURVIVAL);
                s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,s.server);
                s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s.server);
                s.server.overworld().setDayTime(6000);prepare(s.server,0);
            }
            if(s.snapshot!=null){s.sawUse|=p.isUsingItem();publish(s,p);}
        }catch(Exception failure){LogUtils.getLogger().error("[JR_FIRE_PROBE] FAILED",failure);restore(s,false,failure.toString());}
    }
    public static void prepare(MinecraftServer server,int index) {
        if(!server.isSameThread())throw new IllegalStateException("Server executor required");
        Session s=session;if(s==null||s.finished||s.snapshot==null)return;
        ServerPlayer p=server.getPlayerList().getPlayer(s.owner);
        SpellCasting.openJournal(p);
        MagicKnowledge knowledge=new MagicKnowledge();
        if(index==6)knowledge.learn(SpellCatalog.WATER_BOLT.id());
        else if(index>0)for(SpellCatalog spell:SpellCatalog.values())knowledge.learn(spell.id());
        p.setData(ModAttachments.KNOWLEDGE,knowledge);p.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));
        CastingData casting=new CastingData();casting.select((index==1?SpellCatalog.HEALING:index==6||index==7?SpellCatalog.WATER_BOLT:SpellCatalog.FIRE_BALL).id());
        p.setData(ModAttachments.CASTING,casting);p.getInventory().setItem(0,new ItemStack(index==0?ModContent.FIRE_TOME.get():ModContent.TRAINING_STAFF.get()));
        p.getInventory().setItem(1,new ItemStack(ModContent.FIRE_TOME.get()));p.getInventory().selected=0;
        p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());p.setShiftKeyDown(false);p.clearFire();
        p.teleportTo(p.serverLevel(),206.5,-60,199.5,0,0);p.setYHeadRot(0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);
        p.setMainArm(index==9?net.minecraft.world.entity.HumanoidArm.LEFT:net.minecraft.world.entity.HumanoidArm.RIGHT);
        s.caseId=index;s.spawned=0;s.sawUse=false;s.result="READY";sync(p);publish(s,p);
        LogUtils.getLogger().info("[JR_FIRE_PROBE] READY case={}",index);
    }
    private static void publish(Session s,ServerPlayer p) {
        published=new Receipt(true,false,false,s.caseId,s.result,SpellCatalog.selected(p),SpellCatalog.active(p),p.isUsingItem(),
                p.isUsingItem()?p.getTicksUsingItem():0,p.getData(ModAttachments.MANA).mana(),FireMagic.practiceCount(p),FireMagic.isLearned(p),s.spawned,s.sawUse,"Native input probe");
    }
    public static void check(MinecraftServer server,int index) {
        if(!server.isSameThread())throw new IllegalStateException("Server executor required");
        Session s=session;if(s==null||s.finished||s.caseId!=index||!s.result.equals("READY"))return;
        ServerPlayer p=server.getPlayerList().getPlayer(s.owner);
        boolean released=index==2;
        SpellCatalog selected=index==1||index==6?SpellCatalog.WATER_BOLT:SpellCatalog.FIRE_BALL;
        boolean pass=!p.isUsingItem()&&SpellCatalog.active(p)==null&&!EarthWallDismantling.active(p)
                &&p.getData(ModAttachments.MANA).mana()==(released?84:100)
                &&FireMagic.practiceCount(p)==(released?1:0)&&s.spawned==(released?1:0)
                &&FireMagic.isLearned(p)==(index!=6)&&SpellCatalog.selected(p)==selected;
        if(index==0)pass&=p.getMainHandItem().is(ModContent.FIRE_TOME.get())&&p.getMainHandItem().getCount()==1;
        for(SpellCatalog spell:SpellCatalog.values())if(spell!=SpellCatalog.FIRE_BALL)pass&=spell.practiceCount(p)==0;
        s.result=pass?"PASS":"FAIL";if(pass)s.passed++;publish(s,p);
        LogUtils.getLogger().info("[JR_FIRE_PROBE] {} case={} selected={} mana={} firePractice={} learned={} spawned={} sawUse={}",
                s.result,index,SpellCatalog.selected(p),p.getData(ModAttachments.MANA).mana(),FireMagic.practiceCount(p),FireMagic.isLearned(p),s.spawned,s.sawUse);
    }
    @SubscribeEvent public static void spawn(EntityJoinLevelEvent e) {
        if(session!=null&&!session.finished&&!e.getLevel().isClientSide&&e.getEntity() instanceof FireBallProjectile b
                &&b.getOwner()!=null&&b.getOwner().getUUID().equals(session.owner))session.spawned++;
    }
    public static void finish(MinecraftServer server,boolean pass,String reason) {
        if(!server.isSameThread())throw new IllegalStateException("Server executor required");
        if(session!=null&&session.server==server)restore(session,pass&&session.passed==CASE_COUNT,reason);
    }
    private static void restore(Session s,boolean pass,String reason) {
        if(s==null||s.finished)return;s.finished=true;boolean restored=true;
        try{if(s.snapshot!=null){Snapshot old=s.snapshot;ServerPlayer p=s.server.getPlayerList().getPlayer(s.owner);
            if(p==null)throw new IllegalStateException("Owner missing; recovery journal retained");
            SpellCasting.openJournal(p);old.level.getEntities(ModContent.FIRE_BALL.get(),b->b.getOwner()==p&&!old.existing.contains(b.getUUID())).forEach(FireBallProjectile::discard);
            p.removeData(ModAttachments.KNOWLEDGE);p.removeData(ModAttachments.MANA);p.removeData(ModAttachments.CASTING);p.removeData(ModAttachments.TRAINING);
            p.load(old.player.copy());p.setMainArm(old.mainArm);p.setGameMode(old.mode);p.teleportTo(old.level,old.position.x,old.position.y,old.position.z,old.yaw,old.pitch);
            if(p.hasData(ModAttachments.CASTING))p.getData(ModAttachments.CASTING).clearActive();
            p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
            if(p.hasData(ModAttachments.MANA)&&p.getData(ModAttachments.MANA).cooldown()>0)p.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(),p.getData(ModAttachments.MANA).cooldown());
            s.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(old.regen,s.server);
            s.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(old.spawning,s.server);
            s.server.overworld().setDayTime(old.dayTime);sync(p);journal(s,true);
        }}catch(Exception failure){restored=false;LogUtils.getLogger().error("[JR_FIRE_PROBE] RESTORE_FAILED",failure);}
        published=new Receipt(false,true,pass&&restored,s.caseId,pass&&restored?"PASS":"FAIL",null,null,false,0,0,0,false,s.spawned,s.sawUse,reason+" cleanup="+restored);
        LogUtils.getLogger().info("[JR_FIRE_PROBE] ALL_{} {}/{} RESTORED={} {}",pass&&restored?"PASS":"FAIL",s.passed,CASE_COUNT,restored,reason);
    }
    private static void journal(Session s,boolean restored)throws Exception {
        CompoundTag tag=new CompoundTag();tag.putBoolean("Restored",restored);tag.put("Player",s.snapshot.player.copy());
        tag.putString("Purpose","Fire native input/art; no terrain writes; synthetic logical input, not manual input");
        tag.putLong("OriginalDayTime",s.snapshot.dayTime);tag.putBoolean("OriginalNaturalRegeneration",s.snapshot.regen);tag.putBoolean("OriginalMobSpawning",s.snapshot.spawning);
        NbtIo.writeCompressed(tag,s.server.getWorldPath(LevelResource.ROOT).resolve("jr-fire-probe-recovery.nbt"));
    }
    private static void sync(ServerPlayer p){p.syncData(ModAttachments.KNOWLEDGE);p.syncData(ModAttachments.MANA);p.syncData(ModAttachments.CASTING);
        p.getInventory().setChanged();p.inventoryMenu.broadcastFullState();p.connection.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(session!=null&&e.getEntity().getUUID().equals(session.owner))restore(session,false,"Logout");}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){if(session!=null&&e.getServer()==session.server)restore(session,false,"Server stopping");}
    private FireProbe(){}
}
