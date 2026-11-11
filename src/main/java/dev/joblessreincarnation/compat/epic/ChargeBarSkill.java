package dev.joblessreincarnation.compat.epic;

import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

/**
 * A held sword skill that shows the shared sword charge bar ({@code SwordChargeHud}) instead of Epic Fight's own while it
 * charges. The Longsword of Light is the first; later sword skills that charge implement this too and get the same bar.
 */
public interface ChargeBarSkill {
    /** The charge ticks at which each tier is reached, ascending; the last one is the full charge. */
    int[] chargeTiers();

    /** The highest tier, up to {@code reached}, that the player could pay for right now; 0 when not even the first. */
    int affordableTier(PlayerPatch<?> patch, int reached);
}
