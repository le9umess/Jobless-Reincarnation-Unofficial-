package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

/** A finite attributed burn, independent of the vanilla fire timer. Its cadence survives entity saves. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class SearingEffect extends MobEffect {
    public record Burn(@Nullable UUID caster, int untilPulse, float damage) {
        public static final Burn NONE = new Burn(null, 20, 0);
        public static final Codec<Burn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.optionalFieldOf("caster").forGetter(burn -> Optional.ofNullable(burn.caster())),
                Codec.INT.optionalFieldOf("until_pulse", 20).forGetter(Burn::untilPulse),
                Codec.FLOAT.optionalFieldOf("damage", 0F).forGetter(Burn::damage)
        ).apply(instance, (caster, until, damage) -> new Burn(caster.orElse(null), until, damage)));
        public Burn {
            untilPulse = Math.clamp(untilPulse, 1, 20);
            damage = Float.isFinite(damage) ? Math.clamp(damage, 0, 20) : 0;
        }
    }

    public SearingEffect() {
        super(MobEffectCategory.HARMFUL, 0xD45B31, new SpellSpriteOptions(SpellSpriteOptions.SEAR, .1F, 12));
    }

    static void apply(LivingEntity caster, LivingEntity target) {
        if (target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE) || target.isInWaterRainOrBubble()) return;
        Burn previous = target.hasData(ModAttachments.SEARING) ? target.getData(ModAttachments.SEARING) : null;
        boolean changed = target.addEffect(new MobEffectInstance(ModContent.SEARING, MagicConfig.SEARING_TICKS.getAsInt(), 0), caster);
        if (!target.hasEffect(ModContent.SEARING)) return;
        if (changed) {
            // Frequent hits extend the burn but cannot keep restarting its first-damage delay.
            target.setData(ModAttachments.SEARING, new Burn(caster.getUUID(), previous == null ? 20 : previous.untilPulse(),
                    MagicConfig.SEARING_DAMAGE.get().floatValue()));
        } else if (previous != null) {
            target.setData(ModAttachments.SEARING, previous);
        }
    }

    @Override public void onEffectStarted(LivingEntity target, int amplifier) {
        if (!target.level().isClientSide) target.removeData(ModAttachments.SEARING);
    }

    @Override public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) { return true; }

    @Override public boolean applyEffectTick(LivingEntity target, int amplifier) {
        if (!(target.level() instanceof ServerLevel level)) return true;
        if (target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE) || target.isInWaterRainOrBubble()) return false;
        // An operator's display-only effect has no earlier attack's attribution.
        if (!target.hasData(ModAttachments.SEARING)) return true;
        Burn burn = target.getData(ModAttachments.SEARING);
        int next = burn.untilPulse() - 1;
        target.setData(ModAttachments.SEARING, new Burn(burn.caster(), next == 0 ? 20 : next, burn.damage()));
        if (next != 0 || burn.caster() == null || burn.damage() <= 0) return true;
        var owner = level.getEntity(burn.caster());
        if (owner instanceof LivingEntity caster && AttackSpellImpact.canAffect(caster, target))
            target.hurt(target.damageSources().source(DamageTypes.ON_FIRE, caster), burn.damage());
        return true;
    }

    @SubscribeEvent public static void afterTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity target && !target.level().isClientSide
                && target.hasData(ModAttachments.SEARING) && !target.hasEffect(ModContent.SEARING))
            target.removeData(ModAttachments.SEARING);
    }
}
