package dev.joblessreincarnation.encounter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** One private, durable fact. An active bout is never saved or resumed after a restart. */
public record PaulDuelProgress(boolean won) {
    public static final Codec<PaulDuelProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("won", false).forGetter(PaulDuelProgress::won)).apply(i, PaulDuelProgress::new));
}
