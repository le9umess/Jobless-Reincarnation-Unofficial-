package dev.joblessreincarnation.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingBoardMenu;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.IntStream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Disposable-world UI fixture only. Run restore before leaving; snapshots exist only in this server session. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class TrainingPreviewCommands {
    public static final String AUTO_PROPERTY = "jobless_reincarnation.trainingPreview";
    private static final String TEST_WORLD = "JR Magic 0.2.0 Test";
    private static final BlockPos BOARD_POS = new BlockPos(200, -59, 202);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, Map<UUID, Snapshot>> BACKUPS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Automatic>> AUTOMATIC = new WeakHashMap<>();
    private record Snapshot(MagicKnowledge knowledge, TrainingProgress training, List<ItemStack> inventory) {}
    private static final class Automatic {
        int ticksRemaining = 80;
        boolean attempted;
        TrainingBoardMenu menu;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jr_training_preview").requires(source -> source.hasPermission(2))
                .then(Commands.literal("ready").executes(context -> execute(context.getSource(), false)))
                .then(Commands.literal("restore").executes(context -> execute(context.getSource(), true))));
    }

    private static int execute(CommandSourceStack source, boolean restore) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!source.hasPermission(2) || !eligible(player)) {
            source.sendFailure(Component.literal("仅限独立开发测试世界 JR Magic 0.2.0 Test 的主世界，未做任何改动。"));
            return 0;
        }
        if (restore) {
            if (!restore(player, "manual command")) {
                source.sendSuccess(() -> Component.literal("当前服务器会话没有暂存快照，未做任何改动。"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal("已恢复首次 ready 前的魔术知识、练习进度和主背包。"), false);
            return 1;
        }
        ready(player);
        source.sendSuccess(() -> Component.literal("临时 UI 领奖夹具已就绪，不代表实际完成练习；测试后、退出前执行 /jr_training_preview restore。"), false);
        return 1;
    }

    private static boolean eligible(ServerPlayer player) {
        return player.createCommandSourceStack().hasPermission(2)
                && TEST_WORLD.equals(player.getServer().getWorldData().getLevelName())
                && player.level().dimension().equals(Level.OVERWORLD);
    }

    private static void remember(ServerPlayer player) {
        BACKUPS.computeIfAbsent(player.getServer(), server -> new HashMap<>()).computeIfAbsent(player.getUUID(), uuid -> new Snapshot(
                player.hasData(ModAttachments.KNOWLEDGE) ? copy(MagicKnowledge.CODEC, player.getData(ModAttachments.KNOWLEDGE)) : null,
                player.hasData(ModAttachments.TRAINING) ? copy(TrainingProgress.CODEC, player.getData(ModAttachments.TRAINING)) : null,
                IntStream.range(0, 36).mapToObj(slot -> player.getInventory().getItem(slot).copy()).toList()));
    }

    private static void ready(ServerPlayer player) {
        remember(player);
        MagicKnowledge knowledge = copy(MagicKnowledge.CODEC, player.getData(ModAttachments.KNOWLEDGE));
        knowledge.learn(WaterMagic.SPELL);
        while (knowledge.practiceCount(WaterMagic.SPELL) < TrainingTrials.CAST_GOAL) knowledge.practice(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.TRAINING, new TrainingProgress(TrainingTrials.HIT_GOAL, true, false));
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) player.getInventory().setItem(slot, new ItemStack(Items.STONE));
        }
        player.getInventory().setItem(9, ItemStack.EMPTY);
        sync(player);
        LOGGER.info("[JR_TRAINING_PREVIEW] READY {}: temporary UI fixture only; this is not earned practice", player.getUUID());
    }

    private static boolean restore(ServerPlayer player, String reason) {
        Map<UUID, Snapshot> snapshots = BACKUPS.get(player.getServer());
        Snapshot snapshot = snapshots == null ? null : snapshots.remove(player.getUUID());
        if (snapshot == null) return false;
        Map<UUID, Automatic> automatic = AUTOMATIC.get(player.getServer());
        if (automatic != null && automatic.containsKey(player.getUUID())) automatic.get(player.getUUID()).menu = null;
        // Remove tracking before sync/event callbacks so close, logout, and shutdown cannot restore twice.
        if (snapshot.knowledge() == null) player.removeData(ModAttachments.KNOWLEDGE);
        else player.setData(ModAttachments.KNOWLEDGE, copy(MagicKnowledge.CODEC, snapshot.knowledge()));
        if (snapshot.training() == null) player.removeData(ModAttachments.TRAINING);
        else player.setData(ModAttachments.TRAINING, copy(TrainingProgress.CODEC, snapshot.training()));
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, snapshot.inventory().get(slot).copy());
        sync(player);
        LOGGER.info("[JR_TRAINING_PREVIEW] RESTORED {}: original knowledge, training and 36 inventory slots ({})", player.getUUID(), reason);
        return true;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Boolean.getBoolean(AUTO_PROPERTY) || !(event.getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        AUTOMATIC.computeIfAbsent(player.getServer(), server -> new HashMap<>()).computeIfAbsent(player.getUUID(), uuid -> {
            LOGGER.info("[JR_TRAINING_PREVIEW] SCHEDULED {}: one automatic fixture after 80 server ticks", uuid);
            return new Automatic();
        });
    }

    @SubscribeEvent
    public static void afterServerTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean(AUTO_PROPERTY)) return;
        Map<UUID, Automatic> automatic = AUTOMATIC.get(event.getServer());
        if (automatic == null) return;
        for (var entry : automatic.entrySet()) {
            Automatic state = entry.getValue();
            if (state.attempted || --state.ticksRemaining > 0) continue;
            state.attempted = true;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !eligible(player)) continue;
            try {
                remember(player); // Preserve the original inventory before the dev function gives any items.
                CommandSourceStack source = player.createCommandSourceStack();
                event.getServer().getCommands().performPrefixedCommand(source, "function jobless_reincarnation:dev/training_preview");
                event.getServer().getCommands().performPrefixedCommand(source, "time set day");
                if (!player.level().getBlockState(BOARD_POS).is(ModContent.TRAINING_BOARD.get())) {
                    LOGGER.error("[JR_TRAINING_PREVIEW] ABORTED: fixture board is absent; restoring player snapshot");
                    restore(player, "board absent");
                    continue;
                }
                ready(player);
                var opened = player.openMenu(new SimpleMenuProvider((id, inventory, visitor) -> {
                    state.menu = new TrainingBoardMenu(id, inventory, BOARD_POS);
                    return state.menu;
                }, Component.translatable("container.jobless_reincarnation.training_board")), buffer -> buffer.writeBlockPos(BOARD_POS));
                if (opened.isEmpty()) restore(player, "menu open rejected");
                else LOGGER.info("[JR_TRAINING_PREVIEW] OPENED {}: real server board menu {}", player.getUUID(), opened.getAsInt());
            } catch (RuntimeException failure) {
                LOGGER.error("[JR_TRAINING_PREVIEW] ABORTED: restoring snapshot after automatic fixture failure", failure);
                restore(player, "automatic setup exception");
            }
        }
    }

    @SubscribeEvent
    public static void onBoardClosed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Map<UUID, Automatic> automatic = AUTOMATIC.get(player.getServer());
        Automatic state = automatic == null ? null : automatic.get(player.getUUID());
        if (state != null && state.menu == event.getContainer()) restore(player, "automatic board closed");
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // NeoForge fires logout before PlayerList saves the disconnecting player.
        if (event.getEntity() instanceof ServerPlayer player) restore(player, "logout cleanup before player save");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // On orderly shutdown this event precedes stopServer's player save.
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) restore(player, "server stopping cleanup before player save");
        BACKUPS.remove(event.getServer());
        AUTOMATIC.remove(event.getServer());
    }

    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.KNOWLEDGE);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
    }

    private static <T> T copy(Codec<T> codec, T value) {
        return codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()).getOrThrow();
    }

    private TrainingPreviewCommands() {}
}
