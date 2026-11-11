package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** One fixed-strength movement reduction. Reapplication refreshes its duration rather than adding stacks. */
public final class SluggishEffect extends MobEffect {
    public SluggishEffect() {
        super(MobEffectCategory.HARMFUL, 0x4C9FC2, new SpellSpriteOptions(SpellSpriteOptions.DROPLET, .12F, 14));
        addAttributeModifier(Attributes.MOVEMENT_SPEED,
                ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "sluggish"),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -(MagicConfig.SPEC.isLoaded() ? MagicConfig.SLUGGISH_REDUCTION.get() : MagicConfig.SLUGGISH_REDUCTION.getDefault()));
    }
}
