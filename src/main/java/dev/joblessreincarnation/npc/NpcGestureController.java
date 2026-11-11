package dev.joblessreincarnation.npc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * An NPC's one-shot gesture controller: idle until the server triggers one of its clips by name through GeckoLib's own
 * sync, {@code animation.<model>.<trigger>} each. Registered after locomotion, so while a clip plays the bones it keys
 * take over. Its presentation clock follows GeckoLib's own phase and tick offset, so skipped render frames or replacing
 * a renderer during resource reload cannot restart a model's easing independently of the clip. Common code: no client
 * classes, packets, saved state, or gameplay effects.
 *
 * <p>GeckoLib only advances a controller while its NPC is drawn, so a clip triggered off screen would wait and start
 * whenever the NPC came into view, long after the lines and particles the server sent with it. Clips marked
 * {@link #timed} keep the time they arrived instead: drawn late they start where that time puts them, and one already
 * over by then is not played. Untimed clips (the greetings, which the server stops by name) behave as GeckoLib does.
 */
public class NpcGestureController<T extends GeoAnimatable> extends AnimationController<T> {
    /**
     * A clip first drawn this many ticks after it arrived was off screen meanwhile: one that arrives on screen is drawn
     * within the next client tick at 20 frames per second or more.
     */
    static final double UNSEEN_TICKS = 2.0;
    private final String model;
    private final int transitionTicks;
    /** The animatable's client tick count, which keeps counting while it is off screen. */
    private final ToDoubleFunction<? super T> clock;
    private final Set<RawAnimation> timed = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Clock reading when the timed clip that has not been drawn yet arrived; NaN when none is waiting. */
    private double arrivedAt = Double.NaN;
    /** What this frame's first adjustTick returned during a transition; NaN otherwise. */
    private double transitionTick = Double.NaN;

    public NpcGestureController(T animatable, String model, int transitionTicks, ToDoubleFunction<? super T> clock, String... triggers) {
        super(animatable, NpcAnimations.GESTURE_CONTROLLER, transitionTicks, state -> PlayState.STOP);
        this.model = model;
        this.transitionTicks = transitionTicks;
        this.clock = clock;
        for (String trigger : triggers) triggerableAnim(trigger, RawAnimation.begin().thenPlay(clipName(trigger)));
    }

    /** Marks triggers whose clips keep the time they arrived; see the class description. */
    public final NpcGestureController<T> timed(String... triggers) {
        for (String trigger : triggers) {
            RawAnimation clip = triggerableAnimations.get(trigger);
            if (clip == null) throw new IllegalArgumentException("Not a trigger of this controller: " + trigger);
            timed.add(clip);
        }
        return this;
    }

    /** The clip a trigger plays, as named in the model's animation file. */
    public final String clipName(String trigger) {
        return "animation." + model + "." + trigger;
    }

    /**
     * A stopped trigger can still retain GeckoLib's empty old queue until its next idle frame. A timed clip always
     * starts over: a repeat can arrive while the last one is still waiting to be drawn, or still playing.
     */
    @Override
    public boolean tryTriggerAnimation(String animationName) {
        RawAnimation clip = triggerableAnimations.get(animationName);
        boolean keepsTime = clip != null && timed.contains(clip);
        if (clip != null && (keepsTime || hasAnimationFinished())) forceAnimationReset();
        boolean accepted = super.tryTriggerAnimation(animationName);
        if (accepted) {
            double now = keepsTime ? clock.applyAsDouble(animatable) : Double.NaN;
            arrivedAt = Double.isFinite(now) ? now : Double.NaN;
        }
        return accepted;
    }

    @Override
    protected boolean stopTriggeredAnimation() {
        arrivedAt = Double.NaN;
        return super.stopTriggeredAnimation();
    }

    @Override
    public void process(GeoModel<T> model, AnimationState<T> state, Map<String, GeoBone> bones,
            Map<String, BoneSnapshot> snapshots, double seekTime, boolean crashWhenCantFindBone) {
        // Ticks since it arrived; a clock that is not a number, or has gone back, counts as none, and so does a speed
        // GeckoLib cannot run forward through (none, backwards, not a number, endless).
        double late = Double.isNaN(arrivedAt) ? 0.0 : clock.applyAsDouble(animatable) + state.getPartialTick() - arrivedAt;
        double speed = getAnimationSpeed();
        arrivedAt = Double.NaN;
        super.process(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
        if (Double.isFinite(late) && late >= UNSEEN_TICKS && speed > 0.0 && Double.isFinite(speed)
                && animationState == State.TRANSITIONING && currentAnimation != null && playingTimed()) {
            // Its first drawn frame: GeckoLib has queued the clip and saved the pose to blend from, starting at 0.
            // Nothing was drawn since it arrived, so there is no shown pose to blend from; move it on by the ticks it
            // waited (the offset counts in those ticks; GeckoLib applies the animation speed) and work this frame out
            // again.
            tickOffset = seekTime - late;
            super.process(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
        }
        // Over, on arrival or by playing out: clear the trigger now rather than on the next drawn frame.
        if (animationState == State.STOPPED && playingTimed()) stopTriggeredAnimation();
    }

    /**
     * At the end of a timed clip's transition GeckoLib restarts the tick at 0, dropping whatever ran past the
     * transition, which is a whole stretch when the NPC was off screen meanwhile; carry that into the clip instead.
     */
    @Override
    protected double adjustTick(double tick) {
        if (shouldResetTick && animationState == State.RUNNING && transitionTick >= transitionLength && playingTimed()) {
            double speed = getAnimationSpeed(), past = transitionTick - transitionLength;
            // Only for a speed it can count back through; any other is left to GeckoLib's own restart at 0.
            if (speed > 0.0 && Double.isFinite(speed) && Double.isFinite(past)) {
                transitionTick = Double.NaN;
                shouldResetTick = false;
                tickOffset = tick - past / speed;
                return past;
            }
        }
        double adjusted = super.adjustTick(tick);
        transitionTick = animationState == State.TRANSITIONING ? adjusted : Double.NaN;
        return adjusted;
    }

    /** Whether the clip in hand is a timed trigger's own. */
    private boolean playingTimed() {
        return triggeredAnimation != null && timed.contains(triggeredAnimation) && currentRawAnimation == triggeredAnimation;
    }

    /**
     * Read after GeckoLib processes this frame. Returns transition progress, or the transition length plus the current
     * clip tick once running; {@link Double#NaN} means that trigger's clip is not playing. This is a presentation phase,
     * not elapsed wall time: GeckoLib restarts its tick offset on the first rendered frame completing a transition.
     * Reading the protected offset avoids calling {@code adjustTick}, which would mutate the controller's pending reset
     * flag.
     */
    public double presentationTicks(String trigger, double now) {
        if (!Double.isFinite(now) || !isPlayingTriggeredAnimation() || getCurrentAnimation() == null
                || !clipName(trigger).equals(getCurrentAnimation().animation().name())) return Double.NaN;
        double tick = Math.max(0.0, now - tickOffset) * getAnimationSpeed();
        if (!Double.isFinite(tick)) return Double.NaN;
        return switch (getAnimationState()) {
            case TRANSITIONING -> tick;
            case RUNNING -> transitionTicks + tick;
            default -> Double.NaN;
        };
    }
}
