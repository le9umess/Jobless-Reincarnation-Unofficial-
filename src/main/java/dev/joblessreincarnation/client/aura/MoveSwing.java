package dev.joblessreincarnation.client.aura;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.fml.ModList;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * A cut's clip plays on the full-body model, which first person never shows when the sword is drawn the vanilla way.
 * So the hand swings once locally as the cut starts, and it can be seen and felt from inside as well.
 * Only then: Epic Fight turns a vanilla swing into its digging overlay on the full-body model, which would bend the cut
 * in third person, and in its own first-person model the clip already shows. With Punchy, PunchyFirstPerson swings for every attack clip.
 */
public final class MoveSwing {
    private static final Set<String> CUTS = Set.of("v035/player/arm_strike", "v035/player/flow_counter", "v035/player/light_blade_cut");
    private static ResourceLocation playing;

    public static void afterTick(Minecraft minecraft) {
        if (ModList.get().isLoaded("punchy")) return;
        LocalPlayer player = minecraft.player;
        LocalPlayerPatch patch = player == null ? null : EpicFightCapabilities.getEntityPatch(player, LocalPlayerPatch.class);
        ResourceLocation clip = null;
        if (patch != null) {
            var animation = patch.getClientAnimator().getPlayerFor(null).getRealAnimation();
            if (animation != null && animation.registryName() != null
                    && animation.registryName().getNamespace().equals(JoblessReincarnation.MOD_ID)
                    && CUTS.contains(animation.registryName().getPath())) {
                clip = animation.registryName();
            }
        }
        if (clip != null && !clip.equals(playing) && minecraft.options.getCameraType().isFirstPerson()
                && RenderEngine.getInstance().getItemRenderer(player.getMainHandItem()).forceVanillaFirstPerson()) {
            player.swing(InteractionHand.MAIN_HAND, false);
        }
        playing = clip;
    }

    private MoveSwing() {}
}
