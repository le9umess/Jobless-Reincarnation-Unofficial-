package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native picking against the formal OFF3-02 dimensions, rather than repeating a registration constant. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DummyRuntimeGameTests {
    @GameTest(template = "empty", batch = "dummyRuntimeB01")
    public static void formalCrossbarEndsArePickableWithoutAnOldTallHitboxOrSolidBody(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(5, 1, 4));
        var savedSupport = helper.getLevel().getBlockState(support);
        PracticeDummyEntity dummy = ModContent.PRACTICE_DUMMY.get().create(helper.getLevel());
        Entity camera = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(dummy != null && camera != null, "The registered entities exist");
        try {
            helper.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            Vec3 base = Vec3.atBottomCenterOf(support.above());
            dummy.setPos(base);
            helper.assertTrue(helper.getLevel().addFreshEntity(dummy), "The real dummy is admitted for native world queries");
            camera.setPos(base.add(0, 0, -2));
            // OFF3-02's actual crossbar spans x +/- 7.5/16 at y 17/16 .. 19.5/16.
            // Aim just inside the visible ends, which the old width 0.8 excluded.
            for (double edge : new double[] {-7.35 / 16.0, 7.35 / 16.0}) {
                helper.assertTrue(picks(camera, dummy, base.add(edge, 18.0 / 16.0, -2),
                        base.add(edge, 18.0 / 16.0, 2)), "A ray through the visible transverse end selects the real dummy");
                helper.assertTrue(picks(camera, dummy, base.add(-2, 18.0 / 16.0, edge),
                        base.add(2, 18.0 / 16.0, edge)), "The same end remains selectable when the prop faces east/west");
            }
            helper.assertTrue(picks(camera, dummy, base.add(0, 24.9 / 16.0, -2), base.add(0, 24.9 / 16.0, 2)),
                    "The formal top cap at y 25/16 remains selectable");
            helper.assertFalse(picks(camera, dummy, base.add(0, 1.65, -2), base.add(0, 1.65, 2)),
                    "Empty air above the formal model no longer uses the old 1.75-high target");
            helper.assertFalse(picks(camera, dummy, base.add(0.51, 1.1, -2), base.add(0.51, 1.1, 2)),
                    "A ray beyond the single-block footprint does not select the prop");
            helper.assertTrue(helper.getLevel().noCollision(camera, dummy.getBoundingBox().deflate(0.02)),
                    "The wider pickable prop stays passable in the actual level collision query");
            helper.assertTrue(dummy.hasSupport(), "Formal dimensions retain solid dry support validation");
        } finally {
            dummy.discard();
            camera.discard();
            helper.getLevel().setBlockAndUpdate(support, savedSupport);
        }
        helper.succeed();
    }

    private static boolean picks(Entity camera, PracticeDummyEntity dummy, Vec3 start, Vec3 end) {
        var hit = ProjectileUtil.getEntityHitResult(camera, start, end, new AABB(start, end).inflate(0.01),
                entity -> entity == dummy && entity.isPickable(), start.distanceToSqr(end));
        return hit != null && hit.getEntity() == dummy;
    }

    private DummyRuntimeGameTests() { }
}
