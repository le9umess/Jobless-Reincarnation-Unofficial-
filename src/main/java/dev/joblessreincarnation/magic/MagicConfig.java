package dev.joblessreincarnation.magic;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned balance values with optional per-world overrides, synced by NeoForge. */
public final class MagicConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_MANA;
    public static final ModConfigSpec.DoubleValue AURA_HARDNESS, AURA_MAX_REDUCTION_RATIO;
    public static final ModConfigSpec.IntValue SCHOOL_PREPARATION_TICKS;
    public static final ModConfigSpec.BooleanValue NAMING_CUSTOM;
    public static final ModConfigSpec.IntValue NAMING_MAX_LENGTH;
    public static final ModConfigSpec.IntValue DEPLETION_TICKS, DEPLETION_BLIND_TICKS;
    public static final ModConfigSpec.DoubleValue OVERCAST_CONFIRM_RATIO, OVERCAST_UNLOCK_RATIO;
    public static final ModConfigSpec.DoubleValue DEPLETION_REGEN, DEPLETION_WEAKNESS, DEPLETION_SLOWNESS, DEPLETION_MINING;
    public static final ModConfigSpec.BooleanValue GROWTH_ENABLED;
    public static final ModConfigSpec.IntValue GROWTH_BONUS_LIMIT;
    public static final ModConfigSpec.IntValue GROWTH_GAIN_PER_ROUND;
    public static final ModConfigSpec.IntValue GROWTH_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue REGEN_PER_SECOND;
    public static final ModConfigSpec.IntValue RECOVERY_DELAY_TICKS;
    public static final ModConfigSpec.IntValue STONE_CANNON_COST, STONE_CANNON_CHANT_TICKS, STONE_CANNON_COOLDOWN;
    public static final ModConfigSpec.DoubleValue STONE_CANNON_DAMAGE, STONE_CANNON_SPEED, STONE_CANNON_EXTRA_KNOCKBACK;
    public static final ModConfigSpec.IntValue WATER_BOLT_COST;
    public static final ModConfigSpec.IntValue WATER_BOLT_COOLDOWN;
    public static final ModConfigSpec.IntValue WATER_BOLT_CHANT_TICKS;
    public static final ModConfigSpec.DoubleValue WATER_BOLT_DAMAGE;
    public static final ModConfigSpec.IntValue WATER_BOLT_CHARGE_TICKS;
    public static final ModConfigSpec.IntValue WATER_BOLT_EXTRA_COST;
    public static final ModConfigSpec.IntValue MIRE_COST;
    public static final ModConfigSpec.IntValue MIRE_COOLDOWN;
    public static final ModConfigSpec.IntValue MIRE_CHANT_TICKS;
    public static final ModConfigSpec.IntValue WATER_SHIELD_COST;
    public static final ModConfigSpec.IntValue WATER_SHIELD_COOLDOWN;
    public static final ModConfigSpec.IntValue WATER_SHIELD_CHANT_TICKS;
    public static final ModConfigSpec.DoubleValue WATER_BOLT_EXTRA_DAMAGE;
    public static final ModConfigSpec.IntValue EARTH_WALL_COST;
    public static final ModConfigSpec.IntValue EARTH_WALL_COOLDOWN;
    public static final ModConfigSpec.IntValue EARTH_WALL_CHANT_TICKS;
    public static final ModConfigSpec.IntValue EARTH_WALL_RANGE;
    public static final ModConfigSpec.IntValue EARTH_DISMISS_COST;
    public static final ModConfigSpec.IntValue EARTH_DISMISS_HOLD_TICKS;
    public static final ModConfigSpec.IntValue EARTH_DISMISS_COOLDOWN;
    public static final ModConfigSpec.IntValue AIR_BURST_COST;
    public static final ModConfigSpec.IntValue AIR_BURST_COOLDOWN;
    public static final ModConfigSpec.IntValue AIR_BURST_CHANT_TICKS;
    public static final ModConfigSpec.IntValue AIR_BURST_RANGE;
    public static final ModConfigSpec.IntValue AIR_BURST_MAX_TARGETS;
    public static final ModConfigSpec.DoubleValue AIR_BURST_STRENGTH;
    public static final ModConfigSpec.IntValue HEALING_COST;
    public static final ModConfigSpec.IntValue HEALING_COOLDOWN;
    public static final ModConfigSpec.IntValue HEALING_CHANT_TICKS;
    public static final ModConfigSpec.IntValue HEALING_RANGE;
    public static final ModConfigSpec.DoubleValue HEALING_AMOUNT;
    public static final ModConfigSpec.IntValue FIRE_BALL_COST;
    public static final ModConfigSpec.IntValue FIRE_BALL_COOLDOWN;
    public static final ModConfigSpec.IntValue FIRE_BALL_CHANT_TICKS;
    public static final ModConfigSpec.DoubleValue FIRE_BALL_DAMAGE;
    public static final ModConfigSpec.IntValue FIRE_BALL_BURN_SECONDS;
    public static final ModConfigSpec.DoubleValue FIRE_BALL_SPEED;
    public static final ModConfigSpec.DoubleValue PRACTICED_DAMAGE_MULTIPLIER, ADEPT_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue WATER_SPLASH_RADIUS, FIRE_SPLASH_RADIUS, STONE_SPLASH_RADIUS, SPLASH_DAMAGE_RATIO;
    public static final ModConfigSpec.DoubleValue SLUGGISH_REDUCTION, SEARING_DAMAGE;
    public static final ModConfigSpec.IntValue SLUGGISH_TICKS, SEARING_TICKS, STONE_MAX_CONTACTS;
    public static final ModConfigSpec.IntValue STUDY_TICKS;
    public static final ModConfigSpec.IntValue PRACTICED_CASTS;
    public static final ModConfigSpec.IntValue ADEPT_CASTS;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("mana");
        MAX_MANA = builder.comment("Base mana capacity before earned growth. Capacity changes clamp existing mana down, never refill.")
                .defineInRange("maximum", 100, 1, 100000);
        REGEN_PER_SECOND = builder.comment("Mana restored per 20 game ticks; 0 disables recovery.")
                .defineInRange("regenerationPerSecond", 2, 0, 100000);
        RECOVERY_DELAY_TICKS = builder.comment("Delay after a successful cast before regeneration. 20 ticks = 1 second.")
                .defineInRange("recoveryDelayTicks", 40, 0, 12000);
        builder.pop().push("manaDepletion");
        DEPLETION_TICKS = builder.defineInRange("durationTicks", 300, 1, 72000);
        DEPLETION_BLIND_TICKS = builder.defineInRange("blindnessTicks", 60, 0, 1200);
        OVERCAST_CONFIRM_RATIO = builder.defineInRange("additionalChantRatio", 0.5, 0.05, 4.0);
        OVERCAST_UNLOCK_RATIO = builder.defineInRange("unlockManaRatio", 0.35, 0.01, 1.0);
        DEPLETION_REGEN = builder.defineInRange("regenerationMultiplier", 0.5, 0.0, 1.0);
        DEPLETION_WEAKNESS = builder.defineInRange("attackDamagePenalty", 4.0, 0.0, 64.0);
        DEPLETION_SLOWNESS = builder.defineInRange("movementReduction", 0.15, 0.0, 0.95);
        DEPLETION_MINING = builder.defineInRange("miningReduction", 0.7, 0.0, 0.99);
        builder.pop().push("manaGrowth");
        GROWTH_ENABLED = builder.comment("Enable new capacity growth. Disabling pauses growth timers, not earned capacity.")
                .define("enabled", true);
        GROWTH_BONUS_LIMIT = builder.comment("Maximum effective earned capacity. Lowering this preserves already earned records.")
                .defineInRange("bonusLimit", 100, 0, ManaGrowthRules.MAX_BONUS_LIMIT);
        GROWTH_GAIN_PER_ROUND = builder.comment("Capacity awarded per completed paid-spell training round; never immediately refills mana.")
                .defineInRange("gainPerRound", 2, 1, ManaGrowthRules.MAX_GAIN_PER_ROUND);
        GROWTH_COOLDOWN_TICKS = builder.comment("Alive survival/adventure online ticks between growth rounds. Offline time does not count.")
                .defineInRange("cooldownTicks", 6000, 0, ManaGrowthRules.MAX_COOLDOWN_TICKS);
        builder.pop().push("waterBolt");
        WATER_BOLT_COST = builder.defineInRange("manaCost", 12, 1, 100000);
        WATER_BOLT_COOLDOWN = builder.comment("Minimum interval between casts, in game ticks.")
                .defineInRange("cooldownTicks", 20, 1, 12000);
        WATER_BOLT_CHANT_TICKS = builder.comment("Minimum hold before release can cast. Releasing sooner cancels for free.")
                .defineInRange("chantTicks", 12, 1, 1200);
        WATER_BOLT_DAMAGE = builder.comment("Base damage before armor/resistance. Two damage points = one heart.")
                .defineInRange("damage", 6.0, 0.0, 1000.0);
        WATER_BOLT_CHARGE_TICKS = builder.comment("Additional hold after chanting to fully shape a water ball; release to fire. Does not remove chanting.")
                .defineInRange("chargeTicks", 24, 1, 1200);
        WATER_BOLT_EXTRA_COST = builder.comment("Extra mana at full charge. Partial charges round extra cost up. Holding costs nothing until release.")
                .defineInRange("extraManaAtFullCharge", 12, 0, 100000);
        WATER_BOLT_EXTRA_DAMAGE = builder.comment("Extra single-target damage at full charge. Charging does not add area damage or terrain destruction.")
                .defineInRange("extraDamageAtFullCharge", 4.0, 0.0, 1000.0);
        builder.pop().push("waterShield");
        WATER_SHIELD_COST = builder.comment("Mana paid once when a Water Shield forms.").defineInRange("manaCost", 24, 1, 100000);
        WATER_SHIELD_COOLDOWN = builder.comment("Cooldown after a successful Water Shield, in game ticks.").defineInRange("cooldownTicks", 200, 1, 12000);
        WATER_SHIELD_CHANT_TICKS = builder.comment("Minimum hold before a Water Shield can form.").defineInRange("chantTicks", 16, 1, 1200);
        builder.pop().push("mire");
        MIRE_COST = builder.comment("Mana paid once after a valid Mire forms.").defineInRange("manaCost", 28, 1, 100000);
        MIRE_COOLDOWN = builder.comment("Shared spell cooldown after a successful Mire, in ticks.").defineInRange("cooldownTicks", 120, 1, 12000);
        MIRE_CHANT_TICKS = builder.comment("Minimum hold before releasing Mire.").defineInRange("chantTicks", 40, 1, 1200);
        builder.pop().push("earthWall");
        EARTH_WALL_COST = builder.defineInRange("manaCost", 30, 1, 100000);
        EARTH_WALL_COOLDOWN = builder.comment("Shared spell cooldown after a successful wall.")
                .defineInRange("cooldownTicks", 40, 1, 12000);
        EARTH_WALL_CHANT_TICKS = builder.defineInRange("chantTicks", 20, 1, 1200);
        EARTH_WALL_RANGE = builder.comment("Eye-to-support hit range; the wall expands sideways from the middle cell.")
                .defineInRange("range", 6, 1, 16);
        builder.pop().push("earthDismissal");
        EARTH_DISMISS_COST = builder.comment("Mana spent after a successful removal of the caster's own wall group.")
                .defineInRange("manaCost", 6, 1, 100000);
        EARTH_DISMISS_HOLD_TICKS = builder.comment("Hold the dismiss key at least this many ticks, then release. Early release is free.")
                .defineInRange("holdTicks", 20, 1, 100);
        EARTH_DISMISS_COOLDOWN = builder.comment("Shared spell cooldown after successful dismissal; no mana refund or practice reward.")
                .defineInRange("cooldownTicks", 20, 1, 12000);
        builder.pop().push("airBurst");
        AIR_BURST_COST = builder.defineInRange("manaCost", 16, 1, 100000);
        AIR_BURST_COOLDOWN = builder.defineInRange("cooldownTicks", 30, 1, 12000);
        AIR_BURST_CHANT_TICKS = builder.defineInRange("chantTicks", 16, 1, 1200);
        AIR_BURST_RANGE = builder.comment("Eye-to-target-body-center range of the forward 60-degree cone.")
                .defineInRange("range", 6, 1, 16);
        AIR_BURST_MAX_TARGETS = builder.defineInRange("maximumTargets", 8, 1, 64);
        AIR_BURST_STRENGTH = builder.comment("Vanilla horizontal knockback strength, before resistance and knockback events.")
                .defineInRange("knockbackStrength", 0.8, 0.05, 4.0);
        builder.pop().push("healing");
        HEALING_COST = builder.defineInRange("manaCost", 24, 1, 100000);
        HEALING_COOLDOWN = builder.defineInRange("cooldownTicks", 40, 1, 12000);
        HEALING_CHANT_TICKS = builder.defineInRange("chantTicks", 24, 1, 1200);
        HEALING_RANGE = builder.comment("Eye ray reach for a single living target; empty/block-only aim treats the caster.")
                .defineInRange("range", 4, 1, 8);
        HEALING_AMOUNT = builder.comment("Health points passed to vanilla heal after payment; two health points equal one heart.")
                .defineInRange("healthPoints", 4.0, 0.5, 40.0);
        builder.pop().push("fireBall");
        FIRE_BALL_COST = builder.defineInRange("manaCost", 16, 1, 100000);
        FIRE_BALL_COOLDOWN = builder.defineInRange("cooldownTicks", 30, 1, 12000);
        FIRE_BALL_CHANT_TICKS = builder.defineInRange("chantTicks", 16, 1, 1200);
        FIRE_BALL_DAMAGE = builder.comment("Direct fire damage before protection. Ignition requires accepted health or absorption loss.")
                .defineInRange("damage", 5.0, 0.5, 1000.0);
        FIRE_BALL_BURN_SECONDS = builder.comment("Entity burn duration after an accepted hit; zero disables ignition. Never ignites terrain.")
                .defineInRange("burnSeconds", 3, 0, 30);
        FIRE_BALL_SPEED = builder.comment("Initial projectile speed; fire expires after 30 ticks and extinguishes on water contact.")
                .defineInRange("speed", 1.15, 0.2, 3.0);
        builder.pop().push("stoneCannon");
        STONE_CANNON_COST = builder.defineInRange("manaCost", 20, 1, 100000);
        STONE_CANNON_CHANT_TICKS = builder.defineInRange("chantTicks", 20, 1, 1200);
        STONE_CANNON_COOLDOWN = builder.defineInRange("cooldownTicks", 30, 1, 12000);
        STONE_CANNON_DAMAGE = builder.defineInRange("damage", 8.0, 0.5, 1000.0);
        STONE_CANNON_SPEED = builder.comment("Initial constant speed; hard caps are 24 ticks and 36 blocks.").defineInRange("speed", 1.6, 0.2, 3.0);
        STONE_CANNON_EXTRA_KNOCKBACK = builder.comment("Extra knockback after accepted health/absorption loss, in addition to vanilla hurt knockback; resistance and events still apply.").defineInRange("extraKnockback", 0.35, 0.0, 2.0);
        builder.pop().push("attackSpellForms");
        PRACTICED_DAMAGE_MULTIPLIER = builder.defineInRange("practicedDamageMultiplier", 1.25, 1.0, 4.0);
        ADEPT_DAMAGE_MULTIPLIER = builder.comment("Adept never falls below the practiced multiplier.")
                .defineInRange("adeptDamageMultiplier", 1.5, 1.0, 4.0);
        WATER_SPLASH_RADIUS = builder.defineInRange("waterSplashRadius", 2.25, 0.0, 8.0);
        FIRE_SPLASH_RADIUS = builder.defineInRange("fireSplashRadius", 2.5, 0.0, 8.0);
        STONE_SPLASH_RADIUS = builder.defineInRange("stoneSplashRadius", 1.5, 0.0, 8.0);
        SPLASH_DAMAGE_RATIO = builder.comment("Share of the cast's direct damage at the centre; half this share at the outer edge.")
                .defineInRange("splashDamageRatio", .4, 0.0, 1.0);
        SLUGGISH_REDUCTION = builder.defineInRange("sluggishMovementReduction", .25, 0.0, .75);
        SLUGGISH_TICKS = builder.defineInRange("sluggishTicks", 60, 1, 1200);
        SEARING_TICKS = builder.defineInRange("searingTicks", 80, 1, 1200);
        SEARING_DAMAGE = builder.defineInRange("searingDamageEachSecond", 1.0, 0.0, 20.0);
        STONE_MAX_CONTACTS = builder.comment("Total distinct entity contacts of one adept drill, including the final one.")
                .defineInRange("stoneMaximumContacts", 3, 1, 3);
        builder.pop().push("learning");
        STUDY_TICKS = builder.comment("Hold the tome this many ticks to learn a spell. Early release cancels studying.")
                .defineInRange("studyTicks", 40, 1, 1200);
        PRACTICED_CASTS = builder.comment("Successful non-creative casts needed for the practiced stage (80% base chant time).")
                .defineInRange("practicedCasts", 20, 1, MagicKnowledge.MAX_PRACTICE - 1);
        ADEPT_CASTS = builder.comment("Casts for the adept stage (60% base chant time). Always treated as at least practicedCasts + 1.")
                .defineInRange("adeptCasts", 60, 2, MagicKnowledge.MAX_PRACTICE);
        builder.pop().push("battleAura");
        AURA_HARDNESS = builder.comment("Maximum health points battle aura can prevent per eligible physical hit.")
                .defineInRange("hardness", 2.0, 0.0, 1000.0);
        AURA_MAX_REDUCTION_RATIO = builder.comment("Maximum fraction of damage past armor and absorption that battle aura can prevent.")
                .defineInRange("maxReductionRatio", 0.5, 0.0, 1.0);
        builder.pop().push("swordSchool");
        SCHOOL_PREPARATION_TICKS = builder.comment("Ticks between changes made with a training notebook. The Epic Fight skill editor retains the server's own replacement rule.")
                .defineInRange("prepareCooldownTicks", 20, 0, 72000);
        builder.pop().push("naming");
        NAMING_CUSTOM = builder.comment("Whether players may give a name of their own for the NPCs to call them by; otherwise only \"Rudy\" or their game name.")
                .define("allowCustomNames", true);
        NAMING_MAX_LENGTH = builder.comment("Most characters in a name of one's own.")
                .defineInRange("maxLength", dev.joblessreincarnation.naming.NamingChoice.MAX_LENGTH, 1, dev.joblessreincarnation.naming.NamingChoice.MAX_LENGTH);
        builder.pop();
        SPEC = builder.build();
    }

    private MagicConfig() {}
}
