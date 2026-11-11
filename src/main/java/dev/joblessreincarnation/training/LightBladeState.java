package dev.joblessreincarnation.training;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Private, per-player and per-connection. A stale release cannot remove or spend a later session. */
public final class LightBladeState {
    public record Hold(long request, long startedAt, BattleAura.Gathering gathering, @Nullable UUID dummy) {}

    private long lastRequest, pendingRequest, pendingAt;
    private Hold hold;
    private boolean holdingPose;

    public boolean offer(ServerPlayer player, long request) {
        if (request <= 0 || request <= lastRequest || hold != null
                || (pendingRequest != 0 && pendingAt == player.level().getGameTime())) return false;
        pendingRequest = request;
        pendingAt = player.level().getGameTime();
        return true;
    }

    public void cancelled(long request) {
        if (request <= 0) return;
        lastRequest = Math.max(lastRequest, request);
        clearPending(request);
    }

    public void clearPending(long request) {
        if (request == pendingRequest) pendingRequest = 0;
    }

    public boolean begin(ServerPlayer player, @Nullable PracticeDummyEntity dummy) {
        long now = player.level().getGameTime();
        long request = pendingRequest;
        pendingRequest = 0;
        if (hold != null || request <= lastRequest || pendingAt != now) return false;
        var guard = BattleAura.beginGathering(player, LightBladeTiming.MAX_HOLD_TICKS);
        if (guard == null) return false;
        lastRequest = request;
        hold = new Hold(request, now, guard, dummy == null ? null : dummy.getUUID());
        holdingPose = false;
        return true;
    }

    public @Nullable Hold current() { return hold; }
    public boolean matches(long request) { return request > 0 && hold != null && request == hold.request(); }

    public boolean startLoop(long now) {
        if (hold == null || holdingPose || now - hold.startedAt() < LightBladeTiming.TIER_ONE_TICKS) return false;
        holdingPose = true;
        return true;
    }

    public @Nullable Hold finish(ServerPlayer player) {
        var old = hold;
        hold = null;
        pendingRequest = 0;
        holdingPose = false;
        if (old == null || !BattleAura.endGathering(player, old.gathering())) return null;
        return old;
    }
}
// 4C653975
