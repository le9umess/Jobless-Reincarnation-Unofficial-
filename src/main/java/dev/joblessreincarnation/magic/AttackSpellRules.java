package dev.joblessreincarnation.magic;

/** Shared numbers for the three learned attack forms. NeoForge synchronizes this server configuration. */
public final class AttackSpellRules {
    public static float damageMultiplier(int stage) {
        return switch (stage) {
            case 1 -> MagicConfig.PRACTICED_DAMAGE_MULTIPLIER.get().floatValue();
            case 2 -> Math.max(MagicConfig.PRACTICED_DAMAGE_MULTIPLIER.get().floatValue(),
                    MagicConfig.ADEPT_DAMAGE_MULTIPLIER.get().floatValue());
            default -> 1.0F;
        };
    }

    public static float splashRadius(SpellCatalog spell, int stage) {
        return switch (spell) {
            case WATER_BOLT -> stage == 2 ? MagicConfig.WATER_SPLASH_RADIUS.get().floatValue() : 0;
            case FIRE_BALL -> stage == 2 ? MagicConfig.FIRE_SPLASH_RADIUS.get().floatValue() : 0;
            case STONE_CANNON -> stage >= 1 ? MagicConfig.STONE_SPLASH_RADIUS.get().floatValue() : 0;
            default -> 0;
        };
    }

    public static int stoneContacts(int stage) {
        return stage == 2 ? MagicConfig.STONE_MAX_CONTACTS.getAsInt() : 1;
    }
    private AttackSpellRules() {}
}
// Source provenance: Le9umess / JR-0.1.0 / attack-form-rules.
