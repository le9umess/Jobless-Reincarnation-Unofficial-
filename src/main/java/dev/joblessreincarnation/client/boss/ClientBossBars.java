package dev.joblessreincarnation.client.boss;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The boss bars this client knows about, with the little timing each needs to animate: the pale trail left by a hit, the
 * fill at the start and after each emptied bar, the pause between bars counting down, and the boss's line fading in and
 * out. The server sends whole views; {@link #accept} and {@link #remove} are the only ways in. A view seen for the first
 * time (someone walking up mid-fight) shows the fight as it stands, without replaying a refill or an old line.
 */
public final class ClientBossBars {
    /** The trail holds still this long after a hit, then slides down to the bar over {@link #TRAIL_SLIDE}. */
    static final long TRAIL_HOLD = 250L, TRAIL_SLIDE = 300L;
    /** At the start and after an emptied bar, the bar fills over this long with one sweep of light. */
    static final long REFILL = 700L;
    private static final long LINE_FADE_IN = 150L, LINE_FADE_OUT = 500L;

    static final class Shown {
        BossBarView view;
        float trail;
        long trailSince;
        long filledAt = Long.MIN_VALUE;
        /** Whether the refill under way also lifts the seal (it was sealed before this bar). */
        boolean lifting;
        @Nullable Component line;
        int lineId;
        long lineSince, lineUntil;
        long stageUntil;

        Shown(BossBarView view, long now) {
            this.view = view;
            trail = view.progress();
            // A fight just starting fills its bar; one already under way is shown as it stands.
            if (view.stage() == BossBarView.Stage.INTRO) filledAt = now;
            stageUntil = now + view.stageTicks() * 50L;
            line = view.line();
            lineId = view.lineId();
            lineSince = now - LINE_FADE_IN;
            lineUntil = view.line() == null ? now : now + view.lineTicks() * 50L;
        }

        /** The bar as drawn now: while filling it grows from empty to the server's level. */
        float progress(long now) {
            float target = Mth.clamp(view.progress(), 0, 1);
            long since = now - filledAt;
            if (since < 0 || since >= REFILL) return target;
            float t = since / (float) REFILL;
            return target * (1 - (1 - t) * (1 - t));
        }

        /** The pale trail: held at the level before the last hit, then sliding down to the bar. */
        float trail(long now) {
            float bar = progress(now);
            long since = now - trailSince;
            if (trail <= bar) return bar;
            if (since < TRAIL_HOLD) return trail;
            float t = Math.min(1, (since - TRAIL_HOLD) / (float) TRAIL_SLIDE);
            return trail + (bar - trail) * t;
        }

        /** How far the fill's light has swept, 0 to 1, or -1 when the bar is not filling. */
        float sweep(long now) {
            long since = now - filledAt;
            return since < 0 || since >= REFILL ? -1 : since / (float) REFILL;
        }

        /** Milliseconds left in the intro or the pause between bars; 0 while fighting or after the end. */
        long waiting(long now) {
            boolean waits = view.stage() == BossBarView.Stage.INTRO || view.stage() == BossBarView.Stage.INTERMISSION;
            return waits ? Math.max(0, stageUntil - now) : 0;
        }

        /** 0 to 1: how visible the line is now. */
        float lineAlpha(long now) {
            if (line == null || now >= lineUntil) return 0;
            return Math.min(Math.min(1, (now - lineSince) / (float) LINE_FADE_IN), (lineUntil - now) / (float) LINE_FADE_OUT);
        }
    }

    private static final Map<UUID, Shown> BARS = new LinkedHashMap<>();

    /** Show or update a boss bar. A lower level in the same bar leaves a trail; a new transition refills once. */
    public static void accept(BossBarView view) {
        long now = Util.getMillis();
        Shown shown = BARS.get(view.id());
        if (shown == null) { BARS.put(view.id(), new Shown(view, now)); return; }
        if (view.transition() > shown.view.transition()) {
            shown.filledAt = now;
            shown.lifting = shown.view.sealed() && !view.sealed();
            shown.trail = 0;
        } else if (view.phase() == shown.view.phase() && view.progress() < shown.view.progress()) {
            shown.trail = Math.max(shown.trail(now), shown.progress(now));
            shown.trailSince = now;
        }
        if (view.line() == null) {
            shown.line = null;
        } else {
            // A new line fades in; the same line sent again only has its time left corrected.
            if (shown.line == null || view.lineId() != shown.lineId) shown.lineSince = now;
            shown.line = view.line();
            shown.lineId = view.lineId();
            shown.lineUntil = now + view.lineTicks() * 50L;
        }
        shown.stageUntil = now + view.stageTicks() * 50L;
        shown.view = view;
    }

    public static void remove(UUID id) {
        BARS.remove(id);
    }

    public static void clear() {
        BARS.clear();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** Changing dimension or world starts afresh: the server sends whole views again for whatever is in range. */
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clear();
    }

    /** At most {@code max} bars in the player's dimension: their own fight first, then the nearest. */
    static List<Shown> visible(Player player, int max) {
        List<Shown> here = new ArrayList<>();
        for (Shown shown : BARS.values()) if (shown.view.dimension().equals(player.level().dimension())) here.add(shown);
        here.sort(Comparator.comparing((Shown s) -> !s.view.participant())
                .thenComparingDouble(s -> s.view.centre().distanceToSqr(player.position())));
        return here.size() > max ? here.subList(0, max) : here;
    }

    private ClientBossBars() {}
}
