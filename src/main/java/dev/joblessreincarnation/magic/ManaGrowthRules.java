package dev.joblessreincarnation.magic;

/** Immutable, normalized server configuration for the first capacity-growth ruleset. */
public record ManaGrowthRules(boolean enabled, int baseMaximum, int bonusLimit, int gainPerRound, int cooldownTicks) {
    public static final int MAX_BASE_MAXIMUM = 100_000;
    public static final int MAX_BONUS_LIMIT = 100_000;
    public static final int MAX_GAIN_PER_ROUND = 1_000;
    public static final int MAX_COOLDOWN_TICKS = 1_200_000;
    public static final int MAX_EFFECTIVE_MAXIMUM = MAX_BASE_MAXIMUM + MAX_BONUS_LIMIT;

    public ManaGrowthRules {
        baseMaximum = Math.clamp(baseMaximum, 1, MAX_BASE_MAXIMUM);
        bonusLimit = Math.clamp(bonusLimit, 0, MAX_BONUS_LIMIT);
        gainPerRound = Math.clamp(gainPerRound, 1, MAX_GAIN_PER_ROUND);
        cooldownTicks = Math.clamp(cooldownTicks, 0, MAX_COOLDOWN_TICKS);
    }

    public static ManaGrowthRules current() {
        return new ManaGrowthRules(MagicConfig.GROWTH_ENABLED.get(), MagicConfig.MAX_MANA.getAsInt(),
                MagicConfig.GROWTH_BONUS_LIMIT.getAsInt(), MagicConfig.GROWTH_GAIN_PER_ROUND.getAsInt(),
                MagicConfig.GROWTH_COOLDOWN_TICKS.getAsInt());
    }

    /** Full values, rather than a hash, also detect configuration changes while the player is offline. */
    public String signature() {
        return "c04-v1:" + enabled + ":" + baseMaximum + ":" + bonusLimit + ":" + gainPerRound + ":" + cooldownTicks;
    }

    public static int highThreshold(int maximum) {
        return (int) (((long) normalizedMaximum(maximum) * 80L + 99L) / 100L);
    }

    public static int lowThreshold(int maximum) {
        return (int) ((long) normalizedMaximum(maximum) * 20L / 100L);
    }

    public static int requiredSpend(int maximum) {
        return (int) (((long) normalizedMaximum(maximum) * 60L + 99L) / 100L);
    }

    private static int normalizedMaximum(int maximum) {
        return Math.clamp(maximum, 1, MAX_EFFECTIVE_MAXIMUM);
    }
}
