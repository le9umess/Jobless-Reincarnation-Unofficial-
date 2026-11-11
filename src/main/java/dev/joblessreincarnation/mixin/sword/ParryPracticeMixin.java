package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.compat.epic.SchoolSkills;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.event.types.entity.TakeDamageEvent;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.guard.ParryingSkill;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

@Mixin(value = ParryingSkill.class, remap = false)
abstract class ParryPracticeMixin {
    @Inject(method = "guard", at = @At("RETURN"))
    private void jobless$afterActualParry(SkillContainer container, CapabilityItem weapon, ServerPlayerPatch patch,
            TakeDamageEvent.Income hit, float penalty, float impact, boolean advanced, CallbackInfo ci) {
        SchoolSkills.afterParry(patch, hit);
    }
}
