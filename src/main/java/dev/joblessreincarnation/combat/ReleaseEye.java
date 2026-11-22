package dev.joblessreincarnation.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Where the server has the caster's eyes when it handles a held staff release (F01, route A). This tick's movement
 * packet has already moved the server's player, so the position is the one after this tick's movement. The pose is not:
 * the server changes a player's pose only when its own player tick runs (ServerPlayer.doTick, Player.updatePlayerPose),
 * after it has handled this tick's packets, so it still has the pose the player had when this tick started. The client
 * has already changed pose in its own tick by the time the packet goes (standing up: eye height 1.27 on the server,
 * 1.62 on the client). The eyes are therefore the position now at the eye height of the pose at the tick's start.
 * <p>
 * This holds while the server handles this tick's packets in one batch, before its next player tick; when they are split
 * across a server tick, the server may already have the new pose (not tested). Other pose changes (swimming, crawling,
 * gliding) follow the same model (not tested either).
 */
public final class ReleaseEye {
    /** The pose whose eye height the server uses: the one at the tick's start, never the client's new one. */
    public static Pose poseOnServer(Pose atTickStart, Pose now) {
        return atTickStart;
    }

    /** The server's eyes for the release: the position after this tick's movement, at the eye height of the server's pose. */
    public static Vec3 serverEye(Entity entity, Vec3 positionNow, Pose atTickStart, Pose now) {
        return positionNow.add(0.0, entity.getEyeHeight(poseOnServer(atTickStart, now)), 0.0);
    }

    private ReleaseEye() {}
}
