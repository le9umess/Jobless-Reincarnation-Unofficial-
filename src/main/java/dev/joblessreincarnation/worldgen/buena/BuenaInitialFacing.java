package dev.joblessreincarnation.worldgen.buena;

import dev.joblessreincarnation.JoblessReincarnation;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Orient a new character once, after vanilla's first-login positioning. No persistent format or respawn hook. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class BuenaInitialFacing {
    private static final Set<ServerPlayer> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void loaded(PlayerEvent.LoadFromFile event) {
        if (event.getEntity() instanceof ServerPlayer player) LOADED.add(player);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (LOADED.remove(player) || player.serverLevel().dimension() != Level.OVERWORLD) return;
        var server = player.server;
        // A damaged/backup character file is not a fresh character. Also cover the integrated owner's inline data.
        var folder = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        if (Files.exists(folder.resolve(player.getStringUUID() + ".dat"))
                || Files.exists(folder.resolve(player.getStringUUID() + ".dat_old"))
                || (server.isSingleplayerOwner(player.getGameProfile()) && server.getWorldData().getLoadedPlayerTag() != null)) return;
        var level = player.serverLevel();
        var data = BuenaVillageData.get(level);
        if (data == null || !data.compatible() || data.plan() == null || data.initialSpawn() == null) return;
        // Respect a later administrator spawn change or another mod's lobby relocation.
        if (!data.initialSpawn().equals(level.getSharedSpawnPos())
                || player.position().distanceToSqr(data.initialSpawn().getBottomCenter()) > 32 * 32) return;
        var target = data.plan().centre();
        float yaw = Mth.wrapDegrees((float)-Math.toDegrees(Math.atan2(target.getX() - player.getX(), target.getZ() - player.getZ())));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, player.getXRot());
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    @SubscribeEvent
    public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) LOADED.remove(player);
    }

    private BuenaInitialFacing() {}
}
