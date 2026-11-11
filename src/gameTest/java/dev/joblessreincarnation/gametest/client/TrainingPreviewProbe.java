package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.TrainingBoardScreen;
import dev.joblessreincarnation.gametest.TrainingPreviewCommands;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingBoardMenu;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Read-only receipt probe: never awards a card, changes progress, or controls the client. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class TrainingPreviewProbe {
    private static boolean reported;

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (reported || !Boolean.getBoolean(TrainingPreviewCommands.AUTO_PROPERTY)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.screen instanceof TrainingBoardScreen)
                || !(minecraft.player.containerMenu instanceof TrainingBoardMenu menu) || !menu.claimed()
                || !minecraft.player.getInventory().getItem(9).is(ModContent.TRAINING_RECORD.get())) return;
        reported = true;
        LogUtils.getLogger().info("[JR_TRAINING_PREVIEW] CLIENT_CONFIRMED: client received training record in slot 9 while board remained open; menu={}", menu.containerId);
    }

    private TrainingPreviewProbe() {}
}
