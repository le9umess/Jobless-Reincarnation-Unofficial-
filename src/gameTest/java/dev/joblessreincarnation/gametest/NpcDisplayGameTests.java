package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server half of the development Roxy: the GameTest server is a dedicated-server environment, so this also
 * shows the GeckoLib entity registers, spawns and is removed there without any client class.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcDisplayGameTests {
    @GameTest(template = "empty")
    public static void spawnRoxyAddsOneDisplayOnlyEntityThatCanBeDiscarded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 position = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        RoxyDisplayEntity npc = NpcDisplayRegistry.spawnRoxy(level, position, 90.0F);
        helper.assertTrue(npc != null, "spawnRoxy returns the entity on an open site");
        helper.assertTrue(level.getEntity(npc.getUUID()) == npc, "The returned entity is the one in the level");
        helper.assertTrue(npc.position().equals(position), "The entity stands at the requested position");
        helper.assertTrue(npc.getYRot() == 90.0F && npc.getYHeadRot() == 90.0F && npc.yBodyRot == 90.0F, "Body and head face the requested yaw");
        helper.assertTrue(npc.isNoAi() && npc.isInvulnerable() && npc.isSilent(), "No AI, invulnerable and silent");
        helper.assertFalse(npc.shouldBeSaved(), "Never written to the world save");
        helper.assertValueEqual(npc.clip(), 0, "Starts on the idle clip");
        npc.discard();
        helper.assertTrue(npc.isRemoved(), "The caller can remove exactly this entity");
        helper.succeed();
    }

    private NpcDisplayGameTests() {}
}
