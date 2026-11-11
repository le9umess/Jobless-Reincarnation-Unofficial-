package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.SwordDemoPreview;
import dev.joblessreincarnation.gametest.SwordDemoPreview.Step;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import software.bernie.geckolib.animation.AnimationController;

/**
 * Native renderer capture for B01's presentation layer, driven by a fixed script: Paul at rest from four sides; his
 * sword_demo from the front, the side and the front-right, each frame timed from the moment this client sees the
 * gesture controller start (GeckoLib blends in for 4 ticks, so clip time is about (ticks after start - 4) / 20 s);
 * Paul walking; the placeholder practice dummy before and during its hit wobble; the practice sword in first person
 * (main and off hand, and mid-swing), in third person, on the hotbar and in the inventory, dropped and in an item frame.
 * Nothing here needs window focus. The HUD is hidden except for the hotbar and inventory shots.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class SwordDemoPreviewClient {
    /** One script action per tick until it reports done. */
    private interface Action { boolean run(Minecraft client, MinecraftServer server); }
    private static final List<Action> SCRIPT = new ArrayList<>();
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static int at, ticks, captures, waitUntil = -1, requestedSteps = -1, demoStart = -1, finishDelay = 20;
    private static boolean done, restoreRequested, optionsCaptured, originalHideGui;
    private static CameraType originalCamera;

    /** Clip times filmed in each sword_demo pass: guard, raised, mid-cut, end of cut, follow-through, hold. */
    private static final double[] DEMO_TIMES = {0.30, 0.55, 0.70, 0.80, 0.90, 1.15};

    /** -PswordDemoItemsOnly=true films only the practice sword as an item, to compare display transforms quickly. */
    private static final boolean ITEMS_ONLY = Boolean.getBoolean("jobless_reincarnation.swordDemoPreview.itemsOnly");

    static {
        if (SwordDemoPreview.interactionMode()) interactionScenes();
        else { if (!ITEMS_ONLY) scene(); items(); }
    }

    /** Scripted native input in a private development world, not an assertion of manual/PCL testing. */
    private static void interactionScenes() {
        for (int round = 1; round <= 2; round++) {
            String label = "interaction_round_" + round;
            SCRIPT.add(step(Step.INTERACTION_APPROACH));
            SCRIPT.add(waitTicks(12));
            SCRIPT.add(hud(true));
            SCRIPT.add(nativeQuery());
            SCRIPT.add(interactionElapsed(3)); SCRIPT.add(shot(label + "_course_reply"));
            SCRIPT.add(hud(false));
            SCRIPT.add(interactionElapsed(9)); SCRIPT.add(shot(label + "_demonstrating_near"));
            SCRIPT.add(interactionElapsed(14)); SCRIPT.add(step(Step.INTERACTION_LEAVE));
            SCRIPT.add(waitTicks(4));
            SCRIPT.add(shot(label + "_departed_still_demonstrating"));
            SCRIPT.add(interactionElapsed(25)); SCRIPT.add(shot(label + "_departed_late_pose"));
            SCRIPT.add(interactionRecovered()); SCRIPT.add(shot(label + "_autonomous_return_resumed"));
            SCRIPT.add(waitTicks(10));
        }
        SCRIPT.add((client, server) -> saved.get() == captures);
    }

    private static Action nativeQuery() {
        return new Action() {
            private boolean sent;
            @Override public boolean run(Minecraft client, MinecraftServer server) {
                SwordDemoPreview.State state = SwordDemoPreview.state(server);
                if (!sent) {
                    if (!(client.level.getEntity(state.paulId()) instanceof FamilyResidentEntity paul)) return false;
                    if (client.gameMode == null || !client.player.canInteractWithEntity(paul, 0.0))
                        throw new IllegalStateException("Native client query must target Paul within actual interaction reach");
                    var result = client.gameMode.interact(client.player, paul, InteractionHand.MAIN_HAND);
                    sent = true;
                    LogUtils.getLogger().info("[JR_SWORD_INTERACTION_CLIENT] NATIVE_QUERY clientTick={} entity={} result={} player={} paul={}",
                            ticks, paul.getId(), result, client.player.position(), paul.position());
                }
                var observed = SwordDemoPreview.interactionState(server);
                return observed != null && !observed.awaiting() && observed.startedAt() >= 0;
            }
        };
    }

    private static Action interactionElapsed(long elapsed) {
        return (client, server) -> {
            var observed = SwordDemoPreview.interactionState(server);
            return observed != null && !observed.awaiting() && observed.elapsed() >= elapsed;
        };
    }
    private static Action interactionRecovered() {
        return (client, server) -> {
            var observed = SwordDemoPreview.interactionState(server);
            return observed != null && observed.recovered();
        };
    }

    private static void scene() {
        // Paul at rest, with the practice sword in his hand.
        for (Step camera : new Step[] {Step.PAUL_FRONT, Step.PAUL_SIDE, Step.PAUL_THREE_QUARTER, Step.PAUL_BACK}) {
            SCRIPT.add(step(camera)); SCRIPT.add(waitTicks(12)); SCRIPT.add(shot("paul_rest_" + view(camera)));
        }
        // sword_demo from three sides; each pass waits out the 40-tick gesture interval before the next. The last pass
        // is filmed from the front-right with Paul's head turned toward his sword arm, the way it follows a player there.
        for (Step camera : new Step[] {Step.PAUL_FRONT, Step.PAUL_SIDE, Step.PAUL_THREE_QUARTER, Step.HEAD_TO_SWORD_SIDE}) {
            boolean headTurned = camera == Step.HEAD_TO_SWORD_SIDE;
            if (headTurned) { SCRIPT.add(step(Step.PAUL_THREE_QUARTER)); SCRIPT.add(step(Step.HEAD_TO_SWORD_SIDE)); } else SCRIPT.add(step(camera));
            String label = headTurned ? "three_quarter_head_turned" : view(camera);
            SCRIPT.add(waitTicks(48)); SCRIPT.add(step(Step.DEMO)); SCRIPT.add(gestureStarted());
            for (double t : DEMO_TIMES) { SCRIPT.add(afterStart(4 + (int) Math.round(t * 20))); SCRIPT.add(shot(String.format("demo_%s_%.2fs", label, t))); }
            SCRIPT.add(afterStart(4 + 34 + 4)); SCRIPT.add(shot("demo_" + label + "_after"));
            if (headTurned) SCRIPT.add(step(Step.HEAD_AHEAD));
        }
        // Walking past the side camera.
        SCRIPT.add(step(Step.PAUL_SIDE)); SCRIPT.add(waitTicks(8)); SCRIPT.add(step(Step.WALK));
        for (int i = 0; i < 5; i++) { SCRIPT.add(waitTicks(i == 0 ? 14 : 5)); SCRIPT.add(shot("walk_side_" + i)); }
        SCRIPT.add(waitTicks(30));
        // The placeholder dummy, before and during the hit wobble (0.35 s), from the front and the side.
        for (Step camera : new Step[] {Step.DUMMY_FRONT, Step.DUMMY_SIDE}) {
            SCRIPT.add(step(camera)); SCRIPT.add(waitTicks(12)); SCRIPT.add(shot(view(camera) + "_idle"));
            SCRIPT.add(step(Step.HIT));
            for (int t : new int[] {1, 3, 5}) { SCRIPT.add(waitTicks(t == 1 ? 1 : 2)); SCRIPT.add(shot(view(camera) + "_hit_" + t)); }
            SCRIPT.add(waitTicks(12));
        }
    }

    private static void items() {
        // The practice sword as an item.
        SCRIPT.add(step(Step.ITEMS_VIEW)); SCRIPT.add(waitTicks(16)); SCRIPT.add(shot("item_ground_and_frame"));
        // Right hand practice sword, left hand vanilla wooden sword, on an armor stand: side (right hand toward the camera) and front.
        SCRIPT.add(step(Step.STAND_SIDE)); SCRIPT.add(waitTicks(10)); SCRIPT.add(shot("item_stand_side"));
        SCRIPT.add(step(Step.STAND_FRONT)); SCRIPT.add(waitTicks(10)); SCRIPT.add(shot("item_stand_front"));
        // First person needs the HUD on: hiding it (F1) hides the hands too.
        SCRIPT.add(step(Step.HANDS_VIEW)); SCRIPT.add(hud(true)); SCRIPT.add(waitTicks(10)); SCRIPT.add(shot("item_first_person_main"));
        SCRIPT.add(swing()); SCRIPT.add(waitTicks(2)); SCRIPT.add(shot("item_first_person_swing"));
        SCRIPT.add(step(Step.OFF_ONLY)); SCRIPT.add(waitTicks(8)); SCRIPT.add(shot("item_first_person_off"));
        SCRIPT.add(step(Step.COMPARE)); SCRIPT.add(waitTicks(24)); SCRIPT.add(shot("item_first_person_compare_vanilla_left"));
        SCRIPT.add(hud(false)); SCRIPT.add(camera(CameraType.THIRD_PERSON_FRONT)); SCRIPT.add(waitTicks(6)); SCRIPT.add(shot("item_third_person_compare_vanilla_left"));
        SCRIPT.add(step(Step.OFF_ONLY)); SCRIPT.add(waitTicks(6)); SCRIPT.add(shot("item_third_person_off"));
        SCRIPT.add(step(Step.MAIN_ONLY)); SCRIPT.add(waitTicks(6)); SCRIPT.add(shot("item_third_person_main"));
        SCRIPT.add(camera(CameraType.FIRST_PERSON)); SCRIPT.add(hud(true)); SCRIPT.add(waitTicks(8)); SCRIPT.add(shot("item_hotbar"));
        SCRIPT.add(inventory(true)); SCRIPT.add(waitTicks(6)); SCRIPT.add(shot("item_inventory")); SCRIPT.add(inventory(false)); SCRIPT.add(hud(false));
    }

    private static String view(Step camera) {
        return switch (camera) {
            case PAUL_FRONT -> "front"; case PAUL_SIDE -> "side"; case PAUL_THREE_QUARTER -> "three_quarter"; case PAUL_BACK -> "back";
            case DUMMY_FRONT -> "dummy_front"; case DUMMY_SIDE -> "dummy_side";
            default -> camera.name().toLowerCase();
        };
    }

    private static Action step(Step step) {
        return (client, server) -> {
            SwordDemoPreview.State state = SwordDemoPreview.state(server);
            if (requestedSteps < 0) { requestedSteps = state.steps() + 1; server.execute(() -> SwordDemoPreview.perform(server, step)); return false; }
            if (state.steps() < requestedSteps) return false;
            requestedSteps = -1;
            return true;
        };
    }
    private static Action waitTicks(int count) {
        return (client, server) -> {
            if (waitUntil < 0) waitUntil = ticks + count;
            if (ticks < waitUntil) return false;
            waitUntil = -1;
            return true;
        };
    }
    /** Waits for this client to see Paul's gesture controller playing the triggered sword_demo. */
    private static Action gestureStarted() {
        return (client, server) -> {
            AnimationController<?> gesture = gesture(client, server);
            if (gesture == null || !gesture.isPlayingTriggeredAnimation()) return false;
            demoStart = ticks;
            LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW_CLIENT] DEMO_STARTED tick={} state={} animation={}", ticks, gesture.getAnimationState(),
                    gesture.getCurrentAnimation() == null ? "none" : gesture.getCurrentAnimation().animation().name());
            return true;
        };
    }
    private static Action afterStart(int offset) { return (client, server) -> ticks - demoStart >= offset; }
    private static Action shot(String name) { return (client, server) -> { capture(client, server, name); return true; }; }
    private static Action camera(CameraType type) { return (client, server) -> { client.options.setCameraType(type); return true; }; }
    private static Action hud(boolean shown) { return (client, server) -> { client.options.hideGui = !shown; return true; }; }
    private static Action swing() { return (client, server) -> { client.player.swing(InteractionHand.MAIN_HAND); return true; }; }
    private static Action inventory(boolean open) {
        return (client, server) -> { client.setScreen(open ? new InventoryScreen(client.player) : null); return true; };
    }

    private static AnimationController<?> gesture(Minecraft client, MinecraftServer server) {
        SwordDemoPreview.State state = SwordDemoPreview.state(server);
        if (state == null || !(client.level.getEntity(state.paulId()) instanceof FamilyResidentEntity paul)) return null;
        return paul.getAnimatableInstanceCache().getManagerForId(paul.getId()).getAnimationControllers().get(NpcAnimations.GESTURE_CONTROLLER);
    }

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        return SwordDemoPreview.enabled() && !done && server != null && !server.isPublished()
                && client.player != null && server.isSingleplayerOwner(client.player.getGameProfile())
                && SwordDemoPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!SwordDemoPreview.enabled() || done) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { restoreOptions(client); return; }
        SwordDemoPreview.State state = SwordDemoPreview.state(server);
        if (state == null) { restoreOptions(client); return; }
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            restoreOptions(client);
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
            client.stop();
            return;
        }
        if (!privateWorld()) {
            if (!restoreRequested) requestRestore(server, false, "Private owner eligibility ended");
            return;
        }
        if (!state.ready() || client.player == null || client.level == null || client.getOverlay() != null || restoreRequested) return;
        if (!optionsCaptured) {
            originalHideGui = client.options.hideGui;
            originalCamera = client.options.getCameraType();
            optionsCaptured = true;
            client.options.hideGui = true;
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        // If this window gains focus, a grabbed mouse would turn the camera away from the view the server set.
        if (client.mouseHandler.isMouseGrabbed()) client.mouseHandler.releaseMouse();
        client.getToasts().clear();
        ticks++;
        if (ticks > 4000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at action " + at : imageFailure.get());
            return;
        }
        try {
            while (at < SCRIPT.size() && SCRIPT.get(at).run(client, server)) at++;
            if (at >= SCRIPT.size()) requestRestore(server, true, captures + " native renderer screenshots taken"
                    + (SwordDemoPreview.interactionMode() ? "; two real native client/server interaction rounds passed; developer fixture, not manual PCL" : ""));
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_SWORD_DEMO_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    private static void restoreOptions(Minecraft client) {
        if (!optionsCaptured) return;
        if (client.screen instanceof InventoryScreen) client.setScreen(null);
        client.options.hideGui = originalHideGui;
        client.options.setCameraType(originalCamera);
        optionsCaptured = false;
    }

    private static void capture(Minecraft client, MinecraftServer server, String view) {
        captures++;
        AnimationController<?> gesture = gesture(client, server);
        String name = "sword_demo_preview_" + String.format("%02d", captures) + "_" + view + "_" + System.currentTimeMillis() + ".png";
        LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW_CLIENT] CAPTURE {} tick={} sinceDemo={} gesture={} camera={}", name, ticks,
                demoStart < 0 ? -1 : ticks - demoStart, gesture == null ? "none" : gesture.getAnimationState() + "/" + gesture.isPlayingTriggeredAnimation(),
                client.options.getCameraType());
        if (SwordDemoPreview.interactionMode()) LogUtils.getLogger().info("[JR_SWORD_INTERACTION_CLIENT] CAPTURE name={} serverObservation={} gesture={}",
                name, SwordDemoPreview.interactionState(server), gesture == null ? "none" : gesture.getAnimationState());
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }

    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        if (restoreRequested) return;
        restoreRequested = true;
        LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW_CLIENT] RESTORE_REQUESTED passed={} {}", passed, reason);
        server.execute(() -> SwordDemoPreview.finish(server, passed, reason));
    }

    private SwordDemoPreviewClient() {}
}
