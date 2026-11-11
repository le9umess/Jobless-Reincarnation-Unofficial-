package dev.joblessreincarnation.gametest;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.npc.SylphietteFirstLesson;
import dev.joblessreincarnation.npc.SylphietteLessonVisuals;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.training.TrainingProgress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Private-world fixture and server observations. Tested actions come from the actual client screen/input. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class JournalProbe {
    public static final String PROPERTY = "jobless_reincarnation.journalProbe";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    public static final int CASE_COUNT = 10;
    /** The player's advancement file as captured before the probe, kept beside the recovery journal. */
    private static final String ADVANCEMENT_BACKUP = "jr-journal-probe-advancements.json";
    public record Receipt(boolean ready, boolean restored, boolean passed, int caseId, String result,
            SpellCatalog selected, SpellCatalog active, boolean using, int useTicks, int mana, int cooldown,
            int waterPractice, int healingPractice, int learnedMask, boolean dismissActive, int dismissTicks,
            int wallRemaining, int spawned, long tick, String message) {}
    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw,
            float pitch, long dayTime, boolean naturalRegeneration, boolean mobSpawning, Set<UUID> existingBolts) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        Snapshot snapshot;
        List<BlockPos> cells = List.of();
        int ticks, caseId = -1, spawned, passedCases;
        boolean finished, sawUse, lessonDone;
        SylphietteEntity lessonNpc;
        /** The exact advancement file before any probe change: vanilla keeps that progress outside player NBT. */
        byte[] advancements;
        /** Case 9 only counts a first-lesson advancement earned after setup cleared it, and when it came. */
        boolean lessonArmed;
        String lessonCleared = "none";
        int lessonCastTick = -1, lessonEarnedTick = -1;
        String result = "WAIT";
        Session(ServerPlayer player) { server = player.getServer(); playerId = player.getUUID(); }
    }
    private static Session session;
    private static boolean attempted;
    private static volatile MinecraftServer publishedServer;
    private static volatile Receipt published;
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static Receipt receipt(MinecraftServer server) { return publishedServer == server ? published : null; }
    private static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return enabled() && server != null && !server.isDedicatedServer() && !server.isPublished()
                && server.isSingleplayerOwner(player.getGameProfile()) && WORLD.equals(server.getWorldData().getLevelName())
                && player.level().dimension() == Level.OVERWORLD && player.isAlive() && !player.isSpectator()
                && player.createCommandSourceStack().hasPermission(2);
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (attempted || !(event.getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        attempted = true; session = new Session(player); publishedServer = session.server;
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE] SCHEDULED: private owner world; full player and terrain recovery journal");
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 5000) { restore(current, false, "250-second fixture timeout"); return; }
            if (current.ticks == 80) snapshot(current, player);
            if (current.snapshot != null) {
                current.sawUse |= player.isUsingItem();
                publish(current, player, "Client screen/input probe running");
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_JOURNAL_PROBE] EXCEPTION", failure);
            restore(current, false, failure.toString());
        }
    }
    private static void snapshot(Session current, ServerPlayer player) throws Exception {
        current.snapshot = new Snapshot(player.saveWithoutId(new CompoundTag()), player.serverLevel(), player.gameMode.getGameModeForPlayer(),
                player.position(), player.getYRot(), player.getXRot(), current.server.overworld().getDayTime(),
                current.server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION),
                current.server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING),
                player.serverLevel().getEntities(ModContent.WATER_BOLT.get(), bolt -> bolt.getOwner() == player).stream()
                        .map(WaterBolt::getUUID).collect(Collectors.toSet()));
        WallIdentity identity = wallIdentity(player);
        for (BlockPos pos : identity.footprint()) {
            if (!player.serverLevel().hasChunkAt(pos) || !player.serverLevel().getBlockState(pos).isAir()
                    || player.serverLevel().getBlockEntity(pos) != null)
                throw new IllegalStateException("Refusing to replace nonempty/unloaded journal fixture cell " + pos);
        }
        // Restore drops these six mod attachments before loading the saved player; refuse to start if there are more.
        if (ModAttachments.TYPES.getEntries().size() != 6)
            throw new IllegalStateException("A new mod attachment type must be added to the journal probe's restore first");
        // Keep the exact advancement file so restore can bring every criterion back with its original time instead of
        // re-awarding it, which would stamp a new time and show a toast.
        player.getAdvancements().save();
        current.advancements = Files.readAllBytes(advancementFile(current));
        Files.write(current.server.getWorldPath(LevelResource.ROOT).resolve(ADVANCEMENT_BACKUP), current.advancements);
        current.cells = identity.footprint();
        journal(current, false); // Original player and every planned terrain write are durable before mutation.
        player.closeContainer(); SpellCasting.openJournal(player);
        player.setGameMode(GameType.SURVIVAL);
        current.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, current.server);
        current.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, current.server);
        current.server.overworld().setDayTime(6000L);
        player.teleportTo(player.serverLevel(), 206.5, -60, 199.5, 0, 0);
        player.setYHeadRot(0); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        player.setHealth(player.getMaxHealth()); player.getFoodData().setFoodLevel(20);
        player.setShiftKeyDown(false);
        prepare(current.server, 0);
    }
    /** Only fixture state is reset; actual opening, browsing, choosing and cancellation are never called here. */
    public static void prepare(MinecraftServer server, int caseId) {
        requireThread(server);
        Session current = session;
        if (current == null || current.finished || current.server != server || current.snapshot == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(current.playerId);
        if (player == null) return;
        SpellCasting.openJournal(player);
        MagicKnowledge knowledge = new MagicKnowledge();
        if (caseId > 0) knowledge.learn(SpellCatalog.WATER_BOLT.id());
        if (caseId > 1 && caseId < 8) knowledge.learn(SpellCatalog.EARTH_WALL.id());
        if (caseId == 1) {
            knowledge.learn(SpellCatalog.HEALING.id());
            for (int i = 0; i < 7; i++) knowledge.practice(SpellCatalog.WATER_BOLT.id());
            for (int i = 0; i < 3; i++) knowledge.practice(SpellCatalog.HEALING.id());
        }
        if (caseId == 9) {
            knowledge.learn(SpellCatalog.EARTH_WALL.id());
            knowledge.learn(SpellCatalog.MIRE.id());
            for (int i = 0; i < 5; i++) knowledge.practice(SpellCatalog.MIRE.id());
            // Course Records reads these existing private facts; the preview must not create a second progress model.
            player.setData(ModAttachments.TRAINING, new TrainingProgress(2, false, false, 1));
            player.setData(ModAttachments.SWORD_TRAINING, new SwordTrainingProgress(true, 7, false));
            current.lessonDone = false;
            // Only an advancement earned in this run may pass: clear any earlier one (restore puts the original back
            // from the captured file, with its original time) and prove it is gone before the real interaction.
            current.lessonArmed = false; current.lessonCastTick = current.lessonEarnedTick = -1;
            current.lessonCleared = clearLesson(server, player);
            if (StoryAdvancements.hasCompleted(player, StoryAdvancements.SYLPHIETTE_FIRST_LESSON))
                throw new IllegalStateException("S02 advancement is still complete after clearing it");
            current.lessonArmed = true;
            // Case 4's wall still stands 3.5 blocks south, where the demonstration is aimed. These are the probe's own
            // cells: air before the probe, and set to air again on restore.
            for (BlockPos pos : current.cells) player.serverLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            if (current.lessonNpc != null) current.lessonNpc.discard();
            current.lessonNpc = ModContent.SYLPHIETTE.get().create(player.serverLevel());
            if (current.lessonNpc == null) throw new IllegalStateException("Sylphiette preview factory returned null");
            // Sylphiette's model faces south at yaw 0; the player stands south of her and looks north.
            // Keep the camera on the open side of the tree fixture; the player remains at z=199.5 and looks north.
            current.lessonNpc.moveTo(206.5, -60, 196.5, 0, 0);
            current.lessonNpc.setYHeadRot(0); current.lessonNpc.setYBodyRot(0); // head and body face the same way as her yaw
            current.lessonNpc.setNoAi(true); current.lessonNpc.setNoGravity(true);
            if (!player.serverLevel().addFreshEntity(current.lessonNpc)) throw new IllegalStateException("Sylphiette preview did not join");
            player.setYRot(180); player.setYHeadRot(180);
        }
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.MANA, new ManaData(100, 100, 6000, 0));
        CastingData casting = new CastingData();
        casting.select((caseId == 4 ? SpellCatalog.EARTH_WALL : SpellCatalog.WATER_BOLT).id());
        player.setData(ModAttachments.CASTING, casting);
        player.getInventory().setItem(0, caseId == 0 ? ItemStack.EMPTY
                : new ItemStack(caseId == 8 ? ModContent.AIR_TOME.get() : ModContent.TRAINING_STAFF.get()));
        player.getInventory().selected = 0; player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        float previewYaw = caseId == 9 ? 180.0F : 0.0F;
        double previewZ = 199.5;
        player.setShiftKeyDown(false); player.teleportTo(player.serverLevel(), 206.5, -60, previewZ, previewYaw, 0); player.setYHeadRot(previewYaw);
        if (caseId == 4) {
            WallIdentity identity = wallIdentity(player);
            for (BlockPos pos : current.cells) {
                player.serverLevel().setBlock(pos, ModContent.EARTH_WALL.get().defaultBlockState(), Block.UPDATE_ALL);
                ((EarthWallBlockEntity)player.serverLevel().getBlockEntity(pos)).bind(identity);
            }
        }
        current.caseId = caseId; current.spawned = 0; current.sawUse = false; current.result = "READY";
        sync(player); publish(current, player, "Fixture case prepared before client input");
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE] READY case={} selected={} learnedMask={}", caseId, SpellCatalog.selected(player), mask(player));
    }
    public static void check(MinecraftServer server, int caseId) {
        requireThread(server);
        Session current = session; ServerPlayer player = current == null ? null : server.getPlayerList().getPlayer(current.playerId);
        if (current == null || current.finished || player == null || current.caseId != caseId || !current.result.equals("READY")) return;
        if (caseId == 9) {
            // Earned after setup cleared it, ATTEMPT_DELAY_TICKS + FINISH_TICK after the real cast, and still held.
            int expected = SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.FINISH_TICK;
            int took = current.lessonEarnedTick - current.lessonCastTick;
            current.lessonDone = current.lessonArmed && current.lessonCastTick >= 0 && current.lessonEarnedTick >= 0
                    && Math.abs(took - expected) <= 2 && StoryAdvancements.hasCompleted(player, StoryAdvancements.SYLPHIETTE_FIRST_LESSON);
            LogUtils.getLogger().info("[JR_JOURNAL_PROBE] S02_ADVANCEMENT cleared={} castTick={} earnedTick={} took={} expected={} earnedThisRun={}",
                    current.lessonCleared, current.lessonCastTick, current.lessonEarnedTick, took, expected, current.lessonDone);
        }
        SpellCatalog expected = caseId == 4 ? SpellCatalog.EARTH_WALL : SpellCatalog.WATER_BOLT;
        int expectedMask = caseId == 0 ? 0 : caseId == 1 ? (1 << SpellCatalog.WATER_BOLT.ordinal()) | (1 << SpellCatalog.HEALING.ordinal())
                : caseId == 8 ? 1 : caseId == 9 ? (1 << SpellCatalog.WATER_BOLT.ordinal()) | (1 << SpellCatalog.EARTH_WALL.ordinal()) | (1 << SpellCatalog.MIRE.ordinal())
                : (1 << SpellCatalog.WATER_BOLT.ordinal()) | (1 << SpellCatalog.EARTH_WALL.ordinal());
        boolean pass = !player.isUsingItem() && SpellCatalog.active(player) == null && !EarthWallDismantling.active(player)
                && current.spawned == (caseId == 9 && current.lessonDone ? 1 : 0)
                && (caseId != 9 || !current.lessonDone || (player.getData(ModAttachments.MANA).mana() > 0 && player.getData(ModAttachments.MANA).mana() < 100))
                && (caseId == 9 && current.lessonDone || player.getData(ModAttachments.MANA).mana() == 100)
                && player.getData(ModAttachments.MANA).cooldown() == 0 && SpellCatalog.selected(player) == expected
                && mask(player) == expectedMask && SpellCatalog.WATER_BOLT.practiceCount(player) == (caseId == 1 ? 7 : caseId == 9 && current.lessonDone ? 1 : 0)
                && SpellCatalog.HEALING.practiceCount(player) == (caseId == 1 ? 3 : 0)
                && SpellCatalog.EARTH_WALL.practiceCount(player) == 0 && SpellCatalog.AIR_BURST.practiceCount(player) == 0
                && SpellCatalog.MIRE.practiceCount(player) == (caseId == 9 ? 5 : 0)
                && (caseId != 4 || walls(current, player) == 6) && (caseId != 3 || current.sawUse)
                && (caseId != 9 || current.lessonDone);
        current.result = pass ? "PASS" : "FAIL";
        if (pass) current.passedCases++;
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE] {} case={} selected={} mana={} learnedMask={} waterPractice={} healingPractice={} using={} active={} H={} wall={} bolts={}",
                current.result, caseId, SpellCatalog.selected(player), player.getData(ModAttachments.MANA).mana(), mask(player),
                SpellCatalog.WATER_BOLT.practiceCount(player), SpellCatalog.HEALING.practiceCount(player), player.isUsingItem(),
                SpellCatalog.active(player), EarthWallDismantling.active(player), walls(current, player), current.spawned);
        publish(current, player, "Authoritative consequences checked after actual client actions");
    }
    private static WallIdentity wallIdentity(ServerPlayer player) {
        return new WallIdentity(player.getUUID(), UUID.randomUUID(), new BlockPos(206, -60, 203), Direction.Axis.X);
    }
    private static int mask(ServerPlayer player) {
        int mask = 0; for (SpellCatalog spell : SpellCatalog.values()) if (spell.knows(player)) mask |= 1 << spell.ordinal(); return mask;
    }
    private static int walls(Session current, ServerPlayer player) {
        return (int)current.cells.stream().filter(pos -> player.level().getBlockState(pos).is(ModContent.EARTH_WALL.get())).count();
    }
    private static void publish(Session current, ServerPlayer player, String message) {
        published = new Receipt(true, false, false, current.caseId, current.result, SpellCatalog.selected(player), SpellCatalog.active(player),
                player.isUsingItem(), player.isUsingItem() ? player.getTicksUsingItem() : 0, player.getData(ModAttachments.MANA).mana(),
                player.getData(ModAttachments.MANA).cooldown(), SpellCatalog.WATER_BOLT.practiceCount(player), SpellCatalog.HEALING.practiceCount(player),
                mask(player), EarthWallDismantling.active(player), EarthWallDismantling.heldTicks(player), walls(current, player), current.spawned,
                current.server.getTickCount(), message);
    }
    @SubscribeEvent public static void projectile(EntityJoinLevelEvent event) {
        if (session != null && !session.finished && !event.getLevel().isClientSide && event.getEntity() instanceof WaterBolt bolt
                && bolt.getOwner() != null && bolt.getOwner().getUUID().equals(session.playerId)) {
            session.spawned++;
            if (session.lessonArmed && session.lessonCastTick < 0) session.lessonCastTick = session.server.getTickCount();
        }
    }
    /** Records when case 9's player actually earns the first-lesson advancement; setup cleared any earlier one. */
    @SubscribeEvent public static void earned(AdvancementEvent.AdvancementEarnEvent event) {
        Session current = session;
        if (current != null && !current.finished && current.lessonArmed && current.lessonEarnedTick < 0
                && event.getEntity().getUUID().equals(current.playerId)
                && event.getAdvancement().id().equals(StoryAdvancements.SYLPHIETTE_FIRST_LESSON))
            current.lessonEarnedTick = current.server.getTickCount();
    }
    public static void finish(MinecraftServer server, boolean pass, String reason) {
        requireThread(server);
        if (session != null && session.server == server) restore(session, pass && session.passedCases == CASE_COUNT, reason);
    }
    private static void restore(Session current, boolean pass, String reason) {
        if (current == null || current.finished) return;
        current.finished = true; boolean restored = true;
        try {
            Snapshot saved = current.snapshot;
            if (saved != null) {
                ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
                if (player == null) throw new IllegalStateException("Missing player; recovery journal retained");
                SpellCasting.openJournal(player);
                saved.level.getEntities(ModContent.WATER_BOLT.get(), bolt -> bolt.getOwner() == player
                        && !saved.existingBolts.contains(bolt.getUUID())).forEach(WaterBolt::discard);
                for (BlockPos pos : current.cells) saved.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                // Loading the saved player only adds the attachments in its tag, so drop all six first; otherwise one the
                // probe created (sword training in case 9, or mana growth from casting) would survive the restore.
                player.removeData(ModAttachments.KNOWLEDGE); player.removeData(ModAttachments.MANA);
                player.removeData(ModAttachments.CASTING); player.removeData(ModAttachments.TRAINING);
                player.removeData(ModAttachments.MANA_GROWTH); player.removeData(ModAttachments.SWORD_TRAINING);
                player.load(saved.player.copy()); player.setGameMode(saved.mode);
                player.teleportTo(saved.level, saved.position.x, saved.position.y, saved.position.z, saved.yaw, saved.pitch);
                if (player.hasData(ModAttachments.CASTING)) player.getData(ModAttachments.CASTING).clearActive();
                player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
                if (player.hasData(ModAttachments.MANA) && player.getData(ModAttachments.MANA).cooldown() > 0)
                    player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), player.getData(ModAttachments.MANA).cooldown());
                if (current.lessonNpc != null) { current.lessonNpc.discard(); current.lessonNpc = null; }
                current.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(saved.naturalRegeneration, current.server);
                current.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(saved.mobSpawning, current.server);
                current.server.overworld().setDayTime(saved.dayTime);
                if (current.advancements != null) restoreAdvancements(current, player);
                sync(player); journal(current, true);
            }
        } catch (Exception failure) {
            restored = false; LogUtils.getLogger().error("[JR_JOURNAL_PROBE] RESTORE_FAILED: journal retained", failure);
        }
        published = new Receipt(false, true, pass && restored, current.caseId, pass && restored ? "PASS" : "FAIL", null, null,
                false, 0, 0, 0, 0, 0, 0, false, 0, 0, current.spawned, current.server.getTickCount(), reason + "; cleanup=" + restored);
        // RESTORED covers exactly the listed state; say so instead of implying the whole player or world went back.
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE] ALL_{} {}/{} RESTORED={} {}; restore covers player NBT, attachments, game mode, position, "
                + "two game rules, day time, the probe's wall cells, water bolts and preview Sylphiette{}; statistics and game time are not restored",
                pass && restored ? "PASS" : "FAIL", current.passedCases, CASE_COUNT, restored, reason,
                current.advancements == null ? "; advancements were not captured"
                        : ", and the advancement file (same JSON as before the probe, original criterion times)");
    }
    private static void journal(Session current, boolean restored) throws Exception {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Restored", restored);
        tag.putString("Purpose", "Journal actual UI/input regression; synthetic logical/raw events, not human input");
        tag.put("Player", current.snapshot.player.copy()); tag.putLong("OriginalDayTime", current.snapshot.dayTime);
        tag.putBoolean("OriginalNaturalRegeneration", current.snapshot.naturalRegeneration);
        tag.putBoolean("OriginalMobSpawning", current.snapshot.mobSpawning);
        tag.putString("Dimension", current.snapshot.level.dimension().location().toString());
        ListTag cells = new ListTag(); for (BlockPos pos : current.cells) cells.add(NbtUtils.writeBlockPos(pos));
        tag.put("OriginallyAirCells", cells); tag.putBoolean("OriginallyAirWithoutBlockEntities", true);
        tag.putString("AdvancementsFile", "advancements/" + current.playerId + ".json"); tag.putString("AdvancementsBackup", ADVANCEMENT_BACKUP);
        NbtIo.writeCompressed(tag, current.server.getWorldPath(LevelResource.ROOT).resolve("jr-journal-probe-recovery.nbt"));
    }
    private static Path advancementFile(Session current) {
        return current.server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(current.playerId + ".json");
    }
    /** Revokes every completed criterion of the first-lesson advancement and returns what was there, for the log. */
    private static String clearLesson(MinecraftServer server, ServerPlayer player) {
        AdvancementHolder lesson = server.getAdvancements().get(StoryAdvancements.SYLPHIETTE_FIRST_LESSON);
        if (lesson == null) throw new IllegalStateException("S02 advancement is not loaded");
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(lesson);
        List<String> done = new ArrayList<>(); progress.getCompletedCriteria().forEach(done::add);
        List<String> cleared = new ArrayList<>();
        for (String criterion : done) {
            cleared.add(criterion + "@" + progress.getCriterion(criterion).getObtained());
            player.getAdvancements().revoke(lesson, criterion);
        }
        return cleared.isEmpty() ? "none" : String.join(",", cleared);
    }
    /**
     * Writes back the advancement file captured before the probe and reloads it: every criterion returns with its
     * original time and the client gets a reset, not a toast. Then proves the saved result is the same JSON.
     */
    private static void restoreAdvancements(Session current, ServerPlayer player) throws Exception {
        Path file = advancementFile(current);
        Files.write(file, current.advancements);
        player.getAdvancements().reload(current.server.getAdvancements());
        player.getAdvancements().save();
        if (!JsonParser.parseString(Files.readString(file)).equals(JsonParser.parseString(new String(current.advancements, StandardCharsets.UTF_8))))
            throw new IllegalStateException("Advancement file after reload differs from the copy taken before the probe");
    }
    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.KNOWLEDGE); player.syncData(ModAttachments.MANA); player.syncData(ModAttachments.CASTING);
        player.getInventory().setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }
    private static void requireThread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Server fixture must run on server thread"); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before probe finished");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before probe finished");
    }
    private JournalProbe() {}
}
