package dev.joblessreincarnation.magic;

import net.minecraft.world.entity.LivingEntity;

/** Shared presentation/balance math; only the server commits a cast. */
public final class WaterControl {
    public static final int MAX_USE_TICKS = 72000;

    public static float normalize(float charge) {
        return Float.isFinite(charge) ? Math.clamp(charge, 0.0F, 1.0F) : 0.0F;
    }

    public static float charge(LivingEntity caster, int usedTicks) {
        return normalize((usedTicks - WaterMagic.chantTicks(caster)) / (float) MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt());
    }

    public static int manaCost(float charge) {
        return MagicConfig.WATER_BOLT_COST.getAsInt()
                + (int) Math.ceil(MagicConfig.WATER_BOLT_EXTRA_COST.getAsInt() * normalize(charge));
    }

    public static float damage(float charge) {
        return MagicConfig.WATER_BOLT_DAMAGE.get().floatValue()
                + MagicConfig.WATER_BOLT_EXTRA_DAMAGE.get().floatValue() * normalize(charge);
    }

    public static float speed(float charge) { return 1.35F + 0.30F * normalize(charge); }

    /** Diameter in blocks. The projectile retains its small, precise collision trace. */
    public static float visualSize(float charge) { return 0.28F + 0.22F * normalize(charge); }

    private WaterControl() {}
}
