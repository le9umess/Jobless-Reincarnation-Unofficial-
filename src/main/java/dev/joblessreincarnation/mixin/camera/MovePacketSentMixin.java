package dev.joblessreincarnation.mixin.camera;

import dev.joblessreincarnation.client.combat.LastSentLook;
import dev.joblessreincarnation.client.combat.StaffAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Around this tick's movement packet (vanilla LocalPlayer.sendPosition, called after the player's movement this tick),
 * for a staff release held by {@link HeldReleaseMixin}:
 * <ul>
 * <li>at its start, the held release is aimed again, from the eyes as they are now, so the packet vanilla builds carries that look;</li>
 * <li>each packet it sends is seen as it goes (unchanged), so the release knows which movement packet, if any, carried the look;</li>
 * <li>at its end, the held release is settled.</li>
 * </ul>
 * It also gives LocalPlayer's record of the look it last sent ({@link LastSentLook}).
 */
@Mixin(LocalPlayer.class)
abstract class MovePacketSentMixin implements LastSentLook {
    @Shadow private float yRotLast;
    @Shadow private float xRotLast;

    @Override public float jobless$sentYaw() { return yRotLast; }
    @Override public float jobless$sentPitch() { return xRotLast; }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void jobless$beforeMovementSent(CallbackInfo ci) {
        StaffAim.beforeMovementSent((LocalPlayer) (Object) this);
    }

    @ModifyArg(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private Packet<?> jobless$seeMovementPacket(Packet<?> packet) {
        StaffAim.sawMovePacket(packet);
        return packet;
    }

    @Inject(method = "sendPosition", at = @At("TAIL"))
    private void jobless$afterMovementSent(CallbackInfo ci) {
        StaffAim.afterMovementSent((LocalPlayer) (Object) this);
    }
}
