package dev.joblessreincarnation.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Dev-only synthetic raw events plus vanilla KeyMapping; never changes production input latches. */
public final class NativeUseInput {
    private InputConstants.Key pressedKey;
    private int focusRequestedAt = -1000;
    private int focusReadyAt = -1;

    /** Waits for actual native focus and two full ticks; does not fake Minecraft's window-active state. */
    public boolean focusReady(Minecraft client) {
        if (client.player == null || client.screen != null || client.getOverlay() != null) return false;
        long window = client.getWindow().getWindow();
        int now = client.player.tickCount;
        boolean focused = client.isWindowActive()
                && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (focused) {
            if (focusReadyAt < 0 || focusReadyAt > now) focusReadyAt = now;
            return now - focusReadyAt >= 2;
        }
        focusReadyAt = -1;
        if (now < focusRequestedAt || now - focusRequestedAt >= 40) {
            focusRequestedAt = now;
            GLFW.glfwFocusWindow(window);
            LogUtils.getLogger().info("[JR_NATIVE_USE] Requested actual focus for this probe's own window");
        }
        return false;
    }

    public void press(Minecraft client) {
        if (pressedKey != null) return;
        InputConstants.Key key = client.options.keyUse.getKey();
        if (!send(client, key, GLFW.GLFW_PRESS))
            throw new IllegalStateException("Synthetic use PRESS was canceled by another input listener");
        pressedKey = key;
        LogUtils.getLogger().info("[JR_NATIVE_USE] SYNTHETIC_RAW_PRESS key={}; ordinary event bus and KeyMapping, no latch or packet shortcut", key.getName());
    }

    public void hold(Minecraft client, boolean held) {
        if (!held) release(client);
        else client.options.keyUse.setDown(true);
    }

    public void release(Minecraft client) {
        InputConstants.Key key = pressedKey;
        pressedKey = null;
        if (key != null) send(client, key, GLFW.GLFW_RELEASE);
        client.options.keyUse.setDown(false);
    }

    private static boolean send(Minecraft client, InputConstants.Key key, int action) {
        if (key.getType() == InputConstants.Type.MOUSE) {
            var pre = new InputEvent.MouseButton.Pre(key.getValue(), action, 0);
            NeoForge.EVENT_BUS.post(pre);
            if (pre.isCanceled()) return false;
            KeyMapping.set(key, action != GLFW.GLFW_RELEASE);
            if (action == GLFW.GLFW_PRESS) KeyMapping.click(key);
            NeoForge.EVENT_BUS.post(new InputEvent.MouseButton.Post(key.getValue(), action, 0));
        } else {
            KeyMapping.set(key, action != GLFW.GLFW_RELEASE);
            if (action == GLFW.GLFW_PRESS) KeyMapping.click(key);
            int code = key.getType() == InputConstants.Type.SCANCODE ? GLFW.GLFW_KEY_UNKNOWN : key.getValue();
            int scan = key.getType() == InputConstants.Type.SCANCODE ? key.getValue() : GLFW.glfwGetKeyScancode(code);
            NeoForge.EVENT_BUS.post(new InputEvent.Key(code, scan, action, 0));
        }
        return true;
    }
}
