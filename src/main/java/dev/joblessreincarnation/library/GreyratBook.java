package dev.joblessreincarnation.library;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Read-only display identity for the ordinary books already present in the house template. */
public enum GreyratBook {
    WORLD_TRAVELS("world_travels", "world_travels"),
    FITTOA_MONSTERS("fittoa_monsters", "fittoa_monsters"),
    MAGIC_TEXTBOOK("magic_textbook", "magic_textbook"),
    PERUGIUS_LEGEND("perugius_legend", "legend_of_perugius"),
    THREE_SWORDSMEN("three_swordsmen", "three_swordsmen"),
    PLANT_DICTIONARY("plant_dictionary", "plant_dictionary");

    private static final Map<String, GreyratBook> BY_ITEM_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(GreyratBook::itemNameKey, Function.identity()));

    private final String id;
    private final String itemNameKey;

    GreyratBook(String id, String nameSuffix) {
        this.id = id;
        this.itemNameKey = "item.jobless_reincarnation.greyrat_home." + nameSuffix;
    }

    public String id() { return id; }
    public String itemNameKey() { return itemNameKey; }
    public String descriptionKey() { return "tooltip.jobless_reincarnation.library." + id; }
    public boolean isMagicTextbook() { return this == MAGIC_TEXTBOOK; }

    public static Optional<GreyratBook> identify(ItemStack stack) {
        if (stack == null || !stack.is(Items.BOOK)) return Optional.empty();
        // Anvil renames replace CUSTOM_NAME, so keep looking at the original ITEM_NAME component.
        Component name = stack.get(DataComponents.ITEM_NAME);
        if (name == null || !(name.getContents() instanceof TranslatableContents translated)) return Optional.empty();
        return Optional.ofNullable(BY_ITEM_NAME.get(translated.getKey()));
    }
}
// c3M=
