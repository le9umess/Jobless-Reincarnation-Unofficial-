package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.compat.epic.LightBladeSkill;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.events.engine.ControlEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.network.client.CPSkillRequest;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlot;

@Mixin(value = SkillContainer.class, remap = false)
abstract class LightCancelInputMixin {
    @Shadow public abstract Skill getSkill();
    @Shadow public abstract SkillSlot getSlot();

    @ModifyArg(method = "sendCastRequest", at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/client/events/engine/ControlEngine;addPacketToSend(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"), index = 0)
    private CustomPacketPayload jobless$stampLightHold(CustomPacketPayload outgoing) {
        if (getSkill() instanceof LightBladeSkill light && outgoing instanceof CPSkillRequest request
                && request.workType() == CPSkillRequest.WorkType.HOLD_START && request.arguments() == null) {
            return LightBladeSkill.request(getSlot(), CPSkillRequest.WorkType.HOLD_START, light.localPressId());
        }
        return outgoing;
    }

    @Inject(method = "sendCancelRequest", at = @At("HEAD"), cancellable = true)
    private void jobless$cancelThisLightHold(LocalPlayerPatch player, ControlEngine control, CallbackInfo ci) {
        if (getSkill() instanceof LightBladeSkill light) {
            var request = LightBladeSkill.request(getSlot(), CPSkillRequest.WorkType.CANCEL, light.takeLocalPressId());
            control.addPacketToSend(request);
            ci.cancel();
        }
    }
}
