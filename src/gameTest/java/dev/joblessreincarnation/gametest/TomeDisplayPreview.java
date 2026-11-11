package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Opt-in visual inspection of the tomes in item frames (fixed display) and as dropped items (ground display).
 * Only temporary, never-saved entities are added; inventory, knowledge and terrain are untouched.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class TomeDisplayPreview {
    public static final String PROPERTY = "jobless_reincarnation.tomeDisplayPreview";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    public static final int VIEWS = 2;
    /** One frame per tome along +x, facing south; the dropped books lie in a parallel row in front of them. */
    private static final int ROW_X = 199, FRAME_Y = -59, FRAME_Z = 196;
    private static final double GROUND_Y = -59.95, GROUND_Z = 197.5;
    // View 0 faces the frames at eye level; view 1 steps back and looks down at the dropped books.
    private static final double[] VIEW_Z = { 198.4, 199.4 };
    private static final float[] VIEW_PITCH = { 0.0F, 40.0F };
    private static final float FACING_NORTH = 180.0F;

    public record State(boolean ready, boolean restored, boolean passed, int view, String message) {}
    private record Snapshot(Vec3 position, Vec3 motion, float yaw, float pitch, float headYaw, float fallDistance, long dayTime) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        final List<Entity> spawned = new ArrayList<>();
        Snapshot snapshot;
        int ticks, view;
        boolean finished;
        Session(ServerPlayer player) { server = player.getServer(); playerId = player.getUUID(); }
    }
    private static Session session;
    private static boolean attempted;
    private static volatile MinecraftServer publishedServer;
    private static volatile State published;

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static State state(MinecraftServer server) { return publishedServer == server ? published : null; }
    public static List<Item> tomes() {
        return List.of(ModContent.WATER_TOME.get(), ModContent.WATER_SHIELD_TOME.get(), ModContent.EARTH_TOME.get(), ModContent.AIR_TOME.get(), ModContent.HEALING_TOME.get(), ModContent.FIRE_TOME.get());
    }
    /** The row grows with the list, so another tome only needs to be added to {@link #tomes()}. */
    private static AABB displayArea() { return new AABB(ROW_X - 0.1, -60.0, 195.9, ROW_X + tomes().size() + 0.1, -57.9, 198.1); }
    private static Vec3 viewPosition(int view) { return new Vec3(ROW_X + tomes().size() / 2.0, -60.0, VIEW_Z[view]); }
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
        LogUtils.getLogger().info("[JR_TOME_DISPLAY_PREVIEW] SCHEDULED: private owner only, temporary entities, no terrain mutation");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 1600) { restore(current, false, "Visual preview timed out"); return; }
            if (current.ticks == 80) prepare(current, player);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_TOME_DISPLAY_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static boolean clear(ServerLevel level, AABB box, ServerPlayer player) {
        return level.noBlockCollision(null, box) && level.getEntities(player, box).isEmpty()
                && BlockPos.betweenClosedStream(box).allMatch(pos -> level.getFluidState(pos).isEmpty());
    }
    private static boolean sturdy(ServerLevel level, BlockPos pos) { return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP); }

    private static void prepare(Session current, ServerPlayer player) {
        ServerLevel level = current.server.overworld();
        List<Item> tomes = tomes();
        boolean floor = true;
        for (int index = 0; index < tomes.size(); index++) floor &= sturdy(level, new BlockPos(ROW_X + index, -61, 197));
        for (int view = 0; view < VIEWS; view++) {
            Vec3 position = viewPosition(view);
            floor &= sturdy(level, BlockPos.containing(position.x, -61.0, position.z));
            floor &= clear(level, player.getBoundingBox().move(position.subtract(player.position())), player);
        }
        if (!floor || !clear(level, displayArea(), player))
            throw new IllegalStateException("Display area has blocks, fluids, entities or no safe floor; nothing was spawned or moved");
        current.snapshot = new Snapshot(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                player.getYHeadRot(), player.fallDistance, level.getDayTime());
        for (int index = 0; index < tomes.size(); index++) {
            // Preserve vanilla entity types and renderers; transient fixtures cannot be serialized into the save.
            ItemFrame frame = new ItemFrame(level, new BlockPos(ROW_X + index, FRAME_Y, FRAME_Z), Direction.SOUTH) {
                @Override public boolean shouldBeSaved() { return false; }
            };
            frame.setSilent(true); frame.setInvulnerable(true);
            frame.setItem(new ItemStack(tomes.get(index)), false);
            CompoundTag frameData = new CompoundTag();
            frame.addAdditionalSaveData(frameData);
            frameData.putBoolean("Fixed", true);
            frameData.putFloat("ItemDropChance", 0.0F);
            frame.readAdditionalSaveData(frameData);
            // Vanilla Fixed=true survives without backing blocks and disables frame interaction/drops.
            if (!frame.survives()) throw new IllegalStateException("Fixed frame did not survive");
            ItemEntity ground = new ItemEntity(level, ROW_X + index + 0.5, GROUND_Y, GROUND_Z, new ItemStack(tomes.get(index)), 0.0, 0.0, 0.0) {
                @Override public boolean shouldBeSaved() { return false; }
            };
            // Keep age advancing so the real ground renderer still spins; NeverPickUp prevents pickup and merging.
            ground.setNeverPickUp(); ground.setExtendedLifetime(); ground.setInvulnerable(true);
            current.spawned.add(frame); current.spawned.add(ground);
            if (!level.addFreshEntity(frame) || !level.addFreshEntity(ground))
                throw new IllegalStateException("Could not add the temporary display entities");
        }
        level.setDayTime(6000L);
        look(current, player, 0);
        LogUtils.getLogger().info("[JR_TOME_DISPLAY_PREVIEW] READY: {} fixed frames and {} dropped books; inventory, knowledge and terrain untouched",
                tomes.size(), tomes.size());
    }

    private static void look(Session current, ServerPlayer player, int view) {
        current.view = view;
        Vec3 position = viewPosition(view);
        player.teleportTo(current.server.overworld(), position.x, position.y, position.z, FACING_NORTH, VIEW_PITCH[view]);
        player.setYHeadRot(FACING_NORTH); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0.0F;
        published = new State(true, false, false, view, "View " + view);
    }

    /** Invoked through the integrated-server executor by the explicitly enabled local fixture. */
    public static void view(MinecraftServer server, int view) {
        if (!server.isSameThread()) throw new IllegalStateException("View change must run on the server thread");
        Session current = session;
        if (current == null || current.finished || current.server != server || current.snapshot == null || view < 0 || view >= VIEWS) return;
        ServerPlayer player = server.getPlayerList().getPlayer(current.playerId);
        if (player != null) look(current, player, view);
    }
    public static void finish(MinecraftServer server, boolean passed, String reason) {
        if (!server.isSameThread()) throw new IllegalStateException("Restore must run on the server thread");
        if (session != null && session.server == server) restore(session, passed, reason);
    }

    private static void restore(Session current, boolean passed, String reason) {
        if (current == null || current.finished) return;
        current.finished = true;
        boolean success = true;
        try {
            // Exact object references only; never scan or discard unrelated entities.
            for (Entity entity : current.spawned) if (!entity.isRemoved()) entity.discard();
            Snapshot snapshot = current.snapshot;
            if (snapshot != null) {
                ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
                if (player == null) throw new IllegalStateException("Player missing during restore");
                current.server.overworld().setDayTime(snapshot.dayTime);
                player.teleportTo(current.server.overworld(), snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                player.setYHeadRot(snapshot.headYaw); player.setDeltaMovement(snapshot.motion); player.fallDistance = snapshot.fallDistance;
            }
        } catch (Exception failure) {
            success = false;
            LogUtils.getLogger().error("[JR_TOME_DISPLAY_PREVIEW] RESTORE_FAILED", failure);
        }
        published = new State(false, true, passed && success, -1, reason + "; cleanup=" + success);
        LogUtils.getLogger().info("[JR_TOME_DISPLAY_PREVIEW] {} RESTORED={} reason={}; temporary entities removed, no terrain writes were performed",
                passed && success ? "PASS" : "FAIL", success, reason);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before completion");
    }
    private TomeDisplayPreview() {}
}
