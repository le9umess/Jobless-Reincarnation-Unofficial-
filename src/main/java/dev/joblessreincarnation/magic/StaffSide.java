package dev.joblessreincarnation.magic;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * Which side of the caster the held staff is drawn on, for effects placed at the staff (a chant's focus, the icicle's
 * cold air). Vanilla draws the main-hand item in the main arm's hand. Epic Fight's own animated model draws it at its
 * right-hand joint whatever the main arm (its renderer never reads the main arm), and with Epic Fight's default
 * settings that model is used for third person exactly while the player is in its combat mode; outside it the vanilla
 * model is drawn. {@link #of(LivingEntity)} is that rule for the server, which places the chant focus everyone sees: a
 * viewer who sets Epic Fight to use its own model outside combat mode too is not known there, and sees the focus on the
 * main arm's side. The caster's own first-person view re-places the focus at its own hand (StaffTipView). A client that
 * places an effect itself asks Epic Fight directly ({@link #of(HumanoidArm, boolean)} with Epic Fight's overrideRender).
 */
public final class StaffSide {
    /** +1 for the right hand, -1 for the left. */
    public static int of(LivingEntity caster) {
        boolean epicFightModel = false;
        if (caster instanceof Player player) {
            var patch = EpicFightCapabilities.getPlayerPatch(player);
            epicFightModel = patch != null && patch.isEpicFightMode();
        }
        return of(caster.getMainArm(), epicFightModel);
    }

    /** @param epicFightModel the caster is drawn with Epic Fight's own model (in its combat mode) */
    public static int of(HumanoidArm mainArm, boolean epicFightModel) {
        return epicFightModel || mainArm == HumanoidArm.RIGHT ? 1 : -1;
    }

    private StaffSide() {}
}
