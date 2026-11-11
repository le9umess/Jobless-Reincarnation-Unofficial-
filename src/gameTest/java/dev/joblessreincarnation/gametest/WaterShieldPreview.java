package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.magic.WaterShieldSpell;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Opt-in visual check of the Water Shield: real releases through {@link WaterShieldSpell} and arrows fired at the
 * screen, filmed by {@code WaterShieldPreviewClient}. It never writes terrain; the screens are never saved, every
 * fixture arrow is removed, and the owner's state and the time of day are put back afterwards. When the build has the
 * Water Shield advancement, the owner's completed criteria in it are recorded first and restored by criterion: criteria
 * the scene added are revoked, criteria the owner already had are awarded back. A criterion the scene took back and
 * re-awarded gets a new completion time, so only the completed set, not the saved file, is restored as it was.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class WaterShieldPreview {
    public static final String PROPERTY = "jobless_reincarnation.waterShieldPreview";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    /** Neither yaw is a multiple of 90 degrees, so an unrotated or mirrored screen shows at once. */
    public static final float FRONT_YAW = 30.0F, OBLIQUE_YAW = -50.0F;
    /** The advancement a real stopped arrow grants the screen's owner (round A, 0.24.1). */
    public static final ResourceLocation BLOCK_ADVANCEMENT = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "story/water_shield_block");
    private static final float PITCH = 12.0F;
    /** Open ground needed around the caster: the screen, the side view and the third-person cameras all fit in it. */
    private static final int CLEAR = 8;
    public enum Step { CAST, ARROW, CAST_OBLIQUE, ARROW_HIGH, NIGHT_CAST, REVOKE_BLOCK_ADVANCEMENT, SCENE_BLOCK }
    public record State(boolean ready, boolean restored, boolean passed, int steps, String message) {}
    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw, float pitch, long dayTime) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        final List<Arrow> arrows = new ArrayList<>();
        Snapshot snapshot;
        Vec3 stand;
        WaterShieldEntity shield;
        /** Criteria of the block advancement the owner had before the preview, or null when the build has none. */
        Set<String> advancementBefore;
        int ticks, steps;
        boolean finished;
        Session(ServerPlayer player) { server = player.getServer(); playerId = player.getUUID(); }
    }
    private static Session session;
    private static boolean attempted;
    private static volatile MinecraftServer publishedServer;
    private static volatile State published;

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static State state(MinecraftServer server) { return publishedServer == server ? published : null; }
    private static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return enabled() && server != null && !server.isDedicatedServer() && !server.isPublished()
                && server.isSingleplayerOwner(player.getGameProfile()) && WORLD.equals(server.getWorldData().getLevelName())
                && player.level().dimension() == Level.OVERWORLD && player.isAlive() && !player.isSpectator();
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (attempted || !(event.getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        attempted = true; session = new Session(player); publishedServer = session.server;
        published = new State(false, false, false, 0, "Awaiting private-world initialization");
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] SCHEDULED: private owner only, no terrain mutation");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 2400) { restore(current, false, "Visual preview timed out"); return; }
            if (current.ticks == 80) prepare(current, player);
            for (Arrow arrow : current.arrows) {
                if (!arrow.isRemoved() && arrow.tickCount > 20) { restore(current, false, "A fixture arrow was not stopped at " + arrow.position()); return; }
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_WATER_SHIELD_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static void prepare(Session current, ServerPlayer player) throws Exception {
        current.snapshot = new Snapshot(player.saveWithoutId(new CompoundTag()), player.serverLevel(), player.gameMode.getGameModeForPlayer(),
                player.position(), player.getYRot(), player.getXRot(), current.server.overworld().getDayTime());
        journal(current, false);
        AdvancementHolder advancement = current.server.getAdvancements().get(BLOCK_ADVANCEMENT);
        if (advancement != null) {
            current.advancementBefore = new HashSet<>();
            player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria().forEach(current.advancementBefore::add);
        }
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] ADVANCEMENT {} before={}", BLOCK_ADVANCEMENT,
                advancement == null ? "absent in this build" : current.advancementBefore);
        current.stand = stand(current.snapshot.level, current.snapshot.position);
        player.closeContainer(); SpellCasting.cancel(player);
        player.setGameMode(GameType.SURVIVAL);
        // Stray mobs or a missed arrow must not hurt the owner; the saved abilities come back on restore.
        player.getAbilities().invulnerable = true; player.onUpdateAbilities();
        MagicKnowledge knowledge = new MagicKnowledge(); knowledge.learn(WaterShieldSpell.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        CastingData casting = new CastingData(); casting.select(WaterShieldSpell.SPELL); player.setData(ModAttachments.CASTING, casting);
        // A clean hotbar: the staff and the new book only. The saved inventory comes back on restore.
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(ModContent.TRAINING_STAFF.get()));
        player.getInventory().setItem(1, new ItemStack(ModContent.WATER_SHIELD_TOME.get()));
        player.getInventory().selected = 0;
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.setShiftKeyDown(false); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        current.server.overworld().setDayTime(6000L);
        player.teleportTo(current.snapshot.level, current.stand.x, current.stand.y, current.stand.z, FRONT_YAW, PITCH);
        player.setYHeadRot(FRONT_YAW);
        sync(player);
        published = new State(true, false, false, 0, "Ready");
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] READY at {}; real releases and fixture arrows only; no terrain writes", current.stand);
    }

    /** Runs one step of the scene for the client half of the fixture; invoked through the integrated-server executor. */
    public static void perform(MinecraftServer server, Step step) {
        if (!server.isSameThread()) throw new IllegalStateException("Steps must run on the server thread");
        Session current = session;
        if (current == null || current.finished || current.server != server || current.snapshot == null) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            switch (step) {
                case CAST -> release(current, player, FRONT_YAW);
                case ARROW -> fire(current, 0.0, 0.0);
                case CAST_OBLIQUE -> { release(current, player, OBLIQUE_YAW); watchFromSide(current, player); }
                case ARROW_HIGH -> fire(current, -0.9, 0.55);
                case NIGHT_CAST -> { current.server.overworld().setDayTime(18000L); release(current, player, FRONT_YAW); }
                // So each language's scene shows a real first completion: take the node back, then stop one arrow.
                case REVOKE_BLOCK_ADVANCEMENT -> revoke(player, advancement(current));
                case SCENE_BLOCK -> { advancement(current); release(current, player, FRONT_YAW); fire(current, 0.0, 0.0); }
            }
            published = new State(true, false, false, ++current.steps, step + " done");
            LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] STEP {} {}", current.steps, step);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_WATER_SHIELD_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static AdvancementHolder advancement(Session current) {
        AdvancementHolder holder = current.server.getAdvancements().get(BLOCK_ADVANCEMENT);
        if (holder == null || current.advancementBefore == null) throw new IllegalStateException("This build has no " + BLOCK_ADVANCEMENT);
        return holder;
    }

    private static void revoke(ServerPlayer player, AdvancementHolder holder) {
        List<String> completed = new ArrayList<>();
        player.getAdvancements().getOrStartProgress(holder).getCompletedCriteria().forEach(completed::add);
        for (String criterion : completed) player.getAdvancements().revoke(holder, criterion);
    }

    /** A real release from the scene's standing spot; the cooldown and mana are reset first, as a fresh caster's would be. */
    private static void release(Session current, ServerPlayer player, float yaw) {
        player.teleportTo(current.snapshot.level, current.stand.x, current.stand.y, current.stand.z, yaw, PITCH);
        player.setYHeadRot(yaw);
        player.getInventory().selected = 0;
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.setData(ModAttachments.MANA, ManaData.full(100));
        sync(player);
        if (!WaterShieldSpell.cast(player)) throw new IllegalStateException("Water Shield release refused at " + player.position());
        List<WaterShieldEntity> shields = current.snapshot.level.getEntitiesOfClass(WaterShieldEntity.class,
                player.getBoundingBox().inflate(16.0), shield -> current.playerId.equals(shield.ownerId()));
        if (shields.size() != 1) throw new IllegalStateException("Expected exactly one screen, found " + shields.size());
        current.shield = shields.getFirst();
    }

    /** Moves the owner in front of and beside the screen, looking at its middle; the screen stays where it formed. */
    private static void watchFromSide(Session current, ServerPlayer player) {
        WaterShieldEntity shield = current.shield;
        Vec3 feet = shield.position().add(shield.facing().scale(3.5)).add(shield.across().scale(3.0));
        Vec3 look = shield.position().add(0.0, WaterShieldEntity.HEIGHT * 0.5, 0.0).subtract(feet.add(0.0, player.getEyeHeight(), 0.0));
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(look.y, look.horizontalDistance()));
        player.teleportTo(current.snapshot.level, feet.x, feet.y, feet.z, yaw, pitch);
        player.setYHeadRot(yaw);
    }

    /** An unowned arrow from the far side, aimed at a point on the screen given across and up from its middle. */
    private static void fire(Session current, double across, double up) {
        WaterShieldEntity shield = current.shield;
        if (shield == null || shield.isRemoved()) throw new IllegalStateException("No screen to aim at");
        Vec3 target = shield.position().add(0.0, WaterShieldEntity.HEIGHT * 0.5 + up, 0.0).add(shield.across().scale(across));
        Vec3 from = target.add(shield.facing().scale(6.0));
        Arrow arrow = new Arrow(current.snapshot.level, from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        Vec3 path = target.subtract(from);
        arrow.shoot(path.x, path.y, path.z, 2.0F, 0.0F);
        if (!current.snapshot.level.addFreshEntity(arrow)) throw new IllegalStateException("Fixture arrow not admitted");
        current.arrows.add(arrow);
    }

    /** The owner's own spot if the ground around it is flat and open, else the nearest such spot along the axes. */
    private static Vec3 stand(ServerLevel level, Vec3 origin) {
        int[][] offsets = {{0, 0}, {32, 0}, {-32, 0}, {0, 32}, {0, -32}, {64, 0}, {-64, 0}, {0, 64}, {0, -64}};
        for (int[] offset : offsets) {
            BlockPos column = BlockPos.containing(origin.x + offset[0], origin.y, origin.z + offset[1]);
            BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            if (open(level, feet)) return Vec3.atBottomCenterOf(feet);
        }
        throw new IllegalStateException("No open, flat ground near " + origin);
    }

    private static boolean open(ServerLevel level, BlockPos feet) {
        for (int dx = -CLEAR; dx <= CLEAR; dx++) {
            for (int dz = -CLEAR; dz <= CLEAR; dz++) {
                BlockPos ground = feet.offset(dx, -1, dz);
                if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) || !level.getFluidState(ground).isEmpty()) return false;
                for (int dy = 0; dy < 4; dy++) {
                    BlockPos air = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(air).getCollisionShape(level, air).isEmpty() || !level.getFluidState(air).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Invoked through the integrated-server executor by the explicitly enabled local fixture. */
    public static void finish(MinecraftServer server, boolean passed, String reason) {
        if (!server.isSameThread()) throw new IllegalStateException("Restore must run on the server thread");
        if (session != null && session.server == server) restore(session, passed, reason);
    }

    private static void restore(Session current, boolean passed, String reason) {
        if (current == null || current.finished) return;
        current.finished = true;
        boolean success = true;
        try {
            for (Arrow arrow : current.arrows) if (!arrow.isRemoved()) arrow.discard();
            Snapshot snapshot = current.snapshot;
            if (snapshot != null) {
                for (WaterShieldEntity shield : snapshot.level.getEntitiesOfClass(WaterShieldEntity.class,
                        new AABB(current.stand, current.stand).inflate(64.0), shield -> current.playerId.equals(shield.ownerId())))
                    shield.discard();
                ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
                if (player == null) throw new IllegalStateException("Player missing during restore; recovery journal retained");
                SpellCasting.cancel(player);
                player.removeData(ModAttachments.KNOWLEDGE); player.removeData(ModAttachments.MANA); player.removeData(ModAttachments.CASTING);
                player.load(snapshot.player.copy()); player.setGameMode(snapshot.mode);
                player.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                if (player.hasData(ModAttachments.CASTING)) player.getData(ModAttachments.CASTING).clearActive();
                player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
                if (player.hasData(ModAttachments.MANA) && player.getData(ModAttachments.MANA).cooldown() > 0)
                    player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), player.getData(ModAttachments.MANA).cooldown());
                current.server.overworld().setDayTime(snapshot.dayTime);
                restoreAdvancement(current, player);
                sync(player); journal(current, true);
            }
        } catch (Exception failure) {
            success = false;
            LogUtils.getLogger().error("[JR_WATER_SHIELD_PREVIEW] RESTORE_FAILED: recovery journal retained", failure);
        }
        published = new State(false, true, passed && success, current.steps, reason + "; cleanup=" + success);
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] {} RESTORED={} reason={}; no terrain writes were performed",
                passed && success ? "PASS" : "FAIL", success, reason);
    }

    /** Revokes only what the preview added and gives back what the owner had; advancements are not in the player NBT. */
    private static void restoreAdvancement(Session current, ServerPlayer player) {
        if (current.advancementBefore == null) return;
        AdvancementHolder holder = current.server.getAdvancements().get(BLOCK_ADVANCEMENT);
        if (holder == null) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        List<String> added = new ArrayList<>();
        progress.getCompletedCriteria().forEach(criterion -> { if (!current.advancementBefore.contains(criterion)) added.add(criterion); });
        for (String criterion : added) player.getAdvancements().revoke(holder, criterion);
        for (String criterion : current.advancementBefore) player.getAdvancements().award(holder, criterion);
        List<String> after = new ArrayList<>();
        player.getAdvancements().getOrStartProgress(holder).getCompletedCriteria().forEach(after::add);
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW] ADVANCEMENT_RESTORED {} before={} revoked={} now={}", BLOCK_ADVANCEMENT,
                current.advancementBefore, added, after);
    }

    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.KNOWLEDGE); player.syncData(ModAttachments.MANA); player.syncData(ModAttachments.CASTING);
        player.getInventory().setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }
    private static void journal(Session current, boolean restored) throws Exception {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Restored", restored);
        tag.putString("Purpose", "Water Shield visual preview; original player and time; no terrain mutations");
        tag.put("Player", current.snapshot.player.copy()); tag.putLong("OriginalDayTime", current.snapshot.dayTime);
        tag.putString("Dimension", current.snapshot.level.dimension().location().toString());
        NbtIo.writeCompressed(tag, current.server.getWorldPath(LevelResource.ROOT).resolve("jr-water-shield-preview-recovery.nbt"));
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before completion");
    }
    private WaterShieldPreview() {}
}
