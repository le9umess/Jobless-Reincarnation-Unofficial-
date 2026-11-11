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

/** One spell-effect sprite. Public appearance only: no caster identity, knowledge, mana or damage. */
public record SpellSpriteOptions(int style, float size, int life) implements ParticleOptions {
    public static final int EMBER = 0, SMOKE = 1, STEAM = 2, GLOW = 3, FLAME = 4, FOCUS_FLAME = 5, FOCUS_SPARK = 6, FOCUS_GLOW = 7,
            DROPLET = 8, MIST = 9, DUST = 10, RING_WATER = 11, RING_STONE = 12, FLECK = 13, FLASH = 14, FOCUS_CHIP = 15, FOCUS_DUST = 16, FOCUS_PALE = 17,
            /** A small spark that rises and flickers off a creature under {@code searing}; {@link #EMBER} falls instead. */
            SEAR = 18,
            /** A drop winding in to the staff tip while water gathers there. */
            FOCUS_DROP = 19,
            /** A ring lying flat and spreading to {@code size} blocks: how far an adept's burst reached. Water, fire, stone. */
            FLAT_RING_WATER = 20, FLAT_RING_FIRE = 21, FLAT_RING_STONE = 22;
    private static final int STYLE_COUNT = 23;

    public static final MapCodec<SpellSpriteOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("style").forGetter(SpellSpriteOptions::style),
            Codec.FLOAT.fieldOf("size").forGetter(SpellSpriteOptions::size),
            Codec.INT.fieldOf("life").forGetter(SpellSpriteOptions::life)
    ).apply(instance, SpellSpriteOptions::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpellSpriteOptions> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpellSpriteOptions::style,
            ByteBufCodecs.FLOAT, SpellSpriteOptions::size,
            ByteBufCodecs.VAR_INT, SpellSpriteOptions::life,
            SpellSpriteOptions::new);

    public SpellSpriteOptions {
        style = Mth.clamp(style, 0, STYLE_COUNT - 1);
        size = Float.isFinite(size) ? Mth.clamp(size, 0.01F, 4.0F) : 0.1F;
        life = Mth.clamp(life, 1, 100);
    }
    @Override public ParticleType<SpellSpriteOptions> getType() { return ModContent.SPELL_SPRITE.get(); }
}
