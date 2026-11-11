package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.FriendlyNpcEpicPatch;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.entity.ai.goal.AnimatedAttackGoal;
import yesman.epicfight.world.entity.ai.goal.TargetChasingGoal;
import yesman.epicfight.world.damagesource.StunType;

/** The real EF patch must leave the existing friendly entities and their authoritative dimensions alone. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcRemodelGameTests {
    private static void friendly(GameTestHelper h, PathfinderMob entity) {
        var patch = EpicFightCapabilities.getEntityPatch(entity, FriendlyNpcEpicPatch.class);
        h.assertTrue(patch != null, "New mesh entity receives its actual EF patch");
        h.assertTrue(patch.getArmature().hasJoint("Head") && patch.getArmature().hasJoint("Tool_R"), "Common rig joints present");
        h.assertTrue(entity.goalSelector.getAvailableGoals().size() == 6, "Original home/pause/stroll/look goals remain");
        for (var wrapped : entity.goalSelector.getAvailableGoals()) {
            h.assertTrue(!(wrapped.getGoal() instanceof AnimatedAttackGoal<?>) && !(wrapped.getGoal() instanceof TargetChasingGoal), "No enemy combat AI added");
        }
        h.assertTrue(!entity.canAttackType(EntityType.PLAYER), "Friendly targeting preserved");
        float health = entity.getHealth();
        h.assertTrue(!entity.hurt(h.getLevel().damageSources().generic(), 3) && entity.getHealth() == health, "Teacher protection preserved");
        h.assertTrue(!patch.applyStun(StunType.SHORT, 1), "Teaching character is not staggered");
    }
    @GameTest(template="empty")
    public static void remodeledFamilyKeepsIdentityAndProtection(GameTestHelper h) {
        var types = List.of(ModContent.PAUL_GREYRAT.get(), ModContent.ZENITH_GREYRAT.get(), ModContent.LILIA_GREYRAT.get());
        var members = List.of(FamilyResidentEntity.Member.PAUL, FamilyResidentEntity.Member.ZENITH, FamilyResidentEntity.Member.LILIA);
        for (int i=0;i<types.size();i++) {
            var entity=h.spawn(types.get(i),new BlockPos(2+i*3,2,3));
            h.assertTrue(entity.member()==members.get(i), "Family identity is not changed by shared renderer/patch");
            friendly(h,entity);
            h.assertTrue(Math.abs(entity.getBbWidth()-.6F)<.0001 && Math.abs(entity.getBbHeight()-1.8F)<.0001, "Visual enlargement does not enlarge authoritative body box");
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void remodeledChildKeepsChildDimensionsAndProtection(GameTestHelper h) {
        var child=h.spawn(ModContent.SYLPHIETTE.get(),new BlockPos(4,2,4));
        friendly(h,child);
        h.assertTrue(Math.abs(child.getBbWidth()-.5F)<.0001 && Math.abs(child.getBbHeight()-1.3F)<.0001, "Child collision remains child-sized");
        h.assertTrue(Math.abs(child.getEyeHeight()-1.0F)<.0001, "Gameplay eye height retained");
        h.succeed();
    }
    private NpcRemodelGameTests() {}
}
