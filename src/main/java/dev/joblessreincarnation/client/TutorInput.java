package dev.joblessreincarnation.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaReaderItem;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Client convenience only: the server still authorizes every query and applies its own cooldown. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class TutorInput {
    private static final TutorUseState USE = new TutorUseState();
    private static LocalPlayer player;
    private static ClientLevel level;
    private static InputConstants.Key binding;
    private static KeyModifier modifier;
    private static IKeyConflictContext conflictContext;
    private static long clientTicks;
    private static long acceptPressAfterTick;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        clientTicks++;
        refreshContext(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        refreshContext(minecraft);
        if (!minecraft.options.keyUse.matches(event.getKey(), event.getScanCode())) return;
        rawInput(minecraft, event.getAction());
    }

    /** Observe the context before a mouse click can close a GUI or regain mouse capture. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeMouse(InputEvent.MouseButton.Pre event) {
        refreshContext(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void mouse(InputEvent.MouseButton.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        refreshContext(minecraft);
        if (!minecraft.options.keyUse.matchesMouse(event.getButton())) return;
        rawInput(minecraft, event.getAction());
    }

    private static void rawInput(Minecraft minecraft, int action) {
        if (action == GLFW.GLFW_RELEASE) USE.release();
        else if (action == GLFW.GLFW_PRESS) {
            USE.press(inputContext(minecraft) && clientTicks > acceptPressAfterTick
                && !minecraft.player.isUsingItem()
                && minecraft.options.keyUse.isConflictContextAndModifierActive());
        }
        // GLFW_REPEAT must not renew permission even when use is rebound to a keyboard key.
    }

    /** Also cover screens opened and closed between ticks, including a close-button's own press. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opening(ScreenEvent.Opening event) { invalidateContext(); }

    @SubscribeEvent
    public static void closing(ScreenEvent.Closing event) { invalidateContext(); }

    /** StaffInput's journal/G/H checks run first; canceled events never consume our first query. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        Minecraft minecraft = Minecraft.getInstance();
        refreshContext(minecraft);
        // Roxy and the family residents share one latch: one physical press asks at most one of them once.
        boolean tutor = minecraft.hitResult instanceof EntityHitResult hit
            && (hit.getEntity() instanceof RoxyTutorEntity || hit.getEntity() instanceof FamilyResidentEntity
                || hit.getEntity() instanceof SylphietteEntity || hit.getEntity() instanceof PracticeDummyEntity);
        boolean measuring = minecraft.player != null && ManaReaderItem.inMainHand(minecraft.player);
        if (USE.interact(tutor || measuring, event.getHand() == InteractionHand.MAIN_HAND, inputContext(minecraft))) {
            // This hook precedes interactAt's packet send. Returning PASS from entity hooks is too late.
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private static void refreshContext(Minecraft minecraft) {
        InputConstants.Key currentBinding = minecraft.options.keyUse.getKey();
        KeyModifier currentModifier = minecraft.options.keyUse.getKeyModifier();
        IKeyConflictContext currentContext = minecraft.options.keyUse.getKeyConflictContext();
        if (player != minecraft.player || level != minecraft.level || !currentBinding.equals(binding)
                || modifier != currentModifier || conflictContext != currentContext) {
            player = minecraft.player;
            level = minecraft.level;
            binding = currentBinding;
            modifier = currentModifier;
            conflictContext = currentContext;
            invalidateContext();
        }
        if (!inputContext(minecraft)) invalidateContext();
    }

    private static boolean inputContext(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null && minecraft.gameMode != null
            && minecraft.player.isAlive() && !minecraft.player.isSpectator()
            && minecraft.screen == null && minecraft.getOverlay() == null
            && !minecraft.isPaused() && minecraft.isWindowActive();
    }

    private static void invalidateContext() {
        USE.invalidate();
        // Let a full client tick pass after closing before accepting a new gameplay press.
        acceptPressAfterTick = clientTicks + 1;
    }

    private TutorInput() {}
}
