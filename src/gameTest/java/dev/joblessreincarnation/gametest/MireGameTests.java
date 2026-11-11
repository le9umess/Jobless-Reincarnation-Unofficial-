package dev.joblessreincarnation.magic;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** M01 server regressions. Native client display and real-client networking are separate acceptance steps. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MireGameTests {
    private static final class Caster extends FakePlayer {
        boolean spectator;
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "Mire" + UUID.randomUUID().toString().substring(0, 8))); }
        @Override public boolean isSpectator() { return spectator; }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }

    private static Caster reader(GameTestHelper helper, boolean water, boolean earth) {
        Caster player = new Caster(helper.getLevel());
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.MIRE_TOME.get().getDefaultInstance());
        if (water) player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
        if (earth) player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.EARTH_WALL.id());
        return player;
    }

    private static void study(Caster player) {
        ModContent.MIRE_TOME.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        int duration = player.getMainHandItem().getUseDuration(player);
        for (int tick = 0; tick < duration; tick++) player.tickUse();
    }

    @GameTest(template = "empty")
    public static void studyRequiresBothBooksWithoutAutomaticallyGrantingMire(GameTestHelper helper) {
        for (int mask = 0; mask < 3; mask++) {
            Caster player = reader(helper, (mask & 1) != 0, (mask & 2) != 0);
            ItemStack book = player.getMainHandItem();
            var result = ModContent.MIRE_TOME.get().use(player.level(), player, InteractionHand.MAIN_HAND);
            helper.assertValueEqual(result.getResult(), InteractionResult.FAIL, "Either missing prerequisite rejects study");
            helper.assertFalse(player.isUsingItem(), "Missing prerequisite cannot start an item-use timer");
            ModContent.MIRE_TOME.get().finishUsingItem(book, player.level(), player);
            helper.assertFalse(SpellCatalog.MIRE.knows(player), "Calling completion cannot bypass the prerequisites");
            helper.assertTrue(player.getMainHandItem() == book && book.getCount() == 1, "Rejected study preserves the reusable textbook");
        }
        Caster ready = reader(helper, true, true);
        helper.assertFalse(SpellCatalog.MIRE.knows(ready), "Learning water and earth does not itself teach mire");
        var before = ready.getData(ModAttachments.KNOWLEDGE).snapshot();
        ModContent.MIRE_TOME.get().use(ready.level(), ready, InteractionHand.MAIN_HAND);
        ready.tickUse();
        ready.stopUsingItem();
        helper.assertValueEqual(ready.getData(ModAttachments.KNOWLEDGE).snapshot(), before, "Interrupted study grants no knowledge or practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completeStudyRechecksLostPrerequisitesAndBookIdentity(GameTestHelper helper) {
        for (int missing = 0; missing < 2; missing++) {
            Caster player = reader(helper, true, true);
            ItemStack book = player.getMainHandItem();
            ModContent.MIRE_TOME.get().use(player.level(), player, InteractionHand.MAIN_HAND);
            int duration = book.getUseDuration(player);
            for (int tick = 0; tick < duration - 1; tick++) player.tickUse();
            MagicKnowledge replacement = new MagicKnowledge();
            replacement.learn(missing == 0 ? SpellCatalog.EARTH_WALL.id() : SpellCatalog.WATER_BOLT.id());
            player.setData(ModAttachments.KNOWLEDGE, replacement);
            player.tickUse();
            helper.assertFalse(SpellCatalog.MIRE.knows(player), "A prerequisite lost on the final study tick prevents learning");
        }
        Caster replaced = reader(helper, true, true);
        ItemStack original = replaced.getMainHandItem();
        ModContent.MIRE_TOME.get().use(replaced.level(), replaced, InteractionHand.MAIN_HAND);
        replaced.setItemInHand(InteractionHand.MAIN_HAND, ModContent.MIRE_TOME.get().getDefaultInstance());
        ModContent.MIRE_TOME.get().finishUsingItem(original, replaced.level(), replaced);
        helper.assertFalse(SpellCatalog.MIRE.knows(replaced), "An equal-looking replacement cannot complete the old stack's study");
        replaced.stopUsingItem();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullStudyIsReusablePreservesOtherKnowledgeAndSurvivesPlayerNbt(GameTestHelper helper) {
        Caster player = reader(helper, true, true);
        player.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.WATER_BOLT.id());
        player.getData(ModAttachments.CASTING).select(SpellCatalog.EARTH_WALL.id());
        ItemStack original = player.getMainHandItem();
        study(player);
        helper.assertTrue(SpellCatalog.MIRE.knows(player), "Actual full item-use timer teaches mire");
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 0, "Reading grants no successful casts");
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.EARTH_WALL, "Learning preserves an existing valid selection");
        helper.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(player), 1, "Other spell progress remains exact");
        helper.assertTrue(player.getMainHandItem() == original && original.getCount() == 1, "The tome is reusable");
        player.getData(ModAttachments.KNOWLEDGE).practice(SpellCatalog.MIRE.id());
        study(player);
        helper.assertFalse(player.isUsingItem(), "Reviewing a learned book cannot begin another study timer");
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 1, "Reviewing never resets mire practice");
        Caster loaded = new Caster(helper.getLevel());
        loaded.load(player.saveWithoutId(new CompoundTag()));
        helper.assertTrue(SpellCatalog.MIRE.knows(loaded) && SpellCatalog.EARTH_WALL.knows(loaded)
                && SpellCatalog.WATER_BOLT.knows(loaded), "Player NBT retains learned mixed magic and its prerequisites");
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(loaded), 1, "Existing knowledge codec retains mire practice");
        helper.assertValueEqual(SpellCatalog.selected(loaded), SpellCatalog.EARTH_WALL, "Selection survives alongside knowledge");
        helper.succeed();
    }

    private static Caster caster(GameTestHelper helper) {
        Caster player = reader(helper, true, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.MIRE.id());
        player.getData(ModAttachments.CASTING).select(SpellCatalog.MIRE.id());
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.setPos(helper.absoluteVec(new Vec3(8.5, 2, 3.5)));
        aim(player, helper.absoluteVec(new Vec3(8.5, 2, 8.5)));
        return player;
    }

    private static void aim(ServerPlayer player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }

    private static void hold(Caster player, int ticks) {
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        for (int tick = 0; tick < ticks; tick++) player.tickUse();
    }

    @GameTest(template = "empty")
    public static void seventhSpellSelectionCancelsWithoutReleasingOrTransferringItsChant(GameTestHelper helper) {
        Caster player = caster(helper);
        hold(player, SpellCatalog.MIRE.chantTicks(player) - 1);
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 0, "Mire cannot release one tick before its chant completes");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Early release is free");
        hold(player, SpellCatalog.MIRE.chantTicks(player));
        helper.assertTrue(SpellCasting.cycle(player), "The seventh spell wraps to an actually learned spell");
        helper.assertValueEqual(SpellCatalog.selected(player), SpellCatalog.WATER_BOLT, "Cycle wraps from mire to water");
        helper.assertFalse(player.isUsingItem(), "Selection stops the old item-use timer");
        helper.assertTrue(SpellCatalog.active(player) == null, "Selection clears the old casting lock");
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 0, "Switch followed by release does not cast mire");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Cancelled ready cast is free");
        hold(player, 1);
        helper.assertValueEqual(player.getTicksUsingItem(), 1, "The new spell begins with its own first tick");
        player.releaseUsingItem();
        helper.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(player), 0, "Mire's completed chant cannot transfer into a water cast");
        helper.assertEntityNotPresent(ModContent.MIRE.get());
        helper.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        helper.succeed();
    }


    private static List<MireEntity> fields(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(MireEntity.class, helper.getBounds().inflate(2));
    }

    private static MireEntity onlyField(GameTestHelper helper, ServerPlayer player) {
        var fields = fields(helper).stream().filter(field -> player.getUUID().equals(field.ownerId())).toList();
        helper.assertValueEqual(fields.size(), 1, "Exactly one current field for this owner");
        return fields.getFirst();
    }

    private static void ready(Caster player) {
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
    }

    private static void free(GameTestHelper helper, Caster player, int practices, String reason) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, reason + ": no mana charged");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, reason + ": no mana cooldown");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), reason + ": no wand cooldown");
        helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), practices, reason + ": practice unchanged");
    }

    private static void resolve(GameTestHelper helper) {
        MireFields.onLevelTick(new LevelTickEvent.Post(() -> true, helper.getLevel()));
    }

    /** Change only this fixture's synced start time; never mutate the shared server clock. */
    @SuppressWarnings("unchecked")
    private static void age(MireEntity field, int ticks) {
        try {
            Field start = MireEntity.class.getDeclaredField("START_GAME_TIME");
            start.setAccessible(true);
            field.getEntityData().set((EntityDataAccessor<Long>) start.get(null), field.level().getGameTime() - ticks);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }

    private static <T extends Mob> T target(GameTestHelper helper, EntityType<T> type, Vec3 absolute) {
        T mob = type.create(helper.getLevel());
        if (mob == null) throw new IllegalStateException("Fixture mob unavailable: " + type);
        mob.setPos(absolute);
        mob.setNoAi(true);
        mob.setOnGround(true);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setPersistenceRequired();
        helper.assertTrue(helper.getLevel().addFreshEntity(mob), "Fixture target joins the real server level");
        return mob;
    }

    private static boolean slowed(LivingEntity target) {
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.getModifier(MireFields.SLOW_ID) != null;
    }

    private static void close(GameTestHelper helper, Caster player) {
        MireFields.onEntityLeave(new EntityLeaveLevelEvent(player, helper.getLevel()));
        player.discard();
        resolve(helper);
    }

    @GameTest(template = "empty")
    public static void completedReleasePaysOnceCreatesPassableUnsavedFieldAndNeverChangesTerrain(GameTestHelper helper) {
        Caster player = caster(helper);
        Vec3 base = MireGround.findBase(helper.getLevel(), player);
        helper.assertTrue(base != null, "Visible dry floor is a valid target");
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> before = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 1; y++) {
            BlockPos at = BlockPos.containing(base).offset(x, y, z);
            positions.add(at); before.add(helper.getLevel().getBlockState(at));
        }
        try {
            hold(player, SpellCatalog.MIRE.chantTicks(player));
            player.releaseUsingItem();
            MireEntity field = onlyField(helper, player);
            helper.assertTrue(field.position().distanceTo(base) < 1.0E-7, "The field is placed on the aimed block's top");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.MIRE_COST.getAsInt(), "One successful release pays exactly once");
            helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 1, "Successful survival cast grants exactly one practice");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.MIRE_COOLDOWN.getAsInt(), "Mire shares the normal spell cooldown");
            helper.assertFalse(MireSpell.cast(player), "A same-tick second cast is blocked by cooldown");
            helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 1, "Rejected duplicate grants no practice");
            helper.assertFalse(field.shouldBeSaved() || field.isPickable() || field.canBeCollidedWith(), "The visual field is unsaved and passable");
            for (int i = 0; i < positions.size(); i++)
                helper.assertValueEqual(helper.getLevel().getBlockState(positions.get(i)), before.get(i), "Cast preserves every nearby ground and air block");
        } finally { close(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyIncludedGroundCellMustBeDrySupportedAndClearButCornersAreAbsent(GameTestHelper helper) {
        Caster player = caster(helper);
        Vec3 base = MireGround.findBase(helper.getLevel(), player);
        helper.assertTrue(base != null, "Original floor is valid");
        BlockPos center = BlockPos.containing(base);
        // Iterate the independently specified mask rather than merely reusing the implementation's cell list.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos support = center.offset(x, -1, z);
            BlockState original = helper.getLevel().getBlockState(support);
            try {
                helper.getLevel().setBlock(support, Blocks.AIR.defaultBlockState(), 2);
                boolean corner = Math.abs(x) == 2 && Math.abs(z) == 2;
                helper.assertValueEqual(MireGround.isValid(helper.getLevel(), base), corner,
                        "Only the four visually absent corner cells may lack ground: " + x + "," + z);
            } finally { helper.getLevel().setBlock(support, original, 2); }
        }
        BlockPos edge = center.offset(2, 0, 1);
        for (int layer = -1; layer <= 1; layer++) {
            BlockPos at = edge.above(layer);
            BlockState original = helper.getLevel().getBlockState(at);
            try {
                helper.getLevel().setBlock(at, Blocks.WATER.defaultBlockState(), 2);
                helper.assertFalse(MireGround.isValid(helper.getLevel(), base), "Support, foot and head layers must all be dry");
            } finally { helper.getLevel().setBlock(at, original, 2); }
        }
        // One-layer snow has EMPTY collision; the agreed snow exclusion is explicit, unlike collision-free grass.
        for (BlockState obstruction : List.of(Blocks.STONE.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.WHITE_CARPET.defaultBlockState(), Blocks.SNOW.defaultBlockState(),
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2), Blocks.POWDER_SNOW.defaultBlockState())) {
            helper.getLevel().setBlock(edge, obstruction, 2);
            helper.assertValueEqual(helper.getLevel().getBlockState(edge), obstruction, "The intended clearance obstruction is actually present");
            helper.assertFalse(MireGround.isValid(helper.getLevel(), base), "Collision or explicitly excluded snow rejects the entire field: " + obstruction);
            helper.getLevel().setBlock(edge, Blocks.AIR.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(edge.above(), Blocks.STONE.defaultBlockState(), 2);
        helper.assertFalse(MireGround.isValid(helper.getLevel(), base), "The second clearance block matters too");
        helper.getLevel().setBlock(edge.above(), Blocks.AIR.defaultBlockState(), 2);
        BlockPos support = edge.below();
        BlockState originalSupport = helper.getLevel().getBlockState(support);
        helper.getLevel().setBlock(support, Blocks.OAK_SLAB.defaultBlockState(), 2);
        helper.assertFalse(MireGround.isValid(helper.getLevel(), base), "A bottom slab has no full face at the shared integer height");
        helper.getLevel().setBlock(support, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), 2);
        helper.assertTrue(MireGround.isValid(helper.getLevel(), base), "A top slab has a complete dry supporting top at the same integer height");
        for (BlockState loweredCollision : List.of(Blocks.MUD.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState())) {
            helper.getLevel().setBlock(support, loweredCollision, 2);
            helper.assertFalse(MireGround.isValid(helper.getLevel(), base),
                    "A sturdy support declaration cannot replace a full collision top at the actual standing height");
            helper.assertTrue(MireGround.findBase(helper.getLevel(), player) == null,
                    "A lower collision top anywhere in the footprint makes the aimed field unselectable");
            helper.assertFalse(MireSpell.cast(player), "Mud and soul sand reject the complete field before settlement");
            free(helper, player, 0, "Lowered collision top");
            helper.assertValueEqual(fields(helper).size(), 0, "No field is admitted over mismatched collision height");
        }
        helper.getLevel().setBlock(support, originalSupport, 2);
        helper.getLevel().setBlock(edge, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        helper.assertTrue(MireGround.isValid(helper.getLevel(), base), "Collision-free dry grass does not reject usable terrain");
        helper.getLevel().setBlock(edge, Blocks.AIR.defaultBlockState(), 2);
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidVisibleGroundRangeAndWallsFailBeforePayment(GameTestHelper helper) {
        Caster player = caster(helper);
        Vec3 base = MireGround.findBase(helper.getLevel(), player);
        helper.assertTrue(base != null, "Control aim is valid");
        BlockPos edge = BlockPos.containing(base).offset(2, -1, 1);
        BlockState original = helper.getLevel().getBlockState(edge);
        helper.getLevel().setBlock(edge, Blocks.AIR.defaultBlockState(), 2);
        helper.assertFalse(MireSpell.cast(player), "An edge hole rejects the cast, not just a center check");
        free(helper, player, 0, "Hole");
        helper.getLevel().setBlock(edge, original, 2);
        player.setXRot(0);
        helper.assertFalse(MireSpell.cast(player), "A horizontal miss is not a valid ground hit");
        free(helper, player, 0, "Miss");
        aim(player, helper.absoluteVec(new Vec3(8.5, 2, 18.5)));
        helper.assertFalse(MireSpell.cast(player), "A floor hit beyond the eye-to-hit range is rejected");
        free(helper, player, 0, "Range");
        aim(player, base);
        for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(8, y, 5), Blocks.STONE);
        helper.assertFalse(MireSpell.cast(player), "The ray cannot cast through a wall to hidden floor");
        free(helper, player, 0, "Wall");
        helper.assertValueEqual(fields(helper).size(), 0, "No invalid aim admitted a field");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownInvalidCasterAndInsufficientManaCannotReleaseWhileCreativePaysNoPractice(GameTestHelper helper) {
        for (int condition = 0; condition < 5; condition++) {
            Caster player = caster(helper);
            switch (condition) {
                case 0 -> player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
                case 1 -> player.spectator = true;
                case 2 -> player.setHealth(0);
                case 3 -> player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
                case 4 -> player.setData(ModAttachments.MANA, new ManaData(1, 100, 0, 0));
            }
            helper.assertFalse(MireSpell.cast(player), "Invalid caster/payment context cannot release");
            helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 0, "Rejected context cannot train");
            helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), condition == 4 ? 1 : 100, "Rejected context preserves mana");
            player.discard();
        }
        Caster creative = caster(helper);
        creative.getAbilities().instabuild = true;
        try {
            helper.assertTrue(MireSpell.cast(creative), "Creative may use learned mire");
            helper.assertValueEqual(creative.getData(ModAttachments.MANA).mana(), 100, "Creative pays no mana");
            helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(creative), 0, "Creative grants no survival practice");
        } finally { close(helper, creative); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyPaidAdmissionReplacesOwnFieldAndReplacementDoesNotUseNearbyScan(GameTestHelper helper) {
        Caster first = caster(helper), second = caster(helper);
        try {
            helper.assertTrue(MireSpell.cast(first) && MireSpell.cast(second), "Both owners can place their own field");
            MireEntity old = onlyField(helper, first), peer = onlyField(helper, second);
            ready(first);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof MireEntity field && field.owner() == first) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, veto);
            try { helper.assertFalse(MireSpell.cast(first), "Admission veto is a failed release"); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            free(helper, first, 1, "Admission veto");
            helper.assertFalse(old.isRemoved() || peer.isRemoved(), "Failed release preserves both existing fields");
            // Keep the old paid entity in its loaded section: moving that entity can trigger stopTracking/leave
            // before recasting. Instead move only this unregistered fixture player to already loaded valid terrain.
            Vec3 destination = null;
            search:
            for (int x = -160; x <= 160; x += 16) for (int z = -160; z <= 160; z += 16) {
                if (x * x + z * z <= 80 * 80) continue;
                for (int y = -4; y <= 4; y++) {
                    Vec3 candidate = old.position().add(x, y, z);
                    if (MireGround.isValid(helper.getLevel(), candidate)) { destination = candidate; break search; }
                }
            }
            helper.assertTrue(destination != null, "Fixture needs already loaded valid terrain over 80 blocks away; no terrain is created or loaded by this test");
            first.setPos(destination);
            aim(first, destination);
            helper.assertTrue(first.position().distanceTo(old.position()) > 80, "Player genuinely teleported beyond a 64-block scan");
            helper.assertTrue(MireFields.isRegistered(old) && helper.getLevel().getEntity(old.getUUID()) == old
                    && !old.isRemoved() && MireGround.isValid(helper.getLevel(), old.position()),
                    "Old field is still registered, loaded and supported immediately before the replacement cast");
            helper.assertTrue(MireSpell.cast(first), "A later paid cast replaces the owner's indexed field");
            helper.assertTrue(old.isRemoved(), "Far previous field is ended without a local-distance query");
            helper.assertFalse(peer.isRemoved(), "Another owner's field is unaffected");
            List<MireEntity> surviving = new ArrayList<>();
            for (Entity entity : helper.getLevel().getAllEntities())
                if (entity instanceof MireEntity field && first.getUUID().equals(field.ownerId()) && !field.isRemoved()) surviving.add(field);
            helper.assertValueEqual(surviving.size(), 1, "Only one field survives for the teleported owner across the whole loaded level");
            helper.assertTrue(surviving.getFirst() != old && surviving.getFirst().position().distanceTo(destination) < 1.0E-7,
                    "The surviving field occupies the newly aimed distant ground");
            helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(first), 2, "Only the two successful casts count");
        } finally { close(helper, first); close(helper, second); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void admissionReentryAndPostAdmissionPaymentOrTerrainChangesCannotSettleTwice(GameTestHelper helper) {
        Caster player = caster(helper);
        AtomicInteger attempts = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> reenter = event -> {
            if (event.getEntity() instanceof MireEntity field && field.owner() == player) {
                attempts.incrementAndGet();
                helper.assertFalse(MireSpell.cast(player), "Admission callbacks cannot recursively release the same player's spell");
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, reenter);
        try { helper.assertTrue(MireSpell.cast(player), "Outer release succeeds once"); }
        finally { NeoForge.EVENT_BUS.unregister(reenter); }
        helper.assertValueEqual(attempts.get(), 1, "Only one entity was admitted");
        MireEntity old = onlyField(helper, player);
        try {
            for (int condition = 0; condition < 6; condition++) {
                ready(player);
                int selected = condition;
                BlockPos edge = old.blockPosition().offset(2, -1, 1);
                BlockState support = helper.getLevel().getBlockState(edge);
                Consumer<EntityJoinLevelEvent> changed = event -> {
                    if (event.getEntity() instanceof MireEntity field && field.owner() == player) {
                        switch (selected) {
                            case 0 -> player.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
                            case 1 -> player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
                            case 2 -> helper.getLevel().setBlock(edge, Blocks.AIR.defaultBlockState(), 2);
                            case 3 -> field.discard();
                            case 4 -> field.setPos(field.position().add(0, 1, 0));
                            case 5 -> {
                                Vec3 relocated = field.position().add(1, 0, 0);
                                helper.assertTrue(MireGround.isValid(helper.getLevel(), relocated),
                                        "The alternate destination is itself valid, so rejection must enforce the original paid aim");
                                field.setPos(relocated);
                            }
                        }
                    }
                };
                NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, changed);
                try { helper.assertFalse(MireSpell.cast(player), "Post-admission state is revalidated before payment: " + condition); }
                finally { NeoForge.EVENT_BUS.unregister(changed); helper.getLevel().setBlock(edge, support, 2); }
                helper.assertFalse(old.isRemoved(), "Rejected candidate does not replace the paid predecessor");
                helper.assertTrue(onlyField(helper, player) == old, "Rejected moved or mutated admission leaves only the original field");
                helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(player), 1, "Rejected candidate never trains");
                helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), condition == 0 ? 0 : 100, "No extra mana charged after callback change");
                helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "Rejected admission creates no cooldown");
            }
        } finally { close(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void realTicksHaveFormationActiveAndFadeWindowsThenRemoveTheField(GameTestHelper helper) {
        Caster player = caster(helper);
        helper.assertTrue(MireSpell.cast(player), "Field forms");
        MireEntity field = onlyField(helper, player);
        Husk target = target(helper, EntityType.HUSK, field.position());
        helper.runAfterDelay(8, () -> helper.assertFalse(slowed(target), "Formation does not slow yet"));
        helper.runAfterDelay(13, () -> helper.assertTrue(slowed(target), "Actual world ticks activate the field"));
        helper.runAfterDelay(107, () -> helper.assertTrue(slowed(target), "The final active portion still slows"));
        helper.runAfterDelay(113, () -> {
            helper.assertFalse(slowed(target), "Fade restores movement before the drawing disappears");
            helper.assertFalse(field.isRemoved(), "The last visual fade exists without gameplay slowing");
        });
        helper.runAfterDelay(123, () -> {
            try {
                helper.assertTrue(field.isRemoved(), "Field disappears after its total lifetime");
                helper.assertFalse(slowed(target), "Lifetime leaves no attribute residue");
            } finally { target.discard(); close(helper, player); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void exactPhaseBoundariesAndChangedGroundEndContactImmediately(GameTestHelper helper) {
        Caster player = caster(helper);
        helper.assertTrue(MireSpell.cast(player), "Field forms");
        MireEntity field = onlyField(helper, player);
        Husk target = target(helper, EntityType.HUSK, field.position());
        try {
            for (int tick : new int[]{9, 10, 109, 110}) {
                age(field, tick); resolve(helper);
                helper.assertValueEqual(slowed(target), tick >= 10 && tick < 110, "Exact active window at age " + tick);
            }
            age(field, 10); resolve(helper);
            helper.assertTrue(slowed(target), "Contact has resumed in this isolated phase fixture");
            helper.getLevel().setBlock(field.blockPosition().offset(2, -1, 1), Blocks.AIR.defaultBlockState(), 2);
            resolve(helper);
            helper.assertTrue(field.isRemoved(), "Losing any effective support ends the whole field");
            helper.assertFalse(slowed(target), "Unsupported terrain removes the field's modifier in that same level pass");
        } finally { target.discard(); close(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void targetsUseTheSpecifiedWhitelistAndExcludeNpcPetPassengerAndAirborneContexts(GameTestHelper helper) {
        Caster player = caster(helper);
        helper.assertTrue(MireSpell.cast(player), "Field forms");
        MireEntity field = onlyField(helper, player);
        age(field, 10);
        List<Mob> mobs = new ArrayList<>();
        try {
            for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER)) {
                Mob mob = target(helper, type, field.position()); mobs.add(mob);
                helper.assertTrue(MireTargets.eligible(player, mob), "Default supported ground enemy: " + type);
            }
            resolve(helper);
            for (Mob mob : mobs) helper.assertTrue(slowed(mob), "Every supported default type is slowed on the effective floor");
            for (Mob mob : mobs) mob.discard();
            mobs.clear(); resolve(helper);
            for (EntityType<? extends Mob> type : List.of(EntityType.SPIDER, EntityType.WOLF, EntityType.COW,
                    EntityType.BLAZE, EntityType.SQUID, ModContent.PAUL_GREYRAT.get(), ModContent.ROXY_TUTOR.get())) {
                Mob mob = target(helper, type, field.position()); mobs.add(mob);
                helper.assertFalse(MireTargets.eligible(player, mob), "Unsupported or protected entity never becomes an eligible target: " + type);
            }
            helper.assertFalse(MireTargets.eligible(player, player), "Players are hard-excluded");
            resolve(helper);
            for (Mob mob : mobs) helper.assertFalse(slowed(mob), "Protected or unsupported mobs receive no modifier");
            Husk hostile = target(helper, EntityType.HUSK, field.position()); mobs.add(hostile);
            hostile.setNoGravity(true);
            resolve(helper); helper.assertFalse(slowed(hostile), "Artificially flying/no-gravity enemies are excluded");
            hostile.setNoGravity(false); hostile.setOnGround(false);
            resolve(helper); helper.assertFalse(slowed(hostile), "A jumping enemy is not glued down");
            hostile.setOnGround(true);
            hostile.startRiding(mobs.get(1), true);
            resolve(helper); helper.assertFalse(slowed(hostile), "A passenger is not grounded by its vehicle's feet");
            hostile.stopRiding();
        } finally { for (Mob mob : mobs) mob.discard(); close(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void maskCornersFeetHeightAndLeavingClearOnlyMiresOwnSlow(GameTestHelper helper) {
        Caster player = caster(helper);
        helper.assertTrue(MireSpell.cast(player), "Field forms");
        MireEntity field = onlyField(helper, player); age(field, 10);
        Husk target = target(helper, EntityType.HUSK, field.position());
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        ResourceLocation otherId = ResourceLocation.fromNamespaceAndPath("mire_test", "independent_slow");
        speed.addPermanentModifier(new AttributeModifier(otherId, -0.20, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 1));
        double independent = speed.getValue();
        try {
            resolve(helper);
            helper.assertTrue(slowed(target), "Contact adds mire to independently slowed target");
            helper.assertTrue(Math.abs(speed.getValue() - independent * 0.4) < 1.0E-8, "Mire applies once alongside existing modifiers");
            for (Vec3 outside : List.of(field.position().add(2, 0, 2), field.position().add(-2, 0, -2),
                    field.position().add(2.5, 0, 0), field.position().add(0, 0.0625, 0), field.position().add(0, 1, 0))) {
                target.setPos(outside); target.setOnGround(true); resolve(helper);
                helper.assertFalse(slowed(target), "Absent corners, outside edges, carpets and upper floors do not count as feet contact");
                helper.assertTrue(speed.getModifier(otherId) != null && target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "Leaving removes neither another attribute modifier nor a vanilla slow effect");
                helper.assertTrue(Math.abs(speed.getValue() - independent) < 1.0E-8, "Only mire's contribution is removed");
                target.setPos(field.position()); target.setOnGround(true); resolve(helper);
                helper.assertTrue(slowed(target), "Walking back onto valid ground can slow again");
            }
            target.setOnGround(false); resolve(helper);
            helper.assertFalse(slowed(target), "Lifting feet removes mire without suppressing a jump");
            helper.assertTrue(speed.getModifier(otherId) != null && target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "Jumping still retains other effects");
        } finally { target.discard(); close(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingFieldsDoNotStackAndFriendshipIsEvaluatedPerSource(GameTestHelper helper) {
        Caster first = caster(helper), second = caster(helper);
        helper.assertTrue(MireSpell.cast(first) && MireSpell.cast(second), "Two overlapping fields exist");
        MireEntity a = onlyField(helper, first), b = onlyField(helper, second); age(a, 10); age(b, 10);
        Husk target = target(helper, EntityType.HUSK, a.position());
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        double baseline = speed.getValue();
        var board = helper.getLevel().getScoreboard();
        var team = board.addPlayerTeam("mire" + UUID.randomUUID().toString().substring(0, 8));
        try {
            resolve(helper);
            helper.assertTrue(Math.abs(speed.getValue() - baseline * 0.4) < 1.0E-8, "Two sources never multiply the slow twice");
            board.addPlayerToTeam(first.getScoreboardName(), team);
            board.addPlayerToTeam(target.getScoreboardName(), team);
            resolve(helper);
            helper.assertTrue(slowed(target), "Becoming the first owner's ally does not cancel another hostile source");
            b.discard(); resolve(helper);
            helper.assertFalse(slowed(target), "With only an allied source remaining, the target is restored");
            board.removePlayerFromTeam(target.getScoreboardName(), team);
            resolve(helper);
            helper.assertTrue(slowed(target), "The remaining source can act again when the target is no longer allied");
            a.discard(); resolve(helper);
            helper.assertFalse(slowed(target), "Removing the last source restores the target");
        } finally { board.removePlayerTeam(team); target.discard(); close(helper, first); close(helper, second); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ownerDeathLeaveAndTargetRemovalCleanTransientModifiersWithoutNbtResidue(GameTestHelper helper) {
        Caster player = caster(helper);
        helper.assertTrue(MireSpell.cast(player), "Field forms");
        MireEntity field = onlyField(helper, player); age(field, 10);
        Husk target = target(helper, EntityType.HUSK, field.position());
        try {
            resolve(helper); helper.assertTrue(slowed(target), "Target starts under mire");
            CompoundTag saved = target.saveWithoutId(new CompoundTag());
            helper.assertFalse(saved.toString().contains(MireFields.SLOW_ID.toString()), "Transient mire modifier is absent from actual mob NBT");
            Husk loaded = EntityType.HUSK.create(helper.getLevel());
            loaded.load(saved);
            helper.assertFalse(slowed(loaded), "Freshly loaded mob cannot inherit a stale slow");
            loaded.discard();
            MireEntity reconstructed = ModContent.MIRE.get().create(helper.getLevel());
            reconstructed.load(field.saveWithoutId(new CompoundTag()));
            helper.assertTrue(reconstructed.isRemoved(), "Even forced reconstruction discards an unsaved field");
            player.setHealth(0); resolve(helper);
            helper.assertTrue(field.isRemoved(), "Owner death ends the field");
            helper.assertFalse(slowed(target), "Owner death removes its last slow source");
            player.setHealth(player.getMaxHealth()); ready(player);
            helper.assertTrue(MireSpell.cast(player), "Second field for leave cleanup");
            field = onlyField(helper, player); age(field, 10); resolve(helper);
            helper.assertTrue(slowed(target), "Second field applies before leave");
            MireFields.onEntityLeave(new EntityLeaveLevelEvent(target, helper.getLevel()));
            helper.assertFalse(slowed(target), "Target level exit strips transient state before a dimension transfer/save");
            resolve(helper); helper.assertTrue(slowed(target), "Fixture target still in this level resumes after simulated leave");
            MireFields.onEntityLeave(new EntityLeaveLevelEvent(player, helper.getLevel())); resolve(helper);
            helper.assertTrue(field.isRemoved() && !slowed(target), "Owner leave ends field and last-source modifier");
        } finally { target.discard(); close(helper, player); }
        helper.succeed();
    }


    /** Real vanilla advancement storage, with fixture-only UUIDs and no global player-list registration. */
    private static final class Actor extends ServerPlayer implements AutoCloseable {
        private boolean closed;
        Actor(GameTestHelper helper) { this(helper, new GameProfile(UUID.randomUUID(), "MireAdv")); }
        Actor(GameTestHelper helper, GameProfile profile) {
            super(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}
            };
            setPos(helper.absoluteVec(new Vec3(8.5, 2, 3.5)));
            aim(this, helper.absoluteVec(new Vec3(8.5, 2, 8.5)));
            getAbilities().instabuild = false;
            setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
            getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.MIRE.id());
            getData(ModAttachments.CASTING).select(SpellCatalog.MIRE.id());
            setData(ModAttachments.MANA, ManaData.full(100));
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            getAdvancements().stopListening();
            MireFields.onEntityLeave(new EntityLeaveLevelEvent(this, serverLevel()));
            discard();
            removeOwnMapping(server.getPlayerList(), "advancements", getUUID(), getAdvancements());
            removeOwnMapping(server.getPlayerList(), "stats", getUUID(), getStats());
        }
    }

    private static void removeOwnMapping(PlayerList players, String name, UUID uuid, Object expected) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            Object raw = field.get(players);
            if (!(raw instanceof Map<?, ?> records)) throw new IllegalStateException("Unexpected PlayerList cache: " + name);
            if (records.get(uuid) == expected) records.remove(uuid);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }

    private static boolean mireComplete(Actor player) {
        var definition = player.getServer().getAdvancements().get(StoryAdvancements.MIRE_SLOW);
        if (definition == null) throw new IllegalStateException("Mire advancement is missing from the loaded data pack");
        return player.getAdvancements().getOrStartProgress(definition).isDone();
    }

    @GameTest(template = "empty", batch = "mireAdvancement")
    public static void onlyActualContributingOwnersEarnTheNativeMilestoneAndOverlapAwardsBoth(GameTestHelper helper) {
        try (Actor first = new Actor(helper); Actor second = new Actor(helper); Actor observer = new Actor(helper)) {
            helper.assertFalse(mireComplete(first) || mireComplete(second) || mireComplete(observer), "Knowledge alone grants no mire-effect milestone");
            helper.assertTrue(MireSpell.cast(first) && MireSpell.cast(second), "Two independently paid fields overlap");
            MireEntity a = onlyField(helper, first), b = onlyField(helper, second); age(a, 10); age(b, 10);
            resolve(helper);
            helper.assertFalse(mireComplete(first) || mireComplete(second), "Empty active fields earn no milestone");
            Husk target = target(helper, EntityType.HUSK, a.position().add(2, 0, 2));
            try {
                resolve(helper);
                helper.assertFalse(mireComplete(first) || mireComplete(second), "A target in the visually absent corner grants no progress");
                target.setPos(a.position()); target.setOnGround(true); resolve(helper);
                helper.assertTrue(slowed(target), "The advancement follows a real attribute application");
                helper.assertTrue(mireComplete(first) && mireComplete(second), "Every effective owner contributes even when only one modifier is needed");
                helper.assertFalse(mireComplete(observer), "A nearby observer receives no completion");
                helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(first), 1, "Actual control does not charge or practice a second time");
                helper.assertValueEqual(SpellCatalog.MIRE.practiceCount(second), 1, "The second owner also has exactly one paid cast");
                resolve(helper);
                helper.assertTrue(mireComplete(first) && mireComplete(second), "Repeated resolution retains the single native criterion");
            } finally { target.discard(); }
        } finally { resolve(helper); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "mireAdvancement")
    public static void realMireMilestonePersistsByUuidWhileFakePlayersAndOtherPlayersRemainIncomplete(GameTestHelper helper) throws IOException {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Advancement disk fixture is restricted to the disposable test server");
        GameProfile profile = new GameProfile(UUID.randomUUID(), "MireSave");
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).toAbsolutePath().normalize();
        Path file = directory.resolve(profile.getId() + ".json").normalize();
        helper.assertTrue(file.getParent().equals(directory) && !Files.exists(file), "Only a fresh fixture UUID file is owned by this test");
        try {
            try (Actor owner = new Actor(helper, profile)) {
                helper.assertTrue(MireSpell.cast(owner), "Native owner casts");
                MireEntity field = onlyField(helper, owner); age(field, 10);
                Husk target = target(helper, EntityType.HUSK, field.position());
                try {
                    resolve(helper);
                    helper.assertTrue(mireComplete(owner), "A real control event earned the completion");
                    owner.getAdvancements().save();
                    helper.assertTrue(Files.isRegularFile(file) && Files.size(file) > 0, "Vanilla wrote the actual advancement store");
                } finally { target.discard(); }
            }
            try (Actor restored = new Actor(helper, profile); Actor other = new Actor(helper)) {
                helper.assertTrue(mireComplete(restored), "The same UUID reads the persisted completed criterion");
                helper.assertFalse(mireComplete(other), "A different UUID does not inherit completion");
            }
            Caster machine = caster(helper);
            helper.assertTrue(MireSpell.cast(machine), "Fake-player fixture casts a real area");
            MireEntity field = onlyField(helper, machine); age(field, 10);
            Husk target = target(helper, EntityType.HUSK, field.position());
            try {
                resolve(helper);
                helper.assertTrue(slowed(target), "The machine can apply the supported gameplay effect");
                var definition = helper.getLevel().getServer().getAdvancements().get(StoryAdvancements.MIRE_SLOW);
                helper.assertFalse(machine.getAdvancements().getOrStartProgress(definition).isDone(), "Fake players do not receive human story milestones");
            } finally { target.discard(); close(helper, machine); }
        } finally { Files.deleteIfExists(file); resolve(helper); }
        helper.succeed();
    }


    @SuppressWarnings("unchecked")
    private static <T> T privateValue(Object owner, Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(owner);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }

    /** Real section iteration, isolated from every other GameTest's entities and restored synchronously. */
    @GameTest(template = "empty", batch = "mireTrackingBoundary")
    public static void nativeBatchTrackingEndDefersRemovalAndNeverRevivesEndedFields(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PersistentEntitySectionManager<Entity> manager = privateValue(level, ServerLevel.class, "entityManager");
        Long2ObjectMap<Visibility> visibility = privateValue(manager, PersistentEntitySectionManager.class, "chunkVisibility");
        EntitySectionStorage<Entity> sections = privateValue(manager, PersistentEntitySectionManager.class, "sectionStorage");
        Caster first = caster(helper), second = caster(helper);
        List<MireEntity> owned = new ArrayList<>();
        ArmorStand ordinary = EntityType.ARMOR_STAND.create(level);
        if (ordinary == null) throw new IllegalStateException("Vanilla armor stand is unavailable");
        ChunkPos chunk = null;
        Visibility original = null;
        boolean changed = false;
        AtomicInteger mireLeaves = new AtomicInteger(), ordinaryLeaves = new AtomicInteger();
        Consumer<EntityLeaveLevelEvent> observed = event -> {
            if (event.getLevel() != level) return;
            if (owned.contains(event.getEntity())) mireLeaves.incrementAndGet();
            if (event.getEntity() == ordinary) ordinaryLeaves.incrementAndGet();
        };
        try {
            helper.assertTrue(MireSpell.cast(first) && MireSpell.cast(second), "Both same-section fields are genuinely admitted and paid");
            MireEntity a = onlyField(helper, first), b = onlyField(helper, second);
            owned.add(a); owned.add(b);
            ordinary.setPos(a.position().add(0.25, 0, 0.25));
            ordinary.setNoGravity(true);
            helper.assertTrue(level.addFreshEntity(ordinary), "Ordinary vanilla entity joins the same section");
            helper.assertValueEqual(SectionPos.asLong(a.blockPosition()), SectionPos.asLong(b.blockPosition()), "Both fields share one native section collection");
            helper.assertValueEqual(SectionPos.asLong(a.blockPosition()), SectionPos.asLong(ordinary.blockPosition()), "The ordinary entity is in that exact collection");
            chunk = new ChunkPos(a.blockPosition());
            helper.assertTrue(level.hasChunkAt(a.blockPosition()) && manager.areEntitiesLoaded(chunk.toLong()), "The test uses an already loaded entity chunk");
            helper.assertTrue(visibility.containsKey(chunk.toLong()), "Visibility has an explicit original value to restore");
            original = visibility.get(chunk.toLong());
            helper.assertValueEqual(original, Visibility.TICKING, "A normally ticking chunk is the starting point");
            var originalSections = sections.getExistingSectionsInChunk(chunk.toLong()).toList();
            for (var section : originalSections) {
                helper.assertValueEqual(section.getStatus(), original, "Every existing section agrees with the saved chunk visibility");
                for (Entity entity : section.getEntities().toList())
                    helper.assertTrue(entity == a || entity == b || entity == ordinary,
                            "Exclusive fixture chunk required before touching visibility; unrelated entity: " + entity.getType());
            }
            helper.assertTrue(MireFields.isRegistered(a) && MireFields.isRegistered(b), "Both sources are active in the real owner index before stopping tracking");
            NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, observed);
            changed = true;
            // This native API iterates live section streams. Immediate discard in the leave callback used to
            // mutate that collection, unlike manually posting an isolated leave event.
            manager.updateChunkStatus(chunk, Visibility.HIDDEN);
            helper.assertValueEqual(mireLeaves.get(), 2, "The native section walk emitted both field leave events without concurrent modification");
            helper.assertValueEqual(ordinaryLeaves.get(), 1, "Iteration reached the ordinary entity as well");
            helper.assertTrue(a.ended() && b.ended() && !MireFields.isRegistered(a) && !MireFields.isRegistered(b),
                    "Both areas are logically ended and unregistered before deferred physical removal");
            helper.assertFalse(a.isRemoved() || b.isRemoved(), "Field removal is deferred until the native section walk has finished");
            helper.assertFalse(ordinary.isRemoved(), "Stopping tracking does not destroy the ordinary entity");
            a.discard();
            helper.assertTrue(a.isRemoved(), "One queued field may be removed independently before the drain");
            resolve(helper);
            helper.assertTrue(a.isRemoved() && b.isRemoved(), "The level-post drain safely handles the already removed field and removes the other");
            helper.assertFalse(ordinary.isRemoved(), "Draining mire removals never destroys the ordinary entity");
            manager.updateChunkStatus(chunk, original);
            changed = false;
            helper.assertValueEqual(visibility.get(chunk.toLong()), original, "The exact saved chunk visibility is restored");
            helper.assertTrue(level.getEntity(ordinary.getUUID()) == ordinary, "The same ordinary entity resumes tracking after restoration");
            helper.assertTrue(level.getEntity(a.getUUID()) == null && level.getEntity(b.getUUID()) == null,
                    "Ended fields cannot reappear when the section becomes visible again");
            resolve(helper);
            helper.assertFalse(MireFields.isRegistered(a) || MireFields.isRegistered(b), "A later level pass cannot revive either area");
            for (var section : sections.getExistingSectionsInChunk(chunk.toLong()).toList())
                helper.assertValueEqual(section.getStatus(), original, "Native restoration also restores every surviving section status");
        } finally {
            NeoForge.EVENT_BUS.unregister(observed);
            // Restore through the native API even after an assertion/iteration failure, then remove only our objects.
            try {
                if (changed && chunk != null && original != null) manager.updateChunkStatus(chunk, original);
            } finally {
                for (MireEntity field : owned) if (!field.isRemoved()) field.discard();
                ordinary.discard();
                close(helper, first); close(helper, second);
            }
        }
        helper.succeed();
    }

    private MireGameTests() {}
}
