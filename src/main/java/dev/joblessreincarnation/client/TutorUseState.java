package dev.joblessreincarnation.client;

/**
 * One physical use press may start one tutor interaction. This class has no client-runtime
 * dependencies so its lifecycle can also be checked by dedicated-server GameTests.
 */
public final class TutorUseState {
    private boolean down;
    private boolean permit;
    private boolean claimed;
    private boolean ordinaryUse;

    /** A GLFW PRESS, never a key-repeat event. Invalid-context presses cannot unlock a claim. */
    public void press(boolean eligible) {
        if (!eligible) {
            down = true;
            permit = false;
            ordinaryUse = false;
            return;
        }
        if (down) return;
        down = true;
        permit = true;
        claimed = false;
        ordinaryUse = false;
    }

    /** Releasing does not grant permission and does not discard a queued short click. */
    public void release() { down = false; }

    /**
     * A changed input context cancels pending permission, but keeps any owned gesture blocked.
     * Forget the physical-down sample because focus loss/rebinding may hide the old key release.
     * Only a later eligible PRESS can grant permission again.
     */
    public void invalidate() {
        down = false;
        permit = false;
        ordinaryUse = false;
    }

    /**
     * @return true to cancel the entire vanilla use attempt, including its other hand/item fallback
     */
    public boolean interact(boolean tutorTarget, boolean mainHand, boolean eligible) {
        if (claimed) return true;
        if (!tutorTarget) {
            // Only a fresh, eligible press establishes an ordinary gesture. Stale logical use does not.
            if (permit && eligible) ordinaryUse = true;
            permit = false;
            return false;
        }
        // Crossing a tutor blocks this entire attempt, but does not take over an existing ordinary hold.
        if (ordinaryUse && eligible) return true;
        boolean allow = permit && mainHand && eligible;
        permit = false;
        claimed = true;
        return !allow;
    }

    public boolean claimed() { return claimed; }
    public boolean hasPermit() { return permit; }
    public boolean isDown() { return down; }
}
