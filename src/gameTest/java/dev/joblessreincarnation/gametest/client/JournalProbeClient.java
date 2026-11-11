package dev.joblessreincarnation.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.client.DismissInputState;
import dev.joblessreincarnation.client.CourseRecordsScreen;
import dev.joblessreincarnation.client.JournalInput;
import dev.joblessreincarnation.client.MagicJournalScreen;
import dev.joblessreincarnation.client.SpellKeys;
import dev.joblessreincarnation.gametest.JournalProbe;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.npc.SylphietteFirstLesson;
import dev.joblessreincarnation.npc.SylphietteLessonVisuals;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.CourseRecordSnapshot;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ToastAddEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Real screen dispatch + logical key mappings + synthetic raw events; never a human-input claim. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class JournalProbeClient {
    private static final AtomicInteger saved = new AtomicInteger();
    private static final AtomicReference<String> screenshotFailure = new AtomicReference<>();
    private static final String[] NAMES = {"empty_unknown_readonly_bilingual", "subset_browse_mouse_selection_keyboard_navigation",
            "ready_water_K_and_release_cancel", "held_use_and_close_event_need_fresh_press", "ready_H_cancel_and_rebound_close_press",
            "K_wins_simultaneous_G", "other_screen_keeps_focus", "rebound_journal_and_keyboard_use", "study_K_cancels_learning", "seventh_row_bilingual_viewports_scroll_focus_and_server_selection"};
    private static MinecraftServer server;
    private static int caseId, phase, phaseTicks, totalTicks, tabAttempts, finalDelay = 12;
    private static boolean optionsCaptured, done, finishRequested, useDown, dismissDown, originalHideGui, originalToggle;
    private static boolean courseSecondCaptureRequested;
    private static int originalGuiScale, originalWindowWidth, originalWindowHeight, navigationPass, navigationImageTarget;
    private static boolean originalForceUnicode;
    private static int focusRequestedAt = -1000, focusReadyAt = -1, focusWaitStarted = -1, focusRequests;
    private static String originalLanguage;
    private static InputConstants.Key originalJournalKey, originalUseKey, originalDismissKey;
    private static CompletableFuture<Void> languageReload;
    private static boolean clientPassed;
    private static String finishReason = "";
    /** Development-only: the S-02 tree scene's language, so separate world copies can film zh_cn and en_us. */
    private static final String S02_LANGUAGE = JournalProbe.PROPERTY + ".s02Language";
    private static final String LESSON_LINE = "message.jobless_reincarnation.sylphiette.line";
    private static final String LESSON_KEY = "message.jobless_reincarnation.sylphiette.lesson.";
    private static boolean lessonWatching;
    private static int lessonLines, observeAt = -1, tryAt = -1, finishAt = -1, toastAt = -1, turnedAt = -1;
    private static String lessonProblem;
    private static Field toastAdvancement;

    private static boolean privateWorld(Minecraft client) {
        MinecraftServer current = client.getSingleplayerServer();
        return JournalProbe.enabled() && !done && current != null && !current.isPublished() && client.player != null
                && current.isSingleplayerOwner(client.player.getGameProfile())
                && JournalProbe.WORLD.equals(current.getWorldData().getLevelName());
    }
    @SubscribeEvent public static void noPause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateWorld(Minecraft.getInstance())) event.setCanceled(true);
    }
    @SubscribeEvent public static void noAutomaticPause(ScreenEvent.Opening event) {
        if (privateWorld(Minecraft.getInstance()) && event.getNewScreen() instanceof PauseScreen) event.setCanceled(true);
    }
    /** Case 9 counts Sylphiette's lesson lines by translation key, so the check holds in either language. */
    @SubscribeEvent public static void lessonChat(ClientChatReceivedEvent event) {
        if (!lessonWatching) return;
        String line = lessonLine(event.getMessage());
        if (line == null) return;
        lessonLines++;
        switch (line) {
            case "observe" -> { if (observeAt < 0) observeAt = totalTicks; }
            case "try" -> { if (tryAt < 0) tryAt = totalTicks; }
            case "finish" -> { if (finishAt < 0) finishAt = totalTicks; }
            default -> lessonProblem = "Unexpected lesson line " + line;
        }
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] S02_LESSON_LINE {} tick={}", line, totalTicks);
    }
    /** The first-lesson advancement toast actually queued on this client after the server cleared the old one. */
    @SubscribeEvent public static void lessonToast(ToastAddEvent event) {
        if (!lessonWatching || !(event.getToast() instanceof AdvancementToast toast)) return;
        try {
            if (toastAdvancement == null) {
                toastAdvancement = AdvancementToast.class.getDeclaredField("advancement"); toastAdvancement.setAccessible(true);
            }
            if (toastAt < 0 && ((AdvancementHolder)toastAdvancement.get(toast)).id().equals(StoryAdvancements.SYLPHIETTE_FIRST_LESSON)) {
                toastAt = totalTicks;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] S02_TOAST tick={}", totalTicks);
            }
        } catch (ReflectiveOperationException failure) {
            lessonProblem = "Could not read the advancement toast: " + failure;
        }
    }
    private static String lessonLine(Component message) {
        if (!(message.getContents() instanceof TranslatableContents line) || !line.getKey().equals(LESSON_LINE)
                || line.getArgs().length != 2 || !(line.getArgs()[1] instanceof Component spoken)
                || !(spoken.getContents() instanceof TranslatableContents lesson) || !lesson.getKey().startsWith(LESSON_KEY)) return null;
        return lesson.getKey().substring(LESSON_KEY.length());
    }
    private static String s02Language() {
        String language = System.getProperty(S02_LANGUAGE, "zh_cn");
        require(language.equals("zh_cn") || language.equals("en_us"), S02_LANGUAGE + " must be zh_cn or en_us, not " + language);
        return language;
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre event) {
        if (!JournalProbe.enabled() || done) return;
        Minecraft client = Minecraft.getInstance(); MinecraftServer current = client.getSingleplayerServer();
        if (current == null) return;
        JournalProbe.Receipt receipt = JournalProbe.receipt(current);
        if (receipt == null) return;
        if (receipt.restored()) {
            if (--finalDelay > 0) return;
            done = true; restoreOptions(client);
            if (client.level != null) client.level.disconnect();
            client.disconnect(new TitleScreen());
            LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] DISCONNECTED passed={} images={} {}", receipt.passed(), saved.get(), receipt.message());
            client.stop(); return;
        }
        server = current;
        try {
            if (!privateWorld(client)) { fail(client, "Private owner-world context changed"); return; }
            if (!receipt.ready() || client.player == null || client.level == null) return;
            if (!optionsCaptured) captureOptions(client);
            if (phase == 999) { finishWhenRestoredLanguage(client); return; }
            if (++totalTicks > 4600 || screenshotFailure.get() != null) {
                fail(client, screenshotFailure.get() == null ? "Client timeout case=" + caseId + " phase=" + phase : screenshotFailure.get()); return;
            }
            if (finishRequested) return;
            if (languageReload != null) {
                if (!languageReload.isDone() || client.getOverlay() != null) return;
                languageReload.join(); languageReload = null; phaseTicks = 0;
            }
            if (client.getOverlay() != null) return;
            if (!focusReady(client)) return; // Respect native focus and production guards; never fake window-active state.
            phaseTicks++;
            if (phaseTicks > 400) { fail(client, "No progress at case=" + caseId + " phase=" + phase); return; }
            if (phase == 900) {
                if (receipt.caseId() != caseId) return;
                if (receipt.result().equals("FAIL")) throw new IllegalStateException("Server consequences failed for " + NAMES[caseId]);
                if (!receipt.result().equals("PASS")) return;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] CASE_PASS {} {}", caseId, NAMES[caseId]);
                closeScreen(client); useDown = dismissDown = false;
                if (++caseId == NAMES.length) { finish(client, true, "10 actual screen/input scenarios; six bilingual viewport runs, real wheel/Tab/arrows/selection packets; reverse focus-path checks, NOT native Shift+Tab; synthetic inputs, not manual input"); return; }
                int index = caseId; current.execute(() -> JournalProbe.prepare(current, index)); step(0); return;
            }
            if (receipt.caseId() != caseId || !receipt.result().equals("READY")) return;
            switch (caseId) {
                case 0 -> unknown(client, receipt);
                case 1 -> subset(client, receipt);
                case 2 -> waterCancel(client, receipt);
                case 3 -> heldUse(client, receipt);
                case 4 -> dismissCancel(client, receipt);
                case 5 -> simultaneous(client, receipt);
                case 6 -> otherScreen(client, receipt);
                case 7 -> rebound(client, receipt);
                case 8 -> study(client, receipt);
                case 9 -> navigation(client, receipt);
                default -> throw new IllegalStateException("Unknown case " + caseId);
            }
            // Inputs only; no direct startUsingItem/releaseUsingItem, select or latch mutators.
            client.options.keyUse.setDown(useDown); SpellKeys.DISMISS.setDown(dismissDown);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_JOURNAL_PROBE_CLIENT] FAILED case={} phase={}", caseId, phase, failure);
            fail(client, failure.toString());
        }
    }
    /** Requests only this private probe's own GLFW window; no global keyboard or synthetic focus flag. */
    private static boolean focusReady(Minecraft client) {
        if (!privateWorld(client)) return false;
        long window = client.getWindow().getWindow();
        require(window != 0L, "Journal probe has no native window");
        boolean nativeFocused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (client.isWindowActive() && nativeFocused) {
            focusWaitStarted = -1;
            focusRequests = 0;
            if (focusReadyAt < 0) {
                focusReadyAt = totalTicks;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] WINDOW_FOCUS_READY case={} phase={} active=true nativeFocused=true",
                        caseId, phase);
            }
            return totalTicks - focusReadyAt >= 2;
        }
        focusReadyAt = -1;
        if (focusWaitStarted < 0) focusWaitStarted = totalTicks;
        require(totalTicks - focusWaitStarted <= 160,
                "Own journal window did not gain native focus within 160 ticks; no input was sent");
        if (focusRequests < 3 && totalTicks - focusRequestedAt >= 40) {
            focusRequests++;
            focusRequestedAt = totalTicks;
            LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] WINDOW_FOCUS_REQUEST case={} phase={} attempt={} active={} nativeFocused={}",
                    caseId, phase, focusRequests, client.isWindowActive(), nativeFocused);
            GLFW.glfwFocusWindow(window);
        }
        return false;
    }

    private static void unknown(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (!client.player.getMainHandItem().isEmpty() || receipt.learnedMask() != 0 || phaseTicks < 8) return;
                changeLanguage(client, "zh_cn"); step(1);
            }
            case 1 -> { pressJournal(); step(2); }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                require(!screen.isPauseScreen(), "Journal must not pause integrated gameplay");
                for (SpellCatalog spell : SpellCatalog.values()) {
                    browseRow(screen, spell);
                    require(screen.browsedSpell() == spell, "Unknown entry can be browsed");
                    require(!widget(screen, screen.selectionBounds()).active, "All-unknown/empty-handed selection is disabled");
                }
                require(receipt.mana() == 100 && receipt.waterPractice() == 0 && receipt.healingPractice() == 0, "Unknown browsing grants no personal progress");
                browseRow(screen, SpellCatalog.WATER_BOLT); step(3);
            }
            case 3 -> { if (phaseTicks >= 8) { capture(client, "unknown_zh_cn"); step(4); } }
            case 4 -> { if (saved.get() >= 1) { changeLanguage(client, "en_us"); step(5); } }
            case 5 -> { if (phaseTicks >= 8) { capture(client, "unknown_en_us"); step(6); } }
            case 6 -> { if (saved.get() >= 2) { changeLanguage(client, "zh_cn"); step(7); } }
            case 7 -> { if (phaseTicks >= 8) check(); }
            default -> throw new IllegalStateException("Unknown-readonly phase " + phase);
        }
    }
    private static void subset(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (phaseTicks < 8 || !client.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                        || !SpellCatalog.HEALING.knows(client.player) || SpellCatalog.WATER_BOLT.practiceCount(client.player) != 7) return;
                pressJournal(); step(1);
            }
            case 1 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                browseRow(screen, SpellCatalog.EARTH_WALL);
                require(screen.browsedSpell() == SpellCatalog.EARTH_WALL && !widget(screen, screen.selectionBounds()).active,
                        "Unlearned earth can be browsed but not selected");
                require(receipt.selected() == SpellCatalog.WATER_BOLT && SpellCatalog.selected(client.player) == SpellCatalog.WATER_BOLT,
                        "Browsing cannot change owner selection"); step(2);
            }
            case 2 -> {
                if (phaseTicks < 8) return;
                MagicJournalScreen screen = journal(client);
                require(receipt.selected() == SpellCatalog.WATER_BOLT, "Unknown browser row sends no selection");
                browseRow(screen, SpellCatalog.HEALING); step(3);
            }
            case 3 -> {
                if (phaseTicks < 5) return;
                MagicJournalScreen screen = journal(client);
                require(widget(screen, screen.selectionBounds()).active, "Known other spell offers selection with a wand");
                if (screen.maxDetailScroll() > 0) {
                    screen.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
                    require(screen.detailScroll() == screen.maxDetailScroll(), "End reaches the real detail footer");
                    screen.keyPressed(GLFW.GLFW_KEY_PAGE_UP, 0, 0);
                    require(screen.detailScroll() < screen.maxDetailScroll(), "PageUp scrolls detail text");
                    screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
                    require(screen.detailScroll() == 0, "Home restores the first detail line");
                }
                capture(client, "learned_zh_cn");
                click(screen, screen.selectionBounds()); step(4);
            }
            case 4 -> {
                if (saved.get() < 3 || receipt.selected() != SpellCatalog.HEALING || SpellCatalog.selected(client.player) != SpellCatalog.HEALING) return;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] OWNER_SELECTION_ACK Healing: server receipt and received owner attachment agree");
                tabAttempts = 0; step(5);
            }
            case 5 -> {
                MagicJournalScreen screen = journal(client);
                if (!focusAt(screen, screen.spellBounds(SpellCatalog.WATER_BOLT))) { tab(screen); return; }
                screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                require(screen.browsedSpell() == SpellCatalog.WATER_BOLT, "Tab/Enter activates the water row"); tabAttempts = 0; step(6);
            }
            case 6 -> {
                MagicJournalScreen screen = journal(client);
                if (!focusAt(screen, screen.selectionBounds())) { tab(screen); return; }
                screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0); step(7);
            }
            case 7 -> {
                if (receipt.selected() != SpellCatalog.WATER_BOLT || SpellCatalog.selected(client.player) != SpellCatalog.WATER_BOLT || phaseTicks < 6) return;
                require(receipt.waterPractice() == 7 && receipt.healingPractice() == 3, "Selection retains real independent practice"); check();
            }
            default -> throw new IllegalStateException("Subset phase " + phase);
        }
    }
    private static void waterCancel(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> { if (phaseTicks >= 8 && client.screen == null) { freshUse(client); step(1); } }
            case 1 -> {
                if (!receipt.using() || receipt.useTicks() < SpellCatalog.WATER_BOLT.chantTicks(client.player) + MagicConfig.WATER_BOLT_CHARGE_TICKS.getAsInt()) return;
                require(receipt.active() == SpellCatalog.WATER_BOLT, "Actual vanilla use has a water lock");
                useDown = false; pressJournal(); step(2);
            }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen) || phaseTicks < 10) return;
                require(!receipt.using() && receipt.active() == null && receipt.spawned() == 0, "K wins the ready same-tick release without a projectile"); check();
            }
            default -> throw new IllegalStateException("Water cancel phase " + phase);
        }
    }
    private static void heldUse(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> { if (phaseTicks >= 8 && client.screen == null) { freshUse(client); step(1); } }
            case 1 -> {
                if (!receipt.using() || receipt.useTicks() < SpellCatalog.WATER_BOLT.chantTicks(client.player) + 4) return;
                pressJournal(); step(2); // Deliberately retain the logical use hold across the GUI.
            }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                rawUse(client); require(JournalInput.waitingForFreshUse(), "A raw press inside the GUI cannot unlock use");
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                require(client.screen == null, "Escape closes the actual journal");
                rawUse(client); require(JournalInput.waitingForFreshUse(), "Same close-event raw press cannot unlock use");
                step(3);
            }
            case 3 -> {
                require(!receipt.using() && !client.player.isUsingItem(), "Held use after GUI must not restart casting");
                require(JournalInput.waitingForFreshUse(), "GUI-synthesized releases do not remove the raw-press latch");
                if (phaseTicks < 45) return;
                useDown = false; step(4);
            }
            case 4 -> {
                if (phaseTicks < 3) return;
                require(JournalInput.waitingForFreshUse(), "Logical release alone is insufficient");
                freshUse(client); require(!JournalInput.waitingForFreshUse(), "Fresh in-game raw use press unlocks after complete ticks"); step(5);
            }
            case 5 -> {
                if (!receipt.using() || receipt.useTicks() < 2 || !client.player.isUsingItem()) return;
                require(receipt.useTicks() < SpellCatalog.WATER_BOLT.chantTicks(client.player), "Fresh chant starts independently");
                useDown = false; step(6);
            }
            case 6 -> { if (phaseTicks >= 8) check(); }
            default -> throw new IllegalStateException("Held-use phase " + phase);
        }
    }
    private static void dismissCancel(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (phaseTicks < 8 || receipt.selected() != SpellCatalog.EARTH_WALL || SpellCatalog.selected(client.player) != SpellCatalog.EARTH_WALL
                        || receipt.wallRemaining() != 6) return;
                rawMapping(SpellKeys.DISMISS); dismissDown = true; step(1);
            }
            case 1 -> {
                if (!receipt.dismissActive() || receipt.dismissTicks() < MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt() + 3) return;
                pressJournal(); step(2);
            }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                require(!receipt.dismissActive() && receipt.wallRemaining() == 6, "Opening cancels a ready H without removing the wall");
                dismissDown = false;
                SpellKeys.DISMISS.setKey(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT)); KeyMapping.resetMapping();
                click(screen, screen.doneBounds());
                NeoForge.EVENT_BUS.post(new InputEvent.MouseButton.Post(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0));
                require(DismissInputState.waitingForRelease(), "Left-click close event cannot unlock rebound H"); step(3);
            }
            case 3 -> {
                require(!receipt.dismissActive() && receipt.wallRemaining() == 6, "No H begins from the close click");
                if (phaseTicks < 4) return;
                rawMapping(SpellKeys.DISMISS); dismissDown = true; step(4);
            }
            case 4 -> {
                if (!receipt.dismissActive() || receipt.dismissTicks() < 2) return;
                require(receipt.dismissTicks() < MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt(), "New H starts a fresh short hold");
                dismissDown = false; step(5);
            }
            case 5 -> {
                if (phaseTicks < 8 || receipt.dismissActive()) return;
                SpellKeys.DISMISS.setKey(originalDismissKey); KeyMapping.resetMapping(); check();
            }
            default -> throw new IllegalStateException("Dismiss phase " + phase);
        }
    }
    private static void simultaneous(Minecraft client, JournalProbe.Receipt receipt) {
        if (phase == 0) {
            if (phaseTicks < 8 || client.screen != null) return;
            KeyMapping.click(SpellKeys.CYCLE.getKey()); pressJournal(); step(1);
        } else if (phase == 1) {
            if (!(client.screen instanceof MagicJournalScreen) || phaseTicks < 10) return;
            require(receipt.selected() == SpellCatalog.WATER_BOLT && SpellCatalog.selected(client.player) == SpellCatalog.WATER_BOLT,
                    "K drains same-tick G before any selection"); check();
        }
    }
    private static void otherScreen(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> { if (phaseTicks >= 8 && client.screen == null) { KeyMapping.click(client.options.keyInventory.getKey()); step(1); } }
            case 1 -> {
                if (!(client.screen instanceof InventoryScreen)) return;
                pressJournal(); step(2);
            }
            case 2 -> {
                if (phaseTicks < 10) return;
                require(client.screen instanceof InventoryScreen, "K cannot replace another screen");
                closeScreen(client); step(3);
            }
            case 3 -> { if (phaseTicks >= 6) check(); }
            default -> throw new IllegalStateException("Focus phase " + phase);
        }
    }
    private static void rebound(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (phaseTicks < 8 || client.screen != null) return;
                SpellKeys.JOURNAL.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_J));
                client.options.keyUse.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V)); KeyMapping.resetMapping();
                KeyMapping.click(SpellKeys.JOURNAL.getKey()); step(1);
            }
            case 1 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 6) return;
                require(SpellKeys.JOURNAL.matches(GLFW.GLFW_KEY_J, 0), "Journal respects rebound J");
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                rawUse(client); require(JournalInput.waitingForFreshUse(), "Close-event keyboard use stays latched"); step(2);
            }
            case 2 -> {
                if (phaseTicks < 4) return;
                freshUse(client); require(!JournalInput.waitingForFreshUse(), "Rebound V raw key press unlocks"); step(3);
            }
            case 3 -> {
                if (!receipt.using() || receipt.useTicks() < 2) return;
                require(receipt.useTicks() < SpellCatalog.WATER_BOLT.chantTicks(client.player), "Keyboard use starts a new independent chant");
                useDown = false; step(4);
            }
            case 4 -> {
                if (phaseTicks < 8) return;
                SpellKeys.JOURNAL.setKey(originalJournalKey); client.options.keyUse.setKey(originalUseKey); KeyMapping.resetMapping(); check();
            }
            default -> throw new IllegalStateException("Rebind phase " + phase);
        }
    }
    private static void study(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (phaseTicks < 8 || !client.player.getMainHandItem().is(ModContent.AIR_TOME.get()) || SpellCatalog.AIR_BURST.knows(client.player)) return;
                freshUse(client); step(1);
            }
            case 1 -> {
                if (!receipt.using() || receipt.useTicks() < 5) return;
                require(receipt.useTicks() < MagicConfig.STUDY_TICKS.getAsInt(), "Study has not completed before K");
                useDown = false; pressJournal(); step(2);
            }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen) || phaseTicks < MagicConfig.STUDY_TICKS.getAsInt() + 4) return;
                require(!receipt.using() && !SpellCatalog.AIR_BURST.knows(client.player), "Journal cancels actual tome study without teaching later"); check();
            }
            default -> throw new IllegalStateException("Study phase " + phase);
        }
    }
    /** Three actual viewport sizes, both languages; the 854x480 case uses the real scale 2 limit. */
    private static void navigation(Minecraft client, JournalProbe.Receipt receipt) {
        switch (phase) {
            case 0 -> {
                if (phaseTicks < 8 || !SpellCatalog.MIRE.knows(client.player)
                        || SpellCatalog.MIRE.practiceCount(client.player) != 5) return;
                closeScreen(client);
                int width = navigationPass < 2 ? 854 : 1280;
                int height = navigationPass < 2 ? 480 : 720;
                client.getWindow().setWindowed(width, height);
                changeLanguage(client, navigationPass % 2 == 0 ? "zh_cn" : "en_us");
                step(1);
            }
            case 1 -> {
                int width = navigationPass < 2 ? 854 : 1280;
                int height = navigationPass < 2 ? 480 : 720;
                if (phaseTicks < 8 || client.getWindow().getWidth() != width || client.getWindow().getHeight() != height) return;
                // Set scale only after the framebuffer resize: the options validator uses the current size.
                client.options.guiScale().set(navigationPass >= 2 && navigationPass < 4 ? 3 : 2);
                client.resizeDisplay();
                step(11);
            }
            case 11 -> {
                if (phaseTicks < 8) return;
                assertViewport(client);
                pressJournal();
                step(2);
            }
            case 2 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                assertViewport(client);
                navigationChecks(client, screen);
                require(SpellCatalog.selected(client.player) == SpellCatalog.WATER_BOLT,
                        "Wheel, browsing, focus and disabled unknown selection never replace the server-selected spell");
                step(3);
            }
            case 3 -> {
                if (phaseTicks < 8) return;
                MagicJournalScreen screen = journal(client);
                require(receipt.selected() == SpellCatalog.WATER_BOLT && SpellCatalog.selected(client.player) == SpellCatalog.WATER_BOLT,
                        "Server and received attachment remain unchanged after clipped clicks and unknown selection");
                require(screen.browsedSpell() == SpellCatalog.MIRE && widget(screen, screen.selectionBounds()).active,
                        "Visible learned seventh row offers real selection");
                navigationImageTarget = saved.get() + 1;
                capture(client, "mire_" + viewportName() + "_" + client.getLanguageManager().getSelected());
                click(screen, screen.selectionBounds());
                step(4);
            }
            case 4 -> {
                if (phaseTicks < 6 || receipt.selected() != SpellCatalog.MIRE || SpellCatalog.selected(client.player) != SpellCatalog.MIRE) return;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] MIRE_OWNER_SELECTION_ACK viewport={} language={} server+owner=true",
                        viewportName(), client.getLanguageManager().getSelected());
                MagicJournalScreen screen = journal(client);
                browseRow(screen, SpellCatalog.WATER_BOLT);
                int attempts = 0;
                while (!focusAt(screen, screen.selectionBounds())) {
                    require(++attempts <= SpellCatalog.values().length + 2, "Forward Tab reaches the real selection button");
                    screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
                }
                screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                step(5);
            }
            case 5 -> {
                if (phaseTicks < 6 || saved.get() < navigationImageTarget || receipt.selected() != SpellCatalog.WATER_BOLT
                        || SpellCatalog.selected(client.player) != SpellCatalog.WATER_BOLT) return;
                require(SpellCatalog.MIRE.practiceCount(client.player) == 5 && receipt.mana() == 100 && receipt.spawned() == 0,
                        "Bilingual resizing, focus and actual selection retain practice and cost no mana");
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] VIEWPORT_PASS {} language={} pixels={}x{} actualScale={} GUI={}x{}; reverse focus path checked; native Shift+Tab pending",
                        viewportName(), client.getLanguageManager().getSelected(), client.getWindow().getWidth(),
                        client.getWindow().getHeight(), client.getWindow().getGuiScale(),
                        client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
                closeScreen(client);
                if (++navigationPass < 6) step(0);
                else step(6);
            }
            case 6 -> {
                if (phaseTicks < 8 || client.screen != null) return;
                pressJournal();
                step(7);
            }
            case 7 -> {
                if (!(client.screen instanceof MagicJournalScreen screen) || phaseTicks < 8) return;
                click(screen, screen.courseRecordsBounds());
                step(8);
            }
            case 8 -> {
                if (!(client.screen instanceof CourseRecordsScreen screen) || !screen.hasResponse() || phaseTicks < 8) return;
                require(screen.courseBounds(CourseRecordSnapshot.Course.WATER_BOLT).width() > 0,
                        "Course Records exposes a real water row");
                require(screen.courseBounds(CourseRecordSnapshot.Course.SWORD).width() > 0,
                        "Course Records exposes a real sword row");
                navigationImageTarget = saved.get() + 1;
                courseSecondCaptureRequested = false;
                capture(client, "course_records_" + client.getLanguageManager().getSelected());
                click(screen, screen.courseBounds(CourseRecordSnapshot.Course.SWORD));
                changeLanguage(client, "zh_cn");
                step(9);
            }
            case 9 -> {
                if (!(client.screen instanceof CourseRecordsScreen screen) || !screen.hasResponse()
                        || languageReload != null || saved.get() < navigationImageTarget) return;
                if (!courseSecondCaptureRequested) {
                    courseSecondCaptureRequested = true;
                    navigationImageTarget = saved.get() + 1;
                    capture(client, "course_records_sword_" + client.getLanguageManager().getSelected());
                    return;
                }
                if (saved.get() < navigationImageTarget) return;
                require(screen.detailScroll() >= 0, "Course Records detail column is readable");
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] COURSE_RECORDS_PASS bilingual=true response=true water+sword rows=true");
                closeScreen(client);
                courseSecondCaptureRequested = false;
                step(20);
            }
            case 20 -> {
                // Returning from the journal can leave a mouse delta. Set the scene view before waiting for new frames.
                client.player.setYRot(180); client.player.setYHeadRot(180); client.player.setXRot(0);
                if (phaseTicks < 8 || client.screen != null) return;
                // Development-only language for the tree scene; the reload resets phaseTicks and this phase waits again.
                String language = s02Language();
                if (!client.getLanguageManager().getSelected().equals(language)) { changeLanguage(client, language); return; }
                // Use the production client entity-interaction packet so the NPC branch cannot be confused with staff casting.
                var sylphiette = client.level.getEntitiesOfClass(SylphietteEntity.class,
                        client.player.getBoundingBox().inflate(5)).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("S02 preview Sylphiette was not tracked"));
                // Only lines and the toast that follow this real interaction count; the server cleared the advancement at setup.
                lessonWatching = true; lessonLines = 0; observeAt = tryAt = finishAt = toastAt = turnedAt = -1; lessonProblem = null;
                client.gameMode.interact(client.player, sylphiette, InteractionHand.MAIN_HAND);
                step(21);
            }
            case 21 -> {
                // Her invitation must be in the observe frame: wait until a frame has been drawn after it arrived.
                client.player.setYRot(180); client.player.setYHeadRot(180); client.player.setXRot(0);
                require(observeAt >= 0 || phaseTicks <= 40, "Sylphiette's lesson invitation did not follow the real interaction");
                if (observeAt < 0 || totalTicks - observeAt < 2) return;
                navigationImageTarget = saved.get() + 1;
                capture(client, "s02_tree_observe_" + client.getLanguageManager().getSelected());
                // Turn away from the NPC before the demonstration so the same use key becomes a normal Water Ball cast.
                client.player.setYRot(0); client.player.setYHeadRot(0);
                freshUse(client);
                step(22);
            }
            case 22 -> {
                if (phaseTicks < MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt() + 5) return;
                useDown = false;
                navigationImageTarget = saved.get() + 1;
                capture(client, "s02_water_release_" + client.getLanguageManager().getSelected());
                step(23);
            }
            case 23 -> {
                // Her try line proves the server has taken the real cast, so turning back can no longer re-aim the bolt or
                // turn the use key into an interaction. Turn long before any frame: a capture reads the last drawn frame.
                require(tryAt >= 0 || phaseTicks <= 40, "Sylphiette's try line did not follow the real Water Ball release");
                if (tryAt < 0 || languageReload != null || saved.get() < navigationImageTarget) return;
                client.player.setYRot(180); client.player.setYHeadRot(180); client.player.setXRot(0);
                turnedAt = totalTicks;
                step(24);
            }
            case 24 -> {
                // Her attempt starts ATTEMPT_DELAY_TICKS after the try line; RESULT_TICK is the little splash, and three
                // more ticks let its particles arrive and a frame draw them.
                if (totalTicks < tryAt + SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.RESULT_TICK + 3
                        || totalTicks - turnedAt < 2) return;
                navigationImageTarget = saved.get() + 1;
                capture(client, "s02_tree_attempt_" + client.getLanguageManager().getSelected());
                step(25);
            }
            case 25 -> {
                // The closing line and the toast come at attempt FINISH_TICK; let the toast slide in before the last frame.
                int due = tryAt + SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.FINISH_TICK;
                require(finishAt >= 0 && toastAt >= 0 || totalTicks <= due + 30, "Sylphiette's closing line or the lesson toast did not arrive");
                // Long English titles use vanilla's second toast phase; capture after it switches from Goal Reached.
                int toastCaptureDelay = client.getLanguageManager().getSelected().equals("en_us") ? 45 : 15;
                if (finishAt < 0 || toastAt < 0 || totalTicks - Math.max(finishAt, toastAt) < toastCaptureDelay || saved.get() < navigationImageTarget) return;
                navigationImageTarget = saved.get() + 1;
                capture(client, "s02_tree_finish_" + client.getLanguageManager().getSelected());
                step(26);
            }
            case 26 -> {
                if (saved.get() < navigationImageTarget) return;
                int expected = SylphietteFirstLesson.ATTEMPT_DELAY_TICKS + SylphietteLessonVisuals.FINISH_TICK;
                require(lessonProblem == null, String.valueOf(lessonProblem));
                require(lessonLines == 3 && observeAt < tryAt && tryAt < finishAt, "Lesson lines arrive once each, in order: observe, try, finish");
                require(Math.abs(finishAt - tryAt - expected) <= 5, "Closing line came " + (finishAt - tryAt) + " ticks after the try line, expected " + expected);
                require(Math.abs(toastAt - finishAt) <= 10, "Lesson toast came " + (toastAt - finishAt) + " ticks from the closing line");
                lessonWatching = false;
                LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] S02_TREE_PASS language={} observe={} try={} finish={} toast={}; frames observe, release, attempt, finish",
                        client.getLanguageManager().getSelected(), observeAt, tryAt, finishAt, toastAt);
                closeScreen(client);
                check();
            }
            default -> throw new IllegalStateException("Navigation phase " + phase);
        }
    }

    private static String viewportName() {
        return navigationPass < 2 ? "854x480_gui2" : navigationPass < 4 ? "1280x720_gui3" : "1280x720_gui2_all_rows";
    }

    private static void assertViewport(Minecraft client) {
        boolean wide = navigationPass >= 4;
        int expectedScale = navigationPass >= 2 && navigationPass < 4 ? 3 : 2;
        int expectedWidth = wide ? 640 : 427, expectedHeight = wide ? 360 : 240;
        require(client.getWindow().getGuiScale() == expectedScale
                && client.getWindow().getGuiScaledWidth() == expectedWidth
                && client.getWindow().getGuiScaledHeight() == expectedHeight,
                "Actual framebuffer/GUI dimensions must match " + viewportName());
    }

    private static void navigationChecks(Minecraft client, MagicJournalScreen screen) {
        require(!Screen.hasShiftDown(), "Release physical Shift before the synthetic forward Tab probe");
        require(SpellCatalog.values().length == 7 && SpellCatalog.MIRE.ordinal() == 6, "Fixture expects the agreed seventh spell");
        boolean compact = navigationPass < 4;
        require((screen.maxNavigationScroll() > 0) == compact, "Only compact viewports need left-column scrolling");
        int previousBottom = Integer.MIN_VALUE;
        for (SpellCatalog spell : SpellCatalog.values()) {
            var row = screen.spellBounds(spell);
            require(row.height() >= 23 && row.y() > previousBottom, "Fixed readable rows retain their separation");
            previousBottom = row.y() + row.height();
        }
        var nav = screen.navigationBounds();
        require(nav.y() + nav.height() < screen.selectionBounds().y(), "Navigation clipping ends above the footer");
        browseRow(screen, SpellCatalog.WATER_SHIELD);
        require(!widget(screen, screen.selectionBounds()).active, "Unknown shield is browseable but unselectable in the mire fixture");
        click(screen, screen.selectionBounds());
        require(screen.browsedSpell() == SpellCatalog.WATER_SHIELD, "Disabled selection does not select or change the browsed row");

        browseRow(screen, SpellCatalog.MIRE);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        require(screen.detailScroll() == 0 && screen.maxDetailScroll() > 0, "Mire details provide a real independently scrollable right column");
        int beforeNav = screen.navigationScroll();
        wheel(screen, screen.detailBounds(), -2);
        require(screen.detailScroll() > 0 && screen.navigationScroll() == beforeNav, "Right wheel changes only details");
        int beforeDetail = screen.detailScroll();
        wheel(screen, nav, 100);
        require(screen.navigationScroll() == 0 && screen.detailScroll() == beforeDetail, "Left wheel reaches first row without moving details");
        int rejected = rejectClippedClicks(client, screen);
        SpellCatalog beforeBrowse = screen.browsedSpell();
        wheel(screen, nav, -100);
        require(screen.navigationScroll() == screen.maxNavigationScroll() && fullyVisible(screen, SpellCatalog.MIRE)
                && screen.detailScroll() == beforeDetail && screen.browsedSpell() == beforeBrowse,
                "Left wheel exposes seventh row without browsing, selecting or moving details");
        rejected += rejectClippedClicks(client, screen);
        if (compact) require(rejected >= 2, "Both clipped edges were actually exercised");

        int stillNav = screen.navigationScroll(), stillDetail = screen.detailScroll();
        screen.mouseScrolled(nav.x() - 2, nav.y() + 2, 0, -3);
        require(screen.navigationScroll() == stillNav && screen.detailScroll() == stillDetail, "Wheel outside both columns leaves both offsets unchanged");
        browseRow(screen, SpellCatalog.WATER_BOLT);
        for (int i = 1; i < SpellCatalog.values().length; i++) {
            screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
            requireFocusedVisible(screen, SpellCatalog.values()[i], "Forward Tab");
        }
        require(screen.browsedSpell() == SpellCatalog.WATER_BOLT, "Focus navigation alone cannot browse or select");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        require(screen.browsedSpell() == SpellCatalog.MIRE, "Enter browses the focused seventh row");
        for (int i = SpellCatalog.values().length - 2; i >= 0; i--) {
            // Screen.hasShiftDown polls GLFW native state; do not fake Shift+Tab by passing a modifier integer.
            ComponentPath path = screen.nextFocusPath(new FocusNavigationEvent.TabNavigation(false));
            require(path != null, "Public reverse Tab focus path exists");
            screen.clearFocus();
            path.applyFocus(true);
            requireFocusedVisible(screen, SpellCatalog.values()[i], "Reverse focus path (not native Shift+Tab)");
        }
        require(screen.navigationScroll() == 0, "Reverse focus path returns the first row to view");
        for (int i = 1; i < SpellCatalog.values().length; i++) {
            screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
            requireFocusedVisible(screen, SpellCatalog.values()[i], "Down arrow");
        }
        for (int i = SpellCatalog.values().length - 2; i >= 0; i--) {
            screen.keyPressed(GLFW.GLFW_KEY_UP, 0, 0);
            requireFocusedVisible(screen, SpellCatalog.values()[i], "Up arrow");
        }
        require(screen.navigationScroll() == 0, "Arrow traversal returns the first row to view");
        browseRow(screen, SpellCatalog.MIRE);
    }

    private static int rejectClippedClicks(Minecraft client, MagicJournalScreen screen) {
        int rejected = 0;
        var nav = screen.navigationBounds();
        for (SpellCatalog spell : SpellCatalog.values()) {
            var row = screen.spellBounds(spell);
            double y;
            if (row.y() < nav.y()) y = Math.min(row.y() + row.height() - 0.5, nav.y() - 0.5);
            else if (row.y() + row.height() > nav.y() + nav.height()) y = Math.max(row.y() + 0.5, nav.y() + nav.height() + 0.5);
            else continue;
            SpellCatalog before = screen.browsedSpell();
            var focused = screen.getFocused();
            double x = row.x() + row.width() / 2D;
            boolean consumed = screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            require(!consumed && client.screen == screen && screen.browsedSpell() == before && screen.getFocused() == focused,
                    "Clipped " + spell + " cannot be clicked through the header/footer or steal focus");
            rejected++;
        }
        return rejected;
    }

    private static boolean fullyVisible(MagicJournalScreen screen, SpellCatalog spell) {
        var row = screen.spellBounds(spell); var nav = screen.navigationBounds();
        return row.y() >= nav.y() && row.y() + row.height() <= nav.y() + nav.height();
    }
    private static void requireFocusedVisible(MagicJournalScreen screen, SpellCatalog spell, String action) {
        require(focusAt(screen, screen.spellBounds(spell)) && fullyVisible(screen, spell),
                action + " must reveal and focus " + spell);
    }
    private static void wheel(MagicJournalScreen screen, MagicJournalScreen.Bounds bounds, double amount) {
        screen.mouseScrolled(bounds.x() + bounds.width() / 2D, bounds.y() + bounds.height() / 2D, 0, amount);
    }
    private static void browseRow(MagicJournalScreen screen, SpellCatalog spell) {
        for (int attempt = 0; attempt < SpellCatalog.values().length + 2; attempt++) {
            if (fullyVisible(screen, spell)) { click(screen, screen.spellBounds(spell)); return; }
            var row = screen.spellBounds(spell); var nav = screen.navigationBounds();
            wheel(screen, nav, row.y() < nav.y() ? 1 : -1);
        }
        throw new IllegalStateException("Real wheel input could not reveal " + spell);
    }

    private static void rawUse(Minecraft client) { rawMapping(client.options.keyUse); }
    private static void rawMapping(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE)
            NeoForge.EVENT_BUS.post(new InputEvent.MouseButton.Post(key.getValue(), GLFW.GLFW_PRESS, 0));
        else NeoForge.EVENT_BUS.post(new InputEvent.Key(key.getValue(), 0, GLFW.GLFW_PRESS, 0));
    }
    private static void freshUse(Minecraft client) {
        rawUse(client); KeyMapping.click(client.options.keyUse.getKey()); useDown = true;
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] SYNTHETIC_RAW_USE case={} key={}: normal event bus and logical KeyMapping; no latch mutation", caseId, client.options.keyUse.getKey().getName());
    }
    private static void pressJournal() { KeyMapping.click(SpellKeys.JOURNAL.getKey()); }
    private static MagicJournalScreen journal(Minecraft client) {
        if (client.screen instanceof MagicJournalScreen screen) return screen;
        throw new IllegalStateException("Expected actual MagicJournalScreen, got " + client.screen);
    }
    private static AbstractWidget widget(Screen screen, MagicJournalScreen.Bounds bounds) {
        return screen.children().stream().filter(child -> child instanceof AbstractWidget).map(child -> (AbstractWidget)child)
                .filter(widget -> widget.getX() == bounds.x() && widget.getY() == bounds.y()
                        && widget.getWidth() == bounds.width() && widget.getHeight() == bounds.height()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Read-only bounds did not identify a real widget"));
    }
    private static void click(Screen screen, MagicJournalScreen.Bounds bounds) {
        screen.mouseClicked(bounds.x() + bounds.width() / 2D, bounds.y() + bounds.height() / 2D, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseReleased(bounds.x() + bounds.width() / 2D, bounds.y() + bounds.height() / 2D, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void click(Screen screen, CourseRecordsScreen.Bounds bounds) {
        screen.mouseClicked(bounds.x() + bounds.width() / 2D, bounds.y() + bounds.height() / 2D, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseReleased(bounds.x() + bounds.width() / 2D, bounds.y() + bounds.height() / 2D, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static boolean focusAt(Screen screen, MagicJournalScreen.Bounds bounds) { return screen.getFocused() == widget(screen, bounds); }
    private static void tab(Screen screen) {
        if (++tabAttempts > 14) throw new IllegalStateException("Tab navigation did not reach the actual target widget");
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
    }
    private static void closeScreen(Minecraft client) { if (client.screen != null) client.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); }
    private static void check() {
        int index = caseId; MinecraftServer target = server; target.execute(() -> JournalProbe.check(target, index)); step(900);
    }
    private static void step(int value) { phase = value; phaseTicks = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void captureOptions(Minecraft client) {
        originalLanguage = client.getLanguageManager().getSelected(); originalGuiScale = client.options.guiScale().get();
        require(!client.getWindow().isFullscreen(), "Run this private viewport fixture in a window");
        originalWindowWidth = client.getWindow().getScreenWidth(); originalWindowHeight = client.getWindow().getScreenHeight();
        originalForceUnicode = client.options.forceUnicodeFont().get();
        originalHideGui = client.options.hideGui; originalToggle = client.options.toggleCrouch().get();
        originalJournalKey = SpellKeys.JOURNAL.getKey(); originalUseKey = client.options.keyUse.getKey(); originalDismissKey = SpellKeys.DISMISS.getKey();
        optionsCaptured = true; client.options.hideGui = false; client.options.toggleCrouch().set(false); client.options.keyShift.setDown(false);
        client.options.forceUnicodeFont().set(false);
        client.options.guiScale().set(2); client.resizeDisplay();
        LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] BEGIN actual Screen input, {}x{} pixels, GUI {}x{}; not human input",
                client.getWindow().getWidth(), client.getWindow().getHeight(), client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
    }
    private static void restoreOptions(Minecraft client) {
        if (!optionsCaptured) return;
        client.options.keyUse.setDown(false); SpellKeys.DISMISS.setDown(false); client.options.keyShift.setDown(false);
        SpellKeys.JOURNAL.setKey(originalJournalKey); client.options.keyUse.setKey(originalUseKey); SpellKeys.DISMISS.setKey(originalDismissKey);
        KeyMapping.resetMapping(); client.options.hideGui = originalHideGui; client.options.toggleCrouch().set(originalToggle);
        client.getWindow().setWindowed(originalWindowWidth, originalWindowHeight);
        client.options.forceUnicodeFont().set(originalForceUnicode);
        client.options.guiScale().set(originalGuiScale); client.resizeDisplay();
        client.options.languageCode = originalLanguage; client.getLanguageManager().setSelected(originalLanguage);
    }
    private static void changeLanguage(Minecraft client, String language) {
        if (client.getLanguageManager().getSelected().equals(language)) return;
        client.options.languageCode = language; client.getLanguageManager().setSelected(language);
        languageReload = client.reloadResourcePacks();
    }
    private static void capture(Minecraft client, String view) {
        if (caseId == 9) assertViewport(client);
        else require(client.getWindow().getGuiScaledWidth() == 427 && client.getWindow().getGuiScaledHeight() == 240, "Legacy journal capture must use actual GUI427x240");
        String name = "journal_probe_m01_" + view + "_" + System.currentTimeMillis() + ".png";
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("screenshot.success")) {
                saved.incrementAndGet(); LogUtils.getLogger().info("[JR_JOURNAL_PROBE_CLIENT] SCREENSHOT_SAVED {}", name);
            } else screenshotFailure.set("Screenshot failed: " + message.getString());
        });
    }
    private static void fail(Minecraft client, String reason) { finish(client, false, reason); }
    private static void finish(Minecraft client, boolean passed, String reason) {
        if (finishRequested || phase == 999) return;
        useDown = dismissDown = false; closeScreen(client);
        clientPassed = passed; finishReason = reason;
        restoreOptions(client); languageReload = client.reloadResourcePacks(); step(999);
    }
    private static void finishWhenRestoredLanguage(Minecraft client) {
        if (languageReload != null && (!languageReload.isDone() || client.getOverlay() != null)) return;
        if (languageReload != null) { languageReload.join(); languageReload = null; }
        finishRequested = true;
        MinecraftServer target = server; target.execute(() -> JournalProbe.finish(target, clientPassed, finishReason));
    }
    private JournalProbeClient() {}
}

