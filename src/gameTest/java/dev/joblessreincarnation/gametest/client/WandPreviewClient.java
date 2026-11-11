package dev.joblessreincarnation.gametest.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.WandPreviewControls;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opens once per disposable integrated session; never controls ordinary gameplay input. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class WandPreviewClient {
    private static MinecraftServer openedServer;
    private static UUID openedPlayer;

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (!WandPreviewControls.enabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || !minecraft.hasSingleplayerServer()) {
            openedServer = null;
            openedPlayer = null;
            return;
        }
        if (server.isPublished() || minecraft.player == null || minecraft.screen != null || minecraft.getOverlay() != null) return;
        UUID playerId = minecraft.player.getUUID();
        if (server == openedServer && playerId.equals(openedPlayer)) return;
        WandPreviewControls.State state = WandPreviewControls.state(server, playerId);
        if (state == null || !minecraft.player.getInventory().getItem(0).is(ModContent.TRAINING_STAFF.get())) return;
        openedServer = server;
        openedPlayer = playerId;
        minecraft.setScreen(new WandPreviewScreen(server, playerId, state));
    }

    private WandPreviewClient() {}
}
