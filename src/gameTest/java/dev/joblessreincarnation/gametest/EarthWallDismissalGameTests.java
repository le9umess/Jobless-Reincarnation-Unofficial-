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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Provenance, upgrade behavior and no-partial-removal checks on a real server world. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EarthWallDismissalGameTests {
    private record Fixture(FakePlayer player, WallIdentity identity, List<BlockPos> positions) {}

    private static FakePlayer player(GameTestHelper helper, UUID uuid) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(uuid, "DismissTest"));
        player.setPos(helper.absoluteVec(new Vec3(5.5, 2, 2.5)));
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        player.getData(ModAttachments.CASTING).select(EarthWallSpell.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        return player;
    }

    private static void aim(FakePlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }

    private static Fixture fixture(GameTestHelper helper) {
        FakePlayer player = player(helper, UUID.randomUUID());
        for (int x = 4; x <= 6; x++) helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
        aim(player, helper.absoluteVec(new Vec3(5.5, 2, 6.5)));
        EarthWallSpell.Plan plan = EarthWallSpell.plan(player);
        helper.assertTrue(plan.valid(), "Fixture has a valid construction site");
        helper.assertTrue(EarthWallSpell.cast(player), "Fixture constructs a real owned wall");
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        aim(player, Vec3.atCenterOf(plan.positions().getFirst().above()));
        WallIdentity identity = cell(helper, plan.positions().getFirst()).identity().orElseThrow();
        return new Fixture(player, identity, identity.footprint());
    }

    private static EarthWallBlockEntity cell(GameTestHelper helper, BlockPos pos) {
        return (EarthWallBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static boolean dismiss(Fixture fixture) {
        return EarthWallDismissal.dismiss(fixture.player(), fixture.identity(), fixture.player().getMainHandItem());
    }

    private static void intact(GameTestHelper helper, Fixture fixture) {
        for (BlockPos pos : fixture.positions()) {
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get()), "Original cell survives");
            helper.assertTrue(cell(helper, pos).identity().filter(fixture.identity()::equals).isPresent(), "Cell provenance survives");
        }
    }

    private static void free(GameTestHelper helper, Fixture fixture) {
        helper.assertValueEqual(fixture.player().getData(ModAttachments.MANA).mana(), 100, "Failure does not spend mana");
        helper.assertValueEqual(fixture.player().getData(ModAttachments.MANA).cooldown(), 0, "Failure starts no shared cooldown");
        helper.assertFalse(fixture.player().getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Failure starts no item cooldown");
        helper.assertValueEqual(EarthWallSpell.practiceCount(fixture.player()), 1, "Dismissal never changes practice");
    }

    @GameTest(template = "empty")
    public static void sixCellsSharePrivateIdentityAndDismissSettlesOnce(GameTestHelper helper) {
        Fixture f = fixture(helper);
        intact(helper, f);
        for (BlockPos pos : f.positions()) {
            helper.assertTrue(cell(helper, pos).getUpdateTag(helper.getLevel().registryAccess()).isEmpty(), "Owner identity is absent from client update tags");
            helper.assertTrue(cell(helper, pos).getUpdatePacket() == null, "No ownership update packet is sent");
        }
        helper.assertTrue(dismiss(f), "Owner can dismiss the complete construction");
        for (BlockPos pos : f.positions()) {
            helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "All six cells are removed");
            helper.assertTrue(helper.getLevel().getBlockEntity(pos) == null, "Removal cleans provenance with the block");
        }
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).mana(), 100 - MagicConfig.EARTH_DISMISS_COST.getAsInt(), "Exactly one dismissal fee");
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).cooldown(), MagicConfig.EARTH_DISMISS_COOLDOWN.getAsInt(), "Shared cooldown starts");
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).recoveryDelay(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), "Recovery delay starts");
        helper.assertTrue(f.player().getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Item cooldown starts");
        helper.assertValueEqual(EarthWallSpell.practiceCount(f.player()), 1, "No dismissal practice reward");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anotherPlayerCannotDismissOrObtainIdentityFromPlan(GameTestHelper helper) {
        Fixture f = fixture(helper);
        FakePlayer other = player(helper, UUID.randomUUID());
        other.setYRot(f.player().getYRot()); other.setXRot(f.player().getXRot());
        EarthWallDismissal.Plan plan = EarthWallDismissal.plan(other);
        helper.assertFalse(plan.valid(), "Other player has no ownership");
        helper.assertTrue(plan.identity() == null, "Rejected plan does not expose owner identity");
        helper.assertFalse(EarthWallDismissal.dismiss(other, f.identity(), other.getMainHandItem()), "Knowing the UUID does not grant ownership");
        intact(helper, f);
        helper.assertValueEqual(other.getData(ModAttachments.MANA).mana(), 100, "Other player spends nothing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyAndCommandCreatedWallsRemainUnowned(GameTestHelper helper) {
        Fixture f = fixture(helper);
        for (BlockPos pos : f.positions()) {
            helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(pos, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
            helper.assertTrue(cell(helper, pos).identity().isEmpty(), "Ordinary block creation never claims an owner");
        }
        helper.assertFalse(EarthWallDismissal.plan(f.player()).valid(), "No retrospective attribution");
        helper.assertFalse(dismiss(f), "Old identity cannot remove replacement unowned wall");
        free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingAndRebuiltCellsAreNotRemovedWithOldConstruction(GameTestHelper helper) {
        Fixture f = fixture(helper);
        BlockPos mined = f.positions().get(2), replaced = f.positions().get(3), newer = f.positions().get(4);
        helper.getLevel().destroyBlock(mined, false);
        helper.assertTrue(helper.getLevel().getBlockEntity(mined) == null, "Ordinary mining removes cell provenance");
        helper.getLevel().setBlock(replaced, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(newer, Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(newer, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
        WallIdentity other = new WallIdentity(UUID.randomUUID(), UUID.randomUUID(), f.identity().bottomCenter(), f.identity().axis());
        cell(helper, newer).bind(other);
        helper.assertValueEqual(EarthWallDismissal.plan(f.player()).positions().size(), 3, "Only three cells still belong to the old construction");
        helper.assertTrue(dismiss(f), "Remaining part can be dismissed");
        helper.assertTrue(helper.getLevel().getBlockState(replaced).is(Blocks.GOLD_BLOCK), "Replacement block is untouched");
        helper.assertTrue(cell(helper, newer).identity().filter(other::equals).isPresent(), "Different owner's new wall is untouched");
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).mana(), 100 - MagicConfig.EARTH_DISMISS_COST.getAsInt(), "Partial wall still has one flat dismissal fee");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedMetadataRecognizesReturningPlayerUuid(GameTestHelper helper) {
        Fixture f = fixture(helper);
        for (BlockPos pos : f.positions()) {
            CompoundTag saved = cell(helper, pos).saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.getLevel().removeBlockEntity(pos);
            EarthWallBlockEntity restored = new EarthWallBlockEntity(pos, ModContent.EARTH_WALL.get().defaultBlockState());
            restored.loadWithComponents(saved, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(restored);
        }
        FakePlayer returning = player(helper, f.player().getUUID());
        returning.setYRot(f.player().getYRot()); returning.setXRot(f.player().getXRot());
        helper.assertTrue(EarthWallDismissal.plan(returning).identity().equals(f.identity()), "Reloaded metadata recognizes a new player instance with the saved UUID");
        helper.assertTrue(EarthWallDismissal.dismiss(returning, f.identity(), returning.getMainHandItem()), "Returning owner can dismiss");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedMetadataDoesNotCreateOwnership(GameTestHelper helper) {
        Fixture f = fixture(helper);
        EarthWallBlockEntity target = cell(helper, f.positions().get(1));
        CompoundTag saved = target.saveWithFullMetadata(helper.getLevel().registryAccess());
        saved.getCompound("WallIdentity").putString("axis", "y");
        target.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.assertTrue(target.identity().isEmpty(), "Invalid vertical footprint is discarded");
        helper.assertFalse(EarthWallDismissal.plan(f.player()).valid(), "Invalid target cannot authorize other blocks");
        saved.getCompound("WallIdentity").putString("axis", "x");
        saved.getCompound("WallIdentity").putLong("center", target.getBlockPos().offset(20, 0, 0).asLong());
        target.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.assertTrue(target.identity().isEmpty(), "Identity must include its own cell");
        free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancelingAnyBreakProtectsTheWholeRemainingGroup(GameTestHelper helper) {
        Fixture f = fixture(helper);
        AtomicInteger checks = new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> listener = event -> {
            if (event.getPlayer() != f.player()) return;
            checks.incrementAndGet();
            intact(helper, f);
            if (event.getPos().equals(f.positions().get(4))) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
        try { helper.assertFalse(dismiss(f), "One protected outer cell denies the whole group"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.assertValueEqual(checks.get(), 6, "Protection receives all remaining cells before any deletion");
        intact(helper, f); free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void eventReplacingCellWithSameMetadataStillAborts(GameTestHelper helper) {
        Fixture f = fixture(helper);
        BlockPos changed = f.positions().get(4);
        AtomicInteger calls = new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> listener = event -> {
            if (event.getPlayer() != f.player() || calls.incrementAndGet() != 1) return;
            helper.getLevel().setBlock(changed, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(changed, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
            cell(helper, changed).bind(f.identity());
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
        try { helper.assertFalse(dismiss(f), "BE instance replacement during protection checks aborts even with copied identity"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        intact(helper, f); free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialWriteFailureRestoresCompleteBlockEntityData(GameTestHelper helper) {
        Fixture f = fixture(helper);
        AtomicInteger writes = new AtomicInteger();
        boolean success = EarthWallDismissal.dismiss(f.player(), f.identity(), f.player().getMainHandItem(),
                (level, pos, state, flags) -> writes.incrementAndGet() != 4 && level.setBlock(pos, state, flags));
        helper.assertFalse(success, "The fourth removal fails");
        helper.assertValueEqual(writes.get(), 4, "Failure follows three actual removals");
        intact(helper, f); free(helper, f);
        helper.assertFalse(helper.getLevel().captureBlockSnapshots, "Capture flag restored");
        helper.assertFalse(helper.getLevel().restoringBlockSnapshots, "Restore flag restored");
        helper.assertTrue(dismiss(f), "Restored provenance still authorizes a later valid attempt");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reportedFailureAfterDeletionAlsoRestoresThatCell(GameTestHelper helper) {
        Fixture f = fixture(helper);
        AtomicInteger writes = new AtomicInteger();
        boolean success = EarthWallDismissal.dismiss(f.player(), f.identity(), f.player().getMainHandItem(), (level, pos, state, flags) -> {
            boolean actual = level.setBlock(pos, state, flags);
            return writes.incrementAndGet() != 3 && actual;
        });
        helper.assertFalse(success, "Third setter writes air and then reports failure");
        intact(helper, f); free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rollbackPreservesThirdPartyReplacement(GameTestHelper helper) {
        Fixture f = fixture(helper);
        AtomicInteger writes = new AtomicInteger();
        BlockPos changed = f.positions().getFirst();
        boolean success = EarthWallDismissal.dismiss(f.player(), f.identity(), f.player().getMainHandItem(), (level, pos, state, flags) -> {
            if (writes.incrementAndGet() == 3) {
                level.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), flags);
                return false;
            }
            return level.setBlock(pos, state, flags);
        });
        helper.assertFalse(success, "Third write aborts");
        helper.assertTrue(helper.getLevel().getBlockState(changed).is(Blocks.GOLD_BLOCK), "External replacement is not overwritten on rollback");
        for (BlockPos pos : f.positions()) if (!pos.equals(changed)) helper.assertTrue(cell(helper, pos).identity().filter(f.identity()::equals).isPresent(), "Other cells restored");
        free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void modeOrManaChangeInProtectionCallbackCancelsForFree(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Consumer<BlockEvent.BreakEvent> listener = event -> { if (event.getPlayer() == f.player()) f.player().getAbilities().instabuild = true; };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
        try { helper.assertFalse(dismiss(f), "Switching to free creative during protection checks cannot change settlement mode"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        intact(helper, f); free(helper, f);
        f.player().getAbilities().instabuild = false;
        Consumer<BlockEvent.BreakEvent> drain = event -> { if (event.getPlayer() == f.player()) f.player().setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0)); };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, drain);
        try { helper.assertFalse(dismiss(f), "Mana is rechecked after protection callbacks"); }
        finally { NeoForge.EVENT_BUS.unregister(drain); }
        intact(helper, f);
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).mana(), 0, "External mana change is preserved");
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).cooldown(), 0, "No cooldown on failure");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeStillUsesCooldownWithoutPracticeOrRefund(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player().getAbilities().instabuild = true;
        helper.assertTrue(dismiss(f), "Creative owner can dismiss");
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).mana(), 100, "Creative neither spends nor refunds mana");
        helper.assertTrue(f.player().getData(ModAttachments.MANA).cooldown() > 0, "Creative has shared cooldown");
        helper.assertValueEqual(EarthWallSpell.practiceCount(f.player()), 1, "No creative dismissal practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacedWandUnknownSpellAdventureAndDifferentCastAreRejected(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var oldWand = f.player().getMainHandItem();
        f.player().setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        helper.assertFalse(EarthWallDismissal.dismiss(f.player(), f.identity(), oldWand), "A visually identical replacement wand breaks the input lock");
        WallIdentity otherCast = new WallIdentity(f.identity().owner(), UUID.randomUUID(), f.identity().bottomCenter(), f.identity().axis());
        helper.assertFalse(EarthWallDismissal.dismiss(f.player(), otherCast, f.player().getMainHandItem()), "Input lock cannot move to another cast");
        f.player().setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
        helper.assertFalse(dismiss(f), "Forgotten spell cannot dismiss");
        f.player().getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        f.player().gameMode.changeGameModeForPlayer(GameType.ADVENTURE);
        f.player().getAbilities().mayBuild = true;
        helper.assertFalse(dismiss(f), "Adventure cannot bypass dismissal by setting mayBuild");
        intact(helper, f);
        helper.assertValueEqual(f.player().getData(ModAttachments.MANA).mana(), 100, "All refusals are free");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void placementRollbackDoesNotDeleteRecreatedWallOfSameType(GameTestHelper helper) {
        FakePlayer player = player(helper, UUID.randomUUID());
        for (int x = 4; x <= 6; x++) helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
        aim(player, helper.absoluteVec(new Vec3(5.5, 2, 6.5)));
        EarthWallSpell.Plan plan = EarthWallSpell.plan(player);
        BlockPos changed = plan.positions().get(4);
        Consumer<BlockEvent.EntityMultiPlaceEvent> listener = event -> {
            if (event.getEntity() != player) return;
            helper.getLevel().setBlock(changed, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(changed, ModContent.EARTH_WALL.get().defaultBlockState(), 3);
            event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, listener);
        try { helper.assertFalse(EarthWallSpell.cast(player), "Protection rejects tentative cast"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.assertTrue(helper.getLevel().getBlockState(changed).is(ModContent.EARTH_WALL.get()), "Third-party wall of same type survives rollback");
        helper.assertTrue(cell(helper, changed).identity().isEmpty(), "Replacement wall remains unowned");
        for (BlockPos pos : plan.positions()) if (!pos.equals(changed)) helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "Other tentative cells rolled back");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Denied placement does not spend");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void ordinaryExplosionRemovesCellProvenance(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Vec3 center = Vec3.atCenterOf(f.positions().get(1));
        helper.getLevel().explode(null, center.x, center.y, center.z, 1.5F, net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
        int removed = 0;
        for (BlockPos pos : f.positions()) {
            if (!helper.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get())) {
                removed++;
                helper.assertTrue(helper.getLevel().getBlockEntity(pos) == null, "Explosion removes the destroyed cell's BE");
            } else {
                helper.assertTrue(cell(helper, pos).identity().filter(f.identity()::equals).isPresent(), "Surviving cells retain provenance");
            }
        }
        helper.assertTrue(removed > 0, "Explosion physically destroys at least the center cell");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unloadedFootprintIsRejectedWithoutLoadingTheChunk(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int startChunkX = origin.getX() >> 4;
        int z = ((origin.getZ() >> 4) << 4) + 8;
        int edgeChunkX = startChunkX;
        while (edgeChunkX < startChunkX + 256 && level.hasChunkAt(new BlockPos((edgeChunkX + 1) << 4, origin.getY(), z))) edgeChunkX++;
        helper.assertTrue(edgeChunkX < startChunkX + 256, "Fixture finds an unloaded neighboring chunk without requesting it");
        BlockPos center = new BlockPos((edgeChunkX << 4) + 15, level.getMaxBuildHeight() - 16, z);
        BlockPos target = center.above();
        BlockPos unloaded = center.east();
        var prior = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, target);
        FakePlayer player = player(helper, UUID.randomUUID());
        WallIdentity identity = new WallIdentity(player.getUUID(), UUID.randomUUID(), center, Direction.Axis.X);
        try {
            level.setBlock(target, ModContent.EARTH_WALL.get().defaultBlockState(), 2 | 16);
            CompoundTag provenance = new CompoundTag();
            provenance.put("WallIdentity", identity.save());
            cell(helper, target).loadCustomOnly(provenance, level.registryAccess());
            helper.assertFalse(level.hasChunkAt(unloaded), "Fixture does not notify or load the neighboring chunk");
            player.setPos(target.getX() + 0.5, target.getY() - 1.12, target.getZ() - 3.5);
            aim(player, Vec3.atCenterOf(target));
            EarthWallDismissal.Plan plan = EarthWallDismissal.plan(player);
            helper.assertFalse(plan.valid(), "A partly unloaded construction cannot be dismissed");
            helper.assertTrue(identity.equals(plan.identity()), "Ray reaches the loaded owned target before footprint refusal");
            helper.assertFalse(EarthWallDismissal.dismiss(player, identity, player.getMainHandItem()), "Authoritative removal also refuses");
            helper.assertFalse(level.hasChunkAt(unloaded), "No target lookup or transaction loaded the neighboring chunk");
            helper.assertTrue(cell(helper, target).identity().filter(identity::equals).isPresent(), "Loaded target was not removed");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Unloaded refusal is free");
        } finally {
            prior.restore(2 | 16 | 32);
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void protectionCallbackStartingAnotherUseCancelsDismissal(GameTestHelper helper) {
        Fixture f = fixture(helper);
        AtomicInteger callbacks = new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> listener = event -> {
            if (event.getPlayer() == f.player() && callbacks.incrementAndGet() == 1) f.player().startUsingItem(InteractionHand.MAIN_HAND);
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
        try { helper.assertFalse(dismiss(f), "A new ordinary casting use during protection checks cancels dismissal"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.assertTrue(f.player().isUsingItem(), "Callback actually began item use");
        intact(helper, f); free(helper, f);
        SpellCasting.cancel(f.player());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void movingSameWandToAnotherSelectedSlotDuringProtectionCancels(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var original = f.player().getMainHandItem();
        AtomicInteger callbacks = new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> listener = event -> {
            if (event.getPlayer() != f.player() || callbacks.incrementAndGet() != 1) return;
            int previous = f.player().getInventory().selected;
            int next = (previous + 1) % 9;
            f.player().getInventory().setItem(previous, net.minecraft.world.item.ItemStack.EMPTY);
            f.player().getInventory().setItem(next, original);
            f.player().getInventory().selected = next;
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
        try { helper.assertFalse(dismiss(f), "Moving the same object to a new selected slot breaks dismissal's slot lock"); }
        finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.assertTrue(f.player().getMainHandItem() == original, "Wand reference stayed identical, so slot check is necessary");
        intact(helper, f); free(helper, f);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crouchingOrOpeningMenuDuringProtectionCancels(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Consumer<BlockEvent.BreakEvent> crouch = event -> { if (event.getPlayer() == f.player()) f.player().setShiftKeyDown(true); };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, crouch);
        try { helper.assertFalse(dismiss(f), "Crouching during protection callbacks cancels"); }
        finally { NeoForge.EVENT_BUS.unregister(crouch); }
        intact(helper, f); free(helper, f);
        f.player().setShiftKeyDown(false);
        Consumer<BlockEvent.BreakEvent> open = event -> {
            if (event.getPlayer() == f.player()) f.player().containerMenu = net.minecraft.world.inventory.ChestMenu.oneRow(47, f.player().getInventory());
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, open);
        try { helper.assertFalse(dismiss(f), "Opening another menu during protection callbacks cancels"); }
        finally { NeoForge.EVENT_BUS.unregister(open); }
        intact(helper, f); free(helper, f);
        f.player().containerMenu = f.player().inventoryMenu;
        helper.succeed();
    }
}

