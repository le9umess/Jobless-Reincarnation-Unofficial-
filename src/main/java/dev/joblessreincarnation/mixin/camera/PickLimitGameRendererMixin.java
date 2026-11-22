package dev.joblessreincarnation.mixin.camera;

import dev.joblessreincarnation.client.combat.CombatView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame's crosshair pick, judged once it has finished: renderLevel asks whether to draw the block outline right
 * after GameRenderer.pick and its wrappers, and before the camera moves for this frame, so the outline follows the
 * judged result.
 */
@Mixin(GameRenderer.class)
abstract class PickLimitGameRendererMixin {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;shouldRenderBlockOutline()Z"))
    private void jobless$afterPick(DeltaTracker deltaTracker, CallbackInfo ci) {
        CombatView.afterPick(CombatView.PickChain.RENDER);
    }
}
