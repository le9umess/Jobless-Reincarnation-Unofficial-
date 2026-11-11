package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/** Fixed display/cycle order. Knowledge and practice belong to the player, never the wand. */
public enum SpellCatalog {
    WATER_BOLT("water_bolt"), WATER_SHIELD("water_shield"), EARTH_WALL("earth_wall"), AIR_BURST("air_burst"), HEALING("healing"), FIRE_BALL("fire_ball"), MIRE("mire"), STONE_CANNON("stone_cannon");

    private final ResourceLocation id;
    SpellCatalog(String path) { id = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path); }
    public ResourceLocation id() { return id; }
    public Component title() { return Component.translatable("spell.jobless_reincarnation." + id.getPath()); }
    public Item tomeItem() {
        return switch (this) {
            case WATER_BOLT -> ModContent.WATER_TOME.get();
            case WATER_SHIELD -> ModContent.WATER_SHIELD_TOME.get();
            case EARTH_WALL -> ModContent.EARTH_TOME.get();
            case AIR_BURST -> ModContent.AIR_TOME.get();
            case HEALING -> ModContent.HEALING_TOME.get();
            case FIRE_BALL -> ModContent.FIRE_TOME.get();
            case MIRE -> ModContent.MIRE_TOME.get();
            case STONE_CANNON -> ModContent.STONE_CANNON_TOME.get();
        };
    }
    public boolean knows(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(id);
    }
    public int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(id) : 0;
    }
    public int chantTicks(LivingEntity entity) {
        int base = switch (this) {
            case WATER_BOLT -> MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt();
            case WATER_SHIELD -> MagicConfig.WATER_SHIELD_CHANT_TICKS.getAsInt();
            case EARTH_WALL -> MagicConfig.EARTH_WALL_CHANT_TICKS.getAsInt();
            case AIR_BURST -> MagicConfig.AIR_BURST_CHANT_TICKS.getAsInt();
            case HEALING -> MagicConfig.HEALING_CHANT_TICKS.getAsInt();
            case FIRE_BALL -> MagicConfig.FIRE_BALL_CHANT_TICKS.getAsInt();
            case MIRE -> MagicConfig.MIRE_CHANT_TICKS.getAsInt();
            case STONE_CANNON -> MagicConfig.STONE_CANNON_CHANT_TICKS.getAsInt();
        };
        return SpellTraining.chantTicks(base, practiceCount(entity));
    }
    public float charge(LivingEntity entity, int ticks) { return this == WATER_BOLT ? WaterControl.charge(entity, ticks) : 0; }
    public int manaCost(float charge) {
        return switch (this) {
            case WATER_BOLT -> WaterControl.manaCost(charge);
            case WATER_SHIELD -> MagicConfig.WATER_SHIELD_COST.getAsInt();
            case EARTH_WALL -> MagicConfig.EARTH_WALL_COST.getAsInt();
            case AIR_BURST -> MagicConfig.AIR_BURST_COST.getAsInt();
            case HEALING -> MagicConfig.HEALING_COST.getAsInt();
            case FIRE_BALL -> MagicConfig.FIRE_BALL_COST.getAsInt();
            case MIRE -> MagicConfig.MIRE_COST.getAsInt();
            case STONE_CANNON -> MagicConfig.STONE_CANNON_COST.getAsInt();
        };
    }
    public boolean cast(ServerPlayer player, float charge) {
        return cast(player, charge, SpellPayment.NORMAL);
    }
    boolean cast(ServerPlayer player, float charge, SpellPayment mode) {
        return cast(player, charge, mode, SpellTraining.formStage(player, this));
    }
    boolean cast(ServerPlayer player, float charge, SpellPayment mode, int formStage) {
        return switch (this) {
            case WATER_BOLT -> WaterMagic.cast(player, charge, mode, formStage);
            case WATER_SHIELD -> WaterShieldSpell.cast(player, mode);
            case EARTH_WALL -> EarthWallSpell.cast(player, mode);
            case AIR_BURST -> AirBurstSpell.cast(player, mode);
            case HEALING -> HealingSpell.cast(player, mode);
            case FIRE_BALL -> FireMagic.cast(player, mode, formStage);
            case MIRE -> MireSpell.cast(player, mode);
            case STONE_CANNON -> StoneCannonSpell.cast(player, mode, formStage);
        };
    }

    @Nullable
    public static SpellCatalog byId(ResourceLocation id) {
        for (SpellCatalog spell : values()) if (spell.id.equals(id)) return spell;
        return null;
    }
    public static SpellCatalog selected(LivingEntity entity) {
        SpellCatalog stored = entity.hasData(ModAttachments.CASTING) ? byId(entity.getData(ModAttachments.CASTING).selectedSpell()) : WATER_BOLT;
        if (stored != null && stored.knows(entity)) return stored;
        for (SpellCatalog spell : values()) if (spell.knows(entity)) return spell;
        return WATER_BOLT;
    }
    @Nullable
    public static SpellCatalog active(LivingEntity entity) {
        return entity.hasData(ModAttachments.CASTING) ? byId(entity.getData(ModAttachments.CASTING).activeSpell()) : null;
    }
    public static SpellCatalog normalizeSelection(ServerPlayer player) {
        CastingData data = player.getData(ModAttachments.CASTING);
        if (data.normalize(player)) player.syncData(ModAttachments.CASTING);
        return selected(player);
    }
}
