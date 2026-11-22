package dev.joblessreincarnation.combat;

/**
 * The combat view's held staff release, as plain state: the client (StaffAim) drives it, GameTests drive it directly.
 * A release prepared at the start of a tick ({@link #arm}) lets vanilla's own release later in that same tick be held
 * ({@link #hold}), together with the use it belongs to. It is settled right after this tick's movement packet
 * ({@link #afterMove}), or at the end of the tick when vanilla sent no movement packet ({@link #endOfTick}).
 * <p>
 * Settling checks the use again: the same entity, still able to release (alive, still using that item in the main
 * hand), and the same use context (world, connection, item, hand, hotbar slot) as when vanilla released. Only then
 * does it go, once; otherwise it is dropped, and nothing replaces it: whatever ended that use (vanilla stopping a use
 * whose item changed, a switched slot, death, a new world) ends it as it always did. Nothing outlives the tick it was
 * armed in. While the held release is being sent, vanilla's release passes and is not held again; vanilla releasing
 * twice in one tick still makes one release (the second would find nothing in use on the server).
 */
public final class HeldRelease {
    /** How one held release settles. */
    public enum Outcome { NONE, SEND, DROP }
    /** The use a release belongs to: compared field by field (worlds, connections and items by identity). */
    public record Context(Object level, Object connection, Object item, Object hand, int slot) {}

    private Object owner;
    private Context context;
    private boolean armed, pending, sending;

    /** A release is prepared this tick for {@code owner}: vanilla's release later in this tick may be held. */
    public void arm(Object owner) {
        this.owner = owner;
        context = null;
        armed = true;
        pending = false;
    }
    /** Vanilla is releasing now, in this use. @return true to hold it: armed this tick, and not the held release being sent. */
    public boolean hold(Context now) {
        if (!armed || sending) return false;
        if (!pending) context = now;
        pending = true;
        return true;
    }
    /** A release is held for {@code entity} this tick (it is about to be aimed and sent with this tick's movement packet). */
    public boolean pendingFor(Object entity) { return pending && entity == owner; }
    /**
     * This tick's movement packet has gone for {@code entity}. SEND: the held release goes now. DROP: its use is no longer
     * there, it does not go. NONE: nothing is held for this entity.
     */
    public Outcome afterMove(Object entity, Context now, boolean usable) {
        if (!pending || entity != owner) return Outcome.NONE;
        Outcome outcome = usable && context.equals(now) ? Outcome.SEND : Outcome.DROP;
        clear(); // settled: nothing is held any more
        return outcome;
    }
    /** The end of the tick, with the player there now (null when there is none). As {@link #afterMove}; everything is cleared either way. */
    public Outcome endOfTick(Object current, Context now, boolean usable) {
        Outcome outcome = !pending ? Outcome.NONE
                : current != null && current == owner && usable && context.equals(now) ? Outcome.SEND : Outcome.DROP;
        clear(); // the end of the tick: nothing outlives it
        return outcome;
    }
    /** The use context vanilla released in, while a release is held (null otherwise). */
    public Context heldContext() { return pending ? context : null; }
    /** Vanilla's release is called again to send the held one: it passes, and is not held again. */
    public void sending(boolean on) { sending = on; }
    public boolean sending() { return sending; }
    public boolean armed() { return armed; }
    public boolean pending() { return pending; }
    public void clear() {
        owner = null;
        context = null;
        armed = false;
        pending = false;
    }
}
