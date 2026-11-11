package dev.joblessreincarnation.mixin.sword;

import dev.joblessreincarnation.compat.epic.SchoolBookSources;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.data.loot.function.SetSkillFunction;

/** The native chest and mob tables both resolve their weighted skill here. */
@Mixin(value = SetSkillFunction.class, remap = false)
abstract class SkillBookLootMixin {
    @Inject(method = "run", at = @At("RETURN"), cancellable = true)
    private void jobless$replaceGeneratedBook(ItemStack input, LootContext context, CallbackInfoReturnable<ItemStack> result) {
        ItemStack original = result.getReturnValue();
        ItemStack replacement = SchoolBookSources.generatedBook(original);
        if (replacement != original) result.setReturnValue(replacement);
    }
}
