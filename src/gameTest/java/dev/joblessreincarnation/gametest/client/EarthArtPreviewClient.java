package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.EarthWallPreview;
import dev.joblessreincarnation.gametest.EarthArtPreview;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Native renderer capture of the real preview, real inventory and real first-person book. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class EarthArtPreviewClient {
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static int phase, stable, ticks, finishDelay = 20;
    private static boolean done, restoreRequested, optionsCaptured, originalHideGui;
    private static CameraType originalCamera;

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        return EarthArtPreview.enabled() && !done && client.getSingleplayerServer() != null
                && EarthArtPreview.WORLD.equals(client.getSingleplayerServer().getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        // A pause overlay hides the world preview. No synthetic transparent screen replaces ordinary gameplay.
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!privateWorld()) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        EarthArtPreview.State state = EarthArtPreview.state(server);
        if (state == null) return;
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            if (optionsCaptured) {
                client.options.hideGui = originalHideGui;
                client.options.setCameraType(originalCamera);
            }
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
            client.stop();
            return;
        }
        if (!state.ready() || client.player == null || client.level == null || client.getOverlay() != null) return;
        if (restoreRequested) return;
        if (!optionsCaptured) {
            originalHideGui = client.options.hideGui;
            originalCamera = client.options.getCameraType();
            optionsCaptured = true;
            client.options.hideGui = false;
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (++ticks > 1000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at phase " + phase : imageFailure.get());
            return;
        }
        try {
            switch (phase) {
                case 0 -> {
                    var plan = EarthWallPreview.currentPlan();
                    boolean visible = client.screen == null && client.player.hasData(ModAttachments.CASTING)
                            && client.player.getData(ModAttachments.CASTING).selectedSpell().equals(EarthWallSpell.SPELL)
                            && plan != null && plan.valid() && plan.positions().size() == 6;
                    stable = visible ? stable + 1 : 0;
                    if (stable >= 10) {
                        capture(client, "outline"); phase = 1; stable = 0;
                        LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW_CLIENT] OUTLINE: actual EarthWallPreview.currentPlan valid for 10 frames; 6 cells, gameplay HUD, no Screen");
                    }
                }
                case 1 -> {
                    if (saved.get() < 1) return;
                    if (!(client.screen instanceof InventoryScreen)) {
                        client.setScreen(new InventoryScreen(client.player)); stable = 0; return;
                    }
                    if (!client.player.getInventory().getItem(1).is(ModContent.EARTH_TOME.get())) throw new IllegalStateException("Inventory book was not received");
                    if (++stable >= 10) { capture(client, "inventory"); phase = 2; stable = 0; }
                }
                case 2 -> {
                    if (saved.get() < 2) return;
                    client.setScreen(null);
                    KeyMapping.click(client.options.keyHotbarSlots[1].getKey());
                    phase = 3; stable = 0;
                    LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW_CLIENT] HOTBAR: queued the bound slot-2 KeyMapping click; awaiting local and server selection");
                }
                case 3 -> {
                    boolean held = client.screen == null && client.player.getInventory().selected == 1 && state.selectedSlot() == 1
                            && client.player.getMainHandItem().is(ModContent.EARTH_TOME.get());
                    stable = held ? stable + 1 : 0;
                    if (stable >= 10) { capture(client, "tome"); phase = 4; stable = 0; }
                }
                case 4 -> {
                    if (saved.get() == 3) requestRestore(server, true, "Three native renderer screenshots saved: valid wall outline/HUD, inventory book icon, first-person book/HUD");
                }
                default -> throw new IllegalStateException("Unexpected visual phase " + phase);
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_EARTH_ART_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    private static void capture(Minecraft client, String view) {
        String name = "earth_art_preview_07_" + view + "_" + System.currentTimeMillis() + ".png";
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_EARTH_ART_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }
    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        restoreRequested = true;
        server.execute(() -> EarthArtPreview.finish(server, passed, reason));
    }
    private EarthArtPreviewClient() {}
}
