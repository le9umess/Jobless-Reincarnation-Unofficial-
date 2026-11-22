package dev.joblessreincarnation.mixin.hud;

import dev.joblessreincarnation.client.hud.BrassHudPack;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Options.class, remap = false)
public abstract class HudPackOptionsMixin {
    @Inject(method = "loadSelectedResourcePacks", at = @At("RETURN"), remap = false)
    private void jobless$initialHudSelection(PackRepository repository, CallbackInfo ci) {
        BrassHudPack.initializeSelection((Options)(Object)this, repository);
    }
}
