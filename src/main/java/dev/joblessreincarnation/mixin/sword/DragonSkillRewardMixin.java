package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.training.SchoolForm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.world.capabilities.entitypatch.boss.enderdragon.EnderDragonPatch;
import yesman.epicfight.world.item.SkillBookItem;

/** The pinned dragon patch passes a lone skill ID string to the weighted builder, producing a blank book. */
@Mixin(value = EnderDragonPatch.class, remap = false)
abstract class DragonSkillRewardMixin {
    @ModifyArg(method = "onDeath", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;addItem(Lnet/minecraft/world/item/ItemStack;)Z"), index = 0)
    private ItemStack jobless$replaceUnassignedDragonBook(ItemStack reward) {
        if (reward.is(EpicFightItems.SKILLBOOK.get()) && SkillBookItem.getContainSkill(reward).isEmpty()) {
            return new ItemStack(SchoolForm.TEAR_POUCH.note(), reward.getCount());
        }
        return reward;
    }
}
