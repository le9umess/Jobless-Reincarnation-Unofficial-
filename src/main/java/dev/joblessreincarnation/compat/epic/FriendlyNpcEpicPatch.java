package dev.joblessreincarnation.compat.epic;

import net.minecraft.world.entity.PathfinderMob;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.damagesource.StunType;

/** Model-stage friendly rig: the existing entity continues to own AI, lessons and persistence. */
public class FriendlyNpcEpicPatch<T extends PathfinderMob> extends MobPatch<T> {
    public FriendlyNpcEpicPatch(T entity) { super(entity, Factions.NEUTRAL); }
    @Override protected void initAI() { /* Preserve all original home and conversation goals. */ }
    @Override protected void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, NpcEpicAnimations.of(getOriginal()).idle());
        animator.addLivingAnimation(LivingMotions.WALK, NpcEpicAnimations.of(getOriginal()).walk());
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.EMPTY_ANIMATION);
    }
    @Override public void updateMotion(boolean considerInaction) { commonMobUpdateMotion(considerInaction); }
    @Override public AssetAccessor<? extends StaticAnimation> getHitAnimation(StunType type) { return Animations.BIPED_HIT_SHORT; }
    @Override public boolean applyStun(StunType type, float duration) { return false; }
}
