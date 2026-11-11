package dev.joblessreincarnation.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Temporary ground/fixed display inspection in the private development world only. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class DisplayPreviewCommands {
    private static final String TEST_WORLD = "JR Magic 0.2.0 Test";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockPos FRAME_POS = new BlockPos(206, -59, 203);
    private static final BlockPos GROUND_FLOOR = new BlockPos(208, -61, 203);
    private static final Vec3 VIEW_POS = new Vec3(207.0, -60.0, 200.0);
    private static final AABB DISPLAY_AREA = new AABB(205.9, -60.0, 202.5, 209.0, -57.9, 204.4);
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();

    private record Snapshot(Vec3 position, Vec3 motion, float yaw, float pitch,
            float headYaw, float fallDistance, long dayTime) {}
    private record Session(Snapshot snapshot, UUID sessionId, ItemFrame frame, ItemEntity ground) {
        String tag() { return "jr_display_preview:" + sessionId; }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jr_display_preview")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("ready").executes(context -> execute(context.getSource(), false)))
                .then(Commands.literal("end").executes(context -> execute(context.getSource(), true))));
    }

    private static int execute(CommandSourceStack source, boolean end) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        // Cleanup remains available if the owner leaves the fixture area or changes dimension.
        if (end) {
            boolean restored = restore(player, "manual end");
            source.sendSuccess(() -> Component.literal(restored
                    ? "已移除本次展示实体，并恢复首次 ready 前的位置、姿态、速度与时间。"
                    : "当前没有展示预览会话，未做任何改动。"), false);
            return restored ? 1 : 0;
        }
        if (!eligible(player)) {
            source.sendFailure(Component.literal("仅限私人单人测试世界 JR Magic 0.2.0 Test 主世界的管理员存档所有者。"));
            return 0;
        }
        MinecraftServer server = player.getServer();
        Map<UUID, Session> sessions = SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        if (sessions.containsKey(player.getUUID())) {
            source.sendSuccess(() -> Component.literal("展示预览已就绪；保留首次快照，不重复生成实体。结束请执行 /jr_display_preview end。"), false);
            return 1;
        }
        if (WandPreviewControls.state(server, player.getUUID()) != null) {
            source.sendFailure(Component.literal("请先结束施法预览，再开始物品显示预览。"));
            return 0;
        }
        ServerLevel level = server.overworld();
        AABB viewerBox = player.getBoundingBox().move(VIEW_POS.subtract(player.position()));
        if (!clear(level, DISPLAY_AREA, player) || !clear(level, viewerBox, player)
                || !level.getBlockState(GROUND_FLOOR).isFaceSturdy(level, GROUND_FLOOR, Direction.UP)
                || !level.getBlockState(new BlockPos(207, -61, 200)).isFaceSturdy(level, new BlockPos(207, -61, 200), Direction.UP)) {
            source.sendFailure(Component.literal("展示区域存在方块、流体、实体或缺少安全地面；未移动玩家或改动世界。"));
            return 0;
        }
        try {
            prepare(player, sessions);
            source.sendSuccess(() -> Component.literal("展示框使用 fixed，地面魔杖使用 ground；仅临时实体，背包与练习进度未改。结束：/jr_display_preview end。"), false);
            return 1;
        } catch (RuntimeException failure) {
            LOGGER.error("[JR_DISPLAY_PREVIEW] Setup failed; restoring the original scene", failure);
            restore(player, "setup failure");
            source.sendFailure(Component.literal("展示预览未能创建，已执行清理并恢复快照。"));
            return 0;
        }
    }

    private static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && !server.isDedicatedServer() && !server.isPublished()
                && server.isSingleplayerOwner(player.getGameProfile())
                && player.createCommandSourceStack().hasPermission(2)
                && TEST_WORLD.equals(server.getWorldData().getLevelName())
                && player.level().dimension().equals(Level.OVERWORLD)
                && player.isAlive() && !player.isSpectator();
    }

    private static boolean clear(ServerLevel level, AABB box, ServerPlayer player) {
        return level.noBlockCollision(null, box)
                && level.getEntities(player, box).isEmpty()
                && BlockPos.betweenClosedStream(box).allMatch(pos -> level.getFluidState(pos).isEmpty());
    }

    private static void prepare(ServerPlayer player, Map<UUID, Session> sessions) {
        ServerLevel level = player.getServer().overworld();
        Snapshot snapshot = new Snapshot(player.position(), player.getDeltaMovement(), player.getYRot(),
                player.getXRot(), player.getYHeadRot(), player.fallDistance, level.getDayTime());
        // Preserve vanilla entity types and renderers; transient fixtures cannot be serialized into the save.
        ItemFrame frame = new ItemFrame(level, FRAME_POS, Direction.NORTH) {
            @Override public boolean shouldBeSaved() { return false; }
        };
        ItemEntity ground = new ItemEntity(level, 208.5, -59.95, 203.3,
                new ItemStack(ModContent.TRAINING_STAFF.get()), 0.0, 0.0, 0.0) {
            @Override public boolean shouldBeSaved() { return false; }
        };
        Session session = new Session(snapshot, UUID.randomUUID(), frame, ground);
        sessions.put(player.getUUID(), session);
        frame.addTag(session.tag());
        ground.addTag(session.tag());
        frame.setSilent(true);
        frame.setInvulnerable(true);
        frame.setItem(new ItemStack(ModContent.TRAINING_STAFF.get()), false);
        CompoundTag frameData = new CompoundTag();
        frame.addAdditionalSaveData(frameData);
        frameData.putBoolean("Fixed", true);
        frameData.putFloat("ItemDropChance", 0.0F);
        frame.readAdditionalSaveData(frameData);
        // Vanilla Fixed=true survives without backing blocks and disables frame interaction/drops.
        if (!frame.survives()) throw new IllegalStateException("Fixed frame did not survive");
        ground.setNeverPickUp();
        // Keep age advancing so the real ground renderer still spins; NeverPickUp prevents item merging.
        ground.setExtendedLifetime();
        ground.setInvulnerable(true);
        if (!level.addFreshEntity(frame) || !level.addFreshEntity(ground))
            throw new IllegalStateException("Could not add both temporary display entities");
        level.setDayTime(6000L);
        player.teleportTo(level, VIEW_POS.x, VIEW_POS.y, VIEW_POS.z, 0.0F, 15.0F);
        player.setYHeadRot(0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        LOGGER.info("[JR_DISPLAY_PREVIEW] READY player={}, session={}, frame={}, ground={}; inventory/knowledge/training untouched",
                player.getUUID(), session.sessionId(), frame.getUUID(), ground.getUUID());
    }

    private static boolean restore(ServerPlayer player, String reason) {
        MinecraftServer server = player.getServer();
        Map<UUID, Session> sessions = SESSIONS.get(server);
        Session session = sessions == null ? null : sessions.remove(player.getUUID());
        if (session == null) return false;
        discard(session);
        Snapshot snapshot = session.snapshot();
        server.overworld().setDayTime(snapshot.dayTime());
        player.teleportTo(server.overworld(), snapshot.position().x, snapshot.position().y,
                snapshot.position().z, snapshot.yaw(), snapshot.pitch());
        player.setYHeadRot(snapshot.headYaw());
        player.setDeltaMovement(snapshot.motion());
        player.fallDistance = snapshot.fallDistance();
        LOGGER.info("[JR_DISPLAY_PREVIEW] RESTORED player={}, session={}; own entities removed, original scene restored ({})",
                player.getUUID(), session.sessionId(), reason);
        return true;
    }

    private static void discard(Session session) {
        // Exact object references plus the unique session tag; never scan or discard unrelated entities.
        for (Entity entity : List.of(session.frame(), session.ground())) {
            if (!entity.isRemoved() && entity.getTags().contains(session.tag())) entity.discard();
        }
    }

    @SubscribeEvent
    public static void afterServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<UUID, Session> sessions = SESSIONS.get(server);
        if (sessions == null) return;
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && (!eligible(player) || player.position().distanceToSqr(VIEW_POS) > 24.0 * 24.0))
                restore(player, "preview eligibility or viewing area ended");
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) restore(player, "logout before player save");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) restore(player, "server stopping before player save");
        Map<UUID, Session> remaining = SESSIONS.remove(server);
        if (remaining != null) remaining.values().forEach(DisplayPreviewCommands::discard);
    }

    private DisplayPreviewCommands() {}
}
