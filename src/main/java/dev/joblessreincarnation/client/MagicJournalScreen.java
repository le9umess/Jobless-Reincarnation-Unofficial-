package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.network.MoveSpellSlotPayload;
import net.minecraft.ChatFormatting;
import dev.joblessreincarnation.magic.ManaGrowthRules;
import dev.joblessreincarnation.magic.MireEntity;
import dev.joblessreincarnation.magic.MireSpell;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellTraining;
import dev.joblessreincarnation.network.SelectSpellPayload;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.math.BigDecimal;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Personal notes read the owner's synced attachments; browsing never mutates knowledge or selection. */
public final class MagicJournalScreen extends Screen {
    private static final String KEY = "screen.jobless_reincarnation.journal.";
    private static final int INK = 0x493D2F;
    private static final int MUTED = 0x77654E;
    private static final int ACCENT = 0x36798D;
    private static final int GREEN = 0x527854;
    private static final int LINE_HEIGHT = 11;
    /** Keep the name and practice stage readable; excess rows scroll instead of shrinking. */
    private static final int ROW_HEIGHT = 23;
    private static final int ROW_GAP = 3;
    private final LocalPlayer owner;
    private final ClientLevel world;
    private final EnumMap<SpellCatalog, SpellRow> rows = new EnumMap<>(SpellCatalog.class);
    private final List<DetailLine> detailLines = new ArrayList<>();
    private SpellCatalog browsed = SpellCatalog.WATER_BOLT;
    private SpellCatalog pendingSelection;
    private int pendingTicks;
    private boolean receivedKnowledge;
    private int left, top, panelWidth, panelHeight, bodyTop, bodyBottom, detailX, detailWidth;
    private int scroll, maximumScroll, progressY = -1;
    private int navX, navWidth, navScroll, maximumNavScroll;
    private float progress;
    private Button selectButton, courseButton, doneButton;
    private final Button[] slotButtons = new Button[CastingData.SLOT_COUNT];

    public MagicJournalScreen() {
        super(text("title"));
        owner = Minecraft.getInstance().player;
        world = Minecraft.getInstance().level;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(392, width - 16);
        int spellCount = SpellCatalog.values().length;
        // Grow where space allows, while retaining full-height rows in a separate small-screen viewport.
        panelHeight = Math.min(Math.max(220, 90 + spellCount * ROW_HEIGHT + (spellCount - 1) * ROW_GAP), height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        bodyTop = top + 40;
        bodyBottom = top + panelHeight - 70;
        navX = left + 12;
        navWidth = Math.min(106, Math.max(80, panelWidth / 3 - 6));
        detailX = left + navWidth + 26;
        detailWidth = panelWidth - navWidth - 38;
        maximumNavScroll = Math.max(0, spellCount * ROW_HEIGHT + (spellCount - 1) * ROW_GAP - (bodyBottom - bodyTop));
        navScroll = Math.clamp(navScroll, 0, maximumNavScroll);
        rows.clear();
        for (SpellCatalog spell : SpellCatalog.values()) {
            SpellRow row = new SpellRow(spell, navX,
                    bodyTop + spell.ordinal() * (ROW_HEIGHT + ROW_GAP) - navScroll, navWidth, ROW_HEIGHT);
            // Focus/narration belong to the screen; rendering belongs to the clipped navigation viewport.
            rows.put(spell, addWidget(row));
        }
        int slotY = top + panelHeight - 66;
        for (int i = 0; i < slotButtons.length; i++) {
            int target = i;
            int x = left + 12 + i * (panelWidth - 24) / slotButtons.length;
            int next = left + 12 + (i + 1) * (panelWidth - 24) / slotButtons.length;
            slotButtons[i] = addRenderableWidget(Button.builder(Component.empty(), button -> moveToSlot(target))
                    .bounds(x, slotY, Math.max(1, next - x - 1), 18).build());
        }
        int buttonY = top + panelHeight - 42;
        int selectX = detailWidth >= 136 ? detailX : left + 12;
        int selectWidth = left + panelWidth - 84 - selectX;
        selectButton = addRenderableWidget(Button.builder(text("select"), button -> requestSelection())
                .bounds(selectX, buttonY, Math.max(28, selectWidth), 20).build());
        courseButton = addRenderableWidget(Button.builder(text("courses"), button -> openCourses())
                .bounds(left + panelWidth - 100, top + 10, 88, 20).build());
        // What the family calls you (N01): the naming screen again, with the current choice.
        addRenderableWidget(Button.builder(text("naming"), button -> {
                    if (validOwner()) dev.joblessreincarnation.client.naming.NamingClient.open(dev.joblessreincarnation.client.naming.NamingClient.current());
                }).bounds(left + panelWidth - 176, top + 10, 72, 20).tooltip(Tooltip.create(text("naming_hint"))).build());
        doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + panelWidth - 76, buttonY, 64, 20).build());
        updateState();
        setInitialFocus(rows.get(browsed));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        if (!validOwner()) {
            onClose();
            return;
        }
        if (pendingSelection != null && ++pendingTicks >= 100) pendingSelection = null;
        updateState();
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        JournalInput.closing();
        super.removed();
    }

    private boolean validOwner() {
        return minecraft != null && owner != null && minecraft.player == owner && minecraft.level == world
                && owner.isAlive() && !owner.isSpectator();
    }

    private boolean dataReady() {
        return validOwner() && owner.hasData(ModAttachments.KNOWLEDGE) && owner.hasData(ModAttachments.CASTING);
    }

    private void updateState() {
        if (selectButton == null) return;
        boolean ready = dataReady();
        if (ready && !receivedKnowledge) {
            browsed = SpellCatalog.selected(owner);
            receivedKnowledge = true;
            scroll = 0;
            ensureNavVisible(rows.get(browsed));
        }
        if (ready && pendingSelection != null && SpellCatalog.selected(owner) == pendingSelection) pendingSelection = null;
        for (SpellRow row : rows.values()) row.refresh(ready);
        for (int i = 0; i < slotButtons.length; i++) {
            SpellCatalog assigned = ready ? owner.getData(ModAttachments.CASTING).spellAt(owner, i) : null;
            Component key = minecraft.options.keyHotbarSlots[i].getTranslatedKeyMessage();
            slotButtons[i].setMessage(key.copy().withStyle(assigned == browsed ? ChatFormatting.GOLD : ChatFormatting.WHITE));
            slotButtons[i].active = ready && browsed.knows(owner) && assigned != browsed;
            slotButtons[i].setTooltip(Tooltip.create(text("recall_slot", key, assigned == null ? text("recall_empty") : assigned.title())));
        }
        if (ready && getFocused() == null) setInitialFocus(rows.get(browsed));
        Component label;
        boolean canSelect = false;
        if (!ready) label = text("loading");
        else if (pendingSelection != null) label = text("waiting");
        else if (!browsed.knows(owner)) label = text("need_tome");
        else if (SpellCatalog.selected(owner) == browsed) label = text("current");
        else if (!owner.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) label = text("need_wand");
        else { label = text("select"); canSelect = true; }
        selectButton.active = canSelect;
        selectButton.setMessage(label);
        selectButton.setTooltip(Tooltip.create(label));
        courseButton.active = validOwner();
        courseButton.setTooltip(Tooltip.create(text("courses")));
        rebuildDetails(ready);
    }

    private void openCourses() {
        if (validOwner() && minecraft != null) minecraft.setScreen(new CourseRecordsScreen());
    }

    private void browse(SpellCatalog spell) {
        if (!dataReady()) return;
        if (browsed != spell) {
            browsed = spell;
            scroll = 0;
        }
        ensureNavVisible(rows.get(spell));
        updateState();
    }

    /** Keyboard or mouse focus may reach a row that was outside the current navigation viewport. */
    @Override
    public void setFocused(GuiEventListener listener) {
        super.setFocused(listener);
        if (listener instanceof SpellRow row) ensureNavVisible(row);
    }

    private void setNavScroll(int value) {
        navScroll = Math.clamp(value, 0, maximumNavScroll);
        for (SpellRow row : rows.values())
            row.setY(bodyTop + row.spell.ordinal() * (ROW_HEIGHT + ROW_GAP) - navScroll);
    }

    private void ensureNavVisible(SpellRow row) {
        if (row == null) return;
        int rowTop = row.spell.ordinal() * (ROW_HEIGHT + ROW_GAP);
        int viewportHeight = bodyBottom - bodyTop;
        if (rowTop < navScroll) setNavScroll(rowTop);
        else if (rowTop + ROW_HEIGHT > navScroll + viewportHeight)
            setNavScroll(rowTop + ROW_HEIGHT - viewportHeight);
    }

    private boolean inNavigation(double mouseX, double mouseY) {
        return mouseX >= navX && mouseX < navX + navWidth + 6 && mouseY >= bodyTop && mouseY < bodyBottom;
    }

    private void requestSelection() {
        if (!dataReady() || pendingSelection != null || !browsed.knows(owner)
                || SpellCatalog.selected(owner) == browsed
                || !owner.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return;
        pendingSelection = browsed;
        pendingTicks = 0;
        PacketDistributor.sendToServer(new SelectSpellPayload(browsed.id(), owner.getData(ModAttachments.CASTING).revision()));
        updateState();
    }

    private void moveToSlot(int slot) {
        if (!dataReady() || !browsed.knows(owner) || slot < 0 || slot >= slotButtons.length) return;
        CastingData data = owner.getData(ModAttachments.CASTING);
        if (data.spellAt(owner, slot) != browsed)
            PacketDistributor.sendToServer(new MoveSpellSlotPayload(browsed.id(), slot, data.revision()));
    }

    private void rebuildDetails(boolean ready) {
        detailLines.clear();
        progressY = -1;
        int y = 0;
        if (!ready) {
            y = addDetail(text("loading"), MUTED, y);
        } else {
            y = addDetail(browsed.title(), INK, y) + 3;
            boolean learned = browsed.knows(owner);
            if (learned) {
                int casts = browsed.practiceCount(owner);
                y = addDetail(text("stage", SpellTraining.stage(casts).title()), ACCENT, y);
                y = addDetail(SpellTraining.progressText(casts), MUTED, y);
                progressY = y;
                int next = SpellTraining.nextMilestone(casts);
                progress = next == 0 ? 1 : Math.clamp(casts / (float)next, 0, 1);
                y += 9;
            } else {
                y = addDetail(text("unlearned"), MUTED, y) + 3;
            }
            Component cost = browsed == SpellCatalog.WATER_BOLT
                    ? text("cost_range", browsed.manaCost(0), browsed.manaCost(1))
                    : Component.literal(Integer.toString(browsed.manaCost(0)));
            y = addDetail(text(learned ? "cost_chant" : "cost_chant_unlearned", cost,
                    seconds(learned ? browsed.chantTicks(owner) : baseChantTicks(browsed))), ACCENT, y) + 4;
            y = addDetail(text("purpose." + browsed.id().getPath()), INK, y) + 4;
            Component detail = browsed == SpellCatalog.EARTH_WALL
                    ? text("detail.earth_wall", SpellKeys.DISMISS.getTranslatedKeyMessage())
                    : browsed == SpellCatalog.MIRE
                        ? text("detail.mire", (int) MireSpell.RANGE, MireEntity.LIFETIME_TICKS / 20)
                        : browsed == SpellCatalog.STONE_CANNON
                            ? text("detail.stone_cannon", (int) dev.joblessreincarnation.magic.StoneCannonProjectile.MAX_DISTANCE)
                            : text("detail." + browsed.id().getPath());
            y = addDetail(detail, MUTED, y) + 4;
            if (learned) {
                y = addDetail(text("recall_arrange"), MUTED, y);
                y = addDetail(text("overcast"), MUTED, y);
                y = addDetail(text("cast", minecraft.options.keyUse.getTranslatedKeyMessage()), INK, y);
                y = addDetail(text("cancel", minecraft.options.keyShift.getTranslatedKeyMessage()), MUTED, y) + 4;
            }
            y = addDetail(text("tome", browsed.tomeItem().getDefaultInstance().getHoverName()), ACCENT, y);
            y = addDetail(text("study", minecraft.options.keyUse.getTranslatedKeyMessage(),
                    seconds(MagicConfig.STUDY_TICKS.getAsInt())), MUTED, y);
            if (browsed == SpellCatalog.STONE_CANNON)
                y = addDetail(text("prerequisites.stone_cannon", SpellCatalog.EARTH_WALL.title()), MUTED, y);
            if (browsed == SpellCatalog.MIRE)
                y = addDetail(text("prerequisites.mire", SpellCatalog.WATER_BOLT.title(), SpellCatalog.EARTH_WALL.title()), MUTED, y);
            if (owner.hasData(ModAttachments.MANA)) {
                ManaGrowthRules growth = ManaGrowthRules.current();
                int maximum = owner.getData(ModAttachments.MANA).maximum();
                int bonus = Math.clamp(maximum - growth.baseMaximum(), 0, growth.bonusLimit());
                y = addDetail(text("mana_capacity"), ACCENT, y + 5);
                Component rule = !growth.enabled() ? text("mana_capacity.paused")
                        : bonus >= growth.bonusLimit() ? text("mana_capacity.complete")
                        : text("mana_capacity.rule", growthDuration(growth.cooldownTicks()));
                y = addDetail(rule, MUTED, y);
            }
        }
        maximumScroll = Math.max(0, y - (bodyBottom - bodyTop));
        scroll = Math.clamp(scroll, 0, maximumScroll);
    }

    private static Component growthDuration(int ticks) {
        int minutes = ticks / 1200, remainder = ticks % 1200;
        if (remainder == 0) return text("duration.minutes", minutes);
        String seconds = BigDecimal.valueOf(remainder).divide(BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
        return text("duration.minutes_seconds", minutes, seconds);
    }

    private int addDetail(Component component, int color, int y) {
        for (FormattedCharSequence line : font.split(component, Math.max(20, detailWidth - 10))) {
            detailLines.add(new DetailLine(line, y, color));
            y += LINE_HEIGHT;
        }
        return y;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateState();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(navX, bodyTop, navX + navWidth, bodyBottom);
        try {
            for (SpellRow row : rows.values()) row.render(graphics, mouseX, mouseY, partialTick);
        } finally {
            graphics.disableScissor();
        }
        if (maximumNavScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int thumb = Math.max(12, trackHeight * trackHeight / (trackHeight + maximumNavScroll));
            int thumbY = bodyTop + (trackHeight - thumb) * navScroll / maximumNavScroll;
            graphics.fill(navX + navWidth + 3, bodyTop, navX + navWidth + 5, bodyBottom, 0xFFCFBC99);
            graphics.fill(navX + navWidth + 3, thumbY, navX + navWidth + 5, thumbY + thumb, 0xFF8C7554);
            if (mouseX >= navX + navWidth && inNavigation(mouseX, mouseY)) setTooltipForNextRenderPass(text("scroll"));
        }
        graphics.drawString(font, title, left + 15, top + 15, INK, false);
        if (dataReady()) {
            int known = 0;
            for (SpellCatalog spell : SpellCatalog.values()) if (spell.knows(owner)) known++;
            Component count = text("learned_count", known, SpellCatalog.values().length);
            graphics.drawString(font, count, left + panelWidth - 112 - font.width(count), top + 15, MUTED, false);
        }
        graphics.enableScissor(detailX, bodyTop, detailX + detailWidth, bodyBottom);
        for (DetailLine line : detailLines) {
            int y = bodyTop + line.y - scroll;
            if (y + font.lineHeight > bodyTop && y < bodyBottom)
                graphics.drawString(font, line.text, detailX, y, line.color, false);
        }
        if (progressY >= 0) {
            int y = bodyTop + progressY - scroll;
            graphics.fill(detailX, y, detailX + detailWidth - 10, y + 4, 0xFFDAC7A1);
            graphics.fill(detailX, y, detailX + (int)((detailWidth - 10) * progress), y + 4, 0xFF67929B);
        }
        graphics.disableScissor();
        if (maximumScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int thumb = Math.max(12, trackHeight * trackHeight / (trackHeight + maximumScroll));
            int thumbY = bodyTop + (trackHeight - thumb) * scroll / maximumScroll;
            graphics.fill(detailX + detailWidth - 4, bodyTop, detailX + detailWidth - 2, bodyBottom, 0xFFCFBC99);
            graphics.fill(detailX + detailWidth - 4, thumbY, detailX + detailWidth - 2, thumbY + thumb, 0xFF8C7554);
            if (mouseX >= detailX + detailWidth - 8 && mouseX < detailX + detailWidth
                    && mouseY >= bodyTop && mouseY < bodyBottom) setTooltipForNextRenderPass(text("scroll"));
        }
        Component note = text("stages_note");
        if (font.width(note) <= panelWidth - 24)
            graphics.drawString(font, note, left + (panelWidth - font.width(note)) / 2, top + panelHeight - 20, MUTED, false);
        else AbstractWidget.renderScrollingString(graphics, font, note, left + 12, top + panelHeight - 21,
                left + panelWidth - 12, top + panelHeight - 11, MUTED);
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
            setNavScroll(navScroll - (int)Math.round(scrollY * (ROW_HEIGHT + ROW_GAP)));
            return true;
        }
        if (mouseX >= detailX && mouseX < detailX + detailWidth && mouseY >= bodyTop && mouseY < bodyBottom) {
            scroll = Math.clamp(scroll - (int)Math.round(scrollY * 22), 0, maximumScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int match = -1;
        for (int i = 0; i < slotButtons.length; i++) {
            if (minecraft.options.keyHotbarSlots[i].matches(keyCode, scanCode)
                    && minecraft.options.keyHotbarSlots[i].getKeyModifier().isActive(null)) {
                if (match >= 0) return true;
                match = i;
            }
        }
        if (match >= 0) {
            moveToSlot(match);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            int step = Math.max(LINE_HEIGHT, bodyBottom - bodyTop - LINE_HEIGHT);
            scroll = Math.clamp(scroll + (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? step : -step), 0, maximumScroll);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END) {
            scroll = keyCode == GLFW.GLFW_KEY_HOME ? 0 : maximumScroll;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Read-only layout coordinates also allow checks to drive the actual screen input methods. */
    public record Bounds(int x, int y, int width, int height) {}
    public Bounds spellBounds(SpellCatalog spell) { return bounds(rows.get(spell)); }
    public Bounds selectionBounds() { return bounds(selectButton); }
    public Bounds courseRecordsBounds() { return bounds(courseButton); }
    public Bounds doneBounds() { return bounds(doneButton); }
    public SpellCatalog browsedSpell() { return browsed; }
    public int detailScroll() { return scroll; }
    public int maxDetailScroll() { return maximumScroll; }
    public Bounds navigationBounds() { return new Bounds(navX, bodyTop, navWidth + 6, bodyBottom - bodyTop); }
    public Bounds detailBounds() { return new Bounds(detailX, bodyTop, detailWidth, bodyBottom - bodyTop); }
    public int navigationScroll() { return navScroll; }
    public int maxNavigationScroll() { return maximumNavScroll; }
    private static Bounds bounds(Button button) {
        return new Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    private static Component text(String key, Object... args) { return Component.translatable(KEY + key, args); }
    private static String seconds(int ticks) {
        return BigDecimal.valueOf(ticks).divide(BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
    }
    private static int baseChantTicks(SpellCatalog spell) {
        return switch (spell) {
            case WATER_BOLT -> MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt();
            case WATER_SHIELD -> MagicConfig.WATER_SHIELD_CHANT_TICKS.getAsInt();
            case EARTH_WALL -> MagicConfig.EARTH_WALL_CHANT_TICKS.getAsInt();
            case AIR_BURST -> MagicConfig.AIR_BURST_CHANT_TICKS.getAsInt();
            case HEALING -> MagicConfig.HEALING_CHANT_TICKS.getAsInt();
            case FIRE_BALL -> MagicConfig.FIRE_BALL_CHANT_TICKS.getAsInt();
            case MIRE -> MagicConfig.MIRE_CHANT_TICKS.getAsInt();
            case STONE_CANNON -> MagicConfig.STONE_CANNON_CHANT_TICKS.getAsInt();
        };
    }
    private record DetailLine(FormattedCharSequence text, int y, int color) {}

    private final class SpellRow extends Button {
        private final SpellCatalog spell;
        private final ItemStack icon;
        private Component stage = text("loading");
        private boolean current;
        SpellRow(SpellCatalog spell, int x, int y, int width, int height) {
            super(x, y, width, height, spell.title(), ignored -> browse(spell), DEFAULT_NARRATION);
            this.spell = spell;
            icon = spell.tomeItem().getDefaultInstance();
        }
        void refresh(boolean ready) {
            active = ready;
            stage = !ready ? text("loading") : spell.knows(owner)
                    ? SpellTraining.stage(spell.practiceCount(owner)).title() : text("unlearned");
            current = ready && spell.knows(owner) && SpellCatalog.selected(owner) == spell;
            Component status = current ? text("current") : Component.empty();
            setMessage(text("row", spell.title(), stage, status));
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
            boolean intersectsViewport = y < bodyBottom && y + getHeight() > bodyTop;
            setTooltip(intersectsViewport ? Tooltip.create(getMessage()) : null);
            if (!intersectsViewport) return;
            boolean browsing = spell == browsed;
            int edge = isFocused() ? 0xFF36798D : browsing ? 0xFF90764E : 0xFFD3BD97;
            graphics.fill(x, y, x + getWidth(), y + getHeight(), edge);
            graphics.fill(x + 1, y + 1, x + getWidth() - 1, y + getHeight() - 1,
                    isHoveredOrFocused() ? 0xFFF4E5C5 : browsing ? 0xFFF9ECCF : 0xFFE9D9B7);
            if (current) {
                graphics.fill(x + 1, y + 1, x + 3, y + getHeight() - 1, 0xFF527854);
                graphics.drawString(font, "*", x + getWidth() - 7, y + 2, GREEN, false);
            }
            graphics.renderItem(icon, x + 5, y + (getHeight() - 16) / 2);
            int textX = x + 25;
            int lineY = y + Math.max(1, (getHeight() - 22) / 2);
            renderScrollingString(graphics, font, spell.title(), textX, lineY,
                    x + getWidth() - 8, lineY + 10, readyColor());
            if (getHeight() >= ROW_HEIGHT) renderScrollingString(graphics, font, stage, textX, lineY + 11,
                    x + getWidth() - 5, lineY + 21, MUTED);
        }
        private int readyColor() { return active ? INK : MUTED; }
    }
}
