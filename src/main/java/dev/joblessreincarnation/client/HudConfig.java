package dev.joblessreincarnation.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only HUD preferences, kept in {@code config/jobless_reincarnation-client.toml}. */
public final class HudConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** The glint that travels round the brass ring behind the resting mana crystal; off keeps the ring still. */
    public static final ModConfigSpec.BooleanValue ANIMATE_CRYSTAL_RING = BUILDER
            .comment("Let a slow glint travel round the brass ring behind the resting mana crystal. Off keeps the ring still.")
            .define("animateCrystalRing", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private HudConfig() {}
}
