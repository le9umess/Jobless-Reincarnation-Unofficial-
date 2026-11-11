package dev.joblessreincarnation.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.worldgen.buena.BuenaVillageGeneration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Honest status during the survey that precedes vanilla's spawn-chunk percentage. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class BuenaLoadingHud {
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){
        var client=Minecraft.getInstance();var server=client.getSingleplayerServer();
        if(server==null||!(event.getScreen() instanceof LevelLoadingScreen))return;
        var progress=BuenaVillageGeneration.progress(server);if(progress==null)return;
        var screen=event.getScreen();var graphics=event.getGuiGraphics();
        int y=Math.max(4,screen.height-42);
        graphics.fill(0,y-5,screen.width,screen.height,0xF0101620);
        graphics.drawCenteredString(client.font,Component.translatable(progress.preparingSpawn()
                ?"loading.jobless_reincarnation.spawn":"loading.jobless_reincarnation.village"),screen.width/2,y,0xE5D6B0);
        graphics.drawCenteredString(client.font,Component.translatable("loading.jobless_reincarnation.survey",progress.checked(),progress.elapsedMillis()/1000),screen.width/2,y+13,0xFFFFFF);
    }
    private BuenaLoadingHud(){}
}
