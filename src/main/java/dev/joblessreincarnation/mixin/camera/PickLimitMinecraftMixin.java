package dev.joblessreincarnation.mixin.camera;

import dev.joblessreincarnation.client.combat.CombatView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The tick's crosshair pick, judged once it has finished. The tutorial call right after GameRenderer.pick in
 * Minecraft.tick() runs after every wrapper around that pick (Epic Fight's own included), whatever order they were
 * applied in, and before the keys act on the result; its argument is the picked result, and it gets the judged one.
 */
@Mixin(Minecraft.class)
abstract class PickLimitMinecraftMixin {
    @ModifyArg(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/tutorial/Tutorial;onLookAt(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/phys/HitResult;)V"),
            index = 1)
    private HitResult jobless$afterPick(HitResult picked) {
        CombatView.afterPick(CombatView.PickChain.TICK);
        return ((Minecraft) (Object) this).hitResult;
    }
}
