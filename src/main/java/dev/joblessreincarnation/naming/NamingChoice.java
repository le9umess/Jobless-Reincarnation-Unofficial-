package dev.joblessreincarnation.naming;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How the NPCs address this player (user 11-06: the player is Rudeus, but may want to be called something else). One of
 * three: "Rudy" as in the novel (the default, also when the player skips the choice), the player's own game name, or a
 * name of their own. Only the name changes; the family still treats them as their child. Common to both sides: the server
 * keeps each player's choice ({@link PlayerNaming}) and the client shows it.
 *
 * @param mode   which of the three
 * @param custom the player's own name, used only when {@code mode} is {@link Mode#CUSTOM}; already cleaned
 */
public record NamingChoice(Mode mode, String custom) {
    public enum Mode {
        RUDY, ACCOUNT, CUSTOM;

        /** A stored mode; one this version does not know reads as Rudy. */
        static Mode of(String id) {
            for (Mode mode : values()) if (mode.id().equals(id)) return mode;
            return RUDY;
        }

        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** At most this many characters in a name of one's own (a server may allow fewer). */
    public static final int MAX_LENGTH = 16;
    public static final NamingChoice DEFAULT = new NamingChoice(Mode.RUDY, "");

    private static final Codec<Mode> MODE_CODEC = Codec.STRING.xmap(Mode::of, Mode::id);
    /** As stored: {@code mode} and {@code custom} beside the other fields of the record that holds it. */
    public static final MapCodec<NamingChoice> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            MODE_CODEC.optionalFieldOf("mode", Mode.RUDY).forGetter(NamingChoice::mode),
            Codec.STRING.optionalFieldOf("custom", "").forGetter(NamingChoice::custom)
    ).apply(instance, NamingChoice::new));
    /** On the wire the name is limited before it is read; the record then cleans it again. */
    public static final StreamCodec<ByteBuf, NamingChoice> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> i >= 0 && i < Mode.values().length ? Mode.values()[i] : Mode.RUDY, Mode::ordinal), NamingChoice::mode,
            ByteBufCodecs.stringUtf8(64), NamingChoice::custom,
            NamingChoice::new);

    public NamingChoice {
        if (mode == null) mode = Mode.RUDY;
        custom = clean(custom);
    }

    /** A name of one's own as it will be used: no formatting codes or control characters, trimmed, at most {@link #MAX_LENGTH}. */
    public static String clean(String raw) {
        return clean(raw, MAX_LENGTH);
    }

    /** The same, at most {@code max} characters (code points). */
    public static String clean(String raw, int max) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        raw.codePoints().filter(c -> c != '§' && !Character.isISOControl(c)).forEach(out::appendCodePoint);
        String trimmed = out.toString().strip();
        if (trimmed.codePointCount(0, trimmed.length()) > max) trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).strip();
        return trimmed;
    }

    /** A name of one's own must not be empty. */
    public boolean usable() {
        return mode != Mode.CUSTOM || !custom.isEmpty();
    }
}
