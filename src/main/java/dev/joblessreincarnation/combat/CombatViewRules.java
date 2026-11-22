package dev.joblessreincarnation.combat;

/**
 * Who owns the point of view while the combat view takes over Epic Fight's combat mode. Pure: one instance per local
 * player. Epic Fight's mode switches are reported as they happen ({@link #enter}, {@link #exit}); once per client tick
 * {@link #tick} is fed what was observed. A returned view is switched to at once by the caller.
 * <p>
 * Two questions are kept apart: is this round protected (the combat view decides the point of view for it, and Epic
 * Fight's own perspective switches are held back), and did the combat view change the view itself (it owns the view,
 * so it may give it back)?
 * <ul>
 * <li>Each round is classified when Epic Fight switches into combat mode, with the setting and Epic Fight's TPS as they
 *     are at that moment: taking over means a protected round, otherwise an Epic Fight round.</li>
 * <li>A protected round holds Epic Fight's own switch back on entering and on leaving. Entering from first person
 *     switches to the back view and owns it; from the back or front view nothing changes and nothing is owned. F5 or
 *     anything else changing the view during the round is the player's choice: the round yields (nothing forced,
 *     nothing given back) and stays protected. Leaving gives back the view from before entering when it is still owned
 *     and unchanged; otherwise the view stays as it is.</li>
 * <li>An Epic Fight round is Epic Fight's on both switches, as the player set it up.</li>
 * <li>During a protected round, turning the setting off gives back an owned, untouched view, and Epic Fight's TPS being
 *     disabled by another camera mod keeps whatever the player sees; either way the rest of the round is Epic Fight's.
 *     Leaving that meets such a change before any tick has seen it decides the same way. Turning the setting on during
 *     an Epic Fight round leaves that round Epic Fight's; the next entry is protected.</li>
 * <li>A world joined already in combat mode is not an entry, and a switch reported before the first tick of a new
 *     connection is Epic Fight's. A new player entity in the same connection (respawn, another dimension) keeps the
 *     round; if Epic Fight reports a switch into combat mode for it, that is the same round going on, held back if
 *     protected. The
 *     camera moving to another entity drops ownership. A switch that was never reported (a path around Epic Fight's
 *     switch methods) is noticed on the next tick and decided the same way, one tick late; Epic Fight's own switch has
 *     happened by then.</li>
 * </ul>
 */
public final class CombatViewRules {
    /** The three vanilla points of view, independent of client classes so the rules can be tested anywhere. */
    public enum View { FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT }
    /** What the caller should do this tick: a view to switch to (or null), and whether to log a TPS change. */
    public record Step(View request, boolean logTpsLost, boolean logTpsBack) {
        static final Step NONE = new Step(null, false, false);
    }
    /** One Epic Fight mode switch: the view to switch to now (or null), and whether Epic Fight's own perspective switch is held back. */
    public record Switch(View request, boolean holdEpicFight) {
        static final Switch EPIC_FIGHTS = new Switch(null, false);
    }

    private boolean started, combat, guarded, owned, yielded, takeover = true, tpsAvailable = true;
    private View restore = View.FIRST_PERSON;
    /** The view as last seen or set here; null right after a switch Epic Fight makes itself, until the next look. */
    private View lastView;

    /** This round is protected: Epic Fight's own perspective switches are held back on entering and leaving. */
    public boolean guarded() { return guarded; }
    /** The view is the one this class switched to, and nobody has changed it since. */
    public boolean owns() { return owned; }
    /** The player changed the view during this combat round: the combat view leaves the camera alone until the next entry. */
    public boolean yielded() { return yielded; }

    /**
     * Epic Fight is switching the player into combat mode now: the round is classified here.
     * @param takeoverNow the combat view takes over at this moment: the setting is on and Epic Fight's TPS is available
     * @param view        the point of view now
     */
    public Switch enter(boolean takeoverNow, View view) {
        if (!started) return Switch.EPIC_FIGHTS;
        // Already in a round: the same round goes on; nothing is reclassified or taken over again. This is Epic Fight giving
        // a new player entity (respawn, another dimension) the old one's mode when the new patch did not start in combat
        // mode; a new patch usually does start in it, and then no switch is reported at all.
        if (combat) return guarded ? new Switch(null, true) : Switch.EPIC_FIGHTS;
        combat = true; owned = false; yielded = false;
        guarded = takeoverNow;
        if (!guarded) { lastView = null; return Switch.EPIC_FIGHTS; }
        if (view != View.FIRST_PERSON) { lastView = view; return new Switch(null, true); }
        restore = view; owned = true; lastView = View.THIRD_PERSON_BACK;
        return new Switch(View.THIRD_PERSON_BACK, true);
    }

    /**
     * Epic Fight is switching the player out of combat mode now.
     * @param enabled      the player's combat view setting at this moment
     * @param tpsAvailable Epic Fight's TPS is not disabled by another camera mod, at this moment
     * @param view         the point of view now
     */
    public Switch exit(boolean enabled, boolean tpsAvailable, View view) {
        if (!started || !combat) return Switch.EPIC_FIGHTS;
        combat = false;
        boolean ours = owned && view == lastView, stillGuarded = guarded && enabled && tpsAvailable, turnedOff = guarded && !enabled;
        owned = false; yielded = false; guarded = false;
        if (stillGuarded) {
            lastView = ours ? restore : view;
            return new Switch(ours ? restore : null, true);
        }
        // An Epic Fight round, or one that has just become Epic Fight's: a view still owned goes back if the setting went
        // off, and Epic Fight switches as the player set it up.
        lastView = null;
        return turnedOff && ours ? new Switch(restore, false) : Switch.EPIC_FIGHTS;
    }

    /**
     * @param enabled      the player's combat view setting
     * @param tpsAvailable Epic Fight's TPS is not disabled by another camera mod
     * @param combatMode   the local player is in Epic Fight's combat mode
     * @param view         the point of view now
     */
    public Step tick(boolean enabled, boolean tpsAvailable, boolean combatMode, View view) {
        boolean logLost = this.tpsAvailable && !tpsAvailable, logBack = !this.tpsAvailable && tpsAvailable;
        this.tpsAvailable = tpsAvailable;
        boolean takeoverNow = enabled && tpsAvailable;
        View request = null;
        if (!started) {
            started = true;
        } else {
            // Our own switches are recorded as the last view, so any other difference came from somewhere else.
            boolean changedElsewhere = lastView != null && view != lastView;
            if (combat && combatMode) {
                if (guarded && !takeoverNow) {
                    // Turned off, or Epic Fight's TPS lost: an owned, untouched view goes back (turned off only), and the
                    // rest of the round is Epic Fight's.
                    if (owned && !enabled && view == View.THIRD_PERSON_BACK && !changedElsewhere) request = restore;
                    owned = false; yielded = false; guarded = false;
                } else if (takeoverNow && changedElsewhere) {
                    // F5 or another source: the player's choice. The round yields, and a protected one stays protected.
                    owned = false; yielded = true;
                }
            } else if (!combat && combatMode) {
                // An entry no request reported: the same decisions as enter, one tick late.
                owned = false; yielded = false; guarded = takeoverNow;
                if (guarded && view == View.FIRST_PERSON) { restore = view; request = View.THIRD_PERSON_BACK; owned = true; }
            } else if (combat && !combatMode) {
                // An exit no request reported: the same decisions as exit, one tick late.
                boolean tpsJustLost = enabled && !tpsAvailable;
                if (guarded && owned && view == View.THIRD_PERSON_BACK && !changedElsewhere && !tpsJustLost) request = restore;
                owned = false; yielded = false; guarded = false;
            }
        }
        takeover = takeoverNow;
        combat = combatMode;
        lastView = request != null ? request : view;
        return request == null && !logLost && !logBack ? Step.NONE : new Step(request, logLost, logBack);
    }

    /**
     * The camera is on another entity (spectating it): the view is no longer the one this class switched to for the
     * player, so it is no longer owned and is never given back. The round's protection stays.
     */
    public void cameraLeftPlayer() { owned = false; }

    /**
     * A new connection (another world, or back after leaving one): forget everything, entries start over. A new player
     * entity within the same connection (respawn, another dimension) is not a reset: the point of view is a client-wide
     * setting, untouched by the entity change, so the round, its protection, ownership and the view from before
     * entering all carry over.
     */
    public void reset() {
        started = false; combat = false; guarded = false; owned = false; yielded = false; takeover = true; tpsAvailable = true;
        restore = View.FIRST_PERSON; lastView = null;
    }
}
