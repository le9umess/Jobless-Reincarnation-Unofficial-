package dev.joblessreincarnation.training;

import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public enum SchoolForm {
    ARM_STRIKE("arm_strike", 1),
    FLOW_COUNTER("flow_counter", 2),
    TEAR_POUCH("tear_pouch", 4),
    LIGHT_BLADE("light_blade", 8);

    public static final java.util.List<SchoolForm> FIRST_LESSONS = java.util.List.of(ARM_STRIKE, FLOW_COUNTER, TEAR_POUCH);

    public final String id;
    final int practiceBit;

    SchoolForm(String id, int practiceBit) {
        this.id = id;
        this.practiceBit = practiceBit;
    }

    public static boolean isSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ModContent.PRACTICE_WOODEN_SWORD.get());
    }

    public Item note() {
        return switch (this) {
            case ARM_STRIKE -> ModContent.ARM_STRIKE_NOTE.get();
            case FLOW_COUNTER -> ModContent.FLOW_COUNTER_NOTE.get();
            case TEAR_POUCH -> ModContent.TEAR_POUCH_NOTE.get();
            case LIGHT_BLADE -> ModContent.LIGHT_BLADE_NOTE.get();
        };
    }
}
