package dev.joblessreincarnation.mixin.camera;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.joblessreincarnation.client.combat.CombatView;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/**
 * One owner for the point of view, decided at each switch. Epic Fight reads its auto-switch setting only when the
 * player's mode really changes, right where it would switch the view itself; at that read the combat view decides this
 * switch with the setting as it is now, switches the view if the switch is its own, and then holds Epic Fight's own
 * switch back. Any switch the combat view does not make stays Epic Fight's. The player's setting is not changed.
 */
@Mixin(value = LocalPlayerPatch.class, remap = false)
abstract class AutoPerspectiveMixin {
    @ModifyExpressionValue(method = "toEpicFightMode", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lyesman/epicfight/config/ClientConfig;autoPerspectiveSwithing:Z"))
    private boolean jobless$entering(boolean epicFightSwitches) {
        return CombatView.onEnterRequest(((LocalPlayerPatch) (Object) this).getOriginal(), epicFightSwitches);
    }

    @ModifyExpressionValue(method = "toVanillaMode", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lyesman/epicfight/config/ClientConfig;autoPerspectiveSwithing:Z"))
    private boolean jobless$leaving(boolean epicFightSwitches) {
        return CombatView.onExitRequest(((LocalPlayerPatch) (Object) this).getOriginal(), epicFightSwitches);
    }
}
