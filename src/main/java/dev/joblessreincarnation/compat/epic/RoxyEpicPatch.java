package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.npc.RoxyTutorEntity;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.damagesource.StunType;

/** Visual humanoid rig only: preserve the tutor's protected, non-combat AI. */
public final class RoxyEpicPatch extends MobPatch<RoxyTutorEntity> {
    public RoxyEpicPatch(RoxyTutorEntity entity) { super(entity, Factions.NEUTRAL); }
    @Override protected void initAI() { /* Keep all original conversation/home goals. */ }
    @Override protected void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, NpcEpicAnimations.of(getOriginal()).idle());
        animator.addLivingAnimation(LivingMotions.WALK, NpcEpicAnimations.of(getOriginal()).walk());
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        // Only administrative removal can kill this protected tutor; do not extend its vanilla removal timer.
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.EMPTY_ANIMATION);
    }
    @Override public void updateMotion(boolean considerInaction) { commonMobUpdateMotion(considerInaction); }
    @Override public AssetAccessor<? extends StaticAnimation> getHitAnimation(StunType type) {
        return Animations.BIPED_HIT_SHORT;
    }
    @Override public boolean applyStun(StunType type, float duration) { return false; }
}
