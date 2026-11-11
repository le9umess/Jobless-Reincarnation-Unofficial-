package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Learned spells and successful practice casts. Mutable only on the logical server. */
public final class MagicKnowledge {
    public static final int MAX_PRACTICE = 1_000_000;
    public static final Codec<MagicKnowledge> CODEC = Codec.unboundedMap(
            ResourceLocation.CODEC, Codec.intRange(0, MAX_PRACTICE))
            .optionalFieldOf("spells", Map.of()).codec().xmap(MagicKnowledge::new, MagicKnowledge::snapshot);
    public static final StreamCodec<ByteBuf, MagicKnowledge> STREAM_CODEC =
            ByteBufCodecs.<ByteBuf, ResourceLocation, Integer, Map<ResourceLocation, Integer>>map(
                    HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT)
                    .map(MagicKnowledge::new, MagicKnowledge::snapshot);

    // Presence means learned; zero successful casts is distinct from an unknown spell.
    private final Map<ResourceLocation, Integer> spells;

    public MagicKnowledge() { this(Map.of()); }

    private MagicKnowledge(Map<ResourceLocation, Integer> spells) {
        this.spells = new HashMap<>();
        spells.forEach((spell, count) -> this.spells.put(spell, Math.clamp(count, 0, MAX_PRACTICE)));
    }

    public boolean knows(ResourceLocation spell) { return spells.containsKey(spell); }
    public int practiceCount(ResourceLocation spell) { return spells.getOrDefault(spell, 0); }
    public Map<ResourceLocation, Integer> snapshot() { return Map.copyOf(spells); }

    /** Returns false for an already learned spell, preserving existing practice. */
    public boolean learn(ResourceLocation spell) { return spells.putIfAbsent(spell, 0) == null; }

    public boolean practice(ResourceLocation spell) {
        if (!knows(spell) || practiceCount(spell) >= MAX_PRACTICE) return false;
        spells.put(spell, practiceCount(spell) + 1);
        return true;
    }
}
