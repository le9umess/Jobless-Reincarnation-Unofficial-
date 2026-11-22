package dev.joblessreincarnation.mixin.camera;

import dev.joblessreincarnation.client.combat.StaffAim;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds vanilla's staff release (sent from Minecraft.handleKeybinds), with the use it belongs to, while a combat-view
 * release waits for this tick's movement packet; {@link MovePacketSentMixin} aims it again before that packet and
 * settles it right after. Only a release prepared this tick ({@link StaffAim#prepareRelease}) is held, and only once.
 * The whole call waits, the client's own stop of the use included. With diagnostics on, every vanilla staff release is
 * recorded here, held or not (first person too).
 */
@Mixin(MultiPlayerGameMode.class)
abstract class HeldReleaseMixin {
    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void jobless$holdForMovement(Player player, CallbackInfo ci) {
        boolean held = StaffAim.holdRelease(player);
        StaffAim.traceVanillaRelease(player, held);
        if (held) ci.cancel();
    }
}
