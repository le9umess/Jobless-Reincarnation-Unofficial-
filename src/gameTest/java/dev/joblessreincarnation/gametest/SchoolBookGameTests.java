package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.SchoolBookSources;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.SchoolNoteItem;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.data.loot.function.SetSkillFunction;
import yesman.epicfight.registry.EpicFightRegistries;
import yesman.epicfight.registry.entries.EpicFightDataComponentTypes;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.boss.enderdragon.EnderDragonPatch;
import yesman.epicfight.world.item.SkillBookItem;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SchoolBookGameTests {
    private static final Set<String> BASIC = Set.of("roll", "guard", "parrying", "endurance");

    private static void collectBooks(Collection<ItemStack> stacks, Set<String> books) {
        for (ItemStack stack : stacks) {
            if (stack.is(EpicFightItems.SKILLBOOK.get())) {
                SkillBookItem.getContainSkill(stack).flatMap(Holder::unwrapKey).map(ResourceKey::location)
                        .filter(id -> id.getNamespace().equals("epicfight")).ifPresent(id -> books.add(id.getPath()));
            }
        }
    }

    @GameTest(template = "empty")
    public static void exactNativeCatalogueAndLootFunctionPreserveFourBooks(GameTestHelper h) {
        LootParams params = new LootParams.Builder(h.getLevel()).create(LootContextParamSets.EMPTY);
        LootContext context = new LootContext.Builder(params).create(Optional.empty());
        Set<String> kept = new HashSet<>();
        Set<Object> notes = new HashSet<>();
        int offered = 0;
        for (var holder : EpicFightRegistries.SKILL.holders().toList()) {
            ResourceLocation id = holder.key().location();
            if (!id.getNamespace().equals("epicfight") || !holder.value().getCategory().learnable()) continue;
            offered++;
            ItemStack blank = new ItemStack(EpicFightItems.SKILLBOOK.get());
            ItemStack result = SetSkillFunction.builder(holder).build().apply(blank, context);
            if (BASIC.contains(id.getPath())) {
                h.assertTrue(result == blank && SkillBookItem.getContainSkill(result).orElseThrow().is(holder.key()),
                        "Basic book and its actual skill must survive: " + id);
                kept.add(id.getPath());
            } else {
                h.assertTrue(result.getItem() instanceof SchoolNoteItem && result.getCount() == 1,
                        "Expected one notebook from the native loot function for " + id);
                notes.add(result.getItem());
            }
        }
        h.assertValueEqual(offered, 23, "Pinned Epic Fight's native learnable catalogue");
        h.assertValueEqual(kept, BASIC, "Only the four user-selected books survived");
        h.assertValueEqual(notes.size(), 3, "All three schools can be found");
        ItemStack blank = new ItemStack(EpicFightItems.SKILLBOOK.get());
        h.assertTrue(SchoolBookSources.generatedBook(blank) == blank, "An unassigned book is left intact");
        var foreignKey = ResourceKey.create(EpicFightRegistries.Keys.SKILL, ResourceLocation.fromNamespaceAndPath("test_addon", "berserker"));
        var foreign = Holder.Reference.<Skill>createStandAlone(EpicFightRegistries.SKILL.asLookup(), foreignKey);
        blank.set(EpicFightDataComponentTypes.SKILL.get(), foreign);
        h.assertTrue(SchoolBookSources.generatedBook(blank) == blank, "An addon using the same path keeps its book");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void actualCreativeParentAndSearchTabContainOnlyFourNativeBooks(GameTestHelper h) {
        CreativeModeTabs.tryRebuildTabContents(h.getLevel().enabledFeatures(), true, h.getLevel().registryAccess());
        Set<String> parent = new HashSet<>(), search = new HashSet<>();
        for (var tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.CATEGORY) collectBooks(tab.getDisplayItems(), parent);
            if (tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.SEARCH) collectBooks(tab.getDisplayItems(), search);
        }
        h.assertValueEqual(parent, BASIC, "Actual creative categories keep exactly four original skillbooks");
        h.assertValueEqual(search, BASIC, "Actual union search tab keeps the same four");
        for (SchoolForm form : SchoolForm.values()) {
            h.assertTrue(CreativeModeTabs.allTabs().stream().filter(tab -> tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.SEARCH)
                    .anyMatch(tab -> tab.getDisplayItems().stream().anyMatch(stack -> stack.is(form.note()))), "Search includes " + form.id);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dungeonLootActuallyUnpacksTheNotebookInAChest(GameTestHelper h) {
        try (var fixture = new SwordLessonGameTests.Fixture(h)) {
            var reader = fixture.actor();
            var pos = new BlockPos(3, 2, 3);
            fixture.block(pos, Blocks.CHEST.defaultBlockState());
            ChestBlockEntity chest = (ChestBlockEntity)h.getLevel().getBlockEntity(h.absolutePos(pos));
            var table = h.getLevel().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
            var params = new LootParams.Builder(h.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(h.absolutePos(pos)))
                    .withParameter(LootContextParams.THIS_ENTITY, reader).withLuck(reader.getLuck()).create(LootContextParamSets.CHEST);
            long found = 0;
            for (long seed = 1; seed <= 2048 && found == 0; seed++) {
                var draw = table.getRandomItems(params, seed);
                if (draw.stream().anyMatch(s -> s.getItem() instanceof SchoolNoteItem)) found = seed;
            }
            h.assertTrue(found != 0, "A loaded vanilla dungeon loot table produces a notebook");
            chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, found);
            chest.unpackLootTable(reader);
            int books = 0, ordinary = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (s.getItem() instanceof SchoolNoteItem) books++;
                else if (!s.isEmpty()) ordinary++;
            }
            h.assertTrue(books > 0 && ordinary > 0 && chest.getLootTable() == null, "Physical chest unpacked notes and ordinary loot");
            LogUtils.getLogger().info("[SchoolBooks] simple_dungeon seed={} notebooks={} other_stacks={}", found, books, ordinary);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerKillActuallyDropsTrainingNotesThroughEpicFight(GameTestHelper h) {
        try (var fixture = new SwordLessonGameTests.Fixture(h)) {
            var player = fixture.actor();
            Husk mob = EntityType.HUSK.create(h.getLevel());
            mob.setNoAi(true);
            fixture.admit(mob, new Vec3(5.5, 2, 4.5));
            var damage = player.damageSources().playerAttack(player);
            var table = h.getLevel().getServer().reloadableRegistries().getLootTable(mob.getLootTable());
            var params = new LootParams.Builder(h.getLevel()).withParameter(LootContextParams.THIS_ENTITY, mob)
                    .withParameter(LootContextParams.ORIGIN, mob.position()).withParameter(LootContextParams.DAMAGE_SOURCE, damage)
                    .withParameter(LootContextParams.ATTACKING_ENTITY, player).withParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player)
                    .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player).withLuck(player.getLuck()).create(LootContextParamSets.ENTITY);
            long found = 0;
            for (long seed = 1; seed <= 2048 && found == 0; seed++) {
                if (table.getRandomItems(params, seed).stream().anyMatch(s -> s.getItem() instanceof SchoolNoteItem)) found = seed;
            }
            h.assertTrue(found != 0, "The actual loaded husk loot table and Epic Fight modifier can drop a notebook");
            var saved = mob.saveWithoutId(new CompoundTag());
            saved.putString("DeathLootTable", mob.getLootTable().location().toString());
            saved.putLong("DeathLootTableSeed", found);
            mob.readAdditionalSaveData(saved);
            mob.hurt(damage, 100);
            h.assertTrue(mob.isDeadOrDying(), "The control is an actual player kill");
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(2));
            var notes = drops.stream().filter(d -> d.getItem().getItem() instanceof SchoolNoteItem).toList();
            h.assertTrue(!notes.isEmpty(), "The deterministic death emitted a notebook ItemEntity");
            LogUtils.getLogger().info("[SchoolBooks] husk death seed={} notebook={}", found, notes.getFirst().getItem());
            fixture.entities.addAll(drops);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dragonContributorGetsOneNotebookAndBystanderGetsNone(GameTestHelper h) {
        try (var fixture = new SwordLessonGameTests.Fixture(h)) {
            var fighter = fixture.actor();
            var peer = fixture.actor();
            EnderDragon dragon = EntityType.ENDER_DRAGON.create(h.getLevel());
            dragon.setNoAi(true);
            fixture.admit(dragon, new Vec3(7.5, 8, 7.5));
            var patch = EpicFightCapabilities.getEntityPatch(dragon, EnderDragonPatch.class);
            h.assertTrue(patch != null, "The real Epic Fight boss patch is attached");
            var attack = fighter.damageSources().playerAttack(fighter);
            patch.tryHurt(attack, 4.0F);
            patch.onDeath(attack);
            h.assertValueEqual(fighter.getInventory().countItem(SchoolForm.TEAR_POUCH.note()), 1, "The contributor receives the converted direct reward");
            h.assertValueEqual(peer.getInventory().countItem(SchoolForm.TEAR_POUCH.note()), 0, "A bystander receives no reward");
            h.assertValueEqual(fighter.getInventory().countItem(EpicFightItems.SKILLBOOK.get()), 0, "Direct grant did not leave a demolition-leap book");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void readingAReusableNotebookPrivatelyOpensOnlyItsPractice(GameTestHelper h) {
        try (var fixture = new SwordLessonGameTests.Fixture(h)) {
            var reader = fixture.actor();
            var other = fixture.actor();
            ItemStack note = new ItemStack(SchoolForm.FLOW_COUNTER.note());
            reader.setItemInHand(InteractionHand.MAIN_HAND, note);
            var pages = note.get(DataComponents.WRITTEN_BOOK_CONTENT);
            h.assertTrue(pages != null && pages.pages().size() == 2, "Two translatable pages are ready for the book screen");
            note.use(h.getLevel(), reader, InteractionHand.MAIN_HAND);
            h.assertFalse(reader.getData(ModAttachments.SWORD_TRAINING).hasPractice(SchoolForm.FLOW_COUNTER), "Reading before recognition cannot open a drill");
            var training = new SwordTrainingProgress(true, SwordTrainingProgress.GOAL, true);
            reader.setData(ModAttachments.SWORD_TRAINING, training);
            note.use(h.getLevel(), reader, InteractionHand.MAIN_HAND);
            note.use(h.getLevel(), reader, InteractionHand.MAIN_HAND);
            h.assertTrue(training.hasPractice(SchoolForm.FLOW_COUNTER) && !training.hasPractice(SchoolForm.ARM_STRIKE), "Only the chosen practice opened");
            h.assertFalse(other.getData(ModAttachments.SWORD_TRAINING).hasPractice(SchoolForm.FLOW_COUNTER), "The other player did not gain the practice");
            var restored = SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE, SwordTrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, training).getOrThrow()).getOrThrow();
            h.assertTrue(restored.hasPractice(SchoolForm.FLOW_COUNTER) && note.getCount() == 1, "Can share the same notebook and reload personal practice");
        }
        h.succeed();
    }

    private SchoolBookGameTests() {}
}
