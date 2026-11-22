package dev.joblessreincarnation.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The crosshair under the combat view's camera keeps the player's own interaction reach, exactly as in first person:
 * measured from the eyes, blocks by block reach, entities by entity reach, strictly closer (as vanilla's
 * {@code GameRenderer.filterHitResult}). Epic Fight picks the crosshair from its TPS camera and turns any hit farther
 * from that camera than the larger reach plus half the player's own zoom setting into a miss at the same point. That
 * camera-based limit is wrong both ways: with the combat view's camera farther back it drops reachable hits, and with
 * the camera pulled in by a wall it keeps hits the player could not reach. So one finished pick is judged here: a hit
 * Epic Fight dropped only for that distance comes back when it is within reach, a hit Epic Fight kept is cut to a miss
 * at the same point when it is not, and a result anyone else put there stays.
 */
public final class CrosshairReach {
    public enum Verdict {
        /** Epic Fight dropped it only for the camera distance, and it is within the player's own reach: give it back. */
        RESTORE,
        /** Epic Fight kept its hit, and it is within the player's own reach. */
        KEEP_ACCEPTED,
        /** Epic Fight kept its hit, but the player could not reach it in first person: cut it to a miss at the same point. */
        CUT,
        /** Epic Fight dropped it, and the player could not reach it in first person either. */
        KEEP_OUT_OF_REACH,
        /** A miss at the hit's point that Epic Fight's distance cut does not explain. */
        KEEP_NOT_DISTANCE,
        /** No hit to judge, or the result is not Epic Fight's: something else decided it. */
        KEEP_OTHER
    }

    /**
     * @param raw         Epic Fight's crosshair hit from its camera, before its distance cut
     * @param now         the crosshair result after the whole pick, every wrapper included
     * @param camera      the camera position Epic Fight measured its cut from
     * @param eye         the player's eyes now
     * @param blockReach  the player's block interaction reach
     * @param entityReach the player's entity interaction reach
     * @param efZoom      the player's own Epic Fight zoom setting, which Epic Fight's cut uses
     */
    public static Verdict judge(HitResult raw, HitResult now, Vec3 camera, Vec3 eye, double blockReach, double entityReach, int efZoom) {
        if (raw == null || raw.getType() == HitResult.Type.MISS || now == null) return Verdict.KEEP_OTHER;
        if (now == raw) return reachable(raw, eye, blockReach, entityReach) ? Verdict.KEEP_ACCEPTED : Verdict.CUT;
        // Epic Fight's cut leaves a miss at exactly the dropped hit's point; anything else is someone else's result.
        if (now.getType() != HitResult.Type.MISS || !now.getLocation().equals(raw.getLocation())) return Verdict.KEEP_OTHER;
        double efLimit = Math.max(blockReach, entityReach) + efZoom * 0.5;
        if (raw.getLocation().distanceToSqr(camera) <= efLimit * efLimit) return Verdict.KEEP_NOT_DISTANCE;
        return reachable(raw, eye, blockReach, entityReach) ? Verdict.RESTORE : Verdict.KEEP_OUT_OF_REACH;
    }

    /** As vanilla filters its own pick: strictly closer than the reach for this kind of hit, from the eyes. */
    static boolean reachable(HitResult hit, Vec3 eye, double blockReach, double entityReach) {
        double reach = hit.getType() == HitResult.Type.ENTITY ? entityReach : blockReach;
        return hit.getLocation().closerThan(eye, reach);
    }

    /** What vanilla leaves for a hit beyond reach: a miss at the same point, facing away from the eyes. */
    public static HitResult missAt(HitResult hit, Vec3 eye) {
        Vec3 point = hit.getLocation();
        return BlockHitResult.miss(point, Direction.getNearest(point.x - eye.x, point.y - eye.y, point.z - eye.z), BlockPos.containing(point));
    }

    private CrosshairReach() {}
}
