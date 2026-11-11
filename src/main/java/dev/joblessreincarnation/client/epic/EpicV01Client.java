package dev.joblessreincarnation.client.epic;

import dev.joblessreincarnation.registry.ModContent;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;

public final class EpicV01Client {
    public static void install() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(EpicWaterPoseSync::tick);
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event ->
                event.addPatchedEntityRenderer(ModContent.ROXY_TUTOR.get(), type -> new RoxyEpicRenderer()));
    }
    private EpicV01Client() {}
}
