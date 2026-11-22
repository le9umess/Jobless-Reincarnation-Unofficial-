package dev.joblessreincarnation.mixin.camera;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.joblessreincarnation.client.combat.CombatView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.config.ClientConfig;

/**
 * While the combat view is in force Epic Fight's TPS camera is always on, whatever the player's own TPS setting. Only
 * the two places where Epic Fight's camera asks for that setting see "always": whether TPS is on (isTPSMode, where
 * Epic Fight's cancel for another camera mod still follows) and the aiming-only offset scale in setupCamera.
 * The getter itself stays as it is: Epic Fight's settings screen fills its TPS box through it and saves that box when
 * it closes, so changing the getter would write "always" over the player's own choice.
 */
@Mixin(value = EpicFightCameraAPI.class, remap = false)
abstract class TpsActivationMixin {
    @ModifyExpressionValue(method = "isTPSMode", at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/config/ClientConfig;getTpsActivationType()Lyesman/epicfight/config/ClientConfig$TPSActivationType;"))
    private ClientConfig.TPSActivationType jobless$activation(ClientConfig.TPSActivationType player) {
        return CombatView.overriding() ? ClientConfig.TPSActivationType.ALWAYS : player;
    }

    @ModifyExpressionValue(method = "setupCamera", at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/config/ClientConfig;getTpsActivationType()Lyesman/epicfight/config/ClientConfig$TPSActivationType;"))
    private ClientConfig.TPSActivationType jobless$offsetScale(ClientConfig.TPSActivationType player) {
        return CombatView.overriding() ? ClientConfig.TPSActivationType.ALWAYS : player;
    }
}
