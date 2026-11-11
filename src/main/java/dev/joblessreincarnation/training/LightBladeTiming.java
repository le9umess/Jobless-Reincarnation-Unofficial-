package dev.joblessreincarnation.training;

/** Gameplay tuning for the first Light Blade study. Visuals use the same server start tick and thresholds. */
public final class LightBladeTiming {
    public static final int TIER_ONE_TICKS = 8;
    public static final int TIER_TWO_TICKS = 18;
    public static final int FULL_TICKS = 28;
    public static final int MAX_HOLD_TICKS = 120;

    public static int tierAt(long heldTicks) {
        if (heldTicks < TIER_ONE_TICKS) return 0;
        if (heldTicks < TIER_TWO_TICKS) return 1;
        return heldTicks < FULL_TICKS ? 2 : 3;
    }

    public static float staminaCost(int tier) {
        return switch (tier) { case 1 -> 4; case 2 -> 6; case 3 -> 8; default -> 0; };
    }

    /** The highest tier up to {@code reached} that {@code stamina} can pay for; 0 when not even the first. */
    public static int affordableTier(int reached, float stamina) {
        int tier = reached;
        while (tier > 0 && stamina < staminaCost(tier)) tier--;
        return tier;
    }

    public static float damageMultiplier(int tier) {
        return switch (tier) { case 2 -> 1.35F; case 3 -> 1.70F; default -> 1.0F; };
    }

    private LightBladeTiming() {}
}
