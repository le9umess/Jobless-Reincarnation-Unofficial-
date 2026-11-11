package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.SpellCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.config.ClientConfig;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * Local presentation only, active when the optional Punchy! client mod is installed.
 * Punchy draws the first-person arms, so Epic Fight's own first-person model stays off (otherwise both draw arms),
 * and Epic Fight attacks, which skip the vanilla swing, start one so that Punchy plays its arm animation.
 */
public final class PunchyFirstPerson {
    private static final int PREDICTION_TICKS = 8;
    private static Object lastAttack;
    private static boolean wasPressed;
    private static int predictedAt = -PREDICTION_TICKS;

    public static void install() {
        if (!ModList.get().isLoaded("punchy")) return;
        NeoForge.EVENT_BUS.addListener(PunchyFirstPerson::tick);
        NeoForge.EVENT_BUS.addListener(PunchyFirstPerson::keepMainHandPriority);
    }

    private static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        var player = client.player;
        if (player == null) return;
        ClientConfig.enableAnimatedFirstPersonModel = false;
        var patch = EpicFightCapabilities.getPlayerPatch(player);
        if (patch == null || !patch.isEpicFightMode()) {
            lastAttack = null;
            wasPressed = false;
            return;
        }

        // The press starts the arm at once: the server's hit lands before its animation reaches this client.
        boolean pressed = client.screen == null && client.options.keyAttack.isDown();
        var state = patch.getEntityState();
        if (pressed && !wasPressed && !player.isUsingItem() && !state.inaction() && state.canBasicAttack()) {
            player.swing(InteractionHand.MAIN_HAND, false);
            predictedAt = player.tickCount;
        }
        wasPressed = pressed;

        // Each further attack clip is one swing, so a combo keeps moving the arm. swing(hand, false) sends no packet.
        Object attack = null;
        if (state.inaction()) {
            var clip = patch.getClientAnimator().getPlayerFor(null).getRealAnimation();
            if (clip.get() instanceof AttackAnimation) attack = clip;
        }
        if (attack != null && attack != lastAttack && !player.isUsingItem() && player.tickCount - predictedAt > PREDICTION_TICKS) {
            player.swing(InteractionHand.MAIN_HAND, false);
        }
        lastAttack = attack;
    }

    /** A learned tome fails on the client (see TomeFeedback.suppressClientUse); the press must not pass on to the off hand. */
    private static void keepMainHandPriority(InputEvent.InteractionKeyMappingTriggered event) {
        var player = Minecraft.getInstance().player;
        if (player == null || !event.isUseItem() || event.getHand() != InteractionHand.OFF_HAND) return;
        var held = player.getMainHandItem().getItem();
        for (var spell : SpellCatalog.values()) {
            if (spell.tomeItem() == held && spell.knows(player)) {
                event.setCanceled(true);
                event.setSwingHand(false);
                return;
            }
        }
    }

    private PunchyFirstPerson() {}
}
