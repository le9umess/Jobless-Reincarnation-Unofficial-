package dev.joblessreincarnation.naming;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What one player is called in this world, kept by the server with the player and copied on death.
 *
 * @param choice  how the family calls them
 * @param settled whether they have answered the naming screen, either way; until then it opens when they are in the world
 */
public record PlayerNaming(NamingChoice choice, boolean settled) {
    /** A player new to this world: called Rudy, and asked. */
    public static final PlayerNaming NEW = new PlayerNaming(NamingChoice.DEFAULT, false);
    /**
     * What a client assumes before the server has said anything: Rudy, and nothing to ask. The client only asks once the
     * server's own record, sent on every entry, says the player has not answered yet.
     */
    public static final PlayerNaming UNKNOWN = new PlayerNaming(NamingChoice.DEFAULT, true);

    public static final Codec<PlayerNaming> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            NamingChoice.MAP_CODEC.forGetter(PlayerNaming::choice),
            Codec.BOOL.optionalFieldOf("settled", false).forGetter(PlayerNaming::settled)
    ).apply(instance, PlayerNaming::new));
    public static final StreamCodec<ByteBuf, PlayerNaming> STREAM_CODEC = StreamCodec.composite(
            NamingChoice.STREAM_CODEC, PlayerNaming::choice,
            ByteBufCodecs.BOOL, PlayerNaming::settled,
            PlayerNaming::new);

    public PlayerNaming {
        if (choice == null) choice = NamingChoice.DEFAULT;
    }
}
