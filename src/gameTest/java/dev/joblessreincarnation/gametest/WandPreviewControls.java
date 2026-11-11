package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterControl;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingProgress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Disposable integrated-server visual fixture. Never included in the release JAR. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class WandPreviewControls {
    public static final String AUTO_PROPERTY = "jobless_reincarnation.wandPreview";
    private static final String TEST_WORLD = "JR Magic 0.2.0 Test";
    private static final Logger LOGGER = LogUtils.getLogger();
    // These two maps belong exclusively to the server thread.
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Automatic>> AUTOMATIC = new WeakHashMap<>();
    // Only immutable receipts cross into the integrated client's thread.
    private static final Map<MinecraftServer, Map<UUID, State>> PUBLISHED = new ConcurrentHashMap<>();

    public enum Action { IDLE, BEGIN, FIRE, SWAP_HAND, END }

    public record State(boolean using, HumanoidArm mainArm, int originalSlot, HumanoidArm originalArm) {}

    private record Snapshot(MagicKnowledge knowledge, ManaData mana, TrainingProgress training, CastingData casting,
            List<ItemStack> inventory, int selectedSlot, HumanoidArm mainArm, Vec3 position,
            Vec3 motion, float yaw, float pitch, float fallDistance, Difficulty difficulty,
            long dayTime, Set<UUID> existingBolts, boolean sneaking) {}

    private record Session(Snapshot snapshot) {}

    private static final class Automatic {
        int remaining = 80;
        boolean attempted;
    }

    public static boolean enabled() {
        return Boolean.getBoolean(AUTO_PROPERTY) && !Boolean.getBoolean(TrainingPreviewCommands.AUTO_PROPERTY);
    }

    /** Thread-safe, read-only receipt; no client class or live server entity escapes. */
    public static State state(MinecraftServer server, UUID playerId) {
        Map<UUID, State> states = PUBLISHED.get(server);
        return states == null ? null : states.get(playerId);
    }

    private static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return (enabled() || InputProbeControls.enabled()) && server != null && !server.isDedicatedServer() && !server.isPublished()
                && server.isSingleplayerOwner(player.getGameProfile())
                && player.createCommandSourceStack().hasPermission(2)
                && TEST_WORLD.equals(server.getWorldData().getLevelName())
                && player.level().dimension().equals(Level.OVERWORLD)
                && player.isAlive() && !player.isSpectator();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        AUTOMATIC.computeIfAbsent(player.getServer(), ignored -> new HashMap<>()).put(player.getUUID(), new Automatic());
        LOGGER.info("[JR_WAND_PREVIEW] SCHEDULED {}: disposable integrated-world fixture after 80 server ticks", player.getUUID());
    }

    @SubscribeEvent
    public static void afterServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<UUID, Automatic> automatic = AUTOMATIC.get(server);
        if (automatic != null) {
            for (var entry : automatic.entrySet()) {
                Automatic pending = entry.getValue();
                if (pending.attempted || --pending.remaining > 0) continue;
                pending.attempted = true;
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null || !eligible(player)) continue;
                try {
                    prepare(player);
                } catch (RuntimeException failure) {
                    LOGGER.error("[JR_WAND_PREVIEW] ABORTED: restoring snapshot after setup failure", failure);
                    restore(player, "setup failure");
                }
            }
        }
        Map<UUID, Session> sessions = SESSIONS.get(server);
        if (sessions == null) return;
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue; // Logout restores before the player is removed/saved.
            if (!eligible(player)) restore(player, "preview eligibility ended");
            else publish(player, sessions.get(playerId));
        }
    }

    private static void prepare(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Map<UUID, Session> sessions = SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        if (sessions.containsKey(player.getUUID())) return;
        Snapshot snapshot = new Snapshot(
                player.hasData(ModAttachments.KNOWLEDGE) ? copy(MagicKnowledge.CODEC, player.getData(ModAttachments.KNOWLEDGE)) : null,
                player.hasData(ModAttachments.MANA) ? copy(ManaData.CODEC, player.getData(ModAttachments.MANA)) : null,
                player.hasData(ModAttachments.TRAINING) ? copy(TrainingProgress.CODEC, player.getData(ModAttachments.TRAINING)) : null,
                player.hasData(ModAttachments.CASTING) ? copy(CastingData.CODEC, player.getData(ModAttachments.CASTING)) : null,
                IntStream.range(0, 36).mapToObj(slot -> player.getInventory().getItem(slot).copy()).toList(),
                player.getInventory().selected, player.getMainArm(), player.position(), player.getDeltaMovement(),
                player.getYRot(), player.getXRot(), player.fallDistance, server.getWorldData().getDifficulty(),
                server.overworld().getDayTime(),
                player.serverLevel().getEntities(ModContent.WATER_BOLT.get(), bolt -> bolt.getOwner() == player).stream()
                        .map(bolt -> bolt.getUUID()).collect(Collectors.toSet()), player.isShiftKeyDown());
        Session session = new Session(snapshot);
        sessions.put(player.getUUID(), session); // All subsequent mutations have a cleanup snapshot.
        player.stopUsingItem();
        MagicKnowledge knowledge = copy(MagicKnowledge.CODEC, player.getData(ModAttachments.KNOWLEDGE));
        knowledge.learn(WaterMagic.SPELL);
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.MANA, ManaData.full(MagicConfig.MAX_MANA.getAsInt()));
        player.setData(ModAttachments.CASTING, new CastingData());
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.getInventory().setItem(0, new ItemStack(ModContent.TRAINING_STAFF.get()));
        player.getInventory().selected = 0;
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.overworld().setDayTime(6000L);
        player.teleportTo(server.overworld(), 206.5, -60.0, 199.5, 0.0F, 0.0F);
        player.setYHeadRot(0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setShiftKeyDown(false);
        sync(player);
        publish(player, session);
        LOGGER.info("[JR_WAND_PREVIEW] READY {}: temporary learned spell/full mana/wand; original state saved, not earned practice", player.getUUID());
    }

    /** Called only through the integrated server's executor, never through custom packets. */
    public static boolean beginInputProbe(ServerPlayer player) {
        if (!InputProbeControls.enabled() || !eligible(player)) return false;
        prepare(player);
        return state(player.getServer(), player.getUUID()) != null;
    }

    public static void endInputProbe(ServerPlayer player, String reason) {
        restore(player, "input probe: " + reason);
    }

    /** Called only through the integrated server's executor, never through custom packets. */
    public static void perform(MinecraftServer server, UUID playerId, Action action) {
        if (!server.isSameThread()) throw new IllegalStateException("Wand preview actions require the server thread");
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        Map<UUID, Session> sessions = SESSIONS.get(server);
        Session session = sessions == null ? null : sessions.get(playerId);
        if (session == null) return;
        if (action == Action.END || !eligible(player)) {
            restore(player, action == Action.END ? "screen closed" : "action eligibility check");
            return;
        }
        switch (action) {
            case IDLE -> {
                player.stopUsingItem();
                LOGGER.info("[JR_WAND_PREVIEW] IDLE {}", playerId);
            }
            case BEGIN -> {
                player.stopUsingItem();
                if (WaterMagic.canBeginCast(player)) {
                    player.startUsingItem(InteractionHand.MAIN_HAND);
                    LOGGER.info("[JR_WAND_PREVIEW] BEGIN {}: arm={}, chant={} ticks", playerId, player.getMainArm(), WaterMagic.chantTicks(player));
                } else LOGGER.info("[JR_WAND_PREVIEW] BEGIN_REJECTED {}: ordinary spell requirements/cooldown apply", playerId);
            }
            case FIRE -> {
                if (player.isUsingItem()) {
                    int ticks = player.getTicksUsingItem();
                    float charge = WaterControl.charge(player, ticks);
                    int oldMana = player.getData(ModAttachments.MANA).mana();
                    int oldPractice = WaterMagic.practiceCount(player);
                    player.releaseUsingItem();
                    LOGGER.info("[JR_WAND_PREVIEW] RELEASE {}: ticks={}, charge={}, mana {}->{}, practice {}->{}; temporary fixture, restored on exit",
                            playerId, ticks, charge, oldMana, player.getData(ModAttachments.MANA).mana(),
                            oldPractice, WaterMagic.practiceCount(player));
                }
            }
            case SWAP_HAND -> {
                player.stopUsingItem();
                player.setMainArm(player.getMainArm().getOpposite());
                LOGGER.info("[JR_WAND_PREVIEW] HAND {}: {}; persistent client options unchanged", playerId, player.getMainArm());
            }
            case END -> throw new AssertionError("Handled above");
        }
        publish(player, session);
    }

    private static void publish(ServerPlayer player, Session session) {
        PUBLISHED.computeIfAbsent(player.getServer(), ignored -> new ConcurrentHashMap<>()).put(player.getUUID(),
                new State(player.isUsingItem(), player.getMainArm(), session.snapshot().selectedSlot(), session.snapshot().mainArm()));
    }

    private static void restore(ServerPlayer player, String reason) {
        MinecraftServer server = player.getServer();
        Map<UUID, Session> sessions = SESSIONS.get(server);
        Session session = sessions == null ? null : sessions.remove(player.getUUID());
        Map<UUID, State> published = PUBLISHED.get(server);
        if (published != null) published.remove(player.getUUID());
        if (session == null) return;
        // Drop tracking first: logout, screen removal and stopping may all request cleanup.
        Snapshot snapshot = session.snapshot();
        player.stopUsingItem();
        // Prevent a still-flying preview projectile from changing restored training later.
        server.overworld().getEntities(ModContent.WATER_BOLT.get(), bolt -> bolt.getOwner() == player
                && !snapshot.existingBolts().contains(bolt.getUUID())).forEach(bolt -> bolt.discard());
        if (snapshot.knowledge() == null) player.removeData(ModAttachments.KNOWLEDGE);
        else player.setData(ModAttachments.KNOWLEDGE, copy(MagicKnowledge.CODEC, snapshot.knowledge()));
        if (snapshot.mana() == null) player.removeData(ModAttachments.MANA);
        else player.setData(ModAttachments.MANA, copy(ManaData.CODEC, snapshot.mana()));
        if (snapshot.training() == null) player.removeData(ModAttachments.TRAINING);
        else player.setData(ModAttachments.TRAINING, copy(TrainingProgress.CODEC, snapshot.training()));
        if (snapshot.casting() == null) player.removeData(ModAttachments.CASTING);
        else player.setData(ModAttachments.CASTING, copy(CastingData.CODEC, snapshot.casting()));
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, snapshot.inventory().get(slot).copy());
        player.getInventory().selected = snapshot.selectedSlot();
        player.setMainArm(snapshot.mainArm());
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        if (snapshot.mana() != null && snapshot.mana().cooldown() > 0)
            player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), snapshot.mana().cooldown());
        server.setDifficulty(snapshot.difficulty(), true);
        server.overworld().setDayTime(snapshot.dayTime());
        player.teleportTo(server.overworld(), snapshot.position().x, snapshot.position().y, snapshot.position().z, snapshot.yaw(), snapshot.pitch());
        player.setYHeadRot(snapshot.yaw());
        player.setDeltaMovement(snapshot.motion());
        player.fallDistance = snapshot.fallDistance();
        player.setShiftKeyDown(snapshot.sneaking());
        sync(player);
        LOGGER.info("[JR_WAND_PREVIEW] RESTORED {}: original mana, knowledge, training, selected spell, inventory, slot, arm, position and scene ({})", player.getUUID(), reason);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        restore(player, "logout before player save");
        Map<UUID, Automatic> automatic = AUTOMATIC.get(player.getServer());
        if (automatic != null) automatic.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) restore(player, "normal server stopping before player save");
        SESSIONS.remove(event.getServer());
        AUTOMATIC.remove(event.getServer());
        PUBLISHED.remove(event.getServer());
    }

    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.CASTING);
        player.syncData(ModAttachments.KNOWLEDGE);
        player.syncData(ModAttachments.MANA);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
    }

    private static <T> T copy(Codec<T> codec, T value) {
        return codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()).getOrThrow();
    }

    private WandPreviewControls() {}
}
