package dev.joblessreincarnation.npc;

import com.google.common.collect.MapMaker;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/** GeckoLib controllers shared by the mod's NPCs. Common code: it is loaded on dedicated servers and never touches client classes. */
public final class NpcAnimations {
    /** Ticks GeckoLib spends blending from one clip into the next. */
    private static final int TRANSITION_TICKS = 4;
    /** Walk amounts at which locomotion switches to walk, and back to idle; the gap keeps a start from flickering. */
    private static final float WALK_START = 0.12F, WALK_STOP = 0.05F;
    /** Controller of every one-shot gesture, and the trigger name of the teaching gesture. */
    public static final String GESTURE_CONTROLLER = "gesture", LECTURE = "lecture";
    /**
     * Trigger name of the S-03 greeting: {@code animation.<model>.greeting} for Paul, Zenith, Lilia and Sylphiette. The
     * server starts and stops it by name ({@code triggerAnim} / {@code stopTriggeredAnim} on {@link #GESTURE_CONTROLLER}).
     */
    public static final String GREETING = "greeting";
    /** The lecture clip lasts 1.6 s; one NPC starts it at most once per this many ticks, however many people ask. */
    public static final int LECTURE_INTERVAL_TICKS = 40;
    /** Game time of each NPC's last lecture start. Weak keys compare by identity; runtime only, never saved. */
    private static final Map<Entity, Long> LECTURE_STARTS = new MapMaker().weakKeys().makeMap();

    /**
     * Plays {@code animation.<model>.walk} while the entity is walking and {@code animation.<model>.idle} otherwise,
     * matching the clip names in {@code assets/jobless_reincarnation/animations/entity/<model>.animation.json}.
     * Walking is judged from the renderer's walk amount (smoothed from the interpolated position) with a start and a
     * stop threshold, not from GeckoLib's isMoving(): that compares the client's sparsely synced velocity with one
     * threshold and toggled every few ticks while an NPC set off.
     */
    public static <T extends Entity & GeoEntity> void registerLocomotion(AnimatableManager.ControllerRegistrar controllers, T entity, String model) {
        RawAnimation idle = RawAnimation.begin().thenLoop("animation." + model + ".idle");
        RawAnimation walk = RawAnimation.begin().thenLoop("animation." + model + ".walk");
        boolean[] walking = {false}; // GeckoLib registers controllers once per entity, so this is per NPC
        controllers.add(new AnimationController<>(entity, "locomotion", TRANSITION_TICKS, state -> {
            float amount = state.getLimbSwingAmount();
            walking[0] = walking[0] ? amount > WALK_STOP : amount > WALK_START;
            return state.setAndContinue(walking[0] ? walk : idle);
        }));
    }

    /**
     * A tutor's controllers: {@link #registerLocomotion} plus a gesture controller that stays idle until
     * {@link #playLecture} triggers {@code animation.<model>.lecture} once. Registered after locomotion, so the
     * gesture's arms and head take over while it plays. The lecture keeps the time it arrived
     * ({@link NpcGestureController#timed}), so someone who looks over later sees it where it is, not from the start.
     */
    public static <T extends Entity & GeoEntity> void registerTutor(AnimatableManager.ControllerRegistrar controllers, T entity, String model) {
        registerLocomotion(controllers, entity, model);
        controllers.add(new NpcGestureController<>(entity, model, TRANSITION_TICKS, NpcAnimations::clock, LECTURE).timed(LECTURE));
    }

    /** The entity's client tick count: GeckoLib's frame time follows it, and it keeps counting while the entity is off screen. */
    static double clock(Entity entity) {
        return entity.tickCount;
    }

    /**
     * Server side: shows the teaching gesture to everyone tracking the NPC, through GeckoLib's own animation sync.
     * Purely visual (no cast, mana, damage or reward). Requests within {@link #LECTURE_INTERVAL_TICKS} of the last
     * start are ignored, so people asking in turn do not keep restarting it. Returns whether it started.
     */
    public static <T extends Entity & GeoEntity> boolean playLecture(T entity) {
        if (entity.level().isClientSide()) return false;
        long now = entity.level().getGameTime();
        Long last = LECTURE_STARTS.get(entity);
        if (last != null && now - last < LECTURE_INTERVAL_TICKS && now >= last) return false;
        LECTURE_STARTS.put(entity, now);
        entity.triggerAnim(GESTURE_CONTROLLER, LECTURE);
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.lecture(entity);
        return true;
    }

    public static final String SWORD_DEMO = "sword_demo";
    private static final Map<Entity, Long> SWORD_DEMO_STARTS = new MapMaker().weakKeys().makeMap();

    /**
     * A family member's controllers: {@link #registerLocomotion} plus the gesture controller with the greeting. Paul's
     * also holds his sword demonstration and stays a {@link SwordDemoAnimationController}.
     */
    public static void registerFamily(AnimatableManager.ControllerRegistrar controllers, FamilyResidentEntity entity, String model) {
        registerLocomotion(controllers, entity, model);
        controllers.add(entity.member() == FamilyResidentEntity.Member.PAUL ? new SwordDemoAnimationController(entity)
                : new NpcGestureController<>(entity, model, TRANSITION_TICKS, NpcAnimations::clock, GREETING));
    }

    /**
     * Sylphiette's controllers: {@link #registerLocomotion} plus the gesture controller with her lesson attempt and the
     * greeting. The attempt keeps the time it arrived ({@link NpcGestureController#timed}).
     */
    public static void registerSylphiette(AnimatableManager.ControllerRegistrar controllers, SylphietteEntity entity) {
        registerLocomotion(controllers, entity, SylphietteEntity.ID);
        controllers.add(new NpcGestureController<>(entity, SylphietteEntity.ID, SylphietteLessonVisuals.TRANSITION_TICKS,
                NpcAnimations::clock, SylphietteEntity.ATTEMPT, GREETING).timed(SylphietteEntity.ATTEMPT));
    }

    /** A successful trigger also holds Paul still for the clip; rejected repeats never renew that pause. */
    public static boolean playSwordDemo(FamilyResidentEntity entity) {
        if (entity.level().isClientSide() || entity.member() != FamilyResidentEntity.Member.PAUL) return false;
        long now = entity.level().getGameTime();
        Long last = SWORD_DEMO_STARTS.get(entity);
        if (last != null && now >= last && now - last < LECTURE_INTERVAL_TICKS) return false;
        entity.triggerAnim(GESTURE_CONTROLLER, SWORD_DEMO);
        SWORD_DEMO_STARTS.put(entity, now);
        entity.beginSwordDemonstration();
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.sword(entity);
        return true;
    }

    private NpcAnimations() {}
}
