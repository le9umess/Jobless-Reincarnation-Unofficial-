package dev.joblessreincarnation.client.npc;

import com.google.common.collect.MapMaker;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.NpcGestureController;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * How an NPC looks during, and right after, the S-03 greeting. Client only; nothing is synced or saved.
 * <p>
 * While the greeting plays ({@link #compose}): GeckoLib replaces the head's pitch and yaw with where the NPC looks,
 * which hides the clip's own nod, and a greeting's arms pass close to the face, so the look is held inside a
 * per-character window and the clip's change from its first head frame is added back:
 * <pre>
 *   head = look + (clamp(look, window) - look) * w + (clip - clip's first frame) * w
 * </pre>
 * w rises over the first {@link #EASE_IN} presentation ticks of the greeting and falls over the character's last ones,
 * read from the real gesture controller, so it follows the clip. The clip part is left out while GeckoLib is still
 * blending into the greeting, when the bone still carries the previous clip's head. Roll stays with the clip. A fixed
 * base such as Sylphiette's lowered head is applied by her model afterwards, once; subtracting the clip's first frame
 * keeps the clip's own copy of it out.
 * <p>
 * Once it stops ({@link #settle}): GeckoLib gives a bone back to locomotion at once when the gesture controller stops
 * or plays a clip that does not key it, so a greeting stopped by a lesson, or replaced by sword_demo, would drop
 * Paul's raised arm in one frame (integrated run 1, scene tick 33). Every bone the greeting keys therefore fades from
 * what was last on screen to its new pose over {@link #CARRY_TICKS}; bones the next clip keys start from that pose in
 * GeckoLib's own blend anyway, so their offset is about zero. The head is one of these bones, so its look composition
 * is not carried twice. A normal end fades the same way, from the clip's last frame to the current idle phase.
 * Angles and ticks are those of greetings-s03/motion/greeting-look.json, checked offline there.
 */
final class GreetingLook {
    /**
     * Look window in animation-file degrees (+pitch looks down, +yaw turns to the character's own right; GeckoLib negates
     * both on the way to the bone), the presentation ticks over which the window lets go again, the clip's first head
     * frame, and the bones the greeting clip keys.
     */
    record Profile(float yawMin, float yawMax, float pitchMin, float pitchMax, double easeOutStart, double easeOutEnd,
                   float startPitch, float startYaw, String... bones) {}

    /** His free left hand rises out to the side, clear of his head; turned far toward either shoulder, his nod or his wave meets it. */
    static final Profile PAUL = new Profile(-20, 15, -30, 30, 46, 52, 0, 0, "head", "left_arm", "hair_lock");
    /** Her waving hand comes up beside the head: only looking up, at a taller player, stays clear of it. */
    static final Profile ZENITH = new Profile(0, 0, -15, 0, 50, 56, 0, 0, "head", "right_arm", "left_arm", "ponytail", "skirt");
    /** She lowers her eyes with the bow; the look comes back once she stands straight again. */
    static final Profile LILIA = new Profile(0, 0, 0, 0, 40, 48, 0, 0,
            "head", "right_arm", "left_arm", "hair_lock_right", "hair_lock_left", "skirt", "body", "right_leg", "left_leg");
    /** She may keep looking up at the player, not toward her waving right hand; her clip starts 4 degrees down. */
    static final Profile SYLPHIETTE = new Profile(-10, 0, -15, 0, 50, 56, 4, 0,
            "head", "right_arm", "left_arm", "body", "ear_right", "ear_left", "ahoge");

    static final double EASE_IN = 8.0, CARRY_TICKS = 6.0;
    private static final double TRANSITION_TICKS = 4.0;

    /** Per entity: the greeting's bones as last shown, and the offsets fading out since it stopped. */
    private static final class Carry {
        final Map<String, float[]> shown = new HashMap<>();
        final Map<String, float[]> offset = new HashMap<>();
        double lastTick = Double.NaN, fromTick = Double.NaN;
        boolean greeting;
    }
    /** Weak keys compare by identity; runtime only, never saved. */
    private static final Map<Entity, Carry> CARRY = new MapMaker().weakKeys().makeMap();

    static Profile of(FamilyResidentEntity.Member member) {
        return switch (member) {
            case PAUL -> PAUL;
            case ZENITH -> ZENITH;
            case LILIA -> LILIA;
        };
    }

    /** Presentation ticks of the greeting on the entity's gesture controller, or NaN when it is not playing. */
    static <T extends Entity & GeoEntity> double ticks(T entity, long instanceId, double now) {
        var controller = entity.getAnimatableInstanceCache().getManagerForId(instanceId).getAnimationControllers()
                .get(NpcAnimations.GESTURE_CONTROLLER);
        return controller instanceof NpcGestureController<?> gesture ? gesture.presentationTicks(NpcAnimations.GREETING, now) : Double.NaN;
    }

    /**
     * While the greeting plays, composes the head after the model has written the look into it. clipX and clipY are the
     * head's animated rotation read before that, in bone radians; ticks the greeting's presentation ticks.
     */
    static void compose(GeoBone head, Profile profile, float clipX, float clipY, double ticks) {
        if (!Double.isFinite(ticks)) return;
        double w = weight(profile, ticks), clip = ticks < TRANSITION_TICKS ? 0.0 : w;
        float lookX = head.getRotX(), lookY = head.getRotY();
        float minX = -profile.pitchMax() * Mth.DEG_TO_RAD, maxX = -profile.pitchMin() * Mth.DEG_TO_RAD;
        float minY = -profile.yawMax() * Mth.DEG_TO_RAD, maxY = -profile.yawMin() * Mth.DEG_TO_RAD;
        head.setRotX((float) (lookX + (Mth.clamp(lookX, minX, maxX) - lookX) * w + (clipX + profile.startPitch() * Mth.DEG_TO_RAD) * clip));
        head.setRotY((float) (lookY + (Mth.clamp(lookY, minY, maxY) - lookY) * w + (clipY + profile.startYaw() * Mth.DEG_TO_RAD) * clip));
    }

    /**
     * Called last in the model, after every other change to the bones. Records the greeting's bones while it plays;
     * after it stops, fades each from what it last showed. Only a greeting on screen a moment ago is faded, so an
     * entity drawn again after a gap starts clean.
     */
    static void settle(Entity entity, AnimationProcessor<?> processor, Profile profile, boolean playing, double now) {
        Carry carry = CARRY.computeIfAbsent(entity, e -> new Carry());
        boolean recent = Double.isFinite(carry.lastTick) && now >= carry.lastTick && now - carry.lastTick <= 2.0;
        if (playing) {
            carry.fromTick = Double.NaN;
            carry.offset.clear();
            for (String name : profile.bones()) {
                GeoBone bone = processor.getBone(name);
                if (bone != null) carry.shown.put(name, values(bone));
            }
        } else {
            if (carry.greeting && recent) {
                carry.fromTick = now;
                carry.offset.clear();
                for (Map.Entry<String, float[]> entry : carry.shown.entrySet()) {
                    GeoBone bone = processor.getBone(entry.getKey());
                    if (bone == null) continue;
                    float[] current = values(bone), last = entry.getValue(), delta = new float[6];
                    for (int i = 0; i < 6; i++) delta[i] = last[i] - current[i];
                    carry.offset.put(entry.getKey(), delta);
                }
            }
            double left = Double.isFinite(carry.fromTick) && now >= carry.fromTick ? 1.0 - smooth((now - carry.fromTick) / CARRY_TICKS) : 0.0;
            if (left <= 0.0) {
                carry.fromTick = Double.NaN;
                carry.offset.clear();
            } else {
                for (Map.Entry<String, float[]> entry : carry.offset.entrySet()) {
                    GeoBone bone = processor.getBone(entry.getKey());
                    if (bone == null) continue;
                    float[] d = entry.getValue();
                    float k = (float) left;
                    bone.setRotX(bone.getRotX() + d[0] * k);
                    bone.setRotY(bone.getRotY() + d[1] * k);
                    bone.setRotZ(bone.getRotZ() + d[2] * k);
                    bone.setPosX(bone.getPosX() + d[3] * k);
                    bone.setPosY(bone.getPosY() + d[4] * k);
                    bone.setPosZ(bone.getPosZ() + d[5] * k);
                }
            }
        }
        carry.greeting = playing;
        carry.lastTick = now;
    }

    private static float[] values(GeoBone bone) {
        return new float[] {bone.getRotX(), bone.getRotY(), bone.getRotZ(), bone.getPosX(), bone.getPosY(), bone.getPosZ()};
    }

    /** Window weight at a presentation tick of the greeting. */
    static double weight(Profile profile, double ticks) {
        return ticks < profile.easeOutStart() ? smooth(ticks / EASE_IN)
                : 1.0 - smooth((ticks - profile.easeOutStart()) / (profile.easeOutEnd() - profile.easeOutStart()));
    }

    private static double smooth(double x) {
        double t = Math.max(0.0, Math.min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }

    private GreetingLook() {}
}
