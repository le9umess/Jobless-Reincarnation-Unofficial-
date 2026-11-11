package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.npc.SylphietteInteractions;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Character-side smoke checks for Sylphiette: registration, a child's size, landing anchor, protection and a save
 * round trip. The full behaviour, interaction and placement tests belong to the gameplay side.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SylphietteLoadSmokeGameTests {
    private static final String ANCHOR_TAG = "JR_HomeAnchor";

    private static void floor(GameTestHelper helper) {
        // Avoid the structure controller at local 0,0,0 and never touch a neighbouring test.
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    @GameTest(template = "empty")
    public static void sylphietteRegistersSpawnsAndAnchorsWhereSheLands(GameTestHelper helper) {
        floor(helper);
        EntityType<SylphietteEntity> type = ModContent.SYLPHIETTE.get();
        SpawnEggItem egg = ModContent.SYLPHIETTE_SPAWN_EGG.get();
        SylphietteEntity unplaced = type.create(helper.getLevel());
        helper.assertTrue(unplaced != null && unplaced.getHomeAnchor() == null, "An unplaced constructor has no home");
        helper.assertTrue(egg.getType(egg.getDefaultInstance()) == type, "The egg resolves her type");
        helper.assertTrue(type.getWidth() == 0.5F && type.getHeight() == 1.3F, "A child's collision box");
        SylphietteEntity sylphiette = type.spawn(helper.getLevel(), helper.absolutePos(new BlockPos(6, 1, 6)), MobSpawnType.SPAWN_EGG);
        try {
            helper.assertTrue(sylphiette != null && helper.getLevel().getEntity(sylphiette.getUUID()) == sylphiette, "Spawns into the level index");
            helper.assertValueEqual(sylphiette.homeRadius(), SylphietteEntity.HOME_RADIUS, "Home radius");
            GlobalPos home = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 1, 6)));
            helper.assertValueEqual(sylphiette.getHomeAnchor(), home, "Home is the supported landing spot");
            helper.assertTrue(sylphiette.shouldBeSaved() && sylphiette.isPersistenceRequired() && !sylphiette.removeWhenFarAway(1_000_000.0), "Persistent");
            helper.assertValueEqual(sylphiette.getCustomName(), Component.translatable("entity.jobless_reincarnation.sylphiette"), "Localized default name");
            helper.assertTrue(Math.abs(sylphiette.getEyeHeight() - 1.0F) < 1.0E-4F, "Eye height of a child");
            helper.assertTrue(sylphiette.isInvulnerableTo(helper.getLevel().damageSources().generic()), "Ordinary damage does nothing");
            helper.assertFalse(sylphiette.isInvulnerableTo(helper.getLevel().damageSources().genericKill()), "/kill still works");
            helper.assertFalse(sylphiette.canBeLeashed(), "Cannot be leashed");
            helper.assertTrue(sylphiette.getMaxHealth() == 20.0F, "Attributes registered");
        } finally {
            if (sylphiette != null) sylphiette.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void sylphietteHomeSurvivesSaveAndReload(GameTestHelper helper) {
        floor(helper);
        SylphietteEntity original = ModContent.SYLPHIETTE.get().spawn(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 5)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(original != null, "Sylphiette spawns");
        original.setNoAi(true);
        GlobalPos anchor = original.getHomeAnchor();
        UUID uuid = original.getUUID();
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(original.save(saved), "Normal save includes the type id");
        helper.assertValueEqual(saved.getString("id"), "jobless_reincarnation:sylphiette", "Persistent registered type");
        helper.assertTrue(saved.contains(ANCHOR_TAG), "The home is saved under " + ANCHOR_TAG);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            Entity loaded = EntityType.loadEntityRecursive(saved.copy(), helper.getLevel(), value -> value);
            helper.assertTrue(loaded instanceof SylphietteEntity, "The vanilla loader recreates her");
            SylphietteEntity reloaded = (SylphietteEntity)loaded;
            try {
                helper.assertValueEqual(reloaded.getUUID(), uuid, "Reload keeps the UUID");
                helper.assertValueEqual(reloaded.getHomeAnchor(), anchor, "Reload keeps the home without re-anchoring");
                helper.assertTrue(helper.getLevel().addFreshEntity(reloaded), "She joins again without a UUID collision");
            } finally { reloaded.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void sylphietteLinesUseHerKeys(GameTestHelper helper) {
        for (int i = 0; i < SylphietteInteractions.LINES; i++) {
            Component line = SylphietteInteractions.line(i);
            helper.assertTrue(line.getContents() instanceof TranslatableContents format
                    && format.getKey().equals("message.jobless_reincarnation.sylphiette.line")
                    && format.getArgs().length == 2
                    && format.getArgs()[1] instanceof Component text
                    && text.getContents() instanceof TranslatableContents key
                    && key.getKey().equals("message.jobless_reincarnation.sylphiette." + i),
                "Line " + i + " uses its own key");
        }
        helper.succeed();
    }
}
