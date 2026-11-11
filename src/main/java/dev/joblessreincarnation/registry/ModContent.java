package dev.joblessreincarnation.registry;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.encounter.RastaBearEntity;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.StoneCannonProjectile;
import dev.joblessreincarnation.magic.StoneCannonTomeItem;
import dev.joblessreincarnation.magic.FireBallProjectile;
import dev.joblessreincarnation.magic.FireTomeItem;
import dev.joblessreincarnation.magic.ManaReaderItem;
import dev.joblessreincarnation.magic.FireFocusOptions;
import dev.joblessreincarnation.magic.SpellBurstOptions;
import dev.joblessreincarnation.magic.SpellSpriteOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.mojang.serialization.MapCodec;
import dev.joblessreincarnation.magic.EarthWallBlock;
import dev.joblessreincarnation.magic.EarthWallBlockEntity;
import dev.joblessreincarnation.magic.EarthTomeItem;
import dev.joblessreincarnation.magic.AirTomeItem;
import dev.joblessreincarnation.magic.HealingTomeItem;
import dev.joblessreincarnation.magic.WaterTomeItem;
import dev.joblessreincarnation.magic.WaterShieldTomeItem;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.MireTomeItem;
import dev.joblessreincarnation.magic.TrainingStaffItem;
import dev.joblessreincarnation.training.TrainingBoardBlock;
import dev.joblessreincarnation.training.TrainingBoardMenu;
import dev.joblessreincarnation.training.TrainingRecordItem;
import dev.joblessreincarnation.training.PracticeWoodenSwordItem;
import dev.joblessreincarnation.training.PracticeDummyItem;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.SchoolNoteItem;
import dev.joblessreincarnation.training.TearPouchItem;
import dev.joblessreincarnation.training.TearPouchProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import dev.joblessreincarnation.magic.ManaDepletionEffect;
import net.minecraft.world.effect.MobEffect;

public final class ModContent {
    private static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, JoblessReincarnation.MOD_ID);
    public static final DeferredHolder<MobEffect, ManaDepletionEffect> MANA_DEPLETION =
            EFFECTS.register("mana_depletion", ManaDepletionEffect::new);
    public static final DeferredHolder<MobEffect, dev.joblessreincarnation.magic.SluggishEffect> SLUGGISH =
            EFFECTS.register("sluggish", dev.joblessreincarnation.magic.SluggishEffect::new);
    public static final DeferredHolder<MobEffect, dev.joblessreincarnation.magic.SearingEffect> SEARING =
            EFFECTS.register("searing", dev.joblessreincarnation.magic.SearingEffect::new);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(JoblessReincarnation.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, JoblessReincarnation.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, ParticleType<FireFocusOptions>> FIRE_FOCUS = PARTICLES.register("fire_focus", () ->
            new ParticleType<FireFocusOptions>(false) {
                @Override public MapCodec<FireFocusOptions> codec() { return FireFocusOptions.CODEC; }
                @Override public StreamCodec<? super RegistryFriendlyByteBuf, FireFocusOptions> streamCodec() { return FireFocusOptions.STREAM_CODEC; }
            });

    public static final DeferredHolder<ParticleType<?>, ParticleType<SpellSpriteOptions>> SPELL_SPRITE = PARTICLES.register("spell_sprite", () ->
            new ParticleType<SpellSpriteOptions>(false) {
                @Override public MapCodec<SpellSpriteOptions> codec() { return SpellSpriteOptions.CODEC; }
                @Override public StreamCodec<? super RegistryFriendlyByteBuf, SpellSpriteOptions> streamCodec() { return SpellSpriteOptions.STREAM_CODEC; }
            });

    public static final DeferredHolder<ParticleType<?>, ParticleType<SpellBurstOptions>> SPELL_BURST = PARTICLES.register("spell_burst", () ->
            new ParticleType<SpellBurstOptions>(false) {
                @Override public MapCodec<SpellBurstOptions> codec() { return SpellBurstOptions.CODEC; }
                @Override public StreamCodec<? super RegistryFriendlyByteBuf, SpellBurstOptions> streamCodec() { return SpellBurstOptions.STREAM_CODEC; }
            });

    public static final DeferredItem<WaterTomeItem> WATER_TOME = ITEMS.register("water_tome",
            () -> new WaterTomeItem(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<EarthWallBlock> EARTH_WALL = BLOCKS.register("earth_wall", EarthWallBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EarthWallBlockEntity>> EARTH_WALL_ENTITY = BLOCK_ENTITIES.register("earth_wall", () ->
            BlockEntityType.Builder.of(EarthWallBlockEntity::new, EARTH_WALL.get()).build(null));
    public static final DeferredItem<StoneCannonTomeItem> STONE_CANNON_TOME = ITEMS.register("stone_cannon_tome", () -> new StoneCannonTomeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<MireTomeItem> MIRE_TOME = ITEMS.register("mire_tome", () -> new MireTomeItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<WaterShieldTomeItem> WATER_SHIELD_TOME = ITEMS.register("water_shield_tome", () -> new WaterShieldTomeItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<EarthTomeItem> EARTH_TOME = ITEMS.register("earth_tome",
            () -> new EarthTomeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AirTomeItem> AIR_TOME = ITEMS.register("air_tome",
            () -> new AirTomeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<HealingTomeItem> HEALING_TOME = ITEMS.register("healing_tome",
            () -> new HealingTomeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<FireTomeItem> FIRE_TOME = ITEMS.register("fire_tome",
            () -> new FireTomeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TrainingStaffItem> TRAINING_STAFF = ITEMS.register("training_staff",
            () -> new TrainingStaffItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<ManaReaderItem> MANA_READER = ITEMS.register("mana_reader",
            () -> new ManaReaderItem(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<TrainingBoardBlock> TRAINING_BOARD = BLOCKS.register("training_board",
            () -> new TrainingBoardBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                    .strength(1.0F).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredItem<BlockItem> TRAINING_BOARD_ITEM = ITEMS.register("training_board",
            () -> new BlockItem(TRAINING_BOARD.get(), new Item.Properties()));

    public static final DeferredItem<TrainingRecordItem> TRAINING_RECORD = ITEMS.register("training_record",
            () -> new TrainingRecordItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<TearPouchItem> TEAR_POUCH = ITEMS.register("tear_pouch", TearPouchItem::new);
    public static final DeferredHolder<EntityType<?>, EntityType<RastaBearEntity>> RASTA_BEAR = ENTITIES.register("rasta_bear", () ->
            EntityType.Builder.of(RastaBearEntity::new, MobCategory.MONSTER).sized(1.4F, 1.8F)
                    .clientTrackingRange(10).updateInterval(1).build(JoblessReincarnation.MOD_ID + ":rasta_bear"));
    public static final DeferredHolder<EntityType<?>, EntityType<TearPouchProjectile>> TEAR_POUCH_PROJECTILE = ENTITIES.register("tear_pouch", () ->
            EntityType.Builder.<TearPouchProjectile>of(TearPouchProjectile::new, MobCategory.MISC).sized(0.2F, 0.2F)
                    .clientTrackingRange(6).updateInterval(2).noSave().build(JoblessReincarnation.MOD_ID + ":tear_pouch"));

    public static final DeferredItem<SchoolNoteItem> LIGHT_BLADE_NOTE = ITEMS.register("light_blade_note", () -> new SchoolNoteItem(SchoolForm.LIGHT_BLADE));
    public static final DeferredItem<SchoolNoteItem> ARM_STRIKE_NOTE = ITEMS.register("arm_strike_note", () -> new SchoolNoteItem(SchoolForm.ARM_STRIKE));
    public static final DeferredItem<SchoolNoteItem> FLOW_COUNTER_NOTE = ITEMS.register("flow_counter_note", () -> new SchoolNoteItem(SchoolForm.FLOW_COUNTER));
    public static final DeferredItem<SchoolNoteItem> TEAR_POUCH_NOTE = ITEMS.register("tear_pouch_note", () -> new SchoolNoteItem(SchoolForm.TEAR_POUCH));

    public static final DeferredItem<PracticeWoodenSwordItem> PRACTICE_WOODEN_SWORD = ITEMS.register("practice_wooden_sword",
            () -> new PracticeWoodenSwordItem(new Item.Properties()));
    public static final DeferredItem<PracticeDummyItem> PRACTICE_DUMMY_ITEM = ITEMS.register("practice_dummy",
            () -> new PracticeDummyItem(new Item.Properties().stacksTo(16)));
    public static final DeferredHolder<EntityType<?>, EntityType<PracticeDummyEntity>> PRACTICE_DUMMY = ENTITIES.register("practice_dummy", () ->
            EntityType.Builder.<PracticeDummyEntity>of(PracticeDummyEntity::new, MobCategory.MISC)
                    .sized(0.95F, 1.6F).clientTrackingRange(8).updateInterval(3)
                    .build(JoblessReincarnation.MOD_ID + ":practice_dummy"));

    public static final DeferredHolder<MenuType<?>, MenuType<TrainingBoardMenu>> TRAINING_BOARD_MENU = MENUS.register("training_board",
            () -> IMenuTypeExtension.create(TrainingBoardMenu::new));

    public static final DeferredHolder<EntityType<?>, EntityType<StoneCannonProjectile>> STONE_CANNON = ENTITIES.register("stone_cannon", () ->
            EntityType.Builder.<StoneCannonProjectile>of(StoneCannonProjectile::new, MobCategory.MISC).sized(.375F, .375F)
                    .clientTrackingRange(10).updateInterval(1).noSave().build(JoblessReincarnation.MOD_ID + ":stone_cannon"));

    public static final DeferredHolder<EntityType<?>, EntityType<MireEntity>> MIRE = ENTITIES.register("mire", () ->
            EntityType.Builder.<MireEntity>of(MireEntity::new, MobCategory.MISC).sized(5.0F, 0.05F)
                    .clientTrackingRange(10).updateInterval(1).noSave().build(JoblessReincarnation.MOD_ID + ":mire"));

    public static final DeferredHolder<EntityType<?>, EntityType<WaterShieldEntity>> WATER_SHIELD = ENTITIES.register("water_shield", () ->
            EntityType.Builder.<WaterShieldEntity>of(WaterShieldEntity::new, MobCategory.MISC).sized(3.0F, 2.25F).clientTrackingRange(10).updateInterval(1).noSave().build(JoblessReincarnation.MOD_ID + ":water_shield"));

    public static final DeferredHolder<EntityType<?>, EntityType<WaterBolt>> WATER_BOLT = ENTITIES.register("water_bolt", () ->
            EntityType.Builder.<WaterBolt>of(WaterBolt::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(1).noSave()
                    .build(JoblessReincarnation.MOD_ID + ":water_bolt"));

    public static final DeferredHolder<EntityType<?>, EntityType<FireBallProjectile>> FIRE_BALL = ENTITIES.register("fire_ball", () ->
            EntityType.Builder.<FireBallProjectile>of(FireBallProjectile::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(1).fireImmune().noSave()
                    .build(JoblessReincarnation.MOD_ID + ":fire_ball"));

    public static final DeferredHolder<EntityType<?>, EntityType<RoxyTutorEntity>> ROXY_TUTOR = ENTITIES.register("roxy_tutor", () ->
            EntityType.Builder.<RoxyTutorEntity>of(RoxyTutorEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F).eyeHeight(1.36F).clientTrackingRange(10).updateInterval(2)
                    .build(JoblessReincarnation.MOD_ID + ":roxy_tutor"));

    // Hair blue and hat navy sampled by the character-side texture review.
    public static final DeferredItem<DeferredSpawnEggItem> ROXY_TUTOR_SPAWN_EGG = ITEMS.register("roxy_tutor_spawn_egg", () ->
            new DeferredSpawnEggItem(ROXY_TUTOR, 0x7A91D0, 0x313443, new Item.Properties()));

    // The Greyrat family residents: one entity type per member, sharing FamilyResidentEntity and Roxy's size.
    public static final DeferredHolder<EntityType<?>, EntityType<FamilyResidentEntity>> PAUL_GREYRAT = resident(FamilyResidentEntity.Member.PAUL);
    public static final DeferredHolder<EntityType<?>, EntityType<FamilyResidentEntity>> ZENITH_GREYRAT = resident(FamilyResidentEntity.Member.ZENITH);
    public static final DeferredHolder<EntityType<?>, EntityType<FamilyResidentEntity>> LILIA_GREYRAT = resident(FamilyResidentEntity.Member.LILIA);

    // Egg colours from the v002 textures: Paul's cream shirt and brown hair, Zenith's red blouse and gold hair,
    // Lilia's dark dress and wine-red hair.
    public static final DeferredItem<DeferredSpawnEggItem> PAUL_GREYRAT_SPAWN_EGG = ITEMS.register("paul_greyrat_spawn_egg", () ->
            new DeferredSpawnEggItem(PAUL_GREYRAT, 0xECE2D0, 0xA8764C, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> ZENITH_GREYRAT_SPAWN_EGG = ITEMS.register("zenith_greyrat_spawn_egg", () ->
            new DeferredSpawnEggItem(ZENITH_GREYRAT, 0x7E443C, 0xD6A258, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> LILIA_GREYRAT_SPAWN_EGG = ITEMS.register("lilia_greyrat_spawn_egg", () ->
            new DeferredSpawnEggItem(LILIA_GREYRAT, 0x3E3732, 0x82363E, new Item.Properties()));

    private static DeferredHolder<EntityType<?>, EntityType<FamilyResidentEntity>> resident(FamilyResidentEntity.Member member) {
        return ENTITIES.register(member.id(), () ->
                EntityType.Builder.<FamilyResidentEntity>of((type, level) -> new FamilyResidentEntity(type, level, member), MobCategory.CREATURE)
                        .sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).updateInterval(2)
                        .build(JoblessReincarnation.MOD_ID + ":" + member.id()));
    }

    // Sylphiette as a child: her own entity type and a child's size (about 1.3 blocks with her hair).
    public static final DeferredHolder<EntityType<?>, EntityType<SylphietteEntity>> SYLPHIETTE = ENTITIES.register(SylphietteEntity.ID, () ->
            EntityType.Builder.<SylphietteEntity>of(SylphietteEntity::new, MobCategory.CREATURE)
                    .sized(0.5F, 1.3F).eyeHeight(1.0F).clientTrackingRange(10).updateInterval(2)
                    .build(JoblessReincarnation.MOD_ID + ":" + SylphietteEntity.ID));

    // Egg colours from the v002 texture: the cream jacket and the green hair.
    public static final DeferredItem<DeferredSpawnEggItem> SYLPHIETTE_SPAWN_EGG = ITEMS.register("sylphiette_spawn_egg", () ->
            new DeferredSpawnEggItem(SYLPHIETTE, 0xF4ECDC, 0x7CA854, new Item.Properties()));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAGIC_TAB = TABS.register("magic", () ->
            CreativeModeTab.builder().title(Component.translatable("itemGroup.jobless_reincarnation"))
                    .withTabsBefore(CreativeModeTabs.COMBAT).icon(() -> WATER_TOME.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(WATER_TOME.get());
                        output.accept(WATER_SHIELD_TOME.get());
                        output.accept(EARTH_TOME.get());
                        output.accept(AIR_TOME.get());
                        output.accept(HEALING_TOME.get());
                        output.accept(FIRE_TOME.get());
                        output.accept(MIRE_TOME.get());
                        output.accept(STONE_CANNON_TOME.get());
                        output.accept(TRAINING_STAFF.get());
                    output.accept(MANA_READER.get());
                        output.accept(TRAINING_BOARD_ITEM.get());
                        output.accept(TRAINING_RECORD.get());
                        output.accept(PRACTICE_WOODEN_SWORD.get());
                        for (SchoolForm form : SchoolForm.values()) output.accept(form.note());
                        output.accept(TEAR_POUCH.get());
                        output.accept(PRACTICE_DUMMY_ITEM.get());
                        output.accept(ROXY_TUTOR_SPAWN_EGG.get());
                        output.accept(PAUL_GREYRAT_SPAWN_EGG.get());
                        output.accept(ZENITH_GREYRAT_SPAWN_EGG.get());
                        output.accept(LILIA_GREYRAT_SPAWN_EGG.get());
                        output.accept(SYLPHIETTE_SPAWN_EGG.get());
                    }).build());

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
        ITEMS.register(bus);
        PARTICLES.register(bus);
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
    }

    private ModContent() {}
}
