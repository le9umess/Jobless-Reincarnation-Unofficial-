package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;

import dev.joblessreincarnation.gametest.WaterArtPreview;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Locale;
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
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Native renderer capture of the real inventory icon, the held Water Ball book in either hand, and third person. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class WaterArtPreviewClient {
    private static final int IMAGES = 4;
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static int phase, stable, ticks, finishDelay = 20;
    private static boolean done, restoreRequested, optionsCaptured, originalHideGui;
    private static CameraType originalCamera;
    private static HumanoidArm originalArm;

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        return WaterArtPreview.enabled() && !done && server != null && !server.isPublished()
                && client.player != null && server.isSingleplayerOwner(client.player.getGameProfile())
                && WaterArtPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        // A pause overlay hides the world preview. No synthetic transparent screen replaces ordinary gameplay.
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!WaterArtPreview.enabled() || done) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { restoreOptions(client); return; }
        WaterArtPreview.State state = WaterArtPreview.state(server);
        if (state == null) { restoreOptions(client); return; }
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            restoreOptions(client);
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_WATER_ART_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
            client.stop();
            return;
        }
        if (!privateWorld()) {
            if (!restoreRequested) requestRestore(server, false, "Private owner eligibility ended");
            return;
        }
        if (!state.ready() || client.player == null || client.level == null || client.getOverlay() != null) return;
        if (restoreRequested) return;
        if (!optionsCaptured) {
            originalHideGui = client.options.hideGui;
            originalCamera = client.options.getCameraType();
            originalArm = client.options.mainHand().get();
            optionsCaptured = true;
            client.options.hideGui = false;
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (++ticks > 1000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at phase " + phase : imageFailure.get());
            return;
        }
        try {
            boolean held = client.screen == null && client.player.getInventory().selected == 1 && state.selectedSlot() == 1
                    && client.player.getMainHandItem().is(ModContent.WATER_TOME.get());
            switch (phase) {
                case 0 -> {
                    boolean received = client.player.hasData(ModAttachments.CASTING)
                            && client.player.getData(ModAttachments.CASTING).selectedSpell().equals(WaterMagic.SPELL)
                            && client.player.getInventory().getItem(1).is(ModContent.WATER_TOME.get());
                    if (!received) { stable = 0; return; }
                    if (!(client.screen instanceof InventoryScreen)) {
                        client.setScreen(new InventoryScreen(client.player)); stable = 0; return;
                    }
                    if (++stable >= 10) { capture(client, "inventory"); phase = 1; stable = 0; }
                }
                case 1 -> {
                    if (saved.get() < 1) return;
                    client.setScreen(null);
                    KeyMapping.click(client.options.keyHotbarSlots[1].getKey());
                    phase = 2; stable = 0;
                    LogUtils.getLogger().info("[JR_WATER_ART_PREVIEW_CLIENT] HOTBAR: queued bound slot-2 KeyMapping; awaiting local and server selection");
                }
                case 2 -> {
                    stable = held && client.player.getMainArm() == originalArm ? stable + 1 : 0;
                    if (stable >= 10) { capture(client, handView(originalArm)); phase = 3; stable = 0; }
                }
                case 3 -> {
                    if (saved.get() < 2) return;
                    // The option's own update hook reports the new main hand to the server.
                    client.options.mainHand().set(originalArm.getOpposite());
                    phase = 4; stable = 0;
                }
                case 4 -> {
                    stable = held && client.player.getMainArm() == originalArm.getOpposite() ? stable + 1 : 0;
                    if (stable >= 10) { capture(client, handView(originalArm.getOpposite())); phase = 5; stable = 0; }
                }
                case 5 -> {
                    if (saved.get() < 3) return;
                    client.options.mainHand().set(originalArm);
                    client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    phase = 6; stable = 0;
                }
                case 6 -> {
                    stable = held && client.player.getMainArm() == originalArm ? stable + 1 : 0;
                    // Long enough for the selected-item name above the hotbar to fade out of the wide shot.
                    if (stable >= 60) { capture(client, "third_person"); phase = 7; stable = 0; }
                }
                case 7 -> {
                    if (saved.get() == IMAGES) requestRestore(server, true,
                            "Four native renderer screenshots saved: inventory icon, first-person Water Ball book in each hand, third person");
                }
                default -> throw new IllegalStateException("Unexpected visual phase " + phase);
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_WATER_ART_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    private static String handView(HumanoidArm arm) { return "first_person_" + arm.name().toLowerCase(Locale.ROOT); }
    private static void restoreOptions(Minecraft client) {
        if (!optionsCaptured) return;
        client.options.hideGui = originalHideGui;
        client.options.setCameraType(originalCamera);
        if (client.options.mainHand().get() != originalArm) client.options.mainHand().set(originalArm);
        optionsCaptured = false;
    }
    private static void capture(Minecraft client, String view) {
        String name = "water_art_preview_0111_" + view + "_" + System.currentTimeMillis() + ".png";
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_WATER_ART_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }
    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        restoreRequested = true;
        server.execute(() -> WaterArtPreview.finish(server, passed, reason));
    }
    private WaterArtPreviewClient() {}
}
