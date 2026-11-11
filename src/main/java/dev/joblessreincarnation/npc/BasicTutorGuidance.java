package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.MireSpell;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellTraining;
import dev.joblessreincarnation.magic.WaterShieldSpell;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/** Existing spell practice and mixed-magic guidance, without a new course, reward, or source-material rank. */
public final class BasicTutorGuidance {
    public enum Stage { LEARN, NOVICE, PRACTICED, ADEPT }

    /** Captures config as well as progress so later changes cannot rewrite a previous reply. */
    public record Snapshot(SpellCatalog spell, Stage stage, boolean learned, int casts, int nextMilestone, int range) {}

    private static final String PREFIX = "message.jobless_reincarnation.basic_tutor.";

    public static Snapshot snapshot(Player player, SpellCatalog spell) {
        int range = switch (spell) {
            case EARTH_WALL -> MagicConfig.EARTH_WALL_RANGE.getAsInt();
            case AIR_BURST -> MagicConfig.AIR_BURST_RANGE.getAsInt();
            case HEALING -> MagicConfig.HEALING_RANGE.getAsInt();
            case WATER_SHIELD -> (int) WaterShieldSpell.DISTANCE;
            case MIRE -> (int) MireSpell.RANGE;
            case STONE_CANNON -> (int) dev.joblessreincarnation.magic.StoneCannonProjectile.MAX_DISTANCE;
            default -> throw new IllegalArgumentException("Not a basic guidance course: " + spell);
        };
        boolean learned = spell.knows(player);
        int casts = spell.practiceCount(player);
        Stage stage = !learned ? Stage.LEARN : switch (SpellTraining.stage(casts)) {
            case NOVICE -> Stage.NOVICE;
            case PRACTICED -> Stage.PRACTICED;
            case ADEPT -> Stage.ADEPT;
        };
        return new Snapshot(spell, stage, learned, casts, learned ? SpellTraining.nextMilestone(casts) : 0, range);
    }

    public static Component message(Snapshot snapshot) {
        Item book = switch (snapshot.spell()) {
            case EARTH_WALL -> ModContent.EARTH_TOME.get();
            case AIR_BURST -> ModContent.AIR_TOME.get();
            case HEALING -> ModContent.HEALING_TOME.get();
            case WATER_SHIELD -> ModContent.WATER_SHIELD_TOME.get();
            case MIRE -> ModContent.MIRE_TOME.get();
            case STONE_CANNON -> ModContent.STONE_CANNON_TOME.get();
            default -> throw new IllegalArgumentException("Not a basic guidance course: " + snapshot.spell());
        };
        ChatFormatting color = switch (snapshot.spell()) {
            case EARTH_WALL -> ChatFormatting.GOLD;
            case AIR_BURST -> ChatFormatting.AQUA;
            case WATER_SHIELD -> ChatFormatting.BLUE;
            case MIRE -> ChatFormatting.GOLD;
            case STONE_CANNON -> ChatFormatting.GRAY;
            default -> ChatFormatting.GREEN;
        };
        String course = PREFIX + snapshot.spell().id().getPath() + ".";
        String suffix = snapshot.stage().name().toLowerCase(Locale.ROOT);
        Component use = Component.keybind("key.use");
        Component rule = snapshot.stage() == Stage.LEARN
                ? Component.translatable(course + "rule.learn", book.getDescription(), use)
                : snapshot.spell() == SpellCatalog.EARTH_WALL
                    ? Component.translatable(course + "rule.practice", snapshot.range(), use,
                        Component.keybind("key.jobless_reincarnation.dismiss_wall"))
                    : snapshot.spell() == SpellCatalog.MIRE
                        ? Component.translatable(course + "rule.practice", snapshot.range(), use, MireEntity.LIFETIME_TICKS / 20)
                        : Component.translatable(course + "rule.practice", snapshot.range(), use);
        MutableComponent message = Component.translatable(course + "speaker").withStyle(color)
                .append(Component.translatable(course + "dialogue." + suffix).withStyle(color))
                .append(Component.literal("\n"))
                .append(rule.copy().withStyle(ChatFormatting.GRAY));
        if (snapshot.stage() != Stage.LEARN) {
            Component stage = Component.translatable("training.jobless_reincarnation." + suffix);
            Component progress = snapshot.nextMilestone() == 0
                    ? Component.translatable(PREFIX + "progress.adept", stage, snapshot.casts())
                    : Component.translatable(PREFIX + "progress.next", stage, snapshot.casts(), snapshot.nextMilestone());
            message.append(Component.literal("\n")).append(progress.copy().withStyle(ChatFormatting.GRAY));
        }
        return message;
    }

    private BasicTutorGuidance() {}
}
