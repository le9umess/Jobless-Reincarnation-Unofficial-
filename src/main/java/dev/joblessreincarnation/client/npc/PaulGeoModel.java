package dev.joblessreincarnation.client.npc;

import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.SwordDemoAnimationController;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Paul's model: like every family member his head follows where he looks and composes the greeting (FamilyGeoModel),
 * and while he plays sword_demo it eases to straight ahead and back. He looks along his own cut instead of at the
 * player, and his raised arm cannot pass through a head turned toward it: the back corner of his shoulder rises to the
 * height of his jaw when the arm swings forward, and with the head turned 15 degrees or more toward either side the
 * face's corner reaches it (offline check, B01 motion). Client only; nothing is synced. The ease reads this entity's
 * actual gesture phase, with no renderer-owned timer.
 */
final class PaulGeoModel extends FamilyGeoModel {
    /**
     * Presentation ticks include GeckoLib's four-tick blend. The arm is down at clip tick 29 (phase 33), then the head
     * returns during the final five clip ticks. These thresholds follow clip progress even after a slow render frame.
     */
    private static final double EASE_IN = 6.0, EASE_OUT_START = 33.0, EASE_OUT_END = 38.0;

    PaulGeoModel() {
        super(FamilyResidentEntity.Member.PAUL);
    }

    @Override
    void adjustLook(FamilyResidentEntity paul, long instanceId, GeoBone head, double now) {
        double keep = lookShare(paul, instanceId, now);
        if (keep >= 1.0) return;
        head.setRotX((float) (head.getRotX() * keep));
        head.setRotY((float) (head.getRotY() * keep));
    }

    /** Share of the look the head keeps: 1 normally, easing to 0 while sword_demo plays. */
    private double lookShare(FamilyResidentEntity paul, long instanceId, double now) {
        AnimationController<?> gesture = paul.getAnimatableInstanceCache().getManagerForId(instanceId).getAnimationControllers()
                .get(NpcAnimations.GESTURE_CONTROLLER);
        if (!(gesture instanceof SwordDemoAnimationController demo)) return 1.0;
        double elapsed = demo.presentationTicks(now);
        if (!Double.isFinite(elapsed)) return 1.0;
        double away = elapsed < EASE_OUT_START ? smooth(elapsed / EASE_IN)
                : 1.0 - smooth((elapsed - EASE_OUT_START) / (EASE_OUT_END - EASE_OUT_START));
        return 1.0 - away;
    }

    private static double smooth(double x) {
        double t = Math.max(0.0, Math.min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }
}
