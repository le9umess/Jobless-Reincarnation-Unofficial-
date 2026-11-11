package dev.joblessreincarnation.encounter;

import dev.joblessreincarnation.compat.epic.RastaBearCombat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public final class RastaBearEntity extends Monster implements GeoEntity {
    public static final int SWIPE_TICK = 13;
    public static final int CONTACT_TICK = 14;
    public static final int RECOVERY_TICK = 16;
    public static final int END_TICK = 32;
    static final double START_RANGE_SQR = 2.90 * 2.90;
    private static final double LUNGE_STEP = .30;
    private static final double CONTACT_SPACE = 2.30;
    // Source walk cycle at unit scale, converted to world blocks per second for this model.
    private static final double WALK_REFERENCE_SPEED = .493 * 1.3;
    private static final int HURT_TICKS = 10;
    private static final int PAW = 1, RECOIL = 2;
    // Bounds of the authored right foreleg at ticks 14 and 15, in world blocks at 1.3 render scale.
    private static final PawBox CONTACT = new PawBox(.12,.57, .25,.89, 1.16,2.05);
    private static final PawBox FOLLOW_THROUGH = new PawBox(.14,.54, .03,.85, 1.08,1.97);
    // Phase and start tick travel together, including for a client entering tracking range mid-strike.
    private static final EntityDataAccessor<Long> ACTION = SynchedEntityData.defineId(RastaBearEntity.class, EntityDataSerializers.LONG);
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private LivingEntity committedTarget;
    private float committedYaw;
    private int lungeSteps;
    private long nextStrikeAt;
    private float walkRate = 1;

    public enum Motion {
        IDLE("idle", true), WALK("walk", true), WARN("warn", false), SWIPE("swipe", false),
        RECOVER("recover", false), HURT("hurt", false), DEATH("death", false);
        final RawAnimation clip;
        Motion(String name, boolean looping) {
            String key = "animation.rasta_bear." + name;
            clip = looping ? RawAnimation.begin().thenLoop(key) : RawAnimation.begin().thenPlayAndHold(key);
        }
    }

    public RastaBearEntity(EntityType<? extends RastaBearEntity> type, Level level) {
        super(type, level);
        xpReward = 0;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 32).add(Attributes.ATTACK_DAMAGE, 6)
                .add(Attributes.MOVEMENT_SPEED, .24).add(Attributes.FOLLOW_RANGE, 14).add(Attributes.KNOCKBACK_RESISTANCE, .18);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION, 0L);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new BearEncounterGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, .65));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    public long motionStartTick() { return entityData.get(ACTION) >>> 2; }

    public Motion visualPhase() {
        if (isDeadOrDying()) return Motion.DEATH;
        int action = (int)(entityData.get(ACTION) & 3);
        long age = level().getGameTime() - motionStartTick();
        if (action == PAW && age < END_TICK) {
            if (age < SWIPE_TICK) return Motion.WARN;
            return age < RECOVERY_TICK ? Motion.SWIPE : Motion.RECOVER;
        }
        return action == RECOIL && age < HURT_TICKS ? Motion.HURT : Motion.IDLE;
    }

    boolean activePaw() { return (entityData.get(ACTION) & 3) == PAW && level().getGameTime() - motionStartTick() < END_TICK; }

    public boolean startPaw(LivingEntity target) {
        if (!(level() instanceof ServerLevel) || !isAlive() || !eligible(target) || getTarget() != target
                || distanceToSqr(target) > START_RANGE_SQR || !hasLineOfSight(target) || hasEffect(MobEffects.BLINDNESS)
                || level().getGameTime() < nextStrikeAt || activePaw() || visualPhase() == Motion.HURT) return false;
        var patch = RastaBearCombat.patch(this);
        if (patch == null || patch.getEntityState().inaction() || patch.isStunned()) return false;
        faceTarget(target);
        committedYaw = getYRot();
        committedTarget = target;
        lungeSteps = 0;
        nextStrikeAt = level().getGameTime() + END_TICK + 6;
        getNavigation().stop();
        setZza(0);
        setXxa(0);
        setSpeed(0);
        Vec3 drift = getDeltaMovement();
        setDeltaMovement(0, drift.y, 0);
        entityData.set(ACTION, (level().getGameTime() << 2) | PAW);
        playSound(SoundEvents.POLAR_BEAR_WARNING, 1.10F, .70F);
        patch.playAnimationSynchronized(RastaBearCombat.PAW, 0);
        return true;
    }

    @Override public void setTarget(@Nullable LivingEntity newTarget) {
        super.setTarget(newTarget);
        // Re-acquiring the same player later cannot revive a strike whose original target was lost.
        if (committedTarget != null && getTarget() != committedTarget) committedTarget = null;
    }

    private void faceTarget(LivingEntity target) {
        float yaw = (float)Math.toDegrees(Math.atan2(-(target.getX()-getX()), target.getZ()-getZ()));
        face(yaw);
    }

    private void face(float yaw) {
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    static boolean eligible(@Nullable LivingEntity other) {
        return other != null && other.isAlive() && !other.isRemoved()
                && (!(other instanceof Player p) || (!p.isCreative() && !p.isSpectator()));
    }

    /** Called by the native attack clock before its contact query. Never track a dodge or push into a close camera. */
    public void advancePaw(boolean atContact) {
        int dueSteps = atContact ? 2 : 1;
        if (!activePaw() || !eligible(committedTarget) || getTarget() != committedTarget || !isAlive()
                || hasEffect(MobEffects.BLINDNESS) || !hasLineOfSight(committedTarget)) return;
        Vec3 forward = Vec3.directionFromRotation(0, committedYaw);
        while (lungeSteps < dueSteps) {
            lungeSteps++;
            double gap = committedTarget.position().subtract(position()).dot(forward);
            double step = Math.clamp(gap - CONTACT_SPACE, 0, LUNGE_STEP);
            if (step > 0) move(net.minecraft.world.entity.MoverType.SELF, forward.scale(step));
        }
    }

    /** The EF contact sweep can query twice across a phase boundary. Even an empty first sweep spends this paw. */
    public @Nullable LivingEntity takePawContact() {
        LivingEntity victim = committedTarget;
        committedTarget = null;
        if (!activePaw() || !isAlive() || !eligible(victim) || victim.level() != level()
                || getTarget() != victim || isAlliedTo(victim) || hasEffect(MobEffects.BLINDNESS) || !hasLineOfSight(victim)) return null;
        Vec3 forward = Vec3.directionFromRotation(0, committedYaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        if (!CONTACT.overlaps(this, right, forward, victim.getBoundingBox())
                && !FOLLOW_THROUGH.overlaps(this, right, forward, victim.getBoundingBox())) return null;
        Vec3 from = position().add(0, 1.05, 0).add(forward.scale(.40)).add(right.scale(.30));
        Vec3 palm = position().add(0, .57, 0).add(forward.scale(1.70)).add(right.scale(.34));
        AABB box = victim.getBoundingBox();
        Vec3 onTarget = new Vec3(Math.clamp(palm.x, box.minX, box.maxX), Math.clamp(palm.y, box.minY, box.maxY),
                Math.clamp(palm.z, box.minZ, box.maxZ));
        return level().clip(new ClipContext(from, onTarget, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS ? victim : null;
    }

    private record PawBox(double left, double right, double low, double high, double near, double far) {
        private static final double MARGIN = .03;

        boolean overlaps(RastaBearEntity bear, Vec3 side, Vec3 forward, AABB target) {
            if (target.maxY < bear.getY() + low - MARGIN || target.minY > bear.getY() + high + MARGIN) return false;
            Vec3 middle = bear.position().add(side.scale((left + right) * .5)).add(forward.scale((near + far) * .5));
            Vec3 delta = target.getCenter().subtract(middle);
            double sw = (right - left) * .5 + MARGIN, fw = (far - near) * .5 + MARGIN;
            double tx = target.getXsize() * .5, tz = target.getZsize() * .5;
            // Check both paw axes and both world axes so diagonal swings have no oversized corner.
            return Math.abs(delta.dot(side)) <= sw + tx * Math.abs(side.x) + tz * Math.abs(side.z)
                    && Math.abs(delta.dot(forward)) <= fw + tx * Math.abs(forward.x) + tz * Math.abs(forward.z)
                    && Math.abs(delta.x) <= tx + sw * Math.abs(side.x) + fw * Math.abs(forward.x)
                    && Math.abs(delta.z) <= tz + sw * Math.abs(side.z) + fw * Math.abs(forward.z);
        }
    }

    public void finishPaw() {
        committedTarget = null;
        if ((entityData.get(ACTION) & 3) == PAW) entityData.set(ACTION, 0L);
    }

    public void recoil() {
        committedTarget = null;
        if (!level().isClientSide && isAlive()) {
            getNavigation().stop();
            nextStrikeAt = Math.max(nextStrikeAt, level().getGameTime() + HURT_TICKS);
            entityData.set(ACTION, (level().getGameTime() << 2) | RECOIL);
        }
    }

    @Override public void aiStep() {
        super.aiStep();
        if (level().isClientSide) {
            double blocksPerSecond = Math.hypot(getX() - xo, getZ() - zo) * 20;
            float measured = (float)Math.clamp(blocksPerSecond / WALK_REFERENCE_SPEED, .15, 6.0);
            walkRate += (measured - walkRate) * .5F;
        } else if (activePaw()) face(committedYaw);
    }

    @Override protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.POLAR_BEAR_STEP, .32F, .74F);
    }

    @Override public float getVoicePitch() {
        return super.getVoicePitch() * .78F;
    }

    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.POLAR_BEAR_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.POLAR_BEAR_DEATH; }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "encounter", 0, this::selectAnimation));
    }

    private PlayState selectAnimation(AnimationState<RastaBearEntity> state) {
        Motion motion = visualPhase();
        if (motion == Motion.IDLE && hurtTime > 0) motion = Motion.HURT;
        if (motion == Motion.IDLE && state.isMoving()) motion = Motion.WALK;
        state.getController().transitionLength(motion == Motion.HURT || motion == Motion.DEATH ? 3 : 0);
        state.setControllerSpeed(motion == Motion.WALK ? walkRate : 1);
        return state.setAndContinue(motion.clip);
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return animationCache; }
}
