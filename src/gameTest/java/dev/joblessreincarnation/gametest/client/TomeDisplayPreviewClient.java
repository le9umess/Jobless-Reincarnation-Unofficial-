package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;

import dev.joblessreincarnation.gametest.TomeDisplayPreview;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Native renderer capture of the tomes in real item frames and as real dropped items. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class TomeDisplayPreviewClient {
    private static final String[] VIEW_NAMES = { "frames", "dropped" };
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static int phase, stable, ticks, finishDelay = 20;
    private static boolean done, restoreRequested, optionsCaptured, originalHideGui;
    private static CameraType originalCamera;

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        return TomeDisplayPreview.enabled() && !done && server != null && !server.isPublished()
                && client.player != null && server.isSingleplayerOwner(client.player.getGameProfile())
                && TomeDisplayPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        // A pause overlay hides the world preview. No synthetic transparent screen replaces ordinary gameplay.
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!TomeDisplayPreview.enabled() || done) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { restoreOptions(client); return; }
        TomeDisplayPreview.State state = TomeDisplayPreview.state(server);
        if (state == null) { restoreOptions(client); return; }
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            restoreOptions(client);
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_TOME_DISPLAY_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
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
            optionsCaptured = true;
            // The HUD and the held wand are not part of what is being inspected.
            client.options.hideGui = true;
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (++ticks > 1000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at phase " + phase : imageFailure.get());
            return;
        }
        try {
            // Each view is phase 2n (wait and capture) followed by 2n+1 (saved, move on).
            int view = phase / 2;
            if (view >= TomeDisplayPreview.VIEWS) {
                if (saved.get() == TomeDisplayPreview.VIEWS) requestRestore(server, true,
                        "Two native renderer screenshots saved: tomes in fixed item frames and as dropped items");
                return;
            }
            if (phase % 2 == 0) {
                boolean settled = client.screen == null && state.view() == view && displayed(client) && facing(client, view);
                stable = settled ? stable + 1 : 0;
                // Dropped items need a moment to land and start their idle spin before the picture is taken.
                if (stable >= 40) { capture(client, VIEW_NAMES[view]); phase++; stable = 0; }
            } else if (saved.get() > view) {
                int next = view + 1;
                if (next < TomeDisplayPreview.VIEWS) server.execute(() -> TomeDisplayPreview.view(server, next));
                phase++;
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_TOME_DISPLAY_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    /** True once the client has every frame and dropped book the server spawned. */
    private static boolean displayed(Minecraft client) {
        List<Item> tomes = TomeDisplayPreview.tomes();
        int frames = 0, dropped = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ItemFrame frame && tomes.contains(frame.getItem().getItem())) frames++;
            else if (entity instanceof ItemEntity item && tomes.contains(item.getItem().getItem())) dropped++;
        }
        return frames == tomes.size() && dropped == tomes.size();
    }
    private static boolean facing(Minecraft client, int view) {
        float pitch = client.player.getXRot();
        return view == 0 ? Math.abs(pitch) < 5.0F : pitch > 30.0F;
    }

    private static void restoreOptions(Minecraft client) {
        if (!optionsCaptured) return;
        client.options.hideGui = originalHideGui;
        client.options.setCameraType(originalCamera);
        optionsCaptured = false;
    }
    private static void capture(Minecraft client, String view) {
        String name = "tome_display_preview_" + view + "_" + System.currentTimeMillis() + ".png";
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_TOME_DISPLAY_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }
    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        restoreRequested = true;
        server.execute(() -> TomeDisplayPreview.finish(server, passed, reason));
    }
    private TomeDisplayPreviewClient() {}
}
