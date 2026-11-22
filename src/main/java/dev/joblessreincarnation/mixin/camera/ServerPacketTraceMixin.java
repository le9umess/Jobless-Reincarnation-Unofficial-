package dev.joblessreincarnation.mixin.camera;

import dev.joblessreincarnation.combat.AimMath;
import dev.joblessreincarnation.combat.CombatViewTrace;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development diagnostics only (-Djobless.combatview.trace=true; otherwise nothing at all): the order in which the
 * server handles a player's rotation and the release of the item in use, and the look it uses for that release. The
 * handlers are entered twice (network thread, then server thread); only the server-thread pass is recorded.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerPacketTraceMixin {
    @Shadow public ServerPlayer player;
    @Shadow private net.minecraft.world.phys.Vec3 awaitingPositionFromClient;
    @org.spongepowered.asm.mixin.Unique private int jobless$movePackets;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void jobless$traceRotation(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!CombatViewTrace.ENABLED || !packet.hasRotation() || !player.server.isSameThread()) return;
        CombatViewTrace.log("server_rotation", player.level().getGameTime(), "yaw=" + CombatViewTrace.deg(packet.getYRot(0.0F))
                + " pitch=" + CombatViewTrace.deg(packet.getXRot(0.0F)) + " position_too=" + packet.hasPosition());
    }

    /**
     * After each move packet: the movement the server now keeps as the player's known movement. Every move packet sets it
     * from the position change it brings, so one without a position (rotation only) sets it to zero; projectiles shot
     * with Projectile.shootFromRotation add it.
     */
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void jobless$traceKnownMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!CombatViewTrace.ENABLED || !player.server.isSameThread()) return;
        // seq: movement packets this connection has handled; raw: the packet's own fields, as the client wrote them when
        // it sent it; awaiting_teleport: the server is waiting for the client to confirm a teleport (it ignores movement
        // until then, and a rejected move starts such a wait). pos is where the server has the player afterwards.
        jobless$movePackets++;
        CombatViewTrace.log("server_moved", player.level().getGameTime(), "seq=" + jobless$movePackets + " raw=" + CombatViewTrace.rawMove(packet)
                + " awaiting_teleport=" + (awaitingPositionFromClient != null) + " kind=" + CombatViewTrace.moveKind(packet)
                + " position=" + packet.hasPosition() + " rotation=" + packet.hasRotation() + " passenger=" + player.isPassenger()
                + " known=" + CombatViewTrace.vec(player.getKnownMovement()) + " pos=" + CombatViewTrace.vec(player.position())
                + " yaw=" + CombatViewTrace.deg(player.getYRot()) + " pitch=" + CombatViewTrace.deg(player.getXRot()) + " on_ground=" + player.onGround());
    }

    /**
     * Before the server handles a release: the look and movement it will use, and the release as the spell will see it
     * (used_ticks counted as Item.releaseUsing is given it, the spell, its form stage and charge); the projectile lines
     * this release causes carry the same until it returns.
     */
    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void jobless$traceRelease(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!CombatViewTrace.ENABLED || packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM || !player.server.isSameThread()) return;
        String release = CombatViewTrace.beginRelease(player);
        CombatViewTrace.log("server_release", player.level().getGameTime(), "yaw=" + CombatViewTrace.deg(player.getYRot()) + " pitch=" + CombatViewTrace.deg(player.getXRot())
                + " view=" + CombatViewTrace.vec(AimMath.view(player.getYRot(), player.getXRot())) + " eye=" + CombatViewTrace.vec(player.getEyePosition())
                + " known=" + CombatViewTrace.vec(player.getKnownMovement()) + " pos=" + CombatViewTrace.vec(player.position())
                + " on_ground=" + player.onGround() + " passenger=" + player.isPassenger()
                + " ticks_using=" + player.getTicksUsingItem() + " " + release
                + " using=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getUseItem().getItem()));
    }

    @Inject(method = "handlePlayerAction", at = @At("RETURN"))
    private void jobless$traceReleaseDone(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (CombatViewTrace.ENABLED && packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM && player.server.isSameThread()) CombatViewTrace.endRelease();
    }
}
