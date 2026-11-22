package dev.joblessreincarnation.gametest;

import com.google.gson.JsonParser;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.hud.HudPackOrder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HudBrassGameTests {
    @GameTest(template = "empty")
    public static void initialHudKeepsExistingPackPriority(GameTestHelper h) {
        var existing = List.of("vanilla", "mod_resources", "file/my-pack", "file/top-pack");
        var next = HudPackOrder.firstSelection(existing);
        h.assertTrue(next.equals(List.of("vanilla", HudPackOrder.ID, "mod_resources", "file/my-pack", "file/top-pack")),
                "The new default is above vanilla, while all pre-existing packs keep their relative order");
        h.assertTrue(existing.size() == 4, "The input list is not modified");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void explicitlySelectedHudIsNeverRepositioned(GameTestHelper h) {
        var chosen = List.of("vanilla", "file/low", "mod_resources", "file/top", HudPackOrder.ID);
        h.assertTrue(HudPackOrder.firstSelection(chosen).equals(chosen), "A pre-existing explicit choice keeps its exact priority");
        h.assertTrue(HudPackOrder.firstSelection(HudPackOrder.firstSelection(List.of("vanilla"))).equals(List.of("vanilla", HudPackOrder.ID)),
                "Repeated initialization does not duplicate the pack");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void hudResourcesRemainOptionalAndComplete(GameTestHelper h) throws Exception {
        var file = ModList.get().getModFileById(JoblessReincarnation.MOD_ID).getFile();
        var pack = file.findResource("resourcepacks/brass_hud");
        var metadata = JsonParser.parseString(java.nio.file.Files.readString(pack.resolve("pack.mcmeta"))).getAsJsonObject();
        h.assertValueEqual(metadata.getAsJsonObject("pack").get("pack_format").getAsInt(), 34, "The pack targets this Minecraft resource format");
        try (var stream = HudBrassGameTests.class.getResourceAsStream("/hud-brass-inputs.json")) {
            var list = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            h.assertValueEqual(list.size(), 69, "All approved states are accounted for");
            for (var entry : list) {
                var e = entry.getAsJsonObject();String path = e.get("path").getAsString();
                byte[] bytes = java.nio.file.Files.readAllBytes(pack.resolve(path));
                h.assertTrue(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(e.get("sha256").getAsString()),
                        "The approved sprite is unchanged: " + path);
                h.assertValueEqual(ByteBuffer.wrap(bytes).getInt(16), e.get("width").getAsInt(), "Original width: " + path);
                h.assertValueEqual(ByteBuffer.wrap(bytes).getInt(20), e.get("height").getAsInt(), "Original height: " + path);
                h.assertTrue(!java.nio.file.Files.exists(file.findResource(path)), "Vanilla replacements are absent from the always-active mod pack: " + path);
            }
        }
        h.succeed();
    }
}
