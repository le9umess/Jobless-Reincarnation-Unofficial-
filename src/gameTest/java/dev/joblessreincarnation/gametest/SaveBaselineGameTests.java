package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 0.1.0 is the first public release and the save baseline: every later version must still open its worlds and player files.
 * This freezes every jobless_reincarnation identifier such a file can refer to: entries of every registry (items, blocks, entities,
 * attachments, effects, sounds, structures, ...), advancements, recipes and the structure templates that generated chunks name.
 * Removing or renaming one fails here. New identifiers are fine, but they have to be added to the list in the same change.
 * The list is src/gameTest/resources/save-baseline/0.1.0-ids.txt. Without it, the test writes the current identifiers to
 * save-baseline-0.1.0-ids.txt in the run directory and fails, so the list can be reviewed and checked in.
 * Structure templates directly under structure/ are the test fixtures of this source set and are not frozen, and neither is the
 * display entity that only this source set registers ({@link NpcDisplayRegistry#ROXY_ID}).
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SaveBaselineGameTests {
    private static final String EXPECTED = "/save-baseline/0.1.0-ids.txt";

    @GameTest(template = "empty", batch = "saveBaseline")
    public static void identifiersFrozenSince010(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String mod = JoblessReincarnation.MOD_ID;
        TreeSet<String> actual = new TreeSet<>();
        server.registryAccess().registries().forEach(entry -> entry.value().keySet().forEach(id -> {
            if (id.getNamespace().equals(mod) && !id.equals(NpcDisplayRegistry.ROXY_ID)) actual.add("registry " + entry.key().location() + " " + id);
        }));
        for (var advancement : server.getAdvancements().getAllAdvancements())
            if (advancement.id().getNamespace().equals(mod)) actual.add("advancement " + advancement.id());
        for (var recipe : server.getRecipeManager().getRecipes())
            if (recipe.id().getNamespace().equals(mod)) actual.add("recipe " + recipe.id());
        server.getStructureManager().listTemplates()
                .filter(id -> id.getNamespace().equals(mod) && id.getPath().contains("/"))
                .forEach(id -> actual.add("structure_template " + id));

        List<String> expected = expected();
        if (expected == null) {
            Path out = Path.of("save-baseline-0.1.0-ids.txt").toAbsolutePath();
            try {
                Files.writeString(out, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write " + out, e);
            }
            helper.fail("No frozen 0.1.0 identifier list yet; wrote the current " + actual.size() + " identifiers to " + out);
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String id : expected) if (!actual.contains(id)) missing.add(id);
        if (!missing.isEmpty())
            helper.fail(missing.size() + " identifier(s) from 0.1.0 are gone; old worlds or players would lose them: " + missing);
        helper.succeed();
    }

    private static List<String> expected() {
        try (InputStream stream = SaveBaselineGameTests.class.getResourceAsStream(EXPECTED)) {
            if (stream == null) return null;
            List<String> lines = new ArrayList<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + EXPECTED, e);
        }
    }

    private SaveBaselineGameTests() {}
}
