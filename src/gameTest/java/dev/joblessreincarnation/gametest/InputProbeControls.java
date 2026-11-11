package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.EarthWallDismantling;
import dev.joblessreincarnation.magic.EarthWallBlockEntity;
import dev.joblessreincarnation.magic.WallIdentity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameRules;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.WaterControl;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Server fixture and observation only. It never starts, releases, or switches a tested use. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class InputProbeControls {
    public static final String PROPERTY = "jobless_reincarnation.inputProbe";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Receipt>> RECEIPTS = new ConcurrentHashMap<>();

    public record Receipt(int caseId, String caseName, String result, long tick, boolean using,
            int useTicks, int slot, boolean sneaking, int mana, int maximum, int practice,
            int cooldown, int spawned, float charge, int chantTicks, int fullTicks,
            SpellCatalog selectedSpell, SpellCatalog activeSpell, int earthPractice, int blockedTicks, boolean unexpectedUse,
            boolean dismissActive, int dismissTicks, int dismissHoldTicks, int wallRemaining, int lowestMana, int airPractice, int healingPractice, float health, float maximumHealth) {}

    private static final class Session {
        int elapsed;
        int caseId = -1;
        String name = "fixture";
        String result = "READY";
        int spawned;
        float charge = -1.0F;
        boolean monitorBlockedUse;
        int blockedTicks;
        boolean unexpectedUse;
        int lowestMana = Integer.MAX_VALUE;
        float originalHealth, caseHealth;
        CompoundTag originalFood;
        boolean originalNaturalRegeneration;
        int initialTargetHits;
        boolean initialChargedHit, initialClaimed;
        List<BlockSnapshot> originalCells = List.of();
        List<BlockPos> wallCells = List.of();
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY) && !Boolean.getBoolean(WandPreviewControls.AUTO_PROPERTY)
                && !Boolean.getBoolean(TrainingPreviewCommands.AUTO_PROPERTY);
    }

    public static Receipt receipt(MinecraftServer server, UUID playerId) {
        var map = RECEIPTS.get(server);
        return map == null ? null : map.get(playerId);
    }

    public static void begin(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (!enabled() || player == null || player.getAbilities().instabuild) return;
        try {
            Session snapshot = new Session();
            snapshot.originalHealth = player.getHealth();
            snapshot.originalFood = new CompoundTag();
            player.getFoodData().addAdditionalSaveData(snapshot.originalFood);
            snapshot.originalNaturalRegeneration = player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
            SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>()).put(playerId, snapshot);
            if (!WandPreviewControls.beginInputProbe(player)) {
                SESSIONS.get(server).remove(playerId);
                return;
            }
            player.getInventory().setItem(1, new ItemStack(ModContent.TRAINING_STAFF.get()));
            player.inventoryMenu.broadcastFullState();
            LOGGER.info("[JR_INPUT_PROBE] FIXTURE {}: two identical wands; actual client keybind/network path; no screen or use-state mirror", playerId);
            publish(player, session(server, playerId));
        } catch (RuntimeException failure) {
            LOGGER.error("[JR_INPUT_PROBE] SETUP_FAILURE: restoring original fixture", failure);
            finish(server, playerId, "ABORT: setup exception");
        }
    }

    /** Reset only between cases, before the client begins input. Original data has a separate snapshot. */
    public static void prepareCase(MinecraftServer server, UUID playerId, int caseId, String name) {
        requireServerThread(server);
        Session session = session(server, playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null) return;
        EarthWallDismantling.cancel(player);
        player.stopUsingItem();
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        if (caseId >= 7) knowledge.learn(SpellCatalog.EARTH_WALL.id());
        if (caseId >= 16 && caseId != 17) knowledge.learn(SpellCatalog.AIR_BURST.id());
        if (caseId >= 22 && caseId != 23) knowledge.learn(SpellCatalog.HEALING.id());
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.CASTING, new CastingData());
        if (caseId >= 9 && caseId < 16) player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        if (caseId >= 18 && caseId < 22) player.getData(ModAttachments.CASTING).select(SpellCatalog.AIR_BURST.id());
        if (caseId >= 24) player.getData(ModAttachments.CASTING).select(SpellCatalog.HEALING.id());
        player.syncData(ModAttachments.CASTING);
        player.setData(ModAttachments.MANA, ManaData.full(MagicConfig.MAX_MANA.getAsInt()));
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.syncData(ModAttachments.KNOWLEDGE);
        player.syncData(ModAttachments.MANA);
        session.caseId = caseId;
        session.name = name;
        session.result = "READY";
        session.spawned = 0;
        session.charge = -1.0F;
        session.monitorBlockedUse = caseId == 8 || caseId == 15;
        session.blockedTicks = 0;
        session.unexpectedUse = false;
        session.lowestMana = player.getData(ModAttachments.MANA).mana();
        if (caseId >= 22) {
            player.level().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, server);
            player.setHealth(caseId == 25 ? player.getMaxHealth() : Math.max(1F, player.getMaxHealth() - 8F));
        }
        session.caseHealth = player.getHealth();
        session.initialTargetHits = player.getData(ModAttachments.TRAINING).targetHits();
        session.initialChargedHit = player.getData(ModAttachments.TRAINING).chargedTargetHit();
        session.initialClaimed = player.getData(ModAttachments.TRAINING).claimed();
        if ((caseId >= 9 && caseId < 16) || caseId == 19 || caseId == 26) prepareDismissWall(player, session);
        publish(player, session);
    }

    /** Assert actual server consequences after vanilla processed the client's input packets. */
    public static void checkCase(MinecraftServer server, UUID playerId, int caseId,
            boolean expectCast, int expectedSlot, boolean expectedSneaking) {
        requireServerThread(server);
        Session session = session(server, playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null || session.caseId != caseId) return;
        ManaData mana = player.getData(ModAttachments.MANA);
        int cost = expectCast ? WaterControl.manaCost(1.0F) : 0;
        boolean pass = !player.isUsingItem() && player.getInventory().selected == expectedSlot
                && player.isShiftKeyDown() == expectedSneaking
                && mana.mana() == mana.maximum() - cost
                && WaterMagic.practiceCount(player) == (expectCast ? 1 : 0)
                && session.spawned == (expectCast ? 1 : 0)
                && (expectCast ? session.charge == 1.0F : mana.cooldown() == 0)
                && SpellCatalog.selected(player) == (caseId >= 7 ? SpellCatalog.EARTH_WALL : SpellCatalog.WATER_BOLT)
                && SpellCatalog.active(player) == null && SpellCatalog.EARTH_WALL.practiceCount(player) == 0
                && (caseId != 8 || (!session.unexpectedUse && session.blockedTicks >= 40));
        session.result = pass ? "PASS" : "FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} case={} name={} using={} slot={} shift={} mana={}/{} practice={} spawned={} charge={} cooldown={} expectedCast={}",
                session.result, caseId, session.name, player.isUsingItem(), player.getInventory().selected,
                player.isShiftKeyDown(), mana.mana(), mana.maximum(), WaterMagic.practiceCount(player),
                session.spawned, session.charge, mana.cooldown(), expectCast);
        publish(player, session);
    }

    /** Observe the server throughout a sustained post-switch hold; this does not change input/use state. */
    public static void checkHeldSwitch(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        Session session = session(server, playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null || session.caseId != 8) return;
        ManaData mana = player.getData(ModAttachments.MANA);
        boolean pass = session.blockedTicks >= 40 && !session.unexpectedUse && !player.isUsingItem()
                && SpellCatalog.selected(player) == SpellCatalog.EARTH_WALL && SpellCatalog.active(player) == null
                && mana.mana() == mana.maximum() && mana.cooldown() == 0 && session.spawned == 0
                && WaterMagic.practiceCount(player) == 0 && SpellCatalog.EARTH_WALL.practiceCount(player) == 0;
        session.monitorBlockedUse = false;
        session.result = pass ? "HELD_PASS" : "FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} held-switch blockedTicks={} unexpectedUse={} selected={} using={} mana={}/{}",
                session.result, session.blockedTicks, session.unexpectedUse, SpellCatalog.selected(player), player.isUsingItem(), mana.mana(), mana.maximum());
        publish(player, session);
    }

    public static void finish(MinecraftServer server, UUID playerId, String reason) {
        requireServerThread(server);
        Map<UUID, Session> sessions = SESSIONS.get(server);
        Session removed = sessions == null ? null : sessions.remove(playerId);
        if (removed != null) for (BlockSnapshot snapshot : removed.originalCells)
            snapshot.restore(Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            EarthWallDismantling.cancel(player);
            WandPreviewControls.endInputProbe(player, reason);
            if (removed != null) {
                player.setHealth(removed.originalHealth);
                player.getFoodData().readAdditionalSaveData(removed.originalFood);
                player.level().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(removed.originalNaturalRegeneration, server);
                LOGGER.info("[JR_INPUT_PROBE] HEALTH_RESTORED health={} food={} naturalRegeneration={}",
                        player.getHealth(), player.getFoodData().getFoodLevel(), removed.originalNaturalRegeneration);
            }
        }
        var published = RECEIPTS.get(server);
        if (published != null) published.remove(playerId);
        LOGGER.info("[JR_INPUT_PROBE] FINISHED {}: {}", playerId, reason);
    }

    @SubscribeEvent
    public static void onProjectile(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof WaterBolt bolt)
                || !(bolt.getOwner() instanceof ServerPlayer player)) return;
        Session session = session(player.getServer(), player.getUUID());
        if (session == null || session.caseId < 0) return;
        session.spawned++;
        session.charge = bolt.charge();
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        var sessions = SESSIONS.get(event.getServer());
        if (sessions == null) return;
        for (UUID playerId : java.util.List.copyOf(sessions.keySet())) {
            Session session = sessions.get(playerId);
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            if (++session.elapsed > 4200 || WandPreviewControls.state(event.getServer(), playerId) == null) {
                finish(event.getServer(), playerId, "ABORT: 210-second timeout or fixture eligibility ended");
            } else {
                if (session.monitorBlockedUse && SpellCatalog.selected(player) == SpellCatalog.EARTH_WALL) {
                    session.blockedTicks++;
                    session.unexpectedUse |= player.isUsingItem() || SpellCatalog.active(player) != null;
                }
                publish(player, session);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && session(player.getServer(), player.getUUID()) != null)
            finish(player.getServer(), player.getUUID(), "ABORT: logout");
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        var sessions = SESSIONS.get(event.getServer());
        if (sessions != null) for (UUID id : java.util.List.copyOf(sessions.keySet()))
            finish(event.getServer(), id, "ABORT: server stopping");
        SESSIONS.remove(event.getServer());
        RECEIPTS.remove(event.getServer());
    }

    private static Session session(MinecraftServer server, UUID playerId) {
        var sessions = SESSIONS.get(server);
        return sessions == null ? null : sessions.get(playerId);
    }

    private static void publish(ServerPlayer player, Session session) {
        session.lowestMana = Math.min(session.lowestMana, player.getData(ModAttachments.MANA).mana());
        ManaData mana = player.getData(ModAttachments.MANA);
        int chant = SpellCatalog.selected(player).chantTicks(player);
        RECEIPTS.computeIfAbsent(player.getServer(), ignored -> new ConcurrentHashMap<>()).put(player.getUUID(),
                new Receipt(session.caseId, session.name, session.result, player.getServer().getTickCount(),
                        player.isUsingItem(), player.isUsingItem() ? player.getTicksUsingItem() : 0,
                        player.getInventory().selected, player.isShiftKeyDown(), mana.mana(), mana.maximum(),
                        WaterMagic.practiceCount(player), mana.cooldown(), session.spawned, session.charge,
                        chant, chant + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt(),
                        SpellCatalog.selected(player), SpellCatalog.active(player), SpellCatalog.EARTH_WALL.practiceCount(player),
                        session.blockedTicks, session.unexpectedUse,
                        EarthWallDismantling.active(player), EarthWallDismantling.heldTicks(player), MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt(), remaining(player, session), session.lowestMana, SpellCatalog.AIR_BURST.practiceCount(player),
                        SpellCatalog.HEALING.practiceCount(player), player.getHealth(), player.getMaxHealth()));
    }

    private static void prepareDismissWall(ServerPlayer player, Session session) {
        WallIdentity identity = new WallIdentity(player.getUUID(), UUID.randomUUID(), new BlockPos(206,-60,203), Direction.Axis.X);
        if (session.originalCells.isEmpty()) {
            session.originalCells = identity.footprint().stream()
                    .map(pos -> BlockSnapshot.create(player.level().dimension(), player.level(), pos, Block.UPDATE_ALL)).toList();
            session.wallCells = identity.footprint();
        }
        for (BlockPos pos : session.wallCells) {
            player.serverLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            player.serverLevel().setBlock(pos, ModContent.EARTH_WALL.get().defaultBlockState(), Block.UPDATE_ALL);
            ((EarthWallBlockEntity)player.serverLevel().getBlockEntity(pos)).bind(identity);
        }
        player.teleportTo(player.serverLevel(),206.5,-60,199.5,0,0);
        player.setYHeadRot(0); player.setShiftKeyDown(false);
        player.inventoryMenu.broadcastFullState();
    }
    private static int remaining(ServerPlayer player, Session session) {
        return (int)session.wallCells.stream().filter(pos -> player.level().getBlockState(pos).is(ModContent.EARTH_WALL.get())).count();
    }
    public static void checkDismissHeldRight(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        Session session = session(server,playerId); ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null || session.caseId != 15) return;
        boolean pass = !session.unexpectedUse && !player.isUsingItem() && session.blockedTicks >= 40
                && !EarthWallDismantling.active(player) && remaining(player,session)==0
                && session.lowestMana==MagicConfig.MAX_MANA.getAsInt()-MagicConfig.EARTH_DISMISS_COST.getAsInt();
        session.monitorBlockedUse = false;
        session.result = pass ? "HELD_PASS" : "FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} H held-right blockedTicks={} unexpectedUse={} lowestMana={}",session.result,session.blockedTicks,session.unexpectedUse,session.lowestMana);
        publish(player,session);
    }
    public static void checkDismissCase(MinecraftServer server, UUID playerId, int caseId) {
        requireServerThread(server);
        Session session=session(server,playerId); ServerPlayer player=server.getPlayerList().getPlayer(playerId);
        if(session==null||player==null||session.caseId!=caseId)return;
        boolean success=caseId==9||caseId==15;
        boolean pass=!player.isUsingItem()&&!EarthWallDismantling.active(player)
                && remaining(player,session)==(success?0:6)
                && session.lowestMana==MagicConfig.MAX_MANA.getAsInt()-(success?MagicConfig.EARTH_DISMISS_COST.getAsInt():0)
                && SpellCatalog.EARTH_WALL.practiceCount(player)==0 && WaterMagic.practiceCount(player)==0
                && session.spawned==0 && SpellCatalog.active(player)==null
                && SpellCatalog.selected(player)==(caseId==11?SpellCatalog.WATER_BOLT:SpellCatalog.EARTH_WALL)
                && player.getInventory().selected==(caseId==12?1:0)
                && player.isShiftKeyDown()==(caseId==13||caseId==14)
                && (caseId!=15||!session.unexpectedUse);
        session.result=pass?"PASS":"FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} case={} name={} Hactive={} wall={} lowestMana={} slot={} shift={} selected={} using={}",session.result,caseId,session.name,EarthWallDismantling.active(player),remaining(player,session),session.lowestMana,player.getInventory().selected,player.isShiftKeyDown(),SpellCatalog.selected(player),player.isUsingItem());
        publish(player,session);
    }
    /** Observe the third-spell cases after logical input has traversed the normal client/server path. */
    public static void checkAirCase(MinecraftServer server, UUID playerId, int caseId) {
        requireServerThread(server);
        Session session = session(server, playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null || session.caseId != caseId) return;
        boolean cast = caseId == 18;
        SpellCatalog selected = caseId < 18 ? SpellCatalog.WATER_BOLT : SpellCatalog.AIR_BURST;
        boolean pass = !player.isUsingItem() && SpellCatalog.active(player) == null
                && !EarthWallDismantling.active(player) && session.spawned == 0
                && SpellCatalog.selected(player) == selected
                && WaterMagic.practiceCount(player) == 0 && SpellCatalog.EARTH_WALL.practiceCount(player) == 0
                && SpellCatalog.AIR_BURST.practiceCount(player) == (cast ? 1 : 0)
                && session.lowestMana == MagicConfig.MAX_MANA.getAsInt() - (cast ? MagicConfig.AIR_BURST_COST.getAsInt() : 0)
                && player.getInventory().selected == 0 && player.isShiftKeyDown() == (caseId == 21)
                && (caseId != 17 || !SpellCatalog.AIR_BURST.knows(player))
                && (caseId != 19 || remaining(player, session) == 6)
                && player.getData(ModAttachments.TRAINING).targetHits() == session.initialTargetHits
                && player.getData(ModAttachments.TRAINING).chargedTargetHit() == session.initialChargedHit
                && player.getData(ModAttachments.TRAINING).claimed() == session.initialClaimed;
        session.result = pass ? "PASS" : "FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} case={} name={} selected={} airPractice={} waterPractice={} earthPractice={} lowestMana={} Hactive={} wall={} using={} spawned={}",
                session.result, caseId, session.name, SpellCatalog.selected(player), SpellCatalog.AIR_BURST.practiceCount(player),
                WaterMagic.practiceCount(player), SpellCatalog.EARTH_WALL.practiceCount(player), session.lowestMana,
                EarthWallDismantling.active(player), remaining(player, session), player.isUsingItem(), session.spawned);
        publish(player, session);
    }
    /** All spell actions are client input; this method only observes authoritative results. */
    public static void checkHealingCase(MinecraftServer server, UUID playerId, int caseId) {
        requireServerThread(server);
        Session session = session(server, playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (session == null || player == null || session.caseId != caseId) return;
        boolean cast = caseId == 24;
        float expectedHealth = cast ? Math.min(player.getMaxHealth(), session.caseHealth + MagicConfig.HEALING_AMOUNT.get().floatValue()) : session.caseHealth;
        SpellCatalog selected = caseId < 24 ? SpellCatalog.WATER_BOLT : SpellCatalog.HEALING;
        boolean pass = !player.isUsingItem() && SpellCatalog.active(player) == null
                && !EarthWallDismantling.active(player) && session.spawned == 0
                && SpellCatalog.selected(player) == selected
                && WaterMagic.practiceCount(player) == 0 && SpellCatalog.EARTH_WALL.practiceCount(player) == 0
                && SpellCatalog.AIR_BURST.practiceCount(player) == 0
                && SpellCatalog.HEALING.practiceCount(player) == (cast ? 1 : 0)
                && session.lowestMana == MagicConfig.MAX_MANA.getAsInt() - (cast ? MagicConfig.HEALING_COST.getAsInt() : 0)
                && Math.abs(player.getHealth() - expectedHealth) < 0.001F
                && player.getInventory().selected == 0 && player.isShiftKeyDown() == (caseId == 28)
                && (caseId != 23 || !SpellCatalog.HEALING.knows(player))
                && (caseId != 26 || remaining(player, session) == 6)
                && player.getData(ModAttachments.TRAINING).targetHits() == session.initialTargetHits
                && player.getData(ModAttachments.TRAINING).chargedTargetHit() == session.initialChargedHit
                && player.getData(ModAttachments.TRAINING).claimed() == session.initialClaimed;
        session.result = pass ? "PASS" : "FAIL";
        LOGGER.info("[JR_INPUT_PROBE] {} case={} name={} selected={} healingPractice={} health={}/{} expectedHealth={} lowestMana={} Hactive={} wall={} using={}",
                session.result, caseId, session.name, SpellCatalog.selected(player), SpellCatalog.HEALING.practiceCount(player),
                player.getHealth(), player.getMaxHealth(), expectedHealth, session.lowestMana,
                EarthWallDismantling.active(player), remaining(player, session), player.isUsingItem());
        publish(player, session);
    }
    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Input probe fixture requires the server thread");
    }

    private InputProbeControls() {}
}
