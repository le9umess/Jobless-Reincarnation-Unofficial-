package dev.joblessreincarnation.npc;

import software.bernie.geckolib.animatable.GeoEntity;

/** S-03 presentation contract; no reward, learned state, inventory mutation or persistent greeting counters. */
public final class NpcGreetings {
    public static final String TRIGGER = "greeting";
    public static final int COOLDOWN_TICKS = 200;
    /** Four incoming blend ticks plus a bounded settling allowance; verify against the final clips. */
    public static final int SETTLE_TICKS = 12;
    public static final int SYLPHIETTE_HOLD_TICKS = 52 + SETTLE_TICKS;
    /** Roxy's nod and wave is fifty-two ticks long. */
    public static final int ROXY_HOLD_TICKS = 52 + SETTLE_TICKS;

    public static int holdTicks(FamilyResidentEntity.Member member) {
        return switch (member) {
            case PAUL -> 48 + SETTLE_TICKS;
            case ZENITH -> 52 + SETTLE_TICKS;
            case LILIA -> 44 + SETTLE_TICKS;
        };
    }

    static void play(GeoEntity entity) { entity.triggerAnim(NpcAnimations.GESTURE_CONTROLLER, TRIGGER); if(entity instanceof net.minecraft.world.entity.Entity e) dev.joblessreincarnation.compat.epic.NpcEpicGestures.greeting(e); }
    static void stop(GeoEntity entity) { entity.stopTriggeredAnim(NpcAnimations.GESTURE_CONTROLLER, TRIGGER); if(entity instanceof net.minecraft.world.entity.Entity e) dev.joblessreincarnation.compat.epic.NpcEpicGestures.stop(e,dev.joblessreincarnation.compat.epic.NpcGestureVisual.Kind.GREETING); }
    private NpcGreetings() {}
}
