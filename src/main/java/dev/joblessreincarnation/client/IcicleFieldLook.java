package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.IcicleFieldEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the ground frost of one Icicle Field lies on this client, shared by its renderer and its cold air (OFF7-03 layout:
 * one stamp per block cell inside the radius, two in the inner ring, quarter-turned at random, pulled in so none passes the
 * edge). The stamps' places are fixed for the field; the surface under each, and whether the field can see it, are looked
 * up again every few ticks: the server turns water into frosted ice after the field forms, a little higher than the water
 * was, and it melts back later; and the cold stops at walls on the server, so frost behind a wall or inside another room is
 * not shown either (the same block-collider sight test from just above the field, GPT 11-07 review).
 */
final class IcicleFieldLook {
    /** Edge of one square stamp, in blocks. */
    static final float CARD = 0.85F;
    private static final double HALF_DIAGONAL = CARD * Math.sqrt(2) / 2;
    private static final int REFRESH_TICKS = 4;
    /** The sight test starts this far above the field, as on the server. */
    private static final double EYE = 0.6;

    record Stamp(double x, double z, int turn, float edge, float lift) {}

    static final class Layout {
        final List<Stamp> stamps;
        final double[] ground;
        final boolean[] seen;
        long refreshed = Long.MIN_VALUE;

        Layout(List<Stamp> stamps) {
            this.stamps = stamps;
            ground = new double[stamps.size()];
            seen = new boolean[stamps.size()];
        }
    }

    private static final Map<IcicleFieldEntity, Layout> LAYOUTS = new WeakHashMap<>();

    static Layout of(IcicleFieldEntity field) {
        Layout layout = LAYOUTS.computeIfAbsent(field, IcicleFieldLook::create);
        long now = field.level().getGameTime();
        if (layout.refreshed == Long.MIN_VALUE || now < layout.refreshed || now - layout.refreshed >= REFRESH_TICKS) refresh(field, layout, now);
        return layout;
    }

    private static Layout create(IcicleFieldEntity field) {
        RandomSource random = RandomSource.create(field.startGameTime() * 31L + field.getId());
        double radius = IcicleFieldEntity.RADIUS;
        List<Stamp> stamps = new ArrayList<>();
        int reach = Mth.ceil(radius);
        for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
            double centre = Math.sqrt(dx * dx + dz * dz);
            if (centre > radius) continue;
            for (int copy = 0; copy < (centre < 1.5 ? 2 : 1); copy++) {
                double x = dx + (random.nextDouble() - 0.5) * 0.6, z = dz + (random.nextDouble() - 0.5) * 0.6;
                double d = Math.sqrt(x * x + z * z);
                if (d + HALF_DIAGONAL > radius && d > 1.0E-6) {
                    double pulled = Math.max(0, radius - HALF_DIAGONAL);
                    x *= pulled / d;
                    z *= pulled / d;
                    d = pulled;
                }
                // Each stamp a hair higher than the last, so overlapping stamps never share a plane.
                stamps.add(new Stamp(field.getX() + x, field.getZ() + z, random.nextInt(4), (float) (d / radius),
                        0.02F + stamps.size() % 8 * 0.003F));
            }
        }
        return new Layout(stamps);
    }

    private static void refresh(IcicleFieldEntity field, Layout layout, long now) {
        Level level = field.level();
        Vec3 eye = field.position().add(0, EYE, 0);
        for (int i = 0; i < layout.stamps.size(); i++) {
            Stamp stamp = layout.stamps.get(i);
            double ground = ground(level, stamp.x(), field.getY(), stamp.z());
            layout.ground[i] = ground;
            layout.seen[i] = !Double.isNaN(ground) && level.clip(new ClipContext(eye, new Vec3(stamp.x(), ground + 0.1, stamp.z()),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, field)).getType() == HitResult.Type.MISS;
        }
        layout.refreshed = now;
    }

    /** The surface under this point near the field's height: a solid top or a water surface; NaN for a drop or a wall. */
    private static double ground(Level level, double x, double y, double z) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        for (int yy = by + 1; yy >= by - 2; yy--) {
            BlockPos at = new BlockPos(bx, yy, bz);
            var fluid = level.getFluidState(at);
            double top;
            if (!fluid.isEmpty()) top = yy + fluid.getHeight(level, at);
            else {
                var shape = level.getBlockState(at).getCollisionShape(level, at);
                if (shape.isEmpty()) continue;
                top = yy + shape.max(Direction.Axis.Y);
            }
            return top - y > 1.0 ? Double.NaN : top;
        }
        return Double.NaN;
    }

    private IcicleFieldLook() {}
}
