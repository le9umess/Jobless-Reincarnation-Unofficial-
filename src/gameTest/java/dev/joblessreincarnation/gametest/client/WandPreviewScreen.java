package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.gametest.WandPreviewControls;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/** Transparent, non-pausing mouse controls over the actual renderer and HUD. */
public final class WandPreviewScreen extends Screen {
    private final MinecraftServer server;
    private final UUID playerId;
    private final CameraType originalCamera;
    private final int originalSlot;
    private final HumanoidArm originalArm;
    private boolean closed;

    public WandPreviewScreen(MinecraftServer server, UUID playerId, WandPreviewControls.State state) {
        super(Component.literal("临时魔杖视觉预览"));
        this.server = server;
        this.playerId = playerId;
        this.originalCamera = Minecraft.getInstance().options.getCameraType();
        this.originalSlot = state.originalSlot();
        this.originalArm = state.originalArm();
    }

    @Override
    protected void init() {
        int gap = 4;
        int buttonWidth = Math.min(68, (width - 16 - gap * 5) / 6);
        int x = (width - buttonWidth * 6 - gap * 5) / 2;
        int y = height - 28;
        addControl("待机", x, y, buttonWidth, () -> dispatch(WandPreviewControls.Action.IDLE));
        addControl("咏唱/塑形", x + (buttonWidth + gap), y, buttonWidth, () -> dispatch(WandPreviewControls.Action.BEGIN));
        addControl("发射", x + (buttonWidth + gap) * 2, y, buttonWidth, () -> dispatch(WandPreviewControls.Action.FIRE));
        addControl("左右手", x + (buttonWidth + gap) * 3, y, buttonWidth, () -> dispatch(WandPreviewControls.Action.SWAP_HAND));
        addControl("视角", x + (buttonWidth + gap) * 4, y, buttonWidth, this::cycleCamera);
        addControl("结束", x + (buttonWidth + gap) * 5, y, buttonWidth, this::onClose);
        mirrorState();
        LogUtils.getLogger().info("[JR_WAND_PREVIEW] SCREEN: transparent controls active; camera={}", minecraft.options.getCameraType());
    }

    private void addControl(String title, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(title), button -> action.run()).bounds(x, y, width, 20).build());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // In 1.21.1 there is no passEvents field: any Screen blocks handleKeybinds.
        // No background or blur; the world, first-person item and HUD remain real.
    }

    @Override
    public void tick() {
        if (!validClient() || WandPreviewControls.state(server, playerId) == null) {
            onClose();
            return;
        }
        mirrorState();
    }

    private boolean validClient() {
        return !closed && minecraft != null && minecraft.getSingleplayerServer() == server
                && minecraft.player != null && playerId.equals(minecraft.player.getUUID())
                && WandPreviewControls.enabled() && !server.isPublished();
    }

    private void mirrorState() {
        if (!validClient()) return;
        WandPreviewControls.State state = WandPreviewControls.state(server, playerId);
        if (state == null) return;
        minecraft.player.getInventory().selected = 0;
        minecraft.player.setMainArm(state.mainArm());
        minecraft.player.setShiftKeyDown(false);
        if (state.using() && minecraft.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) {
            if (!minecraft.player.isUsingItem()) minecraft.player.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (minecraft.player.isUsingItem()) minecraft.player.stopUsingItem();
    }

    private void dispatch(WandPreviewControls.Action action) {
        if (!validClient()) return;
        Minecraft client = minecraft;
        server.execute(() -> {
            WandPreviewControls.perform(server, playerId, action);
            client.execute(() -> {
                if (client.screen == this && !closed) mirrorState();
            });
        });
    }

    private void cycleCamera() {
        if (!validClient()) return;
        setCamera(minecraft.options.getCameraType().cycle());
        LogUtils.getLogger().info("[JR_WAND_PREVIEW] CAMERA: {}", minecraft.options.getCameraType());
    }

    private void setCamera(CameraType camera) {
        CameraType previous = minecraft.options.getCameraType();
        minecraft.options.setCameraType(camera);
        if (previous.isFirstPerson() != camera.isFirstPerson())
            minecraft.gameRenderer.checkEntityPostEffect(camera.isFirstPerson() ? minecraft.getCameraEntity() : null);
        minecraft.levelRenderer.needsUpdate();
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.screen == this && minecraft.player != null && minecraft.level != null
                && minecraft.getSingleplayerServer() == server && !server.isStopped()) minecraft.setScreen(null);
        else removed();
    }

    @Override
    public void removed() {
        if (closed) return;
        closed = true;
        if (minecraft != null) {
            setCamera(originalCamera);
            if (minecraft.getSingleplayerServer() == server && minecraft.player != null
                    && playerId.equals(minecraft.player.getUUID())) {
                minecraft.player.stopUsingItem();
                minecraft.player.getInventory().selected = originalSlot;
                minecraft.player.setMainArm(originalArm);
            }
        }
        if (!server.isStopped()) server.execute(() -> WandPreviewControls.perform(server, playerId, WandPreviewControls.Action.END));
        LogUtils.getLogger().info("[JR_WAND_PREVIEW] SCREEN_CLOSED: camera restored; original server snapshot restoration requested");
    }
}
