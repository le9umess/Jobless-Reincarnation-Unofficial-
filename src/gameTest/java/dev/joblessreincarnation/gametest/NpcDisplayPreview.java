package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Opt-in visual check of the Roxy candidate model in the real client: one temporary, never-saved display
 * entity, viewed from four sides and then playing each clip. Inventory, knowledge and terrain are untouched.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class NpcDisplayPreview {
    public static final String PROPERTY = "jobless_reincarnation.npcDisplayPreview";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    private static final Vec3 NPC_POS = new Vec3(201.5, -60.0, 195.5);
    /** The display entity faces south (+z); each view is where the player stands to look at it. */
    private record View(String name, Vec3 position, float yaw, float pitch) {}
    private static final View FRONT = new View("front", new Vec3(201.5, -60.0, 199.0), 180.0F, 8.0F);
    /** The player stands at Roxy's side facing the same way; the client films both from the front in third person. */
    private static final View BESIDE = new View("beside", new Vec3(202.6, -60.0, 195.5), 0.0F, 0.0F);
    private static final View[] VIEWS = { FRONT,
            new View("side", new Vec3(198.0, -60.0, 195.5), -90.0F, 8.0F),
            new View("back", new Vec3(201.5, -60.0, 192.0), 0.0F, 8.0F),
            new View("three_quarter", new Vec3(204.0, -60.0, 198.0), 135.0F, 8.0F),
            BESIDE,
            // Closer views for the moving clips: her staff side in profile, straight on, and from her front right.
            new View("side_close", new Vec3(198.8, -60.0, 195.5), -90.0F, 8.0F),
            new View("front_close", new Vec3(201.5, -60.0, 198.2), 180.0F, 8.0F),
            new View("front_right_close", new Vec3(199.6, -60.0, 197.4), -135.0F, 8.0F) };
    /**
     * Shot i shows clip SHOT_CLIPS[i] from SHOT_VIEWS[i] in SHOT_FRAMES[i] frames, FRAME_INTERVAL ticks apart: four sides
     * at idle; walking in profile and straight on (8 frames, more than one 1 s step); the lecture straight on, in profile
     * and from the front right (11 frames, more than its 1.6 s); last the player beside her for height and name tag.
     * Cast and sit are not filmed: the released NPC never plays them.
     */
    private static final int[] SHOT_VIEWS = { 0, 1, 2, 3, 5, 6, 6, 5, 7, 4 };
    private static final int[] SHOT_CLIPS = { 0, 0, 0, 0, 1, 1, 4, 4, 4, 0 };
    private static final int[] SHOT_FRAMES = { 1, 1, 1, 1, 8, 8, 11, 11, 11, 1 };
    public static final int FRAME_INTERVAL = 3;
    public static final int SHOTS = SHOT_VIEWS.length;

    public record State(boolean ready, boolean restored, boolean passed, int shot, String message) {}
    private record Snapshot(Vec3 position, Vec3 motion, float yaw, float pitch, float headYaw, float fallDistance, long dayTime) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        Snapshot snapshot;
        RoxyDisplayEntity npc;
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
    public static String shotName(int shot) { return VIEWS[SHOT_VIEWS[shot]].name() + "_" + RoxyDisplayEntity.CLIPS[SHOT_CLIPS[shot]]; }
    public static Vec3 shotPosition(int shot) { return VIEWS[SHOT_VIEWS[shot]].position(); }
    public static int shotClip(int shot) { return SHOT_CLIPS[shot]; }
    public static int shotFrames(int shot) { return SHOT_FRAMES[shot]; }
    public static boolean shotThirdPerson(int shot) { return VIEWS[SHOT_VIEWS[shot]] == BESIDE; }
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
        LogUtils.getLogger().info("[JR_NPC_DISPLAY_PREVIEW] SCHEDULED: private owner only, one temporary entity, no terrain mutation");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 2400) { restore(current, false, "Visual preview timed out"); return; }
            if (current.ticks == 80) prepare(current, player);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_NPC_DISPLAY_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static boolean clear(ServerLevel level, AABB box, ServerPlayer player) {
        return level.noBlockCollision(null, box) && level.getEntities(player, box).isEmpty()
                && BlockPos.betweenClosedStream(box).allMatch(pos -> level.getFluidState(pos).isEmpty());
    }
    private static boolean standable(ServerLevel level, Vec3 feet) {
        BlockPos below = BlockPos.containing(feet.x, feet.y - 1.0, feet.z);
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static void prepare(Session current, ServerPlayer player) {
        ServerLevel level = current.server.overworld();
        boolean ready = standable(level, NPC_POS) && clear(level, new AABB(NPC_POS.x - 0.6, NPC_POS.y, NPC_POS.z - 0.6, NPC_POS.x + 0.6, NPC_POS.y + 2.7, NPC_POS.z + 0.6), player);
        for (View view : VIEWS) ready &= standable(level, view.position()) && clear(level, player.getBoundingBox().move(view.position().subtract(player.position())), player);
        if (!ready) throw new IllegalStateException("Display area has blocks, fluids, entities or no safe floor; nothing was spawned or moved");
        current.snapshot = new Snapshot(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                player.getYHeadRot(), player.fallDistance, level.getDayTime());
        current.npc = NpcDisplayRegistry.spawnRoxy(level, NPC_POS, 0.0F);
        if (current.npc == null) throw new IllegalStateException("Display entity could not be created or added");
        // A visible name shows whether the name tag clears the hat in the side-by-side shot.
        current.npc.setCustomName(Component.literal("洛琪希"));
        current.npc.setCustomNameVisible(true);
        level.setDayTime(6000L);
        shot(current, player, 0);
        LogUtils.getLogger().info("[JR_NPC_DISPLAY_PREVIEW] READY: Roxy candidate via GeckoLib at {}; inventory, knowledge and terrain untouched", NPC_POS);
    }

    private static void shot(Session current, ServerPlayer player, int shot) {
        View view = VIEWS[SHOT_VIEWS[shot]];
        current.npc.setClip(SHOT_CLIPS[shot]);
        player.teleportTo(current.server.overworld(), view.position().x, view.position().y, view.position().z, view.yaw(), view.pitch());
        player.setYHeadRot(view.yaw()); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0.0F;
        published = new State(true, false, false, shot, "Shot " + shotName(shot));
    }

    /** Invoked through the integrated-server executor by the explicitly enabled local fixture. */
    public static void shot(MinecraftServer server, int shot) {
        if (!server.isSameThread()) throw new IllegalStateException("Shot change must run on the server thread");
        Session current = session;
        if (current == null || current.finished || current.server != server || current.npc == null || shot < 0 || shot >= SHOTS) return;
        ServerPlayer player = server.getPlayerList().getPlayer(current.playerId);
        if (player != null) shot(current, player, shot);
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
            // Exact object reference only; never scan or discard unrelated entities.
            if (current.npc != null && !current.npc.isRemoved()) current.npc.discard();
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
            LogUtils.getLogger().error("[JR_NPC_DISPLAY_PREVIEW] RESTORE_FAILED", failure);
        }
        published = new State(false, true, passed && success, -1, reason + "; cleanup=" + success);
        LogUtils.getLogger().info("[JR_NPC_DISPLAY_PREVIEW] {} RESTORED={} reason={}; display entity removed, no terrain writes were performed",
                passed && success ? "PASS" : "FAIL", success, reason);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before completion");
    }
    private NpcDisplayPreview() {}
}
