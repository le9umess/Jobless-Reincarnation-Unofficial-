package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyInteractions;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
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
 * Character-side smoke checks for the family residents: registration, landing anchor, protection and a save round
 * trip. The full behaviour and interaction tests belong to the gameplay side.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FamilyLoadSmokeGameTests {
    private static final String ANCHOR_TAG = "JR_HomeAnchor";

    private record Case(FamilyResidentEntity.Member member, EntityType<FamilyResidentEntity> type, SpawnEggItem egg, int radius) {}

    private static List<Case> cases() {
        return List.of(
            new Case(FamilyResidentEntity.Member.PAUL, ModContent.PAUL_GREYRAT.get(), ModContent.PAUL_GREYRAT_SPAWN_EGG.get(), 5),
            new Case(FamilyResidentEntity.Member.ZENITH, ModContent.ZENITH_GREYRAT.get(), ModContent.ZENITH_GREYRAT_SPAWN_EGG.get(), 4),
            new Case(FamilyResidentEntity.Member.LILIA, ModContent.LILIA_GREYRAT.get(), ModContent.LILIA_GREYRAT_SPAWN_EGG.get(), 4));
    }

    private static void floor(GameTestHelper helper) {
        // Avoid the structure controller at local 0,0,0 and never touch a neighbouring test.
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    @GameTest(template = "empty")
    public static void residentsRegisterSpawnAndAnchorWhereTheyLand(GameTestHelper helper) {
        floor(helper);
        int x = 3;
        for (Case c : cases()) {
            FamilyResidentEntity unplaced = c.type().create(helper.getLevel());
            helper.assertTrue(unplaced != null && unplaced.getHomeAnchor() == null, c.member() + ": an unplaced constructor has no home");
            helper.assertTrue(c.egg().getType(c.egg().getDefaultInstance()) == c.type(), c.member() + ": the egg resolves its own type");
            FamilyResidentEntity resident = c.type().spawn(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 6)), MobSpawnType.SPAWN_EGG);
            try {
                helper.assertTrue(resident != null && helper.getLevel().getEntity(resident.getUUID()) == resident, c.member() + ": spawns into the level index");
                helper.assertValueEqual(resident.member(), c.member(), "Entity type carries its member");
                helper.assertValueEqual(resident.homeRadius(), c.radius(), c.member() + ": home radius");
                GlobalPos home = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(x, 1, 6)));
                helper.assertValueEqual(resident.getHomeAnchor(), home, c.member() + ": home is the supported landing spot");
                helper.assertTrue(resident.shouldBeSaved() && resident.isPersistenceRequired() && !resident.removeWhenFarAway(1_000_000.0), c.member() + ": persistent");
                helper.assertValueEqual(resident.getCustomName(), Component.translatable("entity.jobless_reincarnation." + c.member().id()), "Localized default name");
                helper.assertTrue(resident.isInvulnerableTo(helper.getLevel().damageSources().generic()), c.member() + ": ordinary damage does nothing");
                helper.assertFalse(resident.isInvulnerableTo(helper.getLevel().damageSources().genericKill()), c.member() + ": /kill still works");
                helper.assertFalse(resident.canBeLeashed(), c.member() + ": cannot be leashed");
                helper.assertTrue(resident.getMaxHealth() == 20.0F, c.member() + ": attributes registered");
            } finally {
                if (resident != null) resident.discard();
            }
            x += 4;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void residentHomeSurvivesSaveAndReload(GameTestHelper helper) {
        floor(helper);
        FamilyResidentEntity original = ModContent.LILIA_GREYRAT.get().spawn(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 5)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(original != null, "Lilia spawns");
        original.setNoAi(true);
        GlobalPos anchor = original.getHomeAnchor();
        UUID uuid = original.getUUID();
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(original.save(saved), "Normal save includes the type id");
        helper.assertValueEqual(saved.getString("id"), "jobless_reincarnation:lilia_greyrat", "Persistent registered type");
        helper.assertTrue(saved.contains(ANCHOR_TAG), "The home is saved under " + ANCHOR_TAG);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            Entity loaded = EntityType.loadEntityRecursive(saved.copy(), helper.getLevel(), value -> value);
            helper.assertTrue(loaded instanceof FamilyResidentEntity, "The vanilla loader recreates the resident");
            FamilyResidentEntity reloaded = (FamilyResidentEntity)loaded;
            try {
                helper.assertValueEqual(reloaded.member(), FamilyResidentEntity.Member.LILIA, "Reload keeps the member");
                helper.assertValueEqual(reloaded.getUUID(), uuid, "Reload keeps the UUID");
                helper.assertValueEqual(reloaded.getHomeAnchor(), anchor, "Reload keeps the home without re-anchoring");
                helper.assertTrue(helper.getLevel().addFreshEntity(reloaded), "The reloaded resident joins without a UUID collision");
            } finally { reloaded.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void linesUseTheMemberKeys(GameTestHelper helper) {
        for (FamilyResidentEntity.Member member : FamilyResidentEntity.Member.values())
            for (int i = 0; i < FamilyInteractions.LINES_PER_MEMBER; i++) {
                Component line = FamilyInteractions.line(member, i);
                helper.assertTrue(line.getContents() instanceof TranslatableContents format
                        && format.getKey().equals("message.jobless_reincarnation.family.line")
                        && format.getArgs().length == 2
                        && format.getArgs()[1] instanceof Component text
                        && text.getContents() instanceof TranslatableContents key
                        && key.getKey().equals("message.jobless_reincarnation.family." + member.id() + "." + i),
                    member + " line " + i + " uses its own key");
            }
        helper.succeed();
    }
}
