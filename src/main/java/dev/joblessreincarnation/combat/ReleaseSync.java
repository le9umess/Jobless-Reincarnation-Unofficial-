package dev.joblessreincarnation.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * What the staff sends at the start of the tick in which vanilla will release it (the use key is up while the staff
 * is still in use). Earlier in the same tick the journal or a menu has had its chance to cancel, G its selection and
 * H its state; vanilla sends the release itself later in this tick, so anything here reaches the server first.
 * A cancelled use never comes here and never gets a release of its own.
 */
public final class ReleaseSync {
    public enum Step { SNEAK, ROTATION }

    /**
     * @param eligible     the staff may act now (alive, in the game, no screen, window focused)
     * @param useKeyDown   the use key is still held: no release this tick
     * @param usingStaff   the player is using the training staff in the main hand
     * @param sneakKey     the sneak key is down now
     * @param sneaking     the player's sneak state as last sent
     * @param aimSync      the combat view aims this release at the crosshair
     */
    public static List<Step> plan(boolean eligible, boolean useKeyDown, boolean usingStaff, boolean sneakKey, boolean sneaking, boolean aimSync) {
        if (!eligible || useKeyDown || !usingStaff) return List.of();
        List<Step> steps = new ArrayList<>(2);
        if (sneakKey != sneaking) steps.add(Step.SNEAK);
        // Every release, not only one with a sneak change: an ordinary release has none.
        if (aimSync) steps.add(Step.ROTATION);
        return List.copyOf(steps);
    }

    private ReleaseSync() {}
}
