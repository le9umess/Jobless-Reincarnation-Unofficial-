package dev.joblessreincarnation.npc;

/**
 * Paul's gesture controller: his sword demonstration (B01) and, since S-03, his greeting. Kept as its own class so code
 * that looks for Paul's sword controller still finds it; {@link #presentationTicks(double)} reports the sword
 * demonstration alone, as before. The demonstration keeps the time it arrived ({@link NpcGestureController#timed}).
 */
public final class SwordDemoAnimationController extends NpcGestureController<FamilyResidentEntity> {
    private static final int TRANSITION_TICKS = 4;

    public SwordDemoAnimationController(FamilyResidentEntity entity) {
        super(entity, FamilyResidentEntity.Member.PAUL.id(), TRANSITION_TICKS, NpcAnimations::clock, NpcAnimations.SWORD_DEMO,
                NpcAnimations.GREETING);
        timed(NpcAnimations.SWORD_DEMO);
    }

    /** Presentation ticks of the sword demonstration; see {@link NpcGestureController#presentationTicks(String, double)}. */
    public double presentationTicks(double now) {
        return presentationTicks(NpcAnimations.SWORD_DEMO, now);
    }
}
