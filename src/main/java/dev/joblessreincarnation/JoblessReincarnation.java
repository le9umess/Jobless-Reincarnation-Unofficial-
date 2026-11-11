package dev.joblessreincarnation;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.ManaEvents;
import dev.joblessreincarnation.magic.ManaGrowth;
import dev.joblessreincarnation.magic.MireFields;
import net.neoforged.bus.api.EventPriority;
import dev.joblessreincarnation.network.ModNetworking;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.worldgen.ModStructures;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(JoblessReincarnation.MOD_ID)
public final class JoblessReincarnation {
    public static final String MOD_ID = "jobless_reincarnation";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JoblessReincarnation(IEventBus modEventBus, ModContainer container) {
        ModAttachments.TYPES.register(modEventBus);
        ModContent.register(modEventBus);
        dev.joblessreincarnation.compat.epic.SchoolSkills.install(modEventBus);
        modEventBus.addListener(EventPriority.LOWEST, dev.joblessreincarnation.compat.epic.SchoolBookSources::creativeContents);
        dev.joblessreincarnation.compat.epic.EpicV01.install(modEventBus);
        dev.joblessreincarnation.compat.epic.EpicNpcRemodel.install(modEventBus);
        dev.joblessreincarnation.compat.epic.RastaBearCombat.install(modEventBus);
        MireFields.register(NeoForge.EVENT_BUS);
        ModStructures.register(modEventBus);
        modEventBus.addListener(ModNetworking::register);
        container.registerConfig(ModConfig.Type.SERVER, MagicConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onRespawn);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onUseStart);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(ManaEvents::onClone);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ManaGrowth::onClone);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ManaGrowth::onGameModeChange);
    }
}
