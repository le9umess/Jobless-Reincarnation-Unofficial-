package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.RoxyEpicPatch;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.registry.entries.EpicFightAttributes;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.entity.ai.goal.AnimatedAttackGoal;
import yesman.epicfight.world.entity.ai.goal.TargetChasingGoal;

/** Actual server entities: EF attachment must preserve the friendly teacher's behaviour and persistence. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicV01GameTests {
    private static RoxyTutorEntity spawn(GameTestHelper h) {
        RoxyTutorEntity roxy = h.spawn(ModContent.ROXY_TUTOR.get(), new BlockPos(4, 2, 4));
        h.assertTrue(roxy != null, "Tutor spawns");
        return roxy;
    }
    @GameTest(template="empty")
    public static void epicTutorKeepsFriendlyGoals(GameTestHelper h) {
        RoxyTutorEntity roxy=spawn(h);
        var patch=EpicFightCapabilities.getEntityPatch(roxy,RoxyEpicPatch.class);
        h.assertTrue(patch != null, "Real tutor receives the custom EF patch");
        h.assertTrue(patch.getArmature().hasJoint("Tool_R"), "Actual biped tool joint is available on the server");
        h.assertTrue(roxy.getAttribute(EpicFightAttributes.WEIGHT) != null, "Custom tutor has required EF attributes");
        h.assertTrue(roxy.goalSelector.getAvailableGoals().size() == 5, "All five existing tutor goals remain");
        for (var wrapped:roxy.goalSelector.getAvailableGoals()) {
            Goal goal=wrapped.getGoal();
            h.assertTrue(!(goal instanceof AnimatedAttackGoal<?>) && !(goal instanceof TargetChasingGoal), "No combat AI installed");
        }
        h.assertTrue(!roxy.canAttackType(EntityType.PLAYER), "Tutor stays non-hostile");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void epicTutorRemainsProtected(GameTestHelper h) {
        RoxyTutorEntity roxy=spawn(h);float health=roxy.getHealth();
        h.assertTrue(!roxy.hurt(h.getLevel().damageSources().generic(),5), "EF does not remove teacher protection");
        h.assertTrue(roxy.getHealth()==health, "Protected teacher loses no health");
        var patch=EpicFightCapabilities.getEntityPatch(roxy,RoxyEpicPatch.class);
        h.assertTrue(!patch.applyStun(yesman.epicfight.world.damagesource.StunType.SHORT,1), "Teaching character is not staggered into a combat state");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void epicTutorKeepsHomeOnReload(GameTestHelper h) {
        RoxyTutorEntity roxy=spawn(h);roxy.reanchor();var home=roxy.getTutorAnchor();
        CompoundTag saved=new CompoundTag();roxy.saveWithoutId(saved);roxy.discard();
        RoxyTutorEntity restored=ModContent.ROXY_TUTOR.get().create(h.getLevel());restored.load(saved);h.getLevel().addFreshEntity(restored);
        h.assertTrue(home != null && home.equals(restored.getTutorAnchor()), "Home dimension and position survive the EF attachment");
        h.assertTrue(EpicFightCapabilities.getEntityPatch(restored,RoxyEpicPatch.class)!=null, "Reloaded entity retains the appropriate patch");
        h.assertTrue(!restored.canAttackType(EntityType.PLAYER), "Reload never adds hostility");
        h.succeed();
    }
    private EpicV01GameTests() {}
}
