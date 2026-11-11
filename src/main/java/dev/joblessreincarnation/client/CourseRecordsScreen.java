package dev.joblessreincarnation.client;

import dev.joblessreincarnation.network.CourseRecordsRequestPayload;
import dev.joblessreincarnation.network.CourseRecordsResponsePayload;
import dev.joblessreincarnation.training.CourseRecordSnapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Read-only views of the three existing lessons. The server is authoritative; this screen never
 * starts, recognizes, claims, or duplicates a course.
 */
public final class CourseRecordsScreen extends Screen {
    private static final String KEY = "screen.jobless_reincarnation.journal.courses.";
    private static final int INK = 0x493D2F;
    private static final int MUTED = 0x77654E;
    private static final int ACCENT = 0x36798D;
    private static final int GREEN = 0x527854;
    private static final int ROW_HEIGHT = 34;
    private static final int ROW_GAP = 4;
    private static int nextNonce;
    private static CourseRecordsScreen activeScreen;

    private final LocalPlayer owner;
    private final ClientLevel world;
    private final EnumMap<CourseRecordSnapshot.Course, CourseRow> rows = new EnumMap<>(CourseRecordSnapshot.Course.class);
    private final List<DetailLine> detailLines = new ArrayList<>();
    private CourseRecordSnapshot.Course browsed = CourseRecordSnapshot.Course.WATER_BOLT;
    private CourseRecordsResponsePayload response;
    private int requestNonce;
    private int pendingTicks;
    private int left, top, panelWidth, panelHeight, bodyTop, bodyBottom, navX, navWidth, detailX, detailWidth;
    private int navScroll, maximumNavScroll, scroll, maximumScroll;
    private Button refreshButton, backButton, doneButton;

    public CourseRecordsScreen() {
        super(text("title"));
        owner = Minecraft.getInstance().player;
        world = Minecraft.getInstance().level;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(440, width - 16);
        panelHeight = Math.min(286, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        bodyTop = top + 42;
        bodyBottom = top + panelHeight - 50;
        navX = left + 12;
        navWidth = Math.min(132, Math.max(100, panelWidth / 3 - 6));
        detailX = left + navWidth + 26;
        detailWidth = panelWidth - navWidth - 38;
        maximumNavScroll = Math.max(0, CourseRecordSnapshot.Course.values().length * ROW_HEIGHT
                + (CourseRecordSnapshot.Course.values().length - 1) * ROW_GAP - (bodyBottom - bodyTop));
        navScroll = Math.clamp(navScroll, 0, maximumNavScroll);
        rows.clear();
        for (CourseRecordSnapshot.Course course : CourseRecordSnapshot.Course.values()) {
            CourseRow row = new CourseRow(course, navX,
                    bodyTop + course.ordinal() * (ROW_HEIGHT + ROW_GAP) - navScroll, navWidth, ROW_HEIGHT);
            rows.put(course, addWidget(row));
        }
        int buttonY = top + panelHeight - 42;
        backButton = addRenderableWidget(Button.builder(text("back"), button -> openSpells())
                .bounds(left + 12, buttonY, 92, 20).build());
        refreshButton = addRenderableWidget(Button.builder(text("refresh"), button -> requestSnapshot())
                .bounds(left + 110, buttonY, 92, 20).build());
        doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + panelWidth - 76, buttonY, 64, 20).build());
        activeScreen = this;
        requestSnapshot();
        setInitialFocus(rows.get(browsed));
    }

    public static void receive(CourseRecordsResponsePayload payload) {
        CourseRecordsScreen screen = activeScreen;
        if (screen == null || screen.minecraft == null || screen.minecraft.screen != screen
                || screen.requestNonce != payload.nonce() || !screen.validOwner()) return;
        screen.response = payload;
        screen.pendingTicks = 0;
        screen.rebuildDetails();
        for (CourseRow row : screen.rows.values()) row.refresh();
    }

    private void requestSnapshot() {
        if (!validOwner()) return;
        requestNonce = ++nextNonce;
        if (requestNonce == 0) requestNonce = ++nextNonce;
        response = null;
        pendingTicks = 0;
        rebuildDetails();
        for (CourseRow row : rows.values()) row.refresh();
        PacketDistributor.sendToServer(new CourseRecordsRequestPayload(requestNonce));
    }

    private boolean validOwner() {
        return minecraft != null && owner != null && minecraft.player == owner && minecraft.level == world
                && owner.isAlive() && !owner.isSpectator();
    }

    private boolean ready() { return response != null; }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        if (!validOwner()) {
            onClose();
            return;
        }
        if (!ready() && ++pendingTicks > 100) {
            pendingTicks = 101;
            rebuildDetails();
        }
    }

    @Override
    public void removed() {
        if (activeScreen == this) activeScreen = null;
        if (minecraft == null || !(minecraft.screen instanceof MagicJournalScreen)) JournalInput.closing();
        super.removed();
    }

    private void openSpells() {
        if (minecraft != null) minecraft.setScreen(new MagicJournalScreen());
    }

    private void browse(CourseRecordSnapshot.Course course) {
        if (!ready()) return;
        browsed = course;
        scroll = 0;
        rebuildDetails();
    }

    @Override
    public void setFocused(GuiEventListener listener) {
        super.setFocused(listener);
        if (listener instanceof CourseRow row) ensureNavVisible(row);
    }

    private void setNavScroll(int value) {
        navScroll = Math.clamp(value, 0, maximumNavScroll);
        for (CourseRow row : rows.values())
            row.setY(bodyTop + row.course.ordinal() * (ROW_HEIGHT + ROW_GAP) - navScroll);
    }

    private void ensureNavVisible(CourseRow row) {
        if (row == null) return;
        int rowTop = row.course.ordinal() * (ROW_HEIGHT + ROW_GAP);
        int viewportHeight = bodyBottom - bodyTop;
        if (rowTop < navScroll) setNavScroll(rowTop);
        else if (rowTop + ROW_HEIGHT > navScroll + viewportHeight)
            setNavScroll(rowTop + ROW_HEIGHT - viewportHeight);
    }

    private boolean inNavigation(double mouseX, double mouseY) {
        return mouseX >= navX && mouseX < navX + navWidth + 6 && mouseY >= bodyTop && mouseY < bodyBottom;
    }

    private CourseRecordSnapshot current() {
        if (response == null) return null;
        return switch (browsed) {
            case WATER_BOLT -> response.water();
            case FIRE_BALL -> response.fire();
            case SWORD -> response.sword();
        };
    }

    private void rebuildDetails() {
        detailLines.clear();
        int y = 0;
        if (!validOwner()) y = addDetail(text("failed"), MUTED, y);
        else if (!ready()) y = addDetail(text(pendingTicks > 100 ? "failed" : "loading"), MUTED, y);
        else {
            CourseRecordSnapshot record = current();
            y = addDetail(courseTitle(record.course()), INK, y) + 4;
            y = addDetail(text("status", statusComponent(record)), ACCENT, y) + 6;
            switch (record.course()) {
                case WATER_BOLT -> y = waterDetails(record, y);
                case FIRE_BALL -> y = fireDetails(record, y);
                case SWORD -> y = swordDetails(record, y);
            }
            y += 5;
            y = addDetail(text("note"), MUTED, y);
        }
        maximumScroll = Math.max(0, y - (bodyBottom - bodyTop));
        scroll = Math.clamp(scroll, 0, maximumScroll);
    }

    private int waterDetails(CourseRecordSnapshot record, int y) {
        y = fact(y, "learned", record.stage() != 0);
        y = factCount(y, "casts", record.primary(), record.primaryTarget());
        y = factCount(y, "targets", record.secondary(), record.secondaryTarget());
        y = fact(y, "shaped", record.flag());
        y = fact(y, "card", record.completed());
        return addDetail(text("next." + waterNext(record)), MUTED, y + 4);
    }

    private int fireDetails(CourseRecordSnapshot record, int y) {
        y = fact(y, "learned", record.stage() != 0);
        y = factCount(y, "casts", record.primary(), record.primaryTarget());
        y = factCount(y, "targets", record.secondary(), record.secondaryTarget());
        return addDetail(text("next." + fireNext(record)), MUTED, y + 4);
    }

    private int swordDetails(CourseRecordSnapshot record, int y) {
        y = factCount(y, "hits", record.primary(), record.primaryTarget());
        y = fact(y, "recognized", record.completed());
        return addDetail(text("next." + swordNext(record)), MUTED, y + 4);
    }

    private int fact(int y, String key, boolean value) {
        return addDetail(text("fact." + key, text(value ? "yes" : "no")), MUTED, y);
    }

    private int factCount(int y, String key, int value, int target) {
        return addDetail(text("fact." + key, value, target), MUTED, y);
    }

    private String waterNext(CourseRecordSnapshot r) {
        if (r.completed()) return "done";
        if (r.stage() == 0) return "water_learn";
        if (r.primary() < r.primaryTarget()) return "water_cast";
        if (r.secondary() < r.secondaryTarget()) return "water_target";
        if (!r.flag()) return "water_shape";
        return "water_card";
    }

    private String fireNext(CourseRecordSnapshot r) {
        if (r.completed()) return "done";
        if (r.stage() == 0) return "fire_learn";
        if (r.primary() < r.primaryTarget()) return "fire_cast";
        return "fire_target";
    }

    private String swordNext(CourseRecordSnapshot r) {
        if (r.completed()) return "done";
        return switch (r.stage()) {
            case 0 -> "sword_start";
            case 1 -> "sword_practice";
            default -> "sword_recognize";
        };
    }

    private String statusKey(CourseRecordSnapshot r) {
        return switch (r.course()) {
            case WATER_BOLT -> switch (r.stage()) {
                case 0 -> "water_unlearned";
                case 1 -> "water_cast";
                case 2 -> "water_distance";
                case 3 -> "water_shape";
                case 4 -> "water_claim";
                default -> "done";
            };
            case FIRE_BALL -> switch (r.stage()) {
                case 0 -> "fire_unlearned";
                case 1 -> "fire_cast";
                case 2 -> "fire_distance";
                default -> "done";
            };
            case SWORD -> switch (r.stage()) {
                case 0 -> "sword_unstarted";
                case 1 -> "sword_practice";
                case 2 -> "sword_ready";
                default -> "done";
            };
        };
    }

    private Component courseTitle(CourseRecordSnapshot.Course course) {
        return text(course == CourseRecordSnapshot.Course.WATER_BOLT ? "water.title"
                : course == CourseRecordSnapshot.Course.FIRE_BALL ? "fire.title" : "sword.title");
    }

    private Component statusComponent(CourseRecordSnapshot record) {
        return text(statusKey(record));
    }

    private int addDetail(Component component, int color, int y) {
        for (FormattedCharSequence line : font.split(component, Math.max(20, detailWidth - 10))) {
            detailLines.add(new DetailLine(line, y, color));
            y += 11;
        }
        return y;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        rebuildDetails();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(navX, bodyTop, navX + navWidth, bodyBottom);
        for (CourseRow row : rows.values()) row.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        graphics.drawString(font, title, left + 15, top + 15, INK, false);
        Component mode = text("subtitle");
        graphics.drawString(font, mode, left + panelWidth - 15 - font.width(mode), top + 15, MUTED, false);
        graphics.enableScissor(detailX, bodyTop, detailX + detailWidth, bodyBottom);
        for (DetailLine line : detailLines) {
            int y = bodyTop + line.y - scroll;
            if (y + font.lineHeight > bodyTop && y < bodyBottom)
                graphics.drawString(font, line.text, detailX, y, line.color, false);
        }
        graphics.disableScissor();
        drawScrollbar(graphics, navX + navWidth + 3, navScroll, maximumNavScroll);
        drawScrollbar(graphics, detailX + detailWidth - 4, scroll, maximumScroll);
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int value, int maximum) {
        if (maximum <= 0) return;
        int trackHeight = bodyBottom - bodyTop;
        int thumb = Math.max(12, trackHeight * trackHeight / (trackHeight + maximum));
        int thumbY = bodyTop + (trackHeight - thumb) * value / maximum;
        graphics.fill(x, bodyTop, x + 2, bodyBottom, 0xFFCFBC99);
        graphics.fill(x, thumbY, x + 2, thumbY + thumb, 0xFF8C7554);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x850B1018);
        graphics.fill(left + 3, top + 3, left + panelWidth + 3, top + panelHeight + 3, 0x80000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF624735);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + panelHeight - 3, 0xFFA07950);
        graphics.fill(left + 7, top + 7, left + panelWidth - 7, top + panelHeight - 7, 0xFFE5D5B3);
        graphics.fill(left + 10, top + 10, left + panelWidth - 10, top + panelHeight - 10, 0xFFF3E7CA);
        graphics.fill(left + 15, top + 32, left + panelWidth - 15, top + 33, 0xFFBEA780);
        graphics.fill(detailX - 8, bodyTop, detailX - 7, bodyBottom, 0xFFCEBA95);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inNavigation(mouseX, mouseY)) {
            setNavScroll(navScroll - (int) Math.round(scrollY * (ROW_HEIGHT + ROW_GAP)));
            return true;
        }
        if (mouseX >= detailX && mouseX < detailX + detailWidth && mouseY >= bodyTop && mouseY < bodyBottom) {
            scroll = Math.clamp(scroll - (int) Math.round(scrollY * 22), 0, maximumScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            int step = Math.max(11, bodyBottom - bodyTop - 11);
            scroll = Math.clamp(scroll + (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? step : -step), 0, maximumScroll);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END) {
            scroll = keyCode == GLFW.GLFW_KEY_HOME ? 0 : maximumScroll;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public record Bounds(int x, int y, int width, int height) {}
    public Bounds courseBounds(CourseRecordSnapshot.Course course) { return bounds(rows.get(course)); }
    public Bounds refreshBounds() { return bounds(refreshButton); }
    public Bounds backBounds() { return bounds(backButton); }
    public Bounds doneBounds() { return bounds(doneButton); }
    public boolean hasResponse() { return response != null; }
    public int detailScroll() { return scroll; }
    public int maxDetailScroll() { return maximumScroll; }

    private static Bounds bounds(Button button) {
        return new Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    private static Component text(String key, Object... args) { return Component.translatable(KEY + key, args); }
    private record DetailLine(FormattedCharSequence text, int y, int color) {}

    private final class CourseRow extends Button {
        private final CourseRecordSnapshot.Course course;
        private Component stage = text("loading");
        private boolean current;

        CourseRow(CourseRecordSnapshot.Course course, int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), ignored -> browse(course), DEFAULT_NARRATION);
            this.course = course;
            refresh();
        }

        void refresh() {
            active = ready();
            CourseRecordSnapshot record = response == null ? null : switch (course) {
                case WATER_BOLT -> response.water();
                case FIRE_BALL -> response.fire();
                case SWORD -> response.sword();
            };
            stage = record == null ? text(pendingTicks > 100 ? "failed" : "loading")
                    : text("status", statusComponent(record));
            current = course == browsed;
            setMessage(text("row", courseTitle(course), stage));
            setTooltip(Tooltip.create(getMessage()));
        }

        @Override
        protected boolean clicked(double mouseX, double mouseY) {
            return inNavigation(mouseX, mouseY) && super.clicked(mouseX, mouseY);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return inNavigation(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY();
            boolean intersects = y < bodyBottom && y + getHeight() > bodyTop;
            if (!intersects) return;
            int edge = isFocused() ? 0xFF36798D : current ? 0xFF90764E : 0xFFD3BD97;
            graphics.fill(x, y, x + getWidth(), y + getHeight(), edge);
            graphics.fill(x + 1, y + 1, x + getWidth() - 1, y + getHeight() - 1,
                    isHoveredOrFocused() ? 0xFFF4E5C5 : current ? 0xFFF9ECCF : 0xFFE9D9B7);
            if (current) graphics.fill(x + 1, y + 1, x + 3, y + getHeight() - 1, GREEN);
            int textX = x + 8;
            renderScrollingString(graphics, font, courseTitle(course), textX, y + 5,
                    x + getWidth() - 8, y + 16, active ? INK : MUTED);
            renderScrollingString(graphics, font, stage, textX, y + 18,
                    x + getWidth() - 8, y + 29, MUTED);
        }
    }
}
