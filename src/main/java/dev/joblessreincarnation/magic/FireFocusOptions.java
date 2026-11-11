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

/** Public appearance, and the caster's public entity id so that their own client can draw it at the staff in their hand. No knowledge, mana or private attachment data. */
public record FireFocusOptions(float progress, int caster, int stage) implements ParticleOptions {
    public static final MapCodec<FireFocusOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.FLOAT.fieldOf("progress").forGetter(FireFocusOptions::progress),
            Codec.INT.optionalFieldOf("caster", -1).forGetter(FireFocusOptions::caster),
            Codec.INT.optionalFieldOf("stage", 0).forGetter(FireFocusOptions::stage)
    ).apply(instance, FireFocusOptions::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, FireFocusOptions> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, FireFocusOptions::progress, ByteBufCodecs.VAR_INT, FireFocusOptions::caster,
            ByteBufCodecs.VAR_INT, FireFocusOptions::stage, FireFocusOptions::new);
    public FireFocusOptions {
        progress = Float.isFinite(progress) ? Mth.clamp(progress, 0, 1) : 0;
        stage = Mth.clamp(stage, 0, 2);
    }
    public FireFocusOptions(float progress, int caster) { this(progress, caster, 0); }
    @Override public ParticleType<FireFocusOptions> getType() { return ModContent.FIRE_FOCUS.get(); }
}
