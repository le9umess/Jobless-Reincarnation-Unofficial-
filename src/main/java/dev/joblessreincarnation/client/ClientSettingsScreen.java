package dev.joblessreincarnation.client;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * The mod's entry in NeoForge's own settings screen: straight to the client settings (crystal ring glint, combat
 * view). NeoForge's usual first page would also list the server's magic settings, which are not meant for players.
 */
final class ClientSettingsScreen {
    static Screen open(ModContainer mod, Screen parent) {
        for (ModConfig config : ModConfigs.getConfigSet(ModConfig.Type.CLIENT))
            if (config.getModId().equals(JoblessReincarnation.MOD_ID))
                return new ConfigurationScreen.ConfigurationSectionScreen(parent, ModConfig.Type.CLIENT, config,
                        Component.translatable("jobless_reincarnation.configuration.client.title"));
        return parent;
    }

    private ClientSettingsScreen() {}
}
