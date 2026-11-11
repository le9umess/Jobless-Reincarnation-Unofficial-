package dev.joblessreincarnation.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.naming.Naming;
import dev.joblessreincarnation.naming.NamingChoice;
import dev.joblessreincarnation.naming.PlayerNaming;
import dev.joblessreincarnation.npc.FamilyInteractions;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** N01: what the family calls each player, kept by the server (user 11-07: in 0.1.0). */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NamingGameTests {
    private static final String KEY = "screen.jobless_reincarnation.naming.";

    /** The two names inside a family line: the short form, then the full form. */
    private static Component[] namesIn(Component line) {
        var outer = (TranslatableContents) line.getContents();
        var said = (TranslatableContents) ((Component) outer.getArgs()[1]).getContents();
        return new Component[] {(Component) said.getArgs()[0], (Component) said.getArgs()[1]};
    }

    private static boolean isKey(Component name, String key) {
        return name.getContents() instanceof TranslatableContents t && t.getKey().equals(KEY + key);
    }

    @GameTest(template = "empty")
    public static void aNewPlayerIsAskedAndLaterKeepsRudy(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = f.actor();
            h.assertValueEqual(Naming.of(p), PlayerNaming.NEW, "A player new to this world is called Rudy and has not answered");
            Naming.answer(p, null);
            h.assertValueEqual(Naming.of(p), new PlayerNaming(NamingChoice.DEFAULT, true), "\"Later\" keeps Rudy and is not asked again");
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "Alice"));
            Naming.answer(p, null);
            h.assertValueEqual(Naming.of(p).choice(), new NamingChoice(NamingChoice.Mode.CUSTOM, "Alice"), "\"Later\" from the journal keeps the name");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eachPlayerHearsTheNameTheyChose(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor alice = f.actor(), rudy = f.actor(), account = f.actor();
            Naming.answer(alice, new NamingChoice(NamingChoice.Mode.CUSTOM, "  Al\u0007ice  "));
            Naming.answer(account, new NamingChoice(NamingChoice.Mode.ACCOUNT, "ignored"));
            h.assertValueEqual(Naming.of(alice).choice().custom(), "Alice", "Control characters and edge spaces are cleaned off");
            h.assertValueEqual(Naming.of(account).choice().custom(), "", "A game-name choice keeps no text of its own");

            Component[] zenith = namesIn(FamilyInteractions.line(FamilyResidentEntity.Member.ZENITH, 2, alice));
            Component[] lilia = namesIn(FamilyInteractions.line(FamilyResidentEntity.Member.LILIA, 0, alice));
            h.assertTrue(zenith[0].getString().equals("Alice") && lilia[1].getString().equals("Alice"), "Zenith and Lilia call Alice by her own name");
            Component[] own = namesIn(FamilyInteractions.line(FamilyResidentEntity.Member.ZENITH, 2, account));
            h.assertValueEqual(own[0].getString(), account.getGameProfile().getName(), "The game name is used as it is");
            Component[] novel = namesIn(FamilyInteractions.line(FamilyResidentEntity.Member.LILIA, 0, rudy));
            h.assertTrue(isKey(novel[0], "rudy_short") && isKey(novel[1], "rudy_full"), "Rudy is still Rudy and Rudeus, in the reader's language");
            Component[] nobody = namesIn(FamilyInteractions.line(FamilyResidentEntity.Member.LILIA, 0));
            h.assertTrue(isKey(nobody[0], "rudy_short") && isKey(nobody[1], "rudy_full"), "Without a listener the line is the novel's");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theServerChecksEveryAnswerAgain(GameTestHelper h) {
        boolean custom = MagicConfig.NAMING_CUSTOM.get();
        int length = MagicConfig.NAMING_MAX_LENGTH.get();
        try (var f = new Fixture(h)) {
            Actor p = f.actor();
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "   "));
            h.assertValueEqual(Naming.of(p).choice(), NamingChoice.DEFAULT, "An empty name of one's own is Rudy");
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "§cRed"));
            h.assertValueEqual(Naming.of(p).choice().custom(), "cRed", "No formatting codes reach the family's lines");
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
            h.assertValueEqual(Naming.of(p).choice().custom(), "ABCDEFGHIJKLMNOP", "At most sixteen characters");
            MagicConfig.NAMING_MAX_LENGTH.set(4);
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "Alexander"));
            h.assertValueEqual(Naming.of(p).choice().custom(), "Alex", "A server may allow shorter names");
            MagicConfig.NAMING_CUSTOM.set(false);
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "Alice"));
            h.assertValueEqual(Naming.of(p).choice(), NamingChoice.DEFAULT, "Where names of one's own are off, one becomes Rudy");
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.ACCOUNT, ""));
            h.assertValueEqual(Naming.of(p).choice().mode(), NamingChoice.Mode.ACCOUNT, "The game name is still allowed");
        } finally {
            MagicConfig.NAMING_CUSTOM.set(custom);
            MagicConfig.NAMING_MAX_LENGTH.set(length);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void resetCommandAsksAgain(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            Actor p = f.actor();
            Naming.answer(p, new NamingChoice(NamingChoice.Mode.CUSTOM, "Alice"));
            var server = h.getLevel().getServer();
            // Test players join the level, not the server's player list: select them by distance (operators type a name).
            var source = server.createCommandSourceStack().withLevel(h.getLevel()).withPosition(p.position()).withPermission(2).withSuppressedOutput();
            try {
                h.assertTrue(server.getCommands().getDispatcher().execute("jr_naming reset @a[distance=..0.5]", source) == 1, "Resets one player");
            } catch (CommandSyntaxException e) {
                throw new IllegalStateException(e);
            }
            h.assertValueEqual(Naming.of(p), PlayerNaming.NEW, "Back to Rudy and asked again");
            boolean refused;
            try {
                server.getCommands().getDispatcher().execute("jr_naming reset @a[distance=..0.5]", source.withPermission(0));
                refused = false;
            } catch (CommandSyntaxException e) {
                refused = true;
            }
            h.assertTrue(refused, "Only operators may reset");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theRecordSavesAndTravelsIntact(GameTestHelper h) {
        var named = new PlayerNaming(new NamingChoice(NamingChoice.Mode.CUSTOM, "Alice"), true);
        var saved = PlayerNaming.CODEC.encodeStart(NbtOps.INSTANCE, named).getOrThrow();
        h.assertValueEqual(PlayerNaming.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow(), named, "Saved and read back unchanged");
        var unknown = new net.minecraft.nbt.CompoundTag();
        unknown.putString("mode", "visitor");
        h.assertValueEqual(PlayerNaming.CODEC.parse(NbtOps.INSTANCE, unknown).getOrThrow(), PlayerNaming.NEW, "An unknown mode reads as Rudy, not yet answered");

        var buffer = Unpooled.buffer();
        try {
            PlayerNaming.STREAM_CODEC.encode(buffer, named);
            h.assertValueEqual(PlayerNaming.STREAM_CODEC.decode(buffer), named, "Sent to the owner unchanged");
            buffer.clear();
            ByteBufCodecs.VAR_INT.encode(buffer, 2);
            ByteBufCodecs.stringUtf8(1000).encode(buffer, "x".repeat(200));
            boolean refused;
            try {
                NamingChoice.STREAM_CODEC.decode(buffer);
                refused = false;
            } catch (RuntimeException tooLong) {
                refused = true;
            }
            h.assertTrue(refused, "An oversized name is refused before it is read");
        } finally {
            buffer.release();
        }
        h.assertTrue(ModAttachments.NAMING.get() != null, "The attachment is registered");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void linesThatNameThePlayerTakeTheChosenName(GameTestHelper h) {
        String[][] expected = {
                {"message.jobless_reincarnation.family.zenith_greyrat.2", "%1$s"},
                {"message.jobless_reincarnation.family.lilia_greyrat.0", "%2$s"},
        };
        for (String lang : new String[] {"zh_cn", "en_us"}) {
            String json;
            try (InputStream in = NamingGameTests.class.getResourceAsStream("/assets/jobless_reincarnation/lang/" + lang + ".json")) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("No " + lang, e);
            }
            var lines = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            for (String[] entry : expected)
                h.assertTrue(lines.get(entry[0]).getAsString().contains(entry[1]), lang + " " + entry[0] + " names the listener with " + entry[1]);
            String note = lines.get("tooltip.jobless_reincarnation.library.plant_dictionary").getAsString();
            h.assertTrue(note.contains("%1$s") || note.contains("%2$s"), lang + " plant dictionary names its reader");
            for (String key : new String[] {"journal.naming", "naming.custom_off_hint"})
                h.assertTrue(lines.has("screen.jobless_reincarnation." + key), lang + " has " + key);
        }
        h.succeed();
    }

    private NamingGameTests() {}
}
