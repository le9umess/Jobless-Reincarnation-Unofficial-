package dev.joblessreincarnation.magic;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server geometry, event cancellation, payment and collision checks. Excluded from release jars. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EarthWallGameTests {
    private static FakePlayer caster(GameTestHelper helper) {
        for (int x = 4; x <= 6; x++) helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "EarthTest"));
        player.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
        player.getAbilities().instabuild = false;
        player.getAbilities().mayBuild = true;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        aim(player, helper.absoluteVec(new Vec3(5.5, 2, 6.5)));
        return player;
    }

    private static void aim(FakePlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }

    private static EarthWallSpell.Plan validPlan(GameTestHelper helper, FakePlayer player) {
        EarthWallSpell.Plan plan = EarthWallSpell.plan(player);
        helper.assertTrue(plan.valid(), "Fixture must provide a valid wall: " + plan.reason().getString());
        helper.assertValueEqual(plan.positions().size(), 6, "Preview contains exactly six cells");
        return plan;
    }

    private static void untouched(GameTestHelper helper, FakePlayer player) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Failed wall costs no mana");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "Failed wall starts no shared cooldown");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "Failed wall grants no practice");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Failed wall starts no item cooldown");
    }

    private static void clear(GameTestHelper helper, EarthWallSpell.Plan plan) {
        for (BlockPos pos : plan.positions()) helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "No half-wall remains at " + pos);
    }

    @GameTest(template = "empty")
    public static void wallBuildsSixCellsAndSettlesOnceIndependently(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        helper.assertTrue(EarthWallSpell.cast(player), "Valid release builds a complete wall");
        for (BlockPos pos : plan.positions()) helper.assertTrue(helper.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get()), "Each preview cell becomes solid soil");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.EARTH_WALL_COST.getAsInt(), "One whole-wall fee is paid");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.EARTH_WALL_COOLDOWN.getAsInt(), "Successful wall starts shared cooldown");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 1, "Six cells count as one earth practice");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "Earth does not advance water practice");
        helper.assertFalse(WaterMagic.cast(player), "Switching to water cannot bypass the wall cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeBuildsWithoutSpendingOrPracticing(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        player.getAbilities().instabuild = true;
        helper.assertTrue(EarthWallSpell.cast(player), "Creative can build at a valid site");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Creative does not spend mana");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "Creative cannot farm practice");
        helper.assertTrue(player.getData(ModAttachments.MANA).cooldown() > 0, "Creative still has shared cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releaseRechecksKnowledgeStaffAndMana(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
        helper.assertFalse(EarthWallSpell.cast(player), "Unknown spell cannot create walls");
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        helper.assertFalse(EarthWallSpell.cast(player), "Missing wand fails server validation");
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        untouched(helper, player);
        player.setData(ModAttachments.MANA, new ManaData(MagicConfig.EARTH_WALL_COST.getAsInt() - 1, 100, 0, 0));
        helper.assertFalse(EarthWallSpell.cast(player), "Insufficient mana cannot make a smaller wall");
        clear(helper, plan);
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "Resource failures grant no practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wallOrientationSnapsAcrossAllFourDirections(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        BlockPos support = helper.absolutePos(new BlockPos(8, 1, 8));
        for (Direction forward : Direction.Plane.HORIZONTAL) {
            for (Direction across : Direction.Plane.HORIZONTAL) helper.getLevel().setBlock(support.relative(across), Blocks.STONE.defaultBlockState(), 3);
            helper.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            Vec3 center = Vec3.atBottomCenterOf(support.above());
            player.setPos(center.subtract(forward.getStepX() * 4.0, 0, forward.getStepZ() * 4.0));
            aim(player, center);
            EarthWallSpell.Plan plan = validPlan(helper, player);
            Direction width = forward.getClockWise();
            helper.assertTrue(plan.positions().contains(support.above().relative(width)), "Wall extends across the viewing direction");
            helper.assertTrue(plan.positions().contains(support.above().relative(width.getOpposite()).above()), "Upper outer corner matches preview");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void occupiedOuterCellRejectsEntireWall(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        BlockPos occupied = plan.positions().get(5);
        helper.getLevel().setBlock(occupied, Blocks.CHEST.defaultBlockState(), 3);
        helper.assertFalse(EarthWallSpell.cast(player), "Existing containers are never replaced");
        helper.assertTrue(helper.getLevel().getBlockState(occupied).is(Blocks.CHEST), "Existing block survives");
        for (BlockPos pos : plan.positions()) if (!pos.equals(occupied)) helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "Failure does not leave partial soil");
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingSideSupportRejectsEntireWall(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        helper.getLevel().setBlock(plan.positions().get(2).below(), Blocks.AIR.defaultBlockState(), 3);
        helper.assertFalse(EarthWallSpell.cast(player), "All three bottom cells require ground");
        clear(helper, plan);
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidInAnOuterCellCannotBeDisplaced(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        BlockPos wet = plan.positions().get(4);
        helper.getLevel().setBlock(wet, Blocks.WATER.defaultBlockState(), 3);
        helper.assertFalse(EarthWallSpell.cast(player), "Wall cannot replace fluid");
        helper.assertFalse(helper.getLevel().getFluidState(wet).isEmpty(), "Fluid survives rejection");
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void entityOccupyingTheWallPreventsConstruction(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        var cow = helper.spawn(EntityType.COW, new BlockPos(5, 2, 6));
        helper.assertFalse(EarthWallSpell.cast(player), "Cannot build inside another entity");
        clear(helper, plan);
        untouched(helper, player);
        cow.discard();
        // FakePlayers are not registered entities; the explicit caster test covers self-intersection too.
        player.setPos(Vec3.atBottomCenterOf(plan.positions().getFirst()));
        helper.assertFalse(EarthWallSpell.validate(player, plan.positions(), false).valid(), "Cannot form a wall inside the caster");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void outOfRangeAndSideHitsAreRejected(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        player.setXRot(0);
        helper.assertFalse(EarthWallSpell.plan(player).valid(), "No surface inside range is invalid");
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.STONE);
        helper.assertFalse(EarthWallSpell.cast(player), "A side face is not a construction floor");
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void heightAndUnloadedAreaChecksDoNotCreateChunks(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        int shiftY = helper.getLevel().getMaxBuildHeight() - plan.positions().getFirst().getY();
        List<BlockPos> high = plan.positions().stream().map(pos -> pos.offset(0, shiftY, 0)).toList();
        helper.assertFalse(EarthWallSpell.validate(player, high, false).valid(), "Ceiling overflow is rejected");
        List<BlockPos> far = plan.positions().stream().map(pos -> pos.offset(10000000, 0, 0)).toList();
        helper.assertFalse(helper.getLevel().hasChunkAt(far.getFirst()), "Remote test chunk is absent");
        helper.assertFalse(EarthWallSpell.validate(player, far, false).valid(), "Unloaded positions are rejected");
        helper.assertFalse(helper.getLevel().hasChunkAt(far.getFirst()), "Validation does not load remote chunks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worldBorderAndAdventureDenialsCostNothing(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        player.getAbilities().mayBuild = false;
        helper.assertFalse(EarthWallSpell.cast(player), "Adventure cannot build magic walls");
        player.getAbilities().mayBuild = true;
        var border = helper.getLevel().getWorldBorder();
        double oldX = border.getCenterX(), oldZ = border.getCenterZ(), oldSize = border.getSize();
        try {
            BlockPos center = plan.positions().getFirst();
            border.setCenter(center.getX() + 0.5, center.getZ() + 0.5);
            border.setSize(2);
            helper.assertFalse(EarthWallSpell.cast(player), "An outer cell crossing the border invalidates all six");
        } finally {
            border.setCenter(oldX, oldZ);
            border.setSize(oldSize);
        }
        clear(helper, plan);
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void multiPlaceCancellationRestoresAllSixAndDoesNotNotifyNeighbors(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        Consumer<BlockEvent.EntityMultiPlaceEvent> deny = event -> {
            if (event.getEntity() != player) return;
            calls.incrementAndGet();
            helper.assertValueEqual(event.getReplacedBlockSnapshots().size(), 6, "Protection receives all six old states");
            for (var snapshot : event.getReplacedBlockSnapshots()) {
                helper.assertTrue(snapshot.getState().isAir(), "Snapshot is the replaced air state");
                helper.assertTrue(snapshot.getCurrentState().is(ModContent.EARTH_WALL.get()), "Protection inspects the actual tentative wall");
            }
            event.setCanceled(true);
        };
        Consumer<BlockEvent.NeighborNotifyEvent> watch = event -> { if (plan.positions().contains(event.getPos())) updates.incrementAndGet(); };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, deny);
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, watch);
        try { helper.assertFalse(EarthWallSpell.cast(player), "Protection cancellation denies the wall"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); NeoForge.EVENT_BUS.unregister(watch); }
        helper.assertValueEqual(calls.get(), 1, "One multi-place event for one cast");
        helper.assertValueEqual(updates.get(), 0, "Rejected wall emitted no self-originated neighbor updates");
        clear(helper, plan);
        untouched(helper, player);
        helper.assertFalse(helper.getLevel().captureBlockSnapshots, "Capture state is restored");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rollbackPreservesProtectionHandlersDifferentBlock(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        BlockPos changed = plan.positions().get(5);
        Consumer<BlockEvent.EntityMultiPlaceEvent> deny = event -> {
            if (event.getEntity() == player) {
                helper.getLevel().setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, deny);
        try { helper.assertFalse(EarthWallSpell.cast(player), "Canceled placement rolls back only its own changes"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        helper.assertTrue(helper.getLevel().getBlockState(changed).is(Blocks.GOLD_BLOCK), "Unrelated third-party change is preserved");
        for (BlockPos pos : plan.positions()) if (!pos.equals(changed)) helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "Our other five tentative cells are removed");
        untouched(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialEngineWriteFailureRollsBackWithoutSettlement(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        AtomicInteger writes = new AtomicInteger(), settlements = new AtomicInteger();
        boolean success = EarthWallPlacement.place(player, plan, () -> { settlements.incrementAndGet(); return true; },
                (level, pos, state, flags) -> writes.incrementAndGet() != 4 && level.setBlock(pos, state, flags));
        helper.assertFalse(success, "A failure at the fourth cell rejects the complete wall");
        helper.assertValueEqual(writes.get(), 4, "Fixture failed after three real world writes");
        helper.assertValueEqual(settlements.get(), 0, "Failed transaction never attempts payment");
        clear(helper, plan);
        untouched(helper, player);
        helper.assertFalse(helper.getLevel().captureBlockSnapshots, "Failure restores capture mode");
        helper.assertFalse(helper.getLevel().restoringBlockSnapshots, "Failure restores rollback mode");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void postEventManaChangeCannotLeaveAFreeWall(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        Consumer<BlockEvent.EntityMultiPlaceEvent> listener = event -> {
            if (event.getEntity() == player) player.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, listener);
        try { helper.assertFalse(EarthWallSpell.cast(player), "Payment rechecks event-modified mana"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        clear(helper, plan);
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 0, "Third-party mana change is preserved");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "Failed payment gives no practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoPlayersCannotSettleForTheSameSpace(GameTestHelper helper) {
        FakePlayer first = caster(helper);
        FakePlayer second = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, first);
        helper.assertTrue(EarthWallSpell.cast(first), "First server-ordered cast succeeds");
        helper.assertFalse(EarthWallSpell.cast(second), "Second release rechecks the occupied space");
        untouched(helper, second);
        helper.assertValueEqual(EarthWallSpell.practiceCount(first), 1, "Exactly one player earns practice");
        for (BlockPos pos : plan.positions()) helper.assertTrue(helper.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get()), "First player's complete wall remains");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void solidWallStopsWaterFromEitherSideAndOrdinaryArrows(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        helper.assertTrue(EarthWallSpell.cast(player), "Fixture builds actual wall");
        BlockPos center = plan.positions().getFirst();
        for (int direction : new int[]{-1, 1}) {
            WaterBolt bolt = new WaterBolt(helper.getLevel(), player);
            bolt.setPos(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5 - direction * 2);
            bolt.setDeltaMovement(0, 0, direction * 1.35);
            helper.getLevel().addFreshEntity(bolt);
            for (int tick = 0; tick < 4 && !bolt.isRemoved(); tick++) bolt.tick();
            helper.assertTrue(bolt.isRemoved(), "Water projectile collides from either side");
        }
        Arrow arrow = new Arrow(helper.getLevel(), center.getX() + 0.5, center.getY() + 0.5, center.getZ() - 2,
                Items.ARROW.getDefaultInstance(), null);
        arrow.setDeltaMovement(0, 0, 2);
        helper.getLevel().addFreshEntity(arrow);
        for (int tick = 0; tick < 8; tick++) arrow.tick();
        helper.assertTrue(arrow.getZ() < center.getZ() + 0.1, "Ordinary arrow cannot pass through the wall");
        net.minecraft.nbt.CompoundTag savedArrow = new net.minecraft.nbt.CompoundTag();
        arrow.addAdditionalSaveData(savedArrow);
        helper.assertTrue(savedArrow.getBoolean("inGround"), "Arrow remains embedded in the wall");
        arrow.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void miningAndSilkTouchYieldNoRecoverableSoil(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        helper.assertTrue(EarthWallSpell.cast(player), "Fixture builds soil");
        BlockPos pos = plan.positions().getFirst();
        var state = helper.getLevel().getBlockState(pos);
        ItemStack silk = Items.DIAMOND_SHOVEL.getDefaultInstance();
        silk.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
        helper.assertTrue(Block.getDrops(state, helper.getLevel(), pos, null, player, ItemStack.EMPTY).isEmpty(), "Hand mining yields no drop");
        helper.assertTrue(Block.getDrops(state, helper.getLevel(), pos, null, player, silk).isEmpty(), "Silk touch yields no drop");
        helper.assertValueEqual(state.getPistonPushReaction(), PushReaction.BLOCK, "Pistons cannot move magic soil");
        helper.getLevel().destroyBlock(pos, true, player);
        helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "Normal destruction removes one cell");
        helper.assertTrue(helper.getLevel().getBlockState(plan.positions().get(1)).is(ModContent.EARTH_WALL.get()), "Dismantling one block leaves the others");
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void explosionBreaksMagicSoilWithoutYieldingItems(GameTestHelper helper) {
        FakePlayer player = caster(helper);
        EarthWallSpell.Plan plan = validPlan(helper, player);
        for (int index = 0; index < plan.positions().size(); index += 2) {
            helper.getLevel().setBlock(plan.positions().get(index).below(), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        helper.assertTrue(EarthWallSpell.cast(player), "Fixture builds real soil above non-dropping blast-proof ground");
        BlockPos center = plan.positions().getFirst();
        helper.getLevel().explode(null, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5,
                2.0F, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
        helper.assertTrue(helper.getLevel().isEmptyBlock(center), "A real explosion can remove magic soil");
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.assertEntityNotPresent(EntityType.EXPERIENCE_ORB);
        helper.succeed();
    }
    private EarthWallGameTests() {}
}


