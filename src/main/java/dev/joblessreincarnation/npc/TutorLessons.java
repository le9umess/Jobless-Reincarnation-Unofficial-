package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Main-hand lesson selection is read-only and never normalizes the player's stored spell. */
public final class TutorLessons {
    /** Whether the main hand holds something that asks about a course: one of the tomes, or the training staff. */
    public static boolean asksAboutCourse(Player player) {
        ItemStack held = player.getMainHandItem();
        return held.is(ModContent.WATER_TOME.get()) || held.is(ModContent.WATER_SHIELD_TOME.get()) || held.is(ModContent.MIRE_TOME.get())
            || held.is(ModContent.STONE_CANNON_TOME.get()) || held.is(ModContent.FIRE_TOME.get()) || held.is(ModContent.EARTH_TOME.get())
            || held.is(ModContent.AIR_TOME.get()) || held.is(ModContent.HEALING_TOME.get()) || held.is(ModContent.TRAINING_STAFF.get());
    }

    public static SpellCatalog lesson(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.is(ModContent.WATER_TOME.get())) return SpellCatalog.WATER_BOLT;
        if (held.is(ModContent.WATER_SHIELD_TOME.get())) return SpellCatalog.WATER_SHIELD;
        if (held.is(ModContent.MIRE_TOME.get())) return SpellCatalog.MIRE;
        if (held.is(ModContent.STONE_CANNON_TOME.get())) return SpellCatalog.STONE_CANNON;
        if (held.is(ModContent.FIRE_TOME.get())) return SpellCatalog.FIRE_BALL;
        if (held.is(ModContent.EARTH_TOME.get())) return SpellCatalog.EARTH_WALL;
        if (held.is(ModContent.AIR_TOME.get())) return SpellCatalog.AIR_BURST;
        if (held.is(ModContent.HEALING_TOME.get())) return SpellCatalog.HEALING;
        if (held.is(ModContent.TRAINING_STAFF.get()) && player.hasData(ModAttachments.CASTING)) {
            SpellCatalog selected = SpellCatalog.byId(player.getData(ModAttachments.CASTING).selectedSpell());
            if (selected != null) return selected;
        }
        return SpellCatalog.WATER_BOLT;
    }

    public static Component message(Player player) {
        SpellCatalog lesson = lesson(player);
        return switch (lesson) {
            case WATER_BOLT -> TutorGuidance.message(TutorGuidance.snapshot(player));
            case FIRE_BALL -> FireTutorGuidance.message(FireTutorGuidance.snapshot(player));
            case WATER_SHIELD -> BasicTutorGuidance.message(BasicTutorGuidance.snapshot(player, lesson));
            default -> BasicTutorGuidance.message(BasicTutorGuidance.snapshot(player, lesson));
        };
    }

    private TutorLessons() {}
}
