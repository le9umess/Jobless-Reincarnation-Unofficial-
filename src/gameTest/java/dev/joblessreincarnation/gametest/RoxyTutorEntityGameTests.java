package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the registered, saveable tutor and live vanilla AI in isolated 16x6x16 test structures. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoxyTutorEntityGameTests {
    private static final String ANCHOR_TAG = "JR_TutorAnchor";

    private static void floor(GameTestHelper helper) {
        // Avoid the structure controller at local 0,0,0 and never touch a neighbouring test.
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static RoxyTutorEntity spawn(GameTestHelper helper, int x, int z) {
        RoxyTutorEntity npc = ModContent.ROXY_TUTOR.get().spawn(helper.getLevel(),
            helper.absolutePos(new BlockPos(x, 1, z)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(npc != null, "The registered entity spawns through EntityType.spawn");
        helper.assertTrue(helper.getLevel().getEntity(npc.getUUID()) == npc, "Spawn is visible in the actual level entity index");
        helper.assertTrue(npc.getTutorAnchor() != null, "A clear supported placement establishes an anchor");
        return npc;
    }

    private static CommandSourceStack source(GameTestHelper helper, int permission) {
        return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
            .withPosition(helper.absoluteVec(new Vec3(8, 2, 8))).withPermission(permission).withSuppressedOutput();
    }

    private static int command(GameTestHelper helper, int permission, String text) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, permission));
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Command failed: " + text, exception);
        }
    }

    private static CompoundTag save(GameTestHelper helper, RoxyTutorEntity npc) {
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(npc.save(saved), "Normal entity save includes a registered type id");
        helper.assertValueEqual(saved.getString("id"), "jobless_reincarnation:roxy_tutor", "Persistent registered type");
        return saved;
    }

    private static RoxyTutorEntity load(GameTestHelper helper, CompoundTag saved) {
        Entity entity = EntityType.loadEntityRecursive(saved.copy(), helper.getLevel(), value -> value);
        helper.assertTrue(entity instanceof RoxyTutorEntity, "Vanilla entity loader recreates the registered tutor");
        return (RoxyTutorEntity)entity;
    }

    @GameTest(template = "empty")
    public static void registeredSpawnUsesPlacementInsteadOfConstructorOrigin(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity unplaced = ModContent.ROXY_TUTOR.get().create(helper.getLevel());
        helper.assertTrue(unplaced != null && unplaced.getTutorAnchor() == null, "An unplaced constructor has no home");
        helper.assertFalse(unplaced.reanchor(), "An entity outside the level cannot create a home");
        ItemStack egg = ModContent.ROXY_TUTOR_SPAWN_EGG.get().getDefaultInstance();
        helper.assertTrue(egg.getItem() instanceof SpawnEggItem, "A real spawn egg is registered");
        helper.assertTrue(((SpawnEggItem)egg.getItem()).getType(egg) == ModContent.ROXY_TUTOR.get(), "The egg resolves the tutor entity type");
        RoxyTutorEntity npc = spawn(helper, 4, 6);
        try {
            GlobalPos home = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 1, 6)));
            helper.assertValueEqual(npc.getTutorAnchor(), home, "Home is the supported spawn position");
            helper.assertTrue(npc.shouldBeSaved() && npc.isPersistenceRequired(), "The formal tutor participates in persistent saves");
            helper.assertFalse(npc.removeWhenFarAway(1_000_000.0), "Distance cannot naturally despawn the tutor");
            helper.assertFalse(npc.isNoAi(), "Formal tutor runs AI");
            helper.assertTrue(npc.isCustomNameVisible(), "The default formal nameplate is visible");
            helper.assertValueEqual(npc.getCustomName(), Component.translatable("entity.jobless_reincarnation.roxy_tutor"), "Default name is localized rather than a fixed language");
            helper.assertTrue(npc.getMaxHealth() == 20.0F, "Formal attribute registration was applied");
            npc.moveTo(helper.absoluteVec(new Vec3(11.5, 4.0, 6.5)));
            helper.assertFalse(npc.reanchor(), "Airborne unsupported placement cannot replace a good home");
            helper.assertValueEqual(npc.getTutorAnchor(), home, "Failed reanchor keeps the prior home");
        } finally { npc.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void normalSaveLoadCanRejoinTwiceWithoutDuplicateIdentity(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity original = spawn(helper, 3, 5);
        original.setNoAi(true);
        original.setCustomName(Component.literal("B01 persistence witness"));
        GlobalPos anchor = original.getTutorAnchor();
        UUID uuid = original.getUUID();
        // Same-dimension movement is saved separately from the persistent home.
        original.moveTo(helper.absoluteVec(new Vec3(11.5, 1.0, 5.5)));
        CompoundTag firstSave = save(helper, original);
        CompoundTag immutableWitness = firstSave.copy();
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(helper.getLevel().getEntity(uuid) == null, "Unloaded identity leaves the entity index");
            RoxyTutorEntity firstReload = load(helper, firstSave);
            helper.assertValueEqual(firstReload.getUUID(), uuid, "First reload retains UUID");
            helper.assertValueEqual(firstReload.getTutorAnchor(), anchor, "First reload retains the original home");
            helper.assertTrue(helper.getLevel().addFreshEntity(firstReload), "First reload joins without UUID collision");
            helper.assertTrue(firstReload.position().equals(helper.absoluteVec(new Vec3(11.5, 1.0, 5.5))), "Saved location is distinct from home");
            CompoundTag secondSave = save(helper, firstReload);
            firstReload.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.runAfterDelay(2, () -> {
                RoxyTutorEntity secondReload = load(helper, secondSave);
                try {
                    helper.assertTrue(helper.getLevel().addFreshEntity(secondReload), "Second reload also joins without UUID collision");
                    helper.assertTrue(helper.getLevel().getEntity(uuid) == secondReload, "Only the current instance owns the persistent UUID");
                    helper.assertValueEqual(secondReload.getTutorAnchor(), anchor, "Repeated save/load does not drift the home");
                    helper.assertValueEqual(secondReload.getCustomName(), Component.literal("B01 persistence witness"), "Normal entity metadata survives");
                    helper.assertValueEqual(firstSave, immutableWitness, "Loading never mutates the original save tag");
                    JoblessReincarnation.LOGGER.info("[JR_ROXY_B01_ENTITY] PASS save_reload cycles=2 uuid={} home={}", uuid, anchor);
                } finally { secondReload.discard(); }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void malformedForeignAndOutOfBoundsHomesRepairPerEntity(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity original = spawn(helper, 2, 3);
        original.setNoAi(true);
        CompoundTag saved = save(helper, original), witness = saved.copy();
        GlobalPos originalHome = original.getTutorAnchor();
        List<RoxyTutorEntity> copies = new ArrayList<>();
        try {
            List<CompoundTag> invalid = new ArrayList<>();
            CompoundTag malformed = new CompoundTag();
            malformed.putString("dimension", "not a dimension");
            invalid.add(malformed);
            invalid.add((CompoundTag)GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE,
                GlobalPos.of(Level.NETHER, originalHome.pos())).result().orElseThrow());
            invalid.add((CompoundTag)GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE,
                GlobalPos.of(helper.getLevel().dimension(), new BlockPos(31_000_000, 100, 0))).result().orElseThrow());
            for (int index = 0; index < invalid.size(); index++) {
                CompoundTag modified = saved.copy();
                modified.putUUID("UUID", UUID.randomUUID());
                modified.put(ANCHOR_TAG, invalid.get(index));
                RoxyTutorEntity copy = load(helper, modified);
                copies.add(copy);
                helper.assertTrue(copy.getTutorAnchor() == null, "Invalid or other-dimension home is rejected before world insertion");
                BlockPos destination = helper.absolutePos(new BlockPos(5 + index * 3, 1, 9));
                copy.moveTo(Vec3.atBottomCenterOf(destination));
                helper.assertTrue(helper.getLevel().addFreshEntity(copy), "Independent copy joins successfully");
                helper.assertValueEqual(copy.getTutorAnchor(), GlobalPos.of(helper.getLevel().dimension(), destination), "Home repairs at this entity's legal destination");
                helper.assertFalse(copy.getUUID().equals(original.getUUID()), "Test copy has its own identity");
            }
            helper.assertValueEqual(original.getTutorAnchor(), originalHome, "Repair cannot alter a neighbouring tutor's home");
            helper.assertValueEqual(saved, witness, "Repairs cannot mutate the original saved document");
        } finally {
            original.discard();
            copies.forEach(Entity::discard);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void onlyAuthorizedAnchorCommandChangesSameDimensionHome(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 3, 5), neighbour = spawn(helper, 10, 11);
        npc.setNoAi(true);
        neighbour.setNoAi(true);
        GlobalPos old = npc.getTutorAnchor(), otherHome = neighbour.getTutorAnchor();
        BlockPos destination = helper.absolutePos(new BlockPos(12, 1, 5));
        npc.moveTo(Vec3.atBottomCenterOf(destination));
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertValueEqual(npc.getTutorAnchor(), old, "Ordinary same-dimension relocation preserves home across ticks");
                String text = "jr_tutor anchor " + npc.getUUID();
                boolean rejected = false;
                try { helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, 1)); }
                catch (CommandSyntaxException expected) { rejected = true; }
                helper.assertTrue(rejected, "The actual command tree rejects permission level one");
                helper.assertValueEqual(npc.getTutorAnchor(), old, "Denied command has no side effect");
                helper.assertValueEqual(command(helper, 2, text), 1, "Permission level two command succeeds");
                helper.assertValueEqual(npc.getTutorAnchor(), GlobalPos.of(helper.getLevel().dimension(), destination), "Authorized relocation anchors the current supported position");
                helper.assertValueEqual(neighbour.getTutorAnchor(), otherHome, "The UUID-targeted command leaves other tutors unchanged");
            } finally { npc.discard(); neighbour.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void teachingProtectionStillAllowsKillWithoutLoot(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 8, 8);
        npc.setNoAi(true);
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
        npc.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
        AABB area = npc.getBoundingBox().inflate(2.0);
        float health = npc.getHealth();
        helper.assertFalse(npc.hurt(helper.getLevel().damageSources().generic(), 1000.0F), "Ordinary damage is rejected");
        helper.assertFalse(npc.hurt(helper.getLevel().damageSources().lava(), 1000.0F), "Environmental damage is rejected");
        helper.assertFalse(npc.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000.0F), "Bypass-invulnerability damage does not bypass teaching protection");
        helper.assertTrue(npc.getHealth() == health && npc.isAlive(), "Teaching protection preserves life");
        helper.assertValueEqual(command(helper, 2, "kill " + npc.getUUID()), 1, "The native admin kill command accepts this tutor");
        helper.assertTrue(npc.isDeadOrDying(), "Generic-kill damage remains effective");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(npc.isRemoved() && helper.getLevel().getEntity(npc.getUUID()) == null, "Normal death completes removal");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "Even guaranteed equipment creates no tutor loot");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, area).isEmpty(), "Death creates no experience");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void liveReturnGoalWalksBackWithoutTeleporting(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 3, 8);
        GlobalPos home = npc.getTutorAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        npc.moveTo(helper.absoluteVec(new Vec3(13.5, 1.0, 8.5)));
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        Vec3[] previous = { npc.position() };
        int[] walkedTicks = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double step = npc.position().distanceTo(previous[0]);
            helper.assertTrue(step < 0.8, "Return must consist of physical walking, not a teleport: " + step);
            if (step > 0.01) walkedTicks[0]++;
            previous[0] = npc.position();
            helper.assertValueEqual(npc.getTutorAnchor(), home, "Returning does not replace home");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(homePoint) < 2.25, "Live AI has returned to within 1.5 blocks of home");
            helper.assertTrue(walkedTicks[0] > 15, "Return was observed over multiple real server ticks");
            JoblessReincarnation.LOGGER.info("[JR_ROXY_B01_ENTITY] PASS return_home walkedTicks={} position={} home={}", walkedTicks[0], npc.position(), home);
            npc.discard();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void blockedReturnWaitsThenRetriesWhenAnExitOpens(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 3, 8);
        GlobalPos home = npc.getTutorAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3 pushed = helper.absoluteVec(new Vec3(13.5, 1.0, 8.5));
        // One-block interior, three-high walls: no complete route and no jumping over the cage.
        for (int y = 1; y <= 3; y++) for (int x = 12; x <= 14; x++) for (int z = 7; z <= 9; z++) {
            if (x == 12 || x == 14 || z == 7 || z == 9) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        npc.moveTo(pushed);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        boolean[] opened = { false };
        Vec3[] previous = { pushed };
        int[] blockedSamples = { 0 }, walkedSamples = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double step = npc.position().distanceTo(previous[0]);
            helper.assertTrue(step < 0.8, "Blocked recovery never teleports through the cage");
            previous[0] = npc.position();
            if (!opened[0]) {
                helper.assertTrue(npc.position().distanceToSqr(pushed) < 0.01, "No complete route means waiting at the pushed location");
                helper.assertTrue(npc.getNavigation().isDone(), "An unreachable partial route is not followed");
                helper.assertBlockPresent(Blocks.STONE, new BlockPos(12, 1, 8));
                blockedSamples[0]++;
            } else if (step > 0.01) walkedSamples[0]++;
            helper.assertValueEqual(npc.getTutorAnchor(), home, "Blocked recovery keeps the original home");
        });
        helper.runAfterDelay(85, () -> {
            helper.assertTrue(blockedSamples[0] >= 75, "Observed waiting across multiple retry windows");
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(12, y, 8), Blocks.AIR);
            opened[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(opened[0] && npc.distanceToSqr(homePoint) < 2.25, "A later retry follows the newly opened route home");
            helper.assertTrue(walkedSamples[0] > 15, "Recovery is visible walking after the obstacle is removed");
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(14, 1, 8));
            JoblessReincarnation.LOGGER.info("[JR_ROXY_B01_ENTITY] PASS blocked_retry blockedSamples={} walkedSamples={} home={}", blockedSamples[0], walkedSamples[0], home);
            npc.discard();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 310)
    public static void liveStrollRemainsNearItsOwnPersistentAnchor(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 8, 8);
        npc.getRandom().setSeed(42L);
        GlobalPos home = npc.getTutorAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3[] previous = { npc.position() };
        double[] totalWalked = { 0.0 }, largestDistance = { 0.0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double distance = npc.position().distanceTo(homePoint);
            largestDistance[0] = Math.max(largestDistance[0], distance);
            helper.assertTrue(distance <= RoxyTutorEntity.HOME_RADIUS + 0.15, "Live bounded stroll remains in the eight-block home area");
            totalWalked[0] += npc.position().distanceTo(previous[0]);
            previous[0] = npc.position();
            helper.assertValueEqual(npc.getTutorAnchor(), home, "Walking never drifts the persistent anchor");
        });
        helper.runAfterDelay(280, () -> {
            try {
                helper.assertTrue(totalWalked[0] > 1.0, "Actual AI selected and walked at least one route");
                JoblessReincarnation.LOGGER.info("[JR_ROXY_B01_ENTITY] PASS bounded_stroll totalWalked={} largestDistance={} home={}", totalWalked[0], largestDistance[0], home);
            } finally { npc.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void conversationInterruptsLiveWalkingForFortyTicksThenReleasesIt(GameTestHelper helper) {
        floor(helper);
        RoxyTutorEntity npc = spawn(helper, 3, 8);
        GlobalPos home = npc.getTutorAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3 pushed = helper.absoluteVec(new Vec3(13.5, 1.0, 8.5));
        npc.moveTo(pushed);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        FakePlayer reader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "TutorAiReader"));
        reader.moveTo(helper.absoluteVec(new Vec3(13.5, 1.0, 11.5)));
        reader.getAbilities().instabuild = false;
        helper.getLevel().addNewPlayer(reader);
        boolean[] closed = { false };
        Runnable cleanup = () -> {
            if (closed[0]) return;
            closed[0] = true;
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(reader));
            reader.discard();
            npc.discard();
        };
        int[] phase = { 0 }, pausedSamples = { 0 };
        long[] started = { -1L };
        Vec3[] pauseStart = { null }, settled = { null };
        helper.onEachTick(() -> {
            if (closed[0]) return;
            try {
                helper.assertValueEqual(npc.getTutorAnchor(), home, "Conversation must not relocate the tutor's home");
                if (phase[0] == 0) {
                    // Wait for genuine AI movement before asking the entity to interrupt that movement.
                    if (npc.getNavigation().isDone() || npc.position().distanceToSqr(pushed) < 0.04) return;
                    helper.assertTrue(npc.distanceToSqr(homePoint) > 8.5 * 8.5, "Start the conversation while return-home is still required");
                    reader.moveTo(npc.position().add(0.0, 0.0, 2.0));
                    helper.assertTrue(helper.getLevel().getEntity(reader.getUUID()) == reader, "Reader is a registered server-level player");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Reader remains in genuine interaction range and sight");
                    helper.assertTrue(npc.beginConversation(reader), "The live walking tutor accepts the registered reader");
                    started[0] = helper.getLevel().getGameTime();
                    pauseStart[0] = npc.position();
                    phase[0] = 1;
                    return;
                }
                long elapsed = helper.getLevel().getGameTime() - started[0];
                if (phase[0] == 1 && elapsed < RoxyTutorEntity.CONVERSATION_TICKS) {
                    helper.assertTrue(npc.isConversing() && npc.conversationPlayer() == reader, "The valid reader remains the conversation target before expiry");
                    helper.assertTrue(npc.getNavigation().isDone(), "Conversation cancels the active return path");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Conversation has not merely expired from invalid range or sight");
                    // Vanilla movement retains brief momentum; evaluate the settled position after eight ticks.
                    helper.assertTrue(npc.position().distanceToSqr(pauseStart[0]) < 0.65 * 0.65, "Residual momentum cannot continue the original walking route");
                    if (elapsed >= 8) {
                        if (settled[0] == null) settled[0] = npc.position();
                        helper.assertTrue(npc.position().distanceToSqr(settled[0]) < 0.05 * 0.05, "After settling the tutor remains still during the conversation");
                    }
                    if (elapsed >= 12) {
                        float wanted = (float)(Mth.atan2(reader.getZ() - npc.getZ(), reader.getX() - npc.getX()) * 180.0 / Math.PI) - 90.0F;
                        helper.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYHeadRot() - wanted)) < 15.0F, "Head turns toward the real reader");
                        helper.assertTrue(Math.abs(Mth.wrapDegrees(npc.yBodyRot - wanted)) < 15.0F, "Body follows the conversation direction");
                    }
                    pausedSamples[0]++;
                    return;
                }
                if (phase[0] == 1) {
                    helper.assertTrue(elapsed == RoxyTutorEntity.CONVERSATION_TICKS, "Expiry is observed at the forty-tick boundary");
                    helper.assertFalse(npc.isConversing(), "The conversation ends at forty ticks while its reader is still valid");
                    helper.assertTrue(pausedSamples[0] >= 38, "The pause was observed throughout the interval");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "The timer, not departing or obstructing the reader, ended the pause");
                    phase[0] = 2;
                    return;
                }
                if (phase[0] == 2 && !npc.getNavigation().isDone() && npc.position().distanceToSqr(settled[0]) > 0.25 * 0.25) {
                    helper.assertFalse(npc.isConversing(), "The expired private conversation does not restart itself");
                    JoblessReincarnation.LOGGER.info("[JR_ROXY_B01_ENTITY] PASS live_conversation pausedSamples={} resumeElapsed={} bodyYaw={} headYaw={}",
                        pausedSamples[0], elapsed, npc.yBodyRot, npc.getYHeadRot());
                    cleanup.run();
                    helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
        });
        helper.runAfterDelay(170, () -> {
            if (closed[0]) return;
            cleanup.run();
            helper.assertTrue(false, "Live conversation did not complete its walk-pause-resume lifecycle");
        });
    }
    private RoxyTutorEntityGameTests() { }
}