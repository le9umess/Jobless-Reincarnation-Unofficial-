package dev.joblessreincarnation.mixin.hud;

import dev.joblessreincarnation.client.hud.BrassHudPack;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** AppleSkin remains entirely optional; no AppleSkin class is referenced from our class signatures. */
@Pseudo
@Mixin(targets = "squeek.appleskin.client.HUDOverlayHandler", remap = false)
public abstract class AppleSkinHudMixin {
    @ModifyArg(
            method = "drawSaturationOverlay(FFLnet/minecraft/world/entity/player/Player;Lnet/minecraft/client/gui/GuiGraphics;IIFI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"),
            index = 0, require = 0, remap = false)
    private static ResourceLocation jobless$matchingSaturationOutline(ResourceLocation original) {
        return BrassHudPack.saturationTexture(original);
    }
}
