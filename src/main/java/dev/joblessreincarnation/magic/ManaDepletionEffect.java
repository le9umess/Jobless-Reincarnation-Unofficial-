package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.EffectCure;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ManaDepletionEffect extends MobEffect {
    public ManaDepletionEffect() {
        super(MobEffectCategory.HARMFUL, 0xABB3B1);
        var id = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mana_depletion");
        addAttributeModifier(Attributes.ATTACK_DAMAGE, id, AttributeModifier.Operation.ADD_VALUE,
                amplifier -> -penalty(MagicConfig.DEPLETION_WEAKNESS));
        addAttributeModifier(Attributes.MOVEMENT_SPEED, id, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -penalty(MagicConfig.DEPLETION_SLOWNESS));
        addAttributeModifier(Attributes.BLOCK_BREAK_SPEED, id, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -penalty(MagicConfig.DEPLETION_MINING));
    }

    private static double penalty(ModConfigSpec.DoubleValue value) {
        return MagicConfig.SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance instance) {
        super.fillEffectCures(cures, instance);
        cures.remove(EffectCures.MILK);
    }
}
