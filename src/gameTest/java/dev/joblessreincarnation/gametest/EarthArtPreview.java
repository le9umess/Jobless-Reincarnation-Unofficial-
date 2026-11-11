package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in visual inspection fixture. It never writes terrain or casts a spell. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class EarthArtPreview {
    public static final String PROPERTY = "jobless_reincarnation.earthArtPreview";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    public record State(boolean ready, boolean restored, boolean passed, int selectedSlot, String message) {}
    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw, float pitch, long dayTime) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        Snapshot snapshot;
        int ticks;
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
        published = new State(false, false, false, -1, "Awaiting private-world initialization");
        LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW] SCHEDULED: private owner only, no terrain mutation");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 1600) { restore(current, false, "Visual preview timed out"); return; }
            if (current.ticks == 80) prepare(current, player);
            if (current.snapshot != null) published = new State(true, false, false, player.getInventory().selected, "Prepared with original state saved");
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_EARTH_ART_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static void prepare(Session current, ServerPlayer player) throws Exception {
        current.snapshot = new Snapshot(player.saveWithoutId(new CompoundTag()), player.serverLevel(), player.gameMode.getGameModeForPlayer(),
                player.position(), player.getYRot(), player.getXRot(), current.server.overworld().getDayTime());
        journal(current, false);
        player.closeContainer(); SpellCasting.cancel(player);
        player.setGameMode(GameType.SURVIVAL);
        MagicKnowledge knowledge = new MagicKnowledge(); knowledge.learn(EarthWallSpell.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.MANA, new ManaData(100, 100, 6000, 0));
        CastingData casting = new CastingData(); casting.select(EarthWallSpell.SPELL); player.setData(ModAttachments.CASTING, casting);
        player.getInventory().setItem(0, new ItemStack(ModContent.TRAINING_STAFF.get()));
        player.getInventory().setItem(1, new ItemStack(ModContent.EARTH_TOME.get()));
        player.getInventory().selected = 0;
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.setShiftKeyDown(false); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        current.server.overworld().setDayTime(6000L);
        BlockPos origin = BlockPos.containing(current.snapshot.position);
        boolean found = false;
        for (int offset = 10; offset <= 28 && !found; offset += 3) {
            int x = origin.getX() + offset, z = origin.getZ() + 8;
            BlockPos probe = new BlockPos(x, origin.getY(), z);
            if (!player.serverLevel().hasChunkAt(probe) || !player.serverLevel().hasChunkAt(probe.offset(4, 0, -5))) continue;
            int y = player.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            player.teleportTo(current.server.overworld(), x + 0.5, y, z - 3.5, 0, 0);
            Vec3 delta = new Vec3(x + 0.5, y, z + 0.5).subtract(player.getEyePosition());
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            player.teleportTo(current.server.overworld(), player.getX(), player.getY(), player.getZ(), 0, pitch);
            player.setYHeadRot(0);
            found = EarthWallSpell.plan(player).valid();
        }
        if (!found) throw new IllegalStateException("No empty supported preview site near the owner");
        sync(player);
        published = new State(true, false, false, 0, "Ready");
        LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW] READY at {}; only geometry is previewed, no blocks placed", player.position());
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
            Snapshot snapshot = current.snapshot;
            if (snapshot != null) {
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
                sync(player); journal(current, true);
            }
        } catch (Exception failure) {
            success = false;
            LogUtils.getLogger().error("[JR_EARTH_ART_PREVIEW] RESTORE_FAILED: recovery journal retained", failure);
        }
        published = new State(false, true, passed && success, -1, reason + "; cleanup=" + success);
        LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW] {} RESTORED={} reason={}; no terrain writes were performed",
                passed && success ? "PASS" : "FAIL", success, reason);
    }

    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.KNOWLEDGE); player.syncData(ModAttachments.MANA); player.syncData(ModAttachments.CASTING);
        player.getInventory().setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }
    private static void journal(Session current, boolean restored) throws Exception {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Restored", restored);
        tag.putString("Purpose", "Earth wall art preview; original player and time; no terrain mutations");
        tag.put("Player", current.snapshot.player.copy()); tag.putLong("OriginalDayTime", current.snapshot.dayTime);
        tag.putString("Dimension", current.snapshot.level.dimension().location().toString());
        NbtIo.writeCompressed(tag, current.server.getWorldPath(LevelResource.ROOT).resolve("jr-earth-art-preview-recovery.nbt"));
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before completion");
    }
    private EarthArtPreview() {}
}
