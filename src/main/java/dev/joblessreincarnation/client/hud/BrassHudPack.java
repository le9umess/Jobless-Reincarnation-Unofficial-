package dev.joblessreincarnation.client.hud;

import dev.joblessreincarnation.JoblessReincarnation;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/** Optional client resource pack. Only its first installation changes the initial selection. */
public final class BrassHudPack {
    public static final ResourceLocation APPLE_ICONS =
            ResourceLocation.fromNamespaceAndPath("appleskin", "textures/icons.png");
    public static final ResourceLocation SATURATION =
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/gui/brass_saturation.png");
    private static final String[] FOOD_STATES = {
            "food_empty", "food_half", "food_full", "food_empty_hunger", "food_half_hunger", "food_full_hunger"
    };
    private static boolean pendingSave;
    private static volatile boolean brassSaturation;

    public static void register(AddPackFindersEvent event) {
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "resourcepacks/brass_hud"),
                PackType.CLIENT_RESOURCES, Component.translatable("pack.jobless_reincarnation.brass_hud"),
                PackSource.BUILT_IN, false, Pack.Position.TOP);
    }

    /** Called after vanilla validates the saved list, before the initial resource reload begins. */
    public static void initializeSelection(Options options, PackRepository repository) {
        Pack pack = repository.getPack(HudPackOrder.ID);
        if (pack == null || !pack.getCompatibility().isCompatible()) return;
        var marker = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config/jobless_reincarnation/brass_hud_initialized.json");
        if (Files.exists(marker)) return;
        try {
            Files.createDirectories(marker.getParent());
            // Claim one attempt before loading assets: a failed reload must not cause an enable/fail loop next launch.
            Files.writeString(marker, "{\"format\":1,\"pack\":\"" + HudPackOrder.ID + "\"}\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException anotherClient) {
            return;
        } catch (IOException unavailable) {
            JoblessReincarnation.LOGGER.warn("Could not record the brass HUD default; it can still be enabled in Resource Packs", unavailable);
            return;
        }
        var visible = repository.getSelectedPacks().stream().filter(p -> !p.isHidden()).map(Pack::getId).toList();
        repository.setSelected(HudPackOrder.firstSelection(visible));
        // Use the same visible selection as the vanilla screen. Save after mod key bindings and startup finish.
        options.resourcePacks.clear();
        for (Pack selected : repository.getSelectedPacks())
            if (!selected.isFixedPosition() && !selected.isHidden()) options.resourcePacks.add(selected.getId());
        pendingSave = true;
    }

    public static void finishFirstSelection(ClientTickEvent.Post event) {
        if (!pendingSave || net.neoforged.neoforge.client.loading.ClientModLoader.isLoading()) return;
        var client = Minecraft.getInstance();
        if (client.getOverlay() != null) return;
        pendingSave = false;
        // This also persists a disabled selection if vanilla recovered from an unrelated failed resource reload.
        client.options.updateResourcePacks(client.getResourcePackRepository());
    }

    public static void reloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) BrassHudPack::resourcesChanged);
    }

    private static void resourcesChanged(ResourceManager resources) {
        boolean ourFood = true;
        for (String state : FOOD_STATES) {
            var food = ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/" + state + ".png");
            ourFood &= resources.getResource(food).map(r -> r.sourcePackId().equals(HudPackOrder.ID)).orElse(false);
        }
        boolean defaultAppleOutline = resources.getResource(APPLE_ICONS)
                .map(r -> r.sourcePackId().equals("mod/appleskin")).orElse(false);
        brassSaturation = ourFood && defaultAppleOutline && resources.getResource(SATURATION).isPresent();
    }

    /** Preserve AppleSkin's calculations, positions and animation; replace only this optional atlas lookup. */
    public static ResourceLocation saturationTexture(ResourceLocation original) {
        return brassSaturation && original.equals(APPLE_ICONS) ? SATURATION : original;
    }

    private BrassHudPack() {}
}
