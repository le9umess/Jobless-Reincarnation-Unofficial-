package dev.joblessreincarnation.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.LightBladeSkill;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class LightBladeInputGuard {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        check(client.screen != null || !client.isWindowActive());
    }

    @SubscribeEvent public static void opening(ScreenEvent.Opening event) { check(true); }

    private static void check(boolean interrupted) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) return;
        var patch = EpicFightCapabilities.getPlayerPatch(client.player);
        if (patch instanceof yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch local
                && local.getSkill(SchoolSkills.Slot.JOBLESS_PREPARED_FORM).getSkill() instanceof LightBladeSkill light) {
            light.cancelLocalIfInvalid(local, interrupted);
        }
    }

    private LightBladeInputGuard() {}
}
