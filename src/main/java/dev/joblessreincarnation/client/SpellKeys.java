package dev.joblessreincarnation.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import yesman.epicfight.api.client.event.types.camera.LockOnEvent;
import yesman.epicfight.client.input.EpicFightKeyMappings;

public final class SpellKeys {
    public static final KeyMapping CYCLE = new KeyMapping("key.jobless_reincarnation.cycle_spell",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "key.categories.jobless_reincarnation");
    public static final KeyMapping DISMISS = new KeyMapping("key.jobless_reincarnation.dismiss_wall",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
            "key.categories.jobless_reincarnation");
    public static final KeyMapping JOURNAL = new KeyMapping("key.jobless_reincarnation.open_journal",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "key.categories.jobless_reincarnation");
    public static void register(RegisterKeyMappingsEvent event) { event.register(CYCLE); event.register(DISMISS); event.register(JOURNAL); }
    /** Both default to G. With the staff out G is the spell key; with anything else in hand it stays Epic Fight's lock-on. */
    public static void keepCycleFromLockingOn(LockOnEvent.Start event) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get()) && CYCLE.same(EpicFightKeyMappings.LOCK_ON)) event.cancel();
    }
    private SpellKeys() {}
}

