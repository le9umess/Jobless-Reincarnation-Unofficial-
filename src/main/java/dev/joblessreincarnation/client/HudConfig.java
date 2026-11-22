package dev.joblessreincarnation.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only preferences, kept in {@code config/jobless_reincarnation-client.toml} and shown in the mod's settings screen. */
public final class HudConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** The glint that travels round the brass ring behind the resting mana crystal; off keeps the ring still. */
    public static final ModConfigSpec.BooleanValue ANIMATE_CRYSTAL_RING = BUILDER
            .comment("Let a slow glint travel round the brass ring behind the resting mana crystal. Off keeps the ring still.")
            .translation("jobless_reincarnation.configuration.animateCrystalRing")
            .define("animateCrystalRing", true);

    /** Epic Fight's combat mode with the mod's over-the-shoulder camera and crosshair aiming for the staff. */
    public static final ModConfigSpec.BooleanValue COMBAT_VIEW = BUILDER
            .comment("Combat view: entering Epic Fight's combat mode switches to an over-the-shoulder camera and aims the staff at the crosshair."
                    + " Epic Fight's own settings are never changed. Off leaves the camera entirely to Epic Fight.")
            .translation("jobless_reincarnation.configuration.combatView")
            .define("combatView", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private HudConfig() {}
}
