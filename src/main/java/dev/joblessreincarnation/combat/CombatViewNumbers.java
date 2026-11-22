package dev.joblessreincarnation.combat;

/**
 * The combat view's camera, in Epic Fight's own units (the user's choice, framing 3): horizontal -3, vertical 3,
 * zoom 10. At pitch 0 that puts the camera 0.6 right of, 0.6 above and 5.0 behind the eyes, before Epic Fight's own
 * collision shortens it. Epic Fight 21.17.3.1 accepts horizontal -10..10, vertical -2..5 and zoom 6..10.
 * What the crosshair can reach under this camera is decided in {@link CrosshairReach}.
 */
public final class CombatViewNumbers {
    public static final int HORIZONTAL = -3, VERTICAL = 3, ZOOM = 10;
    public static final int HORIZONTAL_MIN = -10, HORIZONTAL_MAX = 10, VERTICAL_MIN = -2, VERTICAL_MAX = 5, ZOOM_MIN = 6, ZOOM_MAX = 10;

    private CombatViewNumbers() {}
}
