package dev.joblessreincarnation.client.epic;

import dev.joblessreincarnation.registry.ModContent;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;

public final class EpicNpcRemodelClient {
    public static void install() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(NpcEpicPoseSync::tick);
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event -> {
            event.addPatchedEntityRenderer(ModContent.PAUL_GREYRAT.get(), type -> new V02NpcRenderer<>("paul_epic", 1.1F, true));
            event.addPatchedEntityRenderer(ModContent.ZENITH_GREYRAT.get(), type -> new V02NpcRenderer<>("zenith_epic", 1.0F, false));
            event.addPatchedEntityRenderer(ModContent.LILIA_GREYRAT.get(), type -> new V02NpcRenderer<>("lilia_epic", 1.0F, false));
            event.addPatchedEntityRenderer(ModContent.SYLPHIETTE.get(), type -> new V02NpcRenderer<>("sylphiette_epic", 0.53F, false));
        });
    }
    private EpicNpcRemodelClient() {}
}
