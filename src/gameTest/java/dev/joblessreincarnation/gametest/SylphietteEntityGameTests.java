package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.SylphietteEntity;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Six focused SYLPHIE-S01 entity regressions. Registered types, live server players and actual AI;
 * ordinary NBT round trips are not disk/restart evidence. No production implementation is called to make expectations.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SylphietteEntityGameTests {
    private static final String BATCH = "sylphieS01Entity", ANCHOR_TAG = "JR_HomeAnchor";
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path); }
    private static EntityType<?> type(GameTestHelper helper) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id("sylphiette"));
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(type), id("sylphiette"), "Exact registered child NPC type");
        return type;
    }
    private static void floor(GameTestHelper helper) {
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static SylphietteEntity create(GameTestHelper helper) {
        Entity entity = type(helper).create(helper.getLevel());
        helper.assertTrue(entity instanceof SylphietteEntity, "The registered factory creates the independent Sylphiette class");
        return (SylphietteEntity)entity;
    }
    private static SylphietteEntity spawn(GameTestHelper helper, int x, int z) {
        BlockPos feet = helper.absolutePos(new BlockPos(x, 1, z));
        Entity entity = type(helper).spawn(helper.getLevel(), feet, MobSpawnType.SPAWN_EGG);
        helper.assertTrue(entity instanceof SylphietteEntity, "Normal EntityType.spawn inserts the child NPC");
        SylphietteEntity npc = (SylphietteEntity)entity;
        helper.assertTrue(helper.getLevel().getEntity(npc.getUUID()) == npc, "The live server index owns her UUID");
        helper.assertValueEqual(npc.getHomeAnchor(), GlobalPos.of(helper.getLevel().dimension(), feet), "Supported spawn records its actual world home");
        return npc;
    }
    private static CompoundTag save(GameTestHelper helper, SylphietteEntity npc) {
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(npc.save(saved), "NPC participates in ordinary saving");
        helper.assertValueEqual(saved.getString("id"), id("sylphiette").toString(), "Saved type keeps the independent character identity");
        return saved;
    }
    private static SylphietteEntity load(GameTestHelper helper, CompoundTag tag) {
        Entity entity = EntityType.loadEntityRecursive(tag.copy(), helper.getLevel(), value -> value);
        helper.assertTrue(entity instanceof SylphietteEntity, "Vanilla NBT load restores the independent class");
        return (SylphietteEntity)entity;
    }
    private static CommandSourceStack source(GameTestHelper helper, int permission) {
        return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
            .withPosition(helper.absoluteVec(new Vec3(8, 2, 8))).withPermission(permission).withSuppressedOutput();
    }
    private static int command(GameTestHelper helper, int permission, String text) {
        try { return helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, permission)); }
        catch (CommandSyntaxException error) { throw new IllegalStateException("Unexpected rejection of " + text, error); }
    }
    private static void rejects(GameTestHelper helper, int permission, String text) {
        boolean rejected = false;
        try { helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, permission)); }
        catch (CommandSyntaxException expected) { rejected = true; }
        helper.assertTrue(rejected, "Brigadier rejects the unauthorized or mismatched operation: " + text);
    }
    private static void logout(FakePlayer reader) {
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(reader));
        reader.discard();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 50)
    public static void registeredChildDimensionsEggProtectionAndNativeKillProduceNoLoot(GameTestHelper helper) {
        floor(helper);
        SylphietteEntity npc = spawn(helper, 8, 8);
        AABB area = new AABB(helper.absoluteVec(new Vec3(1, 0, 1)), helper.absoluteVec(new Vec3(15, 5, 15)));
        try {
            helper.assertValueEqual(npc.getType().getCategory(), MobCategory.CREATURE, "The child uses the passive category");
            helper.assertValueEqual(npc.getType().clientTrackingRange(), 10, "Tracking range follows IF1");
            helper.assertValueEqual(npc.getType().updateInterval(), 2, "Update interval follows IF1");
            helper.assertTrue(Math.abs(npc.getBbWidth() - 0.5F) < 0.0001 && Math.abs(npc.getBbHeight() - 1.3F) < 0.0001,
                "A child-sized collision body is registered instead of the adult family's body");
            helper.assertTrue(Math.abs(npc.getEyeHeight() - 1.0F) < 0.0001, "Child eye height follows the actual head");
            helper.assertValueEqual(npc.homeRadius(), 3, "Her own activity radius is three blocks");
            helper.assertTrue(npc.shouldBeSaved() && npc.isPersistenceRequired() && !npc.removeWhenFarAway(1_000_000), "Normal persistent storage and no natural despawn");
            helper.assertFalse(npc.isNoAi() || npc.canBeLeashed() || npc.canPickUpLoot() || npc.canBeSeenAsEnemy()
                || npc.canAttackType(EntityType.PLAYER), "New character retains noncombat resident protection with live AI");
            helper.assertValueEqual(npc.getCustomName(), Component.translatable("entity.jobless_reincarnation.sylphiette"), "Default name is translated independently");
            ItemStack egg = BuiltInRegistries.ITEM.get(id("sylphiette_spawn_egg")).getDefaultInstance();
            helper.assertTrue(egg.getItem() instanceof SpawnEggItem && ((SpawnEggItem)egg.getItem()).getType(egg) == npc.getType(), "Registered egg resolves exactly this character");
            npc.setNoAi(true);
            npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
            npc.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
            float health = npc.getHealth();
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().generic(), 1000), "Generic damage is blocked");
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().lava(), 1000), "Environmental damage is blocked");
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000), "Void damage cannot remove this protected NPC");
            helper.assertValueEqual(npc.getHealth(), health, "Rejected damage leaves health unchanged");
            helper.assertValueEqual(command(helper, 2, "kill " + npc.getUUID()), 1, "Native administrator kill remains effective");
            helper.assertTrue(npc.isDeadOrDying(), "GENERIC_KILL starts normal removal");
        } catch (RuntimeException | Error error) { npc.discard(); throw error; }
        helper.runAfterDelay(25, () -> {
            try {
                helper.assertTrue(npc.isRemoved() && helper.getLevel().getEntity(npc.getUUID()) == null, "Killed NPC leaves the actual index");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "Even guaranteed equipment produces no loot");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, area).isEmpty(), "Removal produces no XP");
                helper.succeed();
            } finally { npc.discard(); }
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 20)
    public static void unsupportedAndBlockedAnchorsRejectWithoutErasingTheGoodHome(GameTestHelper helper) {
        floor(helper); SylphietteEntity npc = create(helper);
        helper.assertTrue(npc.getHomeAnchor() == null && !npc.reanchor(), "Unjoined constructor cannot create a world home");
        npc.setNoAi(true); npc.setNoGravity(true);
        npc.moveTo(helper.absoluteVec(new Vec3(8.5, 3.0, 8.5)));
        helper.assertTrue(helper.getLevel().addFreshEntity(npc), "Unsupported real entity joins");
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(npc.getHomeAnchor() == null && !npc.reanchor(), "Several live ticks cannot anchor unsupported air");
                npc.moveTo(helper.absoluteVec(new Vec3(8.5, 1.0, 8.5)));
                helper.assertTrue(npc.reanchor(), "Supported floor is a positive control");
                GlobalPos good = npc.getHomeAnchor();
                npc.moveTo(helper.absoluteVec(new Vec3(10.5, 3.0, 8.5)));
                helper.assertFalse(npc.reanchor(), "Unsupported relocation is rejected");
                helper.assertValueEqual(npc.getHomeAnchor(), good, "Unsupported rejection preserves the previous good anchor");
                helper.setBlock(new BlockPos(10, 1, 8), Blocks.STONE);
                npc.moveTo(helper.absoluteVec(new Vec3(10.5, 1.0, 8.5)));
                helper.assertFalse(npc.reanchor(), "An occupied body volume is not a legal anchor");
                helper.assertValueEqual(npc.getHomeAnchor(), good, "Blocked rejection also preserves the previous good anchor");
                helper.assertValueEqual(npc.getRestrictCenter(), good.pos(), "Rejected relocation leaves the activity restriction unchanged");
                helper.succeed();
            } finally { npc.discard(); }
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 30)
    public static void normalNbtKeepsIdentityHomeAndNameButDropsTransientConversation(GameTestHelper helper) {
        floor(helper); SylphietteEntity original = spawn(helper, 8, 8);
        original.setNoAi(true); original.setNoGravity(true);
        original.setCustomName(Component.literal("Persisted test name"));
        FakePlayer reader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SylphSaveReader"));
        reader.moveTo(helper.absoluteVec(new Vec3(8.5, 1, 10.5)));
        helper.getLevel().addNewPlayer(reader);
        helper.assertTrue(original.beginConversation(reader), "A genuinely indexed nearby player begins the transient conversation");
        GlobalPos home = original.getHomeAnchor(); UUID uuid = original.getUUID(); Component name = original.getCustomName().copy();
        original.moveTo(original.position().add(0.25, 0, 0)); Vec3 moved = original.position();
        CompoundTag saved = save(helper, original), witness = saved.copy();
        helper.assertTrue(saved.contains(ANCHOR_TAG, Tag.TAG_COMPOUND), "Exact persistent home key is present");
        helper.assertValueEqual(GlobalPos.CODEC.parse(NbtOps.INSTANCE, saved.getCompound(ANCHOR_TAG)).result().orElseThrow(), home, "NBT retains home separately from moved position");
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            SylphietteEntity restored = null;
            try {
                helper.assertTrue(helper.getLevel().getEntity(uuid) == null, "Old identity left the actual server index");
                restored = load(helper, saved);
                helper.assertValueEqual(restored.getUUID(), uuid, "Ordinary NBT load retains UUID");
                helper.assertValueEqual(restored.getHomeAnchor(), home, "Ordinary NBT load retains the original home");
                helper.assertValueEqual(restored.position(), moved, "Moved world position is independent of home");
                helper.assertValueEqual(restored.getCustomName(), name, "Ordinary NBT load retains the name");
                helper.assertFalse(restored.isConversing() || restored.conversationPlayer() != null, "No saved conversation target or pause is restored");
                helper.assertTrue(helper.getLevel().addFreshEntity(restored), "Restored identity rejoins exactly once");
                helper.assertValueEqual(restored.getHomeAnchor(), home, "Joining does not rewrite a valid saved home");
                helper.assertValueEqual(saved, witness, "Vanilla loading does not mutate the source save document");
                helper.succeed();
            } finally { if (restored != null) restored.discard(); original.discard(); logout(reader); }
        });
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void dedicatedAnchorCommandChecksPermissionTargetAndGround(GameTestHelper helper) {
        floor(helper); SylphietteEntity npc = spawn(helper, 3, 5), neighbour = spawn(helper, 10, 11);
        npc.setNoAi(true); neighbour.setNoAi(true);
        Entity family = BuiltInRegistries.ENTITY_TYPE.get(id("paul_greyrat")).spawn(helper.getLevel(),
            helper.absolutePos(new BlockPos(3, 1, 11)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(family != null, "A genuine registered household NPC is available as a mismatched command target");
        try {
            rejects(helper, 2, "jr_sylphiette anchor " + family.getUUID());
            GlobalPos original = npc.getHomeAnchor(), untouched = neighbour.getHomeAnchor();
            BlockPos target = helper.absolutePos(new BlockPos(12, 1, 5)); npc.moveTo(Vec3.atBottomCenterOf(target));
            String own = "jr_sylphiette anchor " + npc.getUUID();
            rejects(helper, 1, own);
            helper.assertValueEqual(npc.getHomeAnchor(), original, "Permission-one command cannot mutate home");
            rejects(helper, 2, "jr_family anchor " + npc.getUUID());
            helper.assertValueEqual(npc.getHomeAnchor(), original, "The family command rejects this independent character");
            helper.assertValueEqual(command(helper, 2, own), 1, "Permission two can deliberately anchor this supported child NPC");
            GlobalPos moved = GlobalPos.of(helper.getLevel().dimension(), target);
            helper.assertValueEqual(npc.getHomeAnchor(), moved, "Dedicated command anchors the chosen supported location");
            helper.assertValueEqual(neighbour.getHomeAnchor(), untouched, "UUID target does not alter a second Sylphiette");
            npc.setNoGravity(true); npc.moveTo(npc.position().add(0, 2, 0));
            rejects(helper, 2, own);
            helper.assertValueEqual(npc.getHomeAnchor(), moved, "Administrator cannot erase a good anchor by choosing unsupported air");
        } finally { npc.discard(); neighbour.discard(); family.discard(); }
        helper.succeed();
    }

    private static void stroll(GameTestHelper helper) {
        floor(helper);
        SylphietteEntity npc = spawn(helper, 8, 8);
        npc.getRandom().setSeed(42L);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 centre = Vec3.atBottomCenterOf(home.pos());
        Vec3[] previous = { npc.position() };
        double[] total = { 0.0 }, furthest = { 0.0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double distance = npc.position().distanceTo(centre);
            furthest[0] = Math.max(furthest[0], distance);
            helper.assertTrue(distance <= SylphietteEntity.HOME_RADIUS + 0.15, "Live stroll respects this member's 3D home radius");
            helper.assertValueEqual(npc.getHomeAnchor(), home, "Strolling does not drift home");
            Path path = npc.getNavigation().getPath();
            if (path != null && !npc.getNavigation().isDone()) {
                helper.assertTrue(path.canReach(), "An accepted stroll route reaches its target");
                helper.assertValueEqual(path.getTarget().getY(), home.pos().getY(), "Flat-ground stroll target stays on the home floor");
                for (int node = path.getNextNodeIndex(); node < path.getNodeCount(); node++) {
                    helper.assertTrue(path.getEntityPosAtNode(npc, node).distanceToSqr(centre) <= SylphietteEntity.HOME_RADIUS * SylphietteEntity.HOME_RADIUS + 0.001,
                        "Every remaining stroll node stays in the member's home volume");
                }
            }
            total[0] += npc.position().distanceTo(previous[0]);
            previous[0] = npc.position();
        });
        helper.runAfterDelay(500, () -> {
            try {
                helper.assertTrue(total[0] > 1.0, "Actual AI selected and walked a route during several independent attempt windows");
                JoblessReincarnation.LOGGER.info("[JR_SYLPHIE_S01_ENTITY] PASS stroll member={} totalWalked={} furthest={} radius={}",
                    "sylphiette", total[0], furthest[0], SylphietteEntity.HOME_RADIUS);
            } finally { npc.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 530)
    public static void actualFlatGroundStrollStaysInsideThreeBlockHome(GameTestHelper helper) { stroll(helper); }

    private static void conversation(GameTestHelper helper) {
        floor(helper);
        SylphietteEntity npc = spawn(helper, 3, 8);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3 pushed = helper.absoluteVec(new Vec3(13.5, 1.0, 8.5));
        npc.moveTo(pushed);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        FakePlayer reader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SylphieAiReader"));
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
        int[] phase = { 0 }, paused = { 0 };
        long[] started = { -1L };
        Vec3[] pauseStart = { null }, settled = { null };
        helper.onEachTick(() -> {
            if (closed[0]) return;
            try {
                helper.assertValueEqual(npc.getHomeAnchor(), home, "Conversation never relocates home");
                if (phase[0] == 0) {
                    if (npc.getNavigation().isDone() || npc.position().distanceToSqr(pushed) < 0.04) return;
                    helper.assertTrue(npc.distanceToSqr(homePoint) > (SylphietteEntity.HOME_RADIUS + 0.5) * (SylphietteEntity.HOME_RADIUS + 0.5),
                        "Conversation starts during a real required return walk");
                    reader.moveTo(npc.position().add(0.0, 0.0, 2.0));
                    helper.assertTrue(helper.getLevel().getEntity(reader.getUUID()) == reader, "Conversation uses a registered server player");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Reader is genuinely in range and sight");
                    helper.assertTrue(npc.beginConversation(reader), "Walking resident accepts the valid reader");
                    started[0] = helper.getLevel().getGameTime();
                    pauseStart[0] = npc.position();
                    // A transient conversation must not be resurrected by the normal entity loader.
                    SylphietteEntity loaded = load(helper, save(helper, npc));
                    helper.assertTrue(!loaded.isConversing() && loaded.conversationPlayer() == null, "Conversation target is not persistent NBT state");
                    loaded.discard();
                    phase[0] = 1;
                    return;
                }
                long elapsed = helper.getLevel().getGameTime() - started[0];
                if (phase[0] == 1 && elapsed < SylphietteEntity.CONVERSATION_TICKS) {
                    helper.assertTrue(npc.isConversing() && npc.conversationPlayer() == reader, "Same valid reader remains the private target before timer expiry");
                    helper.assertTrue(npc.getNavigation().isDone(), "Conversation cancels the active walking path");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Reader has not invalidated the timer test by leaving range or sight");
                    helper.assertTrue(npc.position().distanceToSqr(pauseStart[0]) < 0.65 * 0.65, "Residual momentum cannot carry on the old walking route");
                    if (elapsed >= 8) {
                        if (settled[0] == null) settled[0] = npc.position();
                        helper.assertTrue(npc.position().distanceToSqr(settled[0]) < 0.05 * 0.05, "Settled resident stands still during conversation");
                    }
                    if (elapsed >= 12) {
                        float wanted = (float)(Mth.atan2(reader.getZ() - npc.getZ(), reader.getX() - npc.getX()) * 180.0 / Math.PI) - 90.0F;
                        helper.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYHeadRot() - wanted)) < 15.0F, "Head looks toward the conversation partner");
                        helper.assertTrue(Math.abs(Mth.wrapDegrees(npc.yBodyRot - wanted)) < 15.0F, "Body follows the conversation direction");
                    }
                    paused[0]++;
                    return;
                }
                if (phase[0] == 1) {
                    helper.assertTrue(elapsed == 40 && elapsed == SylphietteEntity.CONVERSATION_TICKS, "Conversation expires at the agreed forty-tick boundary");
                    helper.assertFalse(npc.isConversing(), "Conversation ends while the valid reader remains nearby");
                    helper.assertTrue(paused[0] >= 38, "Pause was observed throughout its intended duration");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Timer expiry caused release, not invalid range or sight");
                    phase[0] = 2;
                    return;
                }
                if (phase[0] == 2 && !npc.getNavigation().isDone() && npc.position().distanceToSqr(settled[0]) > 0.25 * 0.25) {
                    helper.assertFalse(npc.isConversing(), "Expired conversation does not restart itself");
                    JoblessReincarnation.LOGGER.info("[JR_SYLPHIE_S01_ENTITY] PASS conversation member={} pausedTicks={} resumeElapsed={}", "sylphiette", paused[0], elapsed);
                    cleanup.run();
                    helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
        });
        helper.runAfterDelay(190, () -> {
            if (closed[0]) return;
            cleanup.run();
            helper.assertTrue(false, "Sylphiette conversation did not complete its actual walk-pause-resume lifecycle");
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 210)
    public static void conversationInterruptsRealReturnForFortyTicksAndThenResumes(GameTestHelper helper) { conversation(helper); }

    private SylphietteEntityGameTests() { }
}
