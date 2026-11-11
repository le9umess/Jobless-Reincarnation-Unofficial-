package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.compat.epic.SchoolSkills;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import yesman.epicfight.client.events.engine.ControlEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.skill.SkillSlot;

/** Route the already-resolved EF action, including delayed reservations. The physical key is never hard-coded. */
@Mixin(value = ControlEngine.class, remap = false)
abstract class SchoolKeyMixin {
    @Shadow public abstract LocalPlayerPatch getPlayerPatch();

    @ModifyArg(method = {"handleEpicFightKeyMappings", "handleSeparateWeaponInnateSkill"}, at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;getSkill(Lyesman/epicfight/skill/SkillSlot;)Lyesman/epicfight/skill/SkillContainer;"), index = 0)
    private SkillSlot jobless$castFromPreparedSlot(SkillSlot slot) {
        return SchoolSkills.inputSlot(getPlayerPatch(), slot);
    }

    @ModifyArg(method = {"handleEpicFightKeyMappings", "handleSeparateWeaponInnateSkill"}, at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/client/events/engine/ControlEngine;reserveKey(Lyesman/epicfight/skill/SkillSlot;Lyesman/epicfight/api/client/input/action/InputAction;)V"), index = 0)
    private SkillSlot jobless$reservePreparedSlot(SkillSlot slot) {
        return SchoolSkills.inputSlot(getPlayerPatch(), slot);
    }
}
