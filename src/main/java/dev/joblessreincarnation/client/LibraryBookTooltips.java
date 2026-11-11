package dev.joblessreincarnation.client;

import com.mojang.datafixers.util.Either;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.library.GreyratBook;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/** Adds local reading notes without changing the stack, its name, or any player state. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class LibraryBookTooltips {
    private static final String PREFIX = "tooltip.jobless_reincarnation.library.";
    private static final int MAX_WIDTH = 240;

    @SubscribeEvent
    public static void gather(RenderTooltipEvent.GatherComponents event) {
        var identified = GreyratBook.identify(event.getItemStack());
        if (identified.isEmpty()) return;
        GreyratBook book = identified.get();
        int position = Math.min(1, event.getTooltipElements().size());

        // This is the rendering path, not ItemTooltipEvent's startup search-tree construction.
        if (Screen.hasShiftDown()) {
            // A note that names the reader calls them what they chose to be called (N01), short form first.
            var reader = net.minecraft.client.Minecraft.getInstance().player;
            Component shortName = reader == null ? Component.translatable("screen.jobless_reincarnation.naming.rudy_short")
                    : dev.joblessreincarnation.naming.Naming.shortName(reader);
            Component fullName = reader == null ? Component.translatable("screen.jobless_reincarnation.naming.rudy_full")
                    : dev.joblessreincarnation.naming.Naming.fullName(reader);
            event.getTooltipElements().add(position++, Either.left(Component.translatable(book.descriptionKey(), shortName, fullName)
                    .withStyle(ChatFormatting.GRAY)));
            if (book.isMagicTextbook()) {
                event.getTooltipElements().add(position, Either.left(Component.translatable(PREFIX + "magic_textbook_note")
                        .withStyle(ChatFormatting.GRAY)));
            }
        } else {
            event.getTooltipElements().add(position, Either.left(Component.translatable(PREFIX + "hint")
                    .withStyle(ChatFormatting.GRAY)));
        }

        // NeoForge wraps the components using the actual tooltip font and available cursor-side space.
        int width = Math.min(MAX_WIDTH, Math.max(1, event.getScreenWidth() - 24));
        if (event.getMaxWidth() > 0) width = Math.min(width, event.getMaxWidth());
        event.setMaxWidth(width);
    }

    private LibraryBookTooltips() {}
}
