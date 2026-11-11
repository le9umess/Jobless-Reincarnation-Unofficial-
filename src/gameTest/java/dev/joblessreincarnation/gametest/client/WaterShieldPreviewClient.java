package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.MagicJournalScreen;
import dev.joblessreincarnation.gametest.WaterShieldPreview;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.WaterShieldEntity;
import dev.joblessreincarnation.magic.WaterShieldSpell;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Native renderer capture of the Water Shield: rising, standing, rippling after a stopped arrow, fading and gone, from
 * first person, both third-person cameras, beside the screen, and at night; then its journal page in both languages at
 * the journal probe's tight GUI size. Only the real client sees the screens. Nothing here needs window focus: the
 * journal row is clicked through the screen's own handlers, which is not a test of real input.
 *
 * When the build has the Water Shield advancement, each language's journal shot is followed by an advancement scene:
 * the story page with the node visible but unfinished, the toast of a real first block, and the page again with the
 * node's tooltip. The pointer is placed over the node by setting the mouse handler's position, and the description
 * lines vanilla's AdvancementWidget computed are logged. Toasts are cleared during the world shots, so the earlier
 * first block never covers them.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class WaterShieldPreviewClient {
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> imageFailure = new AtomicReference<>();
    private static final Set<Integer> filmed = new HashSet<>();
    private static int phase, requested, captures, stable, ticks, tracked, shieldId = -1, finishDelay = 20;
    private static boolean done, restoreRequested, optionsCaptured, originalHideGui, journalOptions;
    private static CameraType originalCamera;
    private static int originalGuiScale;
    private static String originalLanguage;
    private static CompletableFuture<Void> languageReload;
    private static final ResourceLocation STORY_ROOT = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "story/root");
    private static final Map<String, Field> fields = new HashMap<>();
    /** Phase to go back to after an advancement scene, and the scene tick the client first saw the node completed. */
    private static int sceneReturn, doneAt = -1;
    private static boolean allowToasts;

    private static boolean privateWorld() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        return WaterShieldPreview.enabled() && !done && server != null && !server.isPublished()
                && client.player != null && server.isSingleplayerOwner(client.player.getGameProfile())
                && WaterShieldPreview.WORLD.equals(server.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void preventPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld()) event.setCanceled(true);
    }
    @SubscribeEvent public static void ignoreAutomaticPauseMenu(ScreenEvent.Opening event) {
        if (privateWorld() && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!WaterShieldPreview.enabled() || done) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { restoreOptions(client); return; }
        WaterShieldPreview.State state = WaterShieldPreview.state(server);
        if (state == null) { restoreOptions(client); return; }
        if (state.restored()) {
            if (--finishDelay > 0) return;
            done = true;
            restoreOptions(client);
            var closingLevel = client.level;
            if (closingLevel != null) closingLevel.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] DISCONNECTED passed={} images={} {}", state.passed(), saved.get(), state.message());
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
        // The first stopped arrow already completes the advancement; its toast must not cover the world shots.
        if (!allowToasts) client.getToasts().clear();
        if (++ticks > 2000 || imageFailure.get() != null) {
            requestRestore(server, false, imageFailure.get() == null ? "Visual capture timeout at phase " + phase : imageFailure.get());
            return;
        }
        try {
            WaterShieldEntity shield = client.level.getEntity(shieldId) instanceof WaterShieldEntity found && !found.isRemoved() ? found : null;
            int age = shield == null ? -1 : (int) (client.level.getGameTime() - shield.startGameTime());
            if (shield != null) tracked++;
            boolean expectScreen = phase >= 2 && phase <= 8 || phase == 11 || phase == 12 || phase == 14;
            if (expectScreen && shield == null) throw new IllegalStateException("The filmed screen vanished early at phase " + phase);
            switch (phase) {
                case 0 -> {
                    boolean received = client.screen == null && client.player.hasData(ModAttachments.CASTING)
                            && client.player.getData(ModAttachments.CASTING).selectedSpell().equals(WaterShieldSpell.SPELL)
                            && client.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                            && client.player.getInventory().getItem(1).is(ModContent.WATER_SHIELD_TOME.get());
                    stable = received ? stable + 1 : 0;
                    if (stable >= 20) { request(server, WaterShieldPreview.Step.CAST); phase = 1; }
                }
                case 1 -> { if (follow(client, state, WaterShieldPreview.FRONT_YAW)) phase = 2; }
                // The renderer draws the frame before this tick; wait until the screen has been drawn at least twice.
                case 2 -> { if (shield != null && tracked >= 3 && age >= 3) { capture(client, "rise_first_person", shield, age); phase = 3; } }
                case 3 -> { if (age >= 28) { capture(client, "steady_first_person", shield, age); client.options.hideGui = false; phase = 4; } }
                case 4 -> {
                    if (age >= 34) {
                        capture(client, "steady_first_person_hud", shield, age);
                        client.options.hideGui = true;
                        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                        phase = 5;
                    }
                }
                case 5 -> {
                    if (age >= 41) {
                        capture(client, "steady_third_person_back", shield, age);
                        client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                        phase = 6;
                    }
                }
                case 6 -> { if (age >= 48) { capture(client, "steady_third_person_front", shield, age); request(server, WaterShieldPreview.Step.ARROW); stable = 0; phase = 7; } }
                case 7 -> { if (rippled(client, state, shield, age, "ripple_third_person_front", 75)) phase = 8; }
                case 8 -> {
                    if (age >= 89) {
                        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] ADVANCEMENT after the first stopped arrow: completed={}", clientCompleted(client));
                        capture(client, "fade_third_person_front", shield, age); stable = 0; phase = 9;
                    }
                }
                case 9 -> {
                    stable = shield == null ? stable + 1 : 0;
                    if (stable >= 6) {
                        capture(client, "gone_third_person_front", null, -1);
                        client.options.setCameraType(CameraType.FIRST_PERSON);
                        request(server, WaterShieldPreview.Step.CAST_OBLIQUE);
                        phase = 10;
                    }
                }
                case 10 -> { if (follow(client, state, WaterShieldPreview.OBLIQUE_YAW)) phase = 11; }
                case 11 -> { if (shield != null && age >= 26) { capture(client, "steady_oblique", shield, age); request(server, WaterShieldPreview.Step.ARROW_HIGH); stable = 0; phase = 12; } }
                case 12 -> { if (rippled(client, state, shield, age, "ripple_oblique", 60)) { client.options.setCameraType(CameraType.THIRD_PERSON_BACK); request(server, WaterShieldPreview.Step.NIGHT_CAST); phase = 13; } }
                case 13 -> { if (follow(client, state, WaterShieldPreview.FRONT_YAW)) phase = 14; }
                case 14 -> { if (shield != null && age >= 30) { capture(client, "night_third_person_back", shield, age); phase = 15; } }
                case 15 -> {
                    if (saved.get() < captures) return;
                    // GUI 427x240 like the journal probe: the six rows have the least room there.
                    originalGuiScale = client.options.guiScale().get();
                    originalLanguage = client.getLanguageManager().getSelected();
                    journalOptions = true;
                    client.options.guiScale().set(3); client.resizeDisplay();
                    client.options.hideGui = false;
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    client.setScreen(new MagicJournalScreen());
                    stable = 0; phase = 16;
                }
                case 16 -> {
                    if (!(client.screen instanceof MagicJournalScreen journal) || ++stable < 10) return;
                    MagicJournalScreen.Bounds row = journal.spellBounds(SpellCatalog.WATER_SHIELD);
                    journal.mouseClicked(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    journal.mouseReleased(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    stable = 0; phase = 17;
                }
                case 17, 19 -> {
                    if (!reloaded(client) || !(client.screen instanceof MagicJournalScreen journal) || ++stable < 10) return;
                    if (journal.browsedSpell() != SpellCatalog.WATER_SHIELD) throw new IllegalStateException("The journal is not showing the Water Shield page");
                    capture(client, "journal_" + client.getLanguageManager().getSelected(), null, -1);
                    sceneReturn = phase + 1;
                    phase = client.player.connection.getAdvancements().get(WaterShieldPreview.BLOCK_ADVANCEMENT) != null ? 30 : sceneReturn;
                    if (phase == sceneReturn) LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] ADVANCEMENT {} absent in this build; scene skipped",
                            WaterShieldPreview.BLOCK_ADVANCEMENT);
                }
                case 18 -> {
                    if (saved.get() < captures) return;
                    language(client, "en_us".equals(originalLanguage) ? "zh_cn" : "en_us");
                    stable = 0; phase = 19;
                }
                case 20 -> {
                    if (saved.get() < captures) return;
                    language(client, originalLanguage);
                    stable = 0; phase = 21;
                }
                case 21 -> {
                    if (!reloaded(client) || ++stable < 5) return;
                    client.setScreen(null);
                    restoreOptions(client);
                    requestRestore(server, true, captures + " native renderer screenshots saved: rise, steady (four cameras and the HUD), "
                            + "two ripples, fade, gone, night, the journal page in both languages, and the advancement page before and after "
                            + "a first block with its toast in each language when the build has it");
                    phase = 22;
                }
                // Advancement scene, once per language.
                case 30 -> {
                    if (saved.get() < captures) return;
                    client.setScreen(null);
                    request(server, WaterShieldPreview.Step.REVOKE_BLOCK_ADVANCEMENT);
                    stable = 0; phase = 31;
                }
                case 31 -> {
                    if (++stable > 200) throw new IllegalStateException("The revoked advancement never reached the client");
                    Boolean completed = clientCompleted(client);
                    if (state.steps() < requested || completed == null || completed) return;
                    openStoryPage(client);
                    stable = 0; phase = 32;
                }
                case 32 -> { if (hoverAndCapture(client, "advancement_page_before", false)) phase = 33; }
                case 33 -> {
                    if (saved.get() < captures) return;
                    client.setScreen(null);
                    allowToasts = true;
                    request(server, WaterShieldPreview.Step.SCENE_BLOCK);
                    stable = 0; doneAt = -1; phase = 34;
                }
                case 34 -> {
                    if (++stable > 200) throw new IllegalStateException("The scene's stopped arrow brought no advancement");
                    Boolean completed = clientCompleted(client);
                    if (state.steps() < requested || completed == null || !completed) return;
                    if (doneAt < 0) doneAt = stable;
                    // The toast slides in over about 0.6 s and then stays for 5 s.
                    if (stable - doneAt < 16) return;
                    if (client.getToasts().getToast(AdvancementToast.class, Toast.NO_TOKEN) == null)
                        throw new IllegalStateException("The advancement completed but no advancement toast is showing");
                    capture(client, "advancement_toast_" + client.getLanguageManager().getSelected(), null, -1);
                    phase = 35;
                }
                case 35 -> {
                    if (saved.get() < captures) return;
                    openStoryPage(client);
                    stable = 0; phase = 36;
                }
                case 36 -> { if (hoverAndCapture(client, "advancement_page_after", true)) phase = 37; }
                case 37 -> {
                    if (saved.get() < captures) return;
                    allowToasts = false;
                    client.getToasts().clear();
                    client.setScreen(new MagicJournalScreen());
                    stable = 0; phase = 38;
                }
                case 38 -> {
                    if (!(client.screen instanceof MagicJournalScreen journal) || ++stable < 10) return;
                    MagicJournalScreen.Bounds row = journal.spellBounds(SpellCatalog.WATER_SHIELD);
                    journal.mouseClicked(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    journal.mouseReleased(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    stable = 0; phase = sceneReturn;
                }
                default -> throw new IllegalStateException("Unexpected visual phase " + phase);
            }
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_WATER_SHIELD_PREVIEW_CLIENT] FAILED", failure);
            requestRestore(server, false, failure.toString());
        }
    }

    /** Waits for the server step just requested, then films the one new screen, which must face the requested yaw. */
    private static boolean follow(Minecraft client, WaterShieldPreview.State state, float yaw) {
        if (state.steps() < requested) return false;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof WaterShieldEntity shield && !shield.isRemoved() && !filmed.contains(shield.getId())) {
                if (Math.abs(shield.shieldYaw() - yaw) > 1.0E-3F)
                    throw new IllegalStateException("Client screen yaw " + shield.shieldYaw() + " differs from the release yaw " + yaw);
                filmed.add(shield.getId());
                shieldId = shield.getId();
                tracked = 0;
                LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] FOLLOW screen {} yaw={} start={} at {}",
                        shield.getId(), shield.shieldYaw(), shield.startGameTime(), shield.position());
                return true;
            }
        }
        return false;
    }

    /** After the step's arrow: the client must see the stopped-arrow ripple; the shot waits two frames for it to show. */
    private static boolean rippled(Minecraft client, WaterShieldPreview.State state, WaterShieldEntity shield, int age, String view, int deadline) {
        if (shield == null || age >= deadline) throw new IllegalStateException("No stopped-arrow ripple for " + view + " by age " + age);
        if (state.steps() < requested || shield.ripple(0.0F) <= 0.0F) return false;
        if (++stable < 3) return false;
        capture(client, view, shield, age);
        return true;
    }

    /** Whether the client's own record shows the block advancement completed; null when the client has no such node. */
    private static Boolean clientCompleted(Minecraft client) throws ReflectiveOperationException {
        ClientAdvancements advancements = client.player.connection.getAdvancements();
        AdvancementHolder holder = advancements.get(WaterShieldPreview.BLOCK_ADVANCEMENT);
        if (holder == null) return null;
        Object progress = ((Map<?, ?>) field(ClientAdvancements.class, "progress").get(advancements)).get(holder);
        return progress instanceof AdvancementProgress record && record.isDone();
    }

    private static void openStoryPage(Minecraft client) {
        ClientAdvancements advancements = client.player.connection.getAdvancements();
        AdvancementHolder root = advancements.get(STORY_ROOT);
        if (root == null) throw new IllegalStateException("The client has no " + STORY_ROOT);
        client.setScreen(new AdvancementsScreen(advancements));
        advancements.setSelectedTab(root, true);
    }

    /**
     * After the page has drawn and centred itself, puts the pointer over the Water Shield node, lets the tooltip draw,
     * then films it and logs the description lines. Returns true once the shot is taken.
     */
    private static boolean hoverAndCapture(Minecraft client, String view, boolean expectDone) throws ReflectiveOperationException {
        if (!(client.screen instanceof AdvancementsScreen screen) || ++stable < 10) return false;
        AdvancementNode node = client.player.connection.getAdvancements().getTree().get(WaterShieldPreview.BLOCK_ADVANCEMENT);
        AdvancementWidget widget = node == null ? null : screen.getAdvancementWidget(node);
        if (widget == null) throw new IllegalStateException("The story page shows no Water Shield node");
        if (stable == 10) {
            int left = (screen.width - AdvancementsScreen.WINDOW_WIDTH) / 2, top = (screen.height - AdvancementsScreen.WINDOW_HEIGHT) / 2;
            AdvancementTab tab = (AdvancementTab) field(AdvancementsScreen.class, "selectedTab").get(screen);
            int x = left + 9 + Mth.floor(field(AdvancementTab.class, "scrollX").getDouble(tab)) + widget.getX() + 13;
            int y = top + 18 + Mth.floor(field(AdvancementTab.class, "scrollY").getDouble(tab)) + widget.getY() + 13;
            double scale = client.getWindow().getGuiScale();
            field(MouseHandler.class, "xpos").setDouble(client.mouseHandler, (x + 0.5) * scale);
            field(MouseHandler.class, "ypos").setDouble(client.mouseHandler, (y + 0.5) * scale);
            LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] HOVER {} node at gui {},{} (window {}x{}, scale {})", view, x, y,
                    client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(), scale);
            return false;
        }
        if (stable < 14) return false;
        Boolean completed = clientCompleted(client);
        if (completed == null || completed != expectDone)
            throw new IllegalStateException(view + " expected the node " + (expectDone ? "completed" : "unfinished") + " but the client has " + completed);
        String language = client.getLanguageManager().getSelected();
        capture(client, view + "_" + language, null, -1);
        List<String> lines = new ArrayList<>();
        for (Object line : (List<?>) field(AdvancementWidget.class, "description").get(widget)) {
            StringBuilder builder = new StringBuilder();
            ((FormattedCharSequence) line).accept((index, style, codePoint) -> { builder.appendCodePoint(codePoint); return true; });
            lines.add(builder.toString());
        }
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] ADVANCEMENT_LINES {} {} completed={} lines={}", view, language, completed, lines);
        return true;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        String key = owner.getName() + "#" + name;
        Field found = fields.get(key);
        if (found == null) {
            found = owner.getDeclaredField(name);
            found.setAccessible(true);
            fields.put(key, found);
        }
        return found;
    }

    private static void request(MinecraftServer server, WaterShieldPreview.Step step) {
        requested++;
        server.execute(() -> WaterShieldPreview.perform(server, step));
    }
    private static void language(Minecraft client, String code) {
        if (client.getLanguageManager().getSelected().equals(code)) return;
        client.options.languageCode = code; client.getLanguageManager().setSelected(code);
        languageReload = client.reloadResourcePacks();
    }
    /** True once any language reload has finished and its loading overlay is gone. */
    private static boolean reloaded(Minecraft client) {
        if (languageReload == null) return true;
        if (!languageReload.isDone() || client.getOverlay() != null) return false;
        languageReload.join(); languageReload = null;
        return true;
    }
    private static void restoreOptions(Minecraft client) {
        if (journalOptions) {
            journalOptions = false;
            client.options.guiScale().set(originalGuiScale); client.resizeDisplay();
            if (!client.getLanguageManager().getSelected().equals(originalLanguage)) {
                client.options.languageCode = originalLanguage; client.getLanguageManager().setSelected(originalLanguage);
            }
        }
        if (!optionsCaptured) return;
        client.options.hideGui = originalHideGui;
        client.options.setCameraType(originalCamera);
        optionsCaptured = false;
    }
    private static void capture(Minecraft client, String view, WaterShieldEntity shield, int age) {
        captures++;
        String name = "water_shield_preview_" + String.format("%02d", captures) + "_" + view + "_" + System.currentTimeMillis() + ".png";
        LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] CAPTURE {} age={} ripple={} camera={} time={}", name, age,
                shield == null ? 0.0F : shield.ripple(0.0F), client.options.getCameraType(), client.level.getDayTime());
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet();
                LogUtils.getLogger().info("[JR_WATER_SHIELD_PREVIEW_CLIENT] SCREENSHOT_SAVED {}", name);
            } else imageFailure.set("Native screenshot did not report success: " + message.getString());
        });
    }
    private static void requestRestore(MinecraftServer server, boolean passed, String reason) {
        restoreRequested = true;
        server.execute(() -> WaterShieldPreview.finish(server, passed, reason));
    }
    private WaterShieldPreviewClient() {}
}
