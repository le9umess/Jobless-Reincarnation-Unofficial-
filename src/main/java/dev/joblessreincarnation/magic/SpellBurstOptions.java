package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * A spell's one-off impact: the server sends this once and each client composes the sprites itself. Appearance only;
 * how far it reaches on screen says nothing about what the spell actually hit. A chant also names its caster's public entity
 * id, so that their own client can draw it at the staff in their hand.
 */
public record SpellBurstOptions(int kind, int caster, int stage, float radius, int impactIndex) implements ParticleOptions {
    public static final int FIRE_IMPACT = 0, FIRE_QUENCHED = 1, WATER_IMPACT = 2, STONE_IMPACT = 3, AIR_WAVE = 4, STONE_FOCUS = 5, OVERCAST = 6, TEAR_ASH = 7, FLOW_OPENING = 8, LIGHT_CUT = 9, TEAR_ASH_GUARDED = 10, WATER_FOCUS = 11,
            /** Arm Strike landing inside an opponent's windup and interrupting it; the caster is the striker; stage 1 when it caught a later strike. */
            ARM_BREAK = 12,
            /** The Longsword of Light released: the light of the dash, following the caster; the stage is the tier less one. */
            LIGHT_DASH = 13;
    private static final int KIND_COUNT = 14, NO_CASTER = -1;

    public static final MapCodec<SpellBurstOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("kind").forGetter(SpellBurstOptions::kind),
            Codec.INT.optionalFieldOf("caster", NO_CASTER).forGetter(SpellBurstOptions::caster),
            Codec.INT.optionalFieldOf("stage", 0).forGetter(SpellBurstOptions::stage),
            Codec.FLOAT.optionalFieldOf("radius", 0F).forGetter(SpellBurstOptions::radius),
            Codec.INT.optionalFieldOf("impact_index", 0).forGetter(SpellBurstOptions::impactIndex)
    ).apply(instance, SpellBurstOptions::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpellBurstOptions> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpellBurstOptions::kind, ByteBufCodecs.VAR_INT, SpellBurstOptions::caster,
            ByteBufCodecs.VAR_INT, SpellBurstOptions::stage, ByteBufCodecs.FLOAT, SpellBurstOptions::radius,
            ByteBufCodecs.VAR_INT, SpellBurstOptions::impactIndex, SpellBurstOptions::new);

    public SpellBurstOptions {
        kind = Mth.clamp(kind, 0, KIND_COUNT - 1);
        stage = Mth.clamp(stage, 0, 2);
        impactIndex = Mth.clamp(impactIndex, 0, 2);
        radius = Float.isFinite(radius) ? Mth.clamp(radius, 0, 8) : 0;
    }
    public SpellBurstOptions(int kind, int caster, int stage, float radius) { this(kind, caster, stage, radius, 0); }
    public SpellBurstOptions(int kind, int caster, int stage) { this(kind, caster, stage, 0); }
    public SpellBurstOptions(int kind, int caster) { this(kind, caster, 0, 0); }
    public SpellBurstOptions(int kind) {
        this(kind, NO_CASTER);
    }
    @Override public ParticleType<SpellBurstOptions> getType() { return ModContent.SPELL_BURST.get(); }
}
