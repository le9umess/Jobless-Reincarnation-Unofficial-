package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;

import dev.joblessreincarnation.client.npc.RoxyRendering;
import dev.joblessreincarnation.gametest.NpcDisplayPreview;
import dev.joblessreincarnation.gametest.NpcDisplayRegistry;
import dev.joblessreincarnation.gametest.RoxyDisplayEntity;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Native renderer capture of Roxy drawn by GeckoLib: four sides at idle, frame bursts of walking and of the lecture, then beside the player. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class NpcDisplayPreviewClient {
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static int shot, stable, ticks, frame, sinceFrame, expected, finishDelay = 20;
    private static boolean done, restoreRequested, waitingForSave, optionsCaptured, originalHideGui;
    private static CameraType originalCamera;

    /** The released NPC's renderer settings (scale, name tag, staff), reading the roxy_tutor assets from the main resources. */
    @EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Renderers {
        @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(NpcDisplayRegistry.roxy(), RoxyRendering::create);
        }
        private Renderers() {}
    }

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        return NpcDisplayPreview.enabled() && !done && server != null && !server.isPublished()
                && client.player != null && server.isSingleplayerOwner(client.player.getGameProfile())
                && NpcDisplayPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        // A pause overlay hides the world preview. No synthetic transparent screen replaces ordinary gameplay.
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!NpcDisplayPreview.enabled() || done) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { restoreOptions(client); return; }
        NpcDisplayPreview.State state = NpcDisplayPreview.state(server);
        if (state == null) { restoreOptions(client); return; }
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            restoreOptions(client);
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_NPC_DISPLAY_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
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
            // The HUD and the held item are not part of what is being inspected.
            client.options.hideGui = true;
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (++ticks > 2000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at shot " + shot : imageFailure.get());
            return;
        }
        try {
            if (waitingForSave) {
                if (saved.get() < expected) return;
                waitingForSave = false;
                shot++;
                stable = 0;
                frame = 0;
                if (shot >= NpcDisplayPreview.SHOTS) {
                    requestRestore(server, true, expected + " native renderer screenshots saved in " + NpcDisplayPreview.SHOTS
                            + " shots: Roxy from four sides at idle, walking, giving the lecture, and beside the player");
                    return;
                }
                int next = shot;
                server.execute(() -> NpcDisplayPreview.shot(server, next));
                return;
            }
            if (frame > 0) {
                // The rest of a burst: a frame every few ticks through the clip, without waiting for the files to be written.
                if (++sinceFrame >= NpcDisplayPreview.FRAME_INTERVAL) captureFrame(client);
                return;
            }
            boolean beside = NpcDisplayPreview.shotThirdPerson(shot);
            // Name tags are hidden together with the HUD, so the side-by-side shot keeps the HUD to show the tag.
            client.options.setCameraType(beside ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
            client.options.hideGui = !beside;
            boolean settled = client.screen == null && state.shot() == shot && npcVisible(client)
                    && client.player.position().distanceTo(NpcDisplayPreview.shotPosition(shot)) < 0.1;
            stable = settled ? stable + 1 : 0;
            // Long enough for the clip to blend in from the previous one and for the camera to settle.
            if (stable >= 30) captureFrame(client);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_NPC_DISPLAY_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    /** True once the display entity has arrived on the client with this shot's clip selected. */
    private static boolean npcVisible(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof RoxyDisplayEntity npc && npc.clip() == NpcDisplayPreview.shotClip(shot)) return true;
        }
        return false;
    }

    private static void restoreOptions(Minecraft client) {
        if (!optionsCaptured) return;
        client.options.hideGui = originalHideGui;
        client.options.setCameraType(originalCamera);
        optionsCaptured = false;
    }
    /** Takes the next frame of the current shot; after its last frame, waits for every file before moving on. */
    private static void captureFrame(Minecraft client) {
        int frames = NpcDisplayPreview.shotFrames(shot);
        capture(client, NpcDisplayPreview.shotName(shot) + (frames > 1 ? "_f" + (frame + 1) : ""));
        expected++;
        sinceFrame = 0;
        if (++frame >= frames) waitingForSave = true;
    }

    private static void capture(Minecraft client, String view) {
        String name = "npc_display_preview_roxy_" + view + "_" + System.currentTimeMillis() + ".png";
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_NPC_DISPLAY_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }
    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        restoreRequested = true;
        server.execute(() -> NpcDisplayPreview.finish(server, passed, reason));
    }
    private NpcDisplayPreviewClient() {}
}
