package dev.joblessreincarnation.npc;

import java.util.UUID;
import javax.annotation.Nullable;

/** Per-resident server clock. Deliberately absent from NBT and independent of a player's continued presence. */
final class NpcGreetingState {
    private long observed = Long.MIN_VALUE;
    private long cooldownUntil = Long.MIN_VALUE;
    private long until;
    private boolean active;
    @Nullable private UUID target;

    /** Returns true exactly when an active greeting expired (including a backwards clock). */
    boolean advance(long now) {
        boolean wasActive = active;
        if (observed != Long.MIN_VALUE && now < observed) reset();
        observed = now;
        if (active && now >= until) cancel();
        return wasActive && !active;
    }

    boolean start(long now, int duration, UUID actor) {
        if (actor == null || duration < 1 || duration > 80 || now > Long.MAX_VALUE - NpcGreetings.COOLDOWN_TICKS)
            return false;
        advance(now);
        if (active || now < cooldownUntil) return false;
        active = true;
        target = actor;
        until = now + duration;
        cooldownUntil = now + NpcGreetings.COOLDOWN_TICKS;
        return true;
    }

    boolean active() { return active; }
    long until() { return active ? until : 0L; }
    @Nullable UUID target() { return target; }
    void forgetTarget() { target = null; }

    /** Teaching cancels the gesture but does not refund its shared cooldown. */
    void cancel() { active = false; target = null; until = 0L; }
    void reset() { cancel(); observed = Long.MIN_VALUE; cooldownUntil = Long.MIN_VALUE; }
}
