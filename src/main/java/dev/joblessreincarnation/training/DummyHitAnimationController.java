package dev.joblessreincarnation.training;

import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/** The server's four-tick visual budget already decides which strikes deserve a new reaction. */
public final class DummyHitAnimationController extends AnimationController<PracticeDummyEntity> {
    public DummyHitAnimationController(PracticeDummyEntity entity, RawAnimation idle, RawAnimation hit) {
        super(entity, PracticeDummyEntity.CONTROLLER, 0, state -> state.setAndContinue(idle));
        triggerableAnim(PracticeDummyEntity.HIT_TRIGGER, hit);
    }

    @Override
    public boolean tryTriggerAnimation(String animationName) {
        if (PracticeDummyEntity.HIT_TRIGGER.equals(animationName)) {
            // GeckoLib otherwise retains an equal RawAnimation during playback, swallowing another
            // player's hit at ticks 4..7. Rebuild only this accepted visual trigger, including its
            // completion boundary; idle, lesson counts and the server's visual limit stay unchanged.
            forceAnimationReset();
        }
        return super.tryTriggerAnimation(animationName);
    }
}
