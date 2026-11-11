package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/**
 * Stops an arrow that is about to hit something behind a water shield. An arrow moves and hits in the same tick, so
 * without this a fast arrow could reach the person behind the screen before the screen's own tick sees it.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class WaterShieldArrows {
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || !(arrow.level() instanceof ServerLevel level)) return;
        Vec3 from = arrow.position();
        Vec3 to = event.getRayTraceResult().getLocation();
        AABB area = new AABB(from, to).inflate(WaterShieldEntity.REACH);
        WaterShieldEntity first = null;
        Vec3 firstPoint = null;
        for (WaterShieldEntity shield : level.getEntitiesOfClass(WaterShieldEntity.class, area, shield -> shield.isAlive() && shield.stops(arrow))) {
            Vec3 point = shield.crossing(from, to);
            if (point != null && (firstPoint == null || point.distanceToSqr(from) < firstPoint.distanceToSqr(from))) {
                first = shield;
                firstPoint = point;
            }
        }
        if (first == null) return;
        event.setCanceled(true);
        first.block(level, arrow, firstPoint);
    }

    private WaterShieldArrows() {}
}
