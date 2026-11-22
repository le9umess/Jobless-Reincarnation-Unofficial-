package dev.joblessreincarnation.mixin.camera;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.joblessreincarnation.client.combat.CombatView;
import dev.joblessreincarnation.combat.CombatViewNumbers;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * Combat view framing where Epic Fight places its TPS camera: the same reads of the player's camera settings, our
 * values while the combat view is in force. Epic Fight's collision, crosshair and conflict handling are untouched, and
 * the player's settings (and Epic Fight's in-memory copy of them) are never written.
 */
@Mixin(value = EpicFightCameraAPI.class, remap = false)
abstract class CameraValuesMixin {
    @ModifyExpressionValue(method = "setupCamera", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lyesman/epicfight/config/ClientConfig;cameraHorizontalLocation:I"))
    private int jobless$horizontal(int player) { return CombatView.overriding() ? CombatViewNumbers.HORIZONTAL : player; }

    @ModifyExpressionValue(method = "setupCamera", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lyesman/epicfight/config/ClientConfig;cameraVerticalLocation:I"))
    private int jobless$vertical(int player) { return CombatView.overriding() ? CombatViewNumbers.VERTICAL : player; }

    @ModifyExpressionValue(method = "setupCamera", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lyesman/epicfight/config/ClientConfig;cameraZoom:I"))
    private int jobless$zoom(int player) { return CombatView.overriding() ? CombatViewNumbers.ZOOM : player; }
}
