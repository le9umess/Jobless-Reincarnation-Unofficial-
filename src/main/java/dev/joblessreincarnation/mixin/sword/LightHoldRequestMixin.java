package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.compat.epic.LightBladeSkill;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.LightBladeTiming;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/** EF's consume-event cancellation only suppresses stamina payment; it does not reject a HOLD_START. */
@Mixin(value = SkillContainer.class, remap = false)
abstract class LightHoldRequestMixin {
    @Shadow public abstract Skill getSkill();

    @Inject(method = "requestHold", at = @At("HEAD"), cancellable = true)
    private void jobless$validateLightPress(ServerPlayerPatch patch, CompoundTag arguments, CallbackInfoReturnable<Boolean> ci) {
        if (!(getSkill() instanceof LightBladeSkill)) return;
        // Not enough stamina for even the first tier: no charge at all (the client says why and cancels its own).
        if (patch.getStamina() < LightBladeTiming.staminaCost(1)) {
            ci.setReturnValue(false);
            return;
        }
        var player = patch.getOriginal();
        if (!player.getData(ModAttachments.LIGHT_BLADE_SESSION).offer(player, LightBladeSkill.pressId(arguments))) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "requestHold", at = @At("RETURN"))
    private void jobless$forgetUnusedPress(ServerPlayerPatch patch, CompoundTag arguments, CallbackInfoReturnable<Boolean> ci) {
        if (getSkill() instanceof LightBladeSkill && patch.getOriginal().hasData(ModAttachments.LIGHT_BLADE_SESSION)) {
            patch.getOriginal().getData(ModAttachments.LIGHT_BLADE_SESSION).clearPending(LightBladeSkill.pressId(arguments));
        }
    }
}
