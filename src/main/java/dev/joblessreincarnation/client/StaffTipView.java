package dev.joblessreincarnation.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3f;

/**
 * Where a chant's focus belongs in the caster's own first-person view. The server's point is right for everyone else, but
 * the held staff is drawn in the hand's own space, so from inside the caster's head that point hangs away from the visible
 * tip. Looks only, on this client only: nobody else sees the difference, and the shot still leaves from where it always did.
 */
final class StaffTipView {
    /** The hand is always drawn at a field of view of 70, whatever the player's setting. */
    private static final double HAND_TAN = Math.tan(Math.toRadians(35.0));
    /** Right of and below the view's centre per block of depth, at the hand's field of view. Read off real shots: Punchy swings the staff forward during a chant, and without it the staff stays where it is held. */
    private static final double RIGHT_POINTED = 0.364, DOWN_POINTED = 0.187, RIGHT_HELD = 0.18, DOWN_HELD = 0.06;

    /** @param caster the public entity id the server sent with the effect: only the caster's own view moves it, however close anyone else stands */
    static Vec3 focus(Vec3 world, int caster) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (player == null || player.getId() != caster || camera.isDetached() || camera.getEntity() != player) return world;
        boolean pointed = ModList.get().isLoaded("punchy");
        double scale = Math.tan(Math.toRadians(minecraft.options.fov().get() / 2.0)) / HAND_TAN;
        double side = player.getMainArm() == HumanoidArm.RIGHT ? 1.0 : -1.0;
        double right = side * scale * (pointed ? RIGHT_POINTED : RIGHT_HELD), down = scale * (pointed ? DOWN_POINTED : DOWN_HELD);
        Vector3f look = camera.getLookVector(), left = camera.getLeftVector(), up = camera.getUpVector();
        Vec3 eye = camera.getPosition();
        return new Vec3(eye.x + look.x - left.x * right - up.x * down, eye.y + look.y - left.y * right - up.y * down, eye.z + look.z - left.z * right - up.z * down);
    }

    private StaffTipView() {}
}
