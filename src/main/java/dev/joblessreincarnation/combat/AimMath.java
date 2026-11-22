package dev.joblessreincarnation.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The combat view's aim, as plain geometry: the camera's crosshair picks a point, and the player's eyes are turned to
 * look at it. Minecraft's conventions: yaw 0 looks along +z, +90 along -x; positive pitch looks down.
 */
public final class AimMath {
    /** How far the crosshair ray looks for something to hit, and where it aims when it hits nothing. */
    public static final double PICK_RANGE = 128.0, MISS_DISTANCE = 64.0;
    /** Closer than this to the eyes, a point gives no stable direction (a wall right in front of the face). */
    public static final double MIN_DISTANCE = 0.5;
    /** A point this far behind the camera's own direction, as seen from the eyes, is not what the crosshair means. */
    public static final double BEHIND_DOT = -0.1;

    public enum Source { BLOCK, ENTITY, MISS, CAMERA }
    /** The look the player is turned to: yaw and pitch in degrees, and where they came from. */
    public record Aim(float yaw, float pitch, Vec3 point, Source source) {}
    /**
     * One crosshair input, kept: the eyes, the camera and its look, and what the crosshair ray hit at that moment. The same
     * world point is aimed at again from wherever the eyes are later ({@link #from}), with the same fallbacks. The point
     * only gives a direction on this client; it is never sent anywhere.
     */
    public record Input(Vec3 eye, Vec3 camera, Vec3 look, Vec3 hit, boolean entity) {
        /** The aim at the input moment, from the eyes as they were then. */
        public Aim first() { return aim(eye, camera, look, hit, entity); }
        /** The same world point, aimed at from the eyes now. */
        public Aim from(Vec3 eyeNow) { return aim(eyeNow, camera, look, hit, entity); }
    }

    /**
     * @param eye    the player's eye position
     * @param look   the camera's unit look vector
     * @param camera the camera's position
     * @param hit    the first block or entity the crosshair ray hit, or null for none within {@link #PICK_RANGE}
     */
    public static Aim aim(Vec3 eye, Vec3 camera, Vec3 look, Vec3 hit, boolean entity) {
        Vec3 point = hit != null ? hit : camera.add(look.scale(MISS_DISTANCE));
        Source source = hit == null ? Source.MISS : entity ? Source.ENTITY : Source.BLOCK;
        Vec3 toPoint = point.subtract(eye);
        double length = toPoint.length();
        if (length < MIN_DISTANCE || look.dot(toPoint.scale(1.0 / length)) < BEHIND_DOT)
            return new Aim(yaw(look), pitch(look), point, Source.CAMERA);
        return new Aim(yaw(toPoint), pitch(toPoint), point, source);
    }
    // Math.atan2, not Mth.atan2: Mth's version normalises with an approximate inverse square root; the exact one keeps that small error out of the aim.
    public static float yaw(Vec3 v) { return (float)Math.toDegrees(Math.atan2(-v.x, v.z)); }
    public static float pitch(Vec3 v) { return (float)Math.toDegrees(-Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z))); }
    /** The unit look vector for a yaw and pitch, as Entity.calculateViewVector does. */
    public static Vec3 view(float yaw, float pitch) {
        float p = pitch * Mth.DEG_TO_RAD, y = -yaw * Mth.DEG_TO_RAD;
        float cy = Mth.cos(y), sy = Mth.sin(y), cp = Mth.cos(p), sp = Mth.sin(p);
        return new Vec3(sy * cp, -sp, cy * cp);
    }
    /** The angle between two looks, in degrees: the acceptance measure between the aimed and the server-used look. */
    public static double angle(float yawA, float pitchA, float yawB, float pitchB) {
        Vec3 a = view(yawA, pitchA), b = view(yawB, pitchB);
        // atan2 of the cross and dot products: unlike acos of the dot it does not turn the sine table's tiny length error into a false half degree.
        return Math.toDegrees(Math.atan2(a.cross(b).length(), a.dot(b)));
    }
    /** A yaw equal to target but continuous with current (no 360-degree jump in between for interpolation). */
    public static float continuous(float current, float target) { return current + Mth.wrapDegrees(target - current); }

    private AimMath() {}
}
