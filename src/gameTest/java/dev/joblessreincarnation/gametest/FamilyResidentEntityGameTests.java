package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.FamilyResidentEntity.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
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
 * FAMILY-M02: actual registered entities and vanilla AI in isolated 16x6x16 arenas.
 * Save/reload checks use the ordinary NBT loader and live entity index; they are not disk/server restart tests.
 * Template placement and packet/private-dialogue routing have their own test classes.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FamilyResidentEntityGameTests {
    private static final String ANCHOR_TAG = "JR_HomeAnchor", BATCH = "familyM02";
    private record Role(String id, Member member, int radius) { }
    private static final Role PAUL = new Role("paul_greyrat", Member.PAUL, 5);
    private static final Role ZENITH = new Role("zenith_greyrat", Member.ZENITH, 4);
    private static final Role LILIA = new Role("lilia_greyrat", Member.LILIA, 4);
    private static final List<Role> ROLES = List.of(PAUL, ZENITH, LILIA);
    private record Saved(Role role, CompoundTag tag, UUID uuid, GlobalPos home, Vec3 position, Component name) { }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }

    private static void floor(GameTestHelper helper) {
        // Leave the controller at local 0,0,0 alone and keep every edit inside the arena.
        for (int x = 1; x <= 14; x++) for (int z = 1; z <= 14; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static EntityType<?> type(GameTestHelper helper, Role role) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id(role.id()));
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(type), id(role.id()), "Exact registered resident type: " + role.id());
        return type;
    }

    private static FamilyResidentEntity create(GameTestHelper helper, Role role) {
        Entity entity = type(helper, role).create(helper.getLevel());
        helper.assertTrue(entity instanceof FamilyResidentEntity, "Registered factory creates a family resident: " + role.id());
        FamilyResidentEntity npc = (FamilyResidentEntity)entity;
        helper.assertValueEqual(npc.member(), role.member(), "Factory binds its distinct member: " + role.id());
        return npc;
    }

    private static FamilyResidentEntity spawn(GameTestHelper helper, Role role, int x, int z) {
        Entity entity = type(helper, role).spawn(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(entity instanceof FamilyResidentEntity, "Normal EntityType.spawn creates the resident: " + role.id());
        FamilyResidentEntity npc = (FamilyResidentEntity)entity;
        helper.assertTrue(helper.getLevel().getEntity(npc.getUUID()) == npc, "Resident joins the live entity index");
        helper.assertValueEqual(npc.member(), role.member(), "Spawn keeps the registered member");
        helper.assertValueEqual(npc.getHomeAnchor(), GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(x, 1, z))),
            "Supported spawn establishes the actual placement as home");
        return npc;
    }

    private static CompoundTag save(GameTestHelper helper, FamilyResidentEntity npc, Role role) {
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(npc.save(saved), "Resident participates in ordinary registered entity saving");
        helper.assertValueEqual(saved.getString("id"), id(role.id()).toString(), "Save preserves its distinct resident type");
        return saved;
    }

    private static FamilyResidentEntity load(GameTestHelper helper, CompoundTag tag, Role role) {
        Entity entity = EntityType.loadEntityRecursive(tag.copy(), helper.getLevel(), value -> value);
        helper.assertTrue(entity instanceof FamilyResidentEntity, "Vanilla NBT loader resolves a family type");
        FamilyResidentEntity npc = (FamilyResidentEntity)entity;
        helper.assertValueEqual(npc.member(), role.member(), "NBT loader resolves the correct family member");
        return npc;
    }

    private static CompoundTag homeTag(GlobalPos pos) {
        return (CompoundTag)GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).result().orElseThrow();
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

    @GameTest(template = "empty", batch = BATCH)
    public static void threeRegisteredRolesHaveEggsAttributesAndNonCombatProtection(GameTestHelper helper) {
        floor(helper);
        List<FamilyResidentEntity> residents = new ArrayList<>();
        try {
            for (int index = 0; index < ROLES.size(); index++) {
                Role role = ROLES.get(index);
                FamilyResidentEntity npc = spawn(helper, role, 3 + index * 5, 6);
                residents.add(npc);
                EntityType<?> type = npc.getType();
                helper.assertValueEqual(type.getCategory(), MobCategory.CREATURE, "Family types use the passive creature category");
                helper.assertValueEqual(type.clientTrackingRange(), 10, "Family tracking range follows IF1");
                helper.assertValueEqual(type.updateInterval(), 2, "Family tracking update interval follows IF1");
                helper.assertTrue(Math.abs(npc.getBbWidth() - 0.6F) < 0.001F && Math.abs(npc.getBbHeight() - 1.8F) < 0.001F,
                    "Living collision dimensions follow IF1 rather than the decorative model bounds");
                helper.assertTrue(Math.abs(npc.getEyeHeight() - 1.62F) < 0.001F, "Resident eye height is registered");
                helper.assertTrue(npc.getMaxHealth() == 20.0F && Math.abs(npc.getAttributeValue(Attributes.MOVEMENT_SPEED) - 0.2) < 0.0001,
                    "Live attribute registration supplies health and movement speed");
                helper.assertValueEqual(npc.homeRadius(), role.radius(), "Member-specific home radius is applied");
                helper.assertTrue(npc.shouldBeSaved() && npc.isPersistenceRequired(), "Family residents participate in persistent saving");
                helper.assertFalse(npc.removeWhenFarAway(1_000_000.0), "Distance cannot naturally despawn a resident");
                helper.assertFalse(npc.isNoAi(), "The resident runs actual AI");
                helper.assertFalse(npc.canBeLeashed(), "A resident cannot be dragged around on a lead");
                helper.assertFalse(npc.canPickUpLoot(), "Dropped equipment cannot alter the resident's loadout");
                helper.assertFalse(npc.canBeSeenAsEnemy() || npc.canAttackType(EntityType.PLAYER), "Resident is not a combat target or attacker");
                helper.assertValueEqual(npc.getCustomName(), Component.translatable("entity.jobless_reincarnation." + role.id()),
                    "Default resident name is localized by distinct role");
                helper.assertTrue(npc.isCustomNameVisible(), "Formal resident nameplate is visible");
                ItemStack egg = BuiltInRegistries.ITEM.get(id(role.id() + "_spawn_egg")).getDefaultInstance();
                helper.assertTrue(egg.getItem() instanceof SpawnEggItem, "A real family spawn egg is registered");
                helper.assertTrue(((SpawnEggItem)egg.getItem()).getType(egg) == type, "Each egg resolves its own family member");
            }
            helper.assertFalse(residents.get(0).canAttack(residents.get(1)), "Residents do not attack other residents");
        } finally { residents.forEach(Entity::discard); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 30)
    public static void homesRequireLegalSupportedPlacementAndMainThread(GameTestHelper helper) {
        floor(helper);
        List<FamilyResidentEntity> residents = new ArrayList<>();
        for (int index = 0; index < ROLES.size(); index++) {
            Role role = ROLES.get(index);
            FamilyResidentEntity npc = create(helper, role);
            residents.add(npc);
            helper.assertTrue(npc.getHomeAnchor() == null, "A constructor does not anchor its temporary origin");
            helper.assertFalse(npc.reanchor(), "An entity outside the level cannot establish home");
            npc.setNoAi(true);
            npc.setNoGravity(true);
            npc.moveTo(helper.absoluteVec(new Vec3(3.5 + index * 5, 3.0, 6.5)));
            helper.assertTrue(helper.getLevel().addFreshEntity(npc), "Airborne test entity enters the level");
            helper.assertTrue(npc.getHomeAnchor() == null, "Airborne unsupported entry does not anchor");
        }
        helper.runAfterDelay(3, () -> {
            try {
                for (int index = 0; index < residents.size(); index++) {
                    FamilyResidentEntity npc = residents.get(index);
                    helper.assertTrue(npc.getHomeAnchor() == null, "Ticks still cannot anchor unsupported air");
                    BlockPos legal = helper.absolutePos(new BlockPos(3 + index * 5, 1, 6));
                    npc.moveTo(Vec3.atBottomCenterOf(legal));
                    helper.assertTrue(npc.reanchor(), "A supported clear position can become home");
                    GlobalPos home = npc.getHomeAnchor();
                    npc.moveTo(helper.absoluteVec(new Vec3(3.5 + index * 5, 1.0, 10.5)));
                    try {
                        boolean changed = CompletableFuture.supplyAsync(npc::reanchor).get(2, TimeUnit.SECONDS);
                        helper.assertFalse(changed, "A background thread cannot mutate the home even at a legal site");
                    } catch (Exception exception) {
                        throw new IllegalStateException("Background anchor guard did not promptly return", exception);
                    }
                    helper.assertValueEqual(npc.getHomeAnchor(), home, "Rejected off-thread mutation preserves home");
                    npc.moveTo(helper.absoluteVec(new Vec3(3.5 + index * 5, 3.0, 10.5)));
                    helper.assertFalse(npc.reanchor(), "Unsupported relocation cannot overwrite a good home");
                    helper.assertValueEqual(npc.getHomeAnchor(), home, "Failed legal-site check leaves the old home intact");
                    npc.moveTo(helper.absoluteVec(new Vec3(3.5 + index * 5, 0.0, 10.5)));
                    helper.assertFalse(npc.reanchor(), "A body intersecting the floor is not a legal anchor site");
                    helper.assertValueEqual(npc.getHomeAnchor(), home, "Collision rejection also preserves home");
                }
            } finally { residents.forEach(Entity::discard); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 40)
    public static void allThreeSaveAndRejoinTwiceWithoutHomeOrIdentityDrift(GameTestHelper helper) {
        floor(helper);
        List<Saved> first = new ArrayList<>();
        for (int index = 0; index < ROLES.size(); index++) {
            Role role = ROLES.get(index);
            FamilyResidentEntity npc = spawn(helper, role, 3 + index * 5, 3);
            npc.setNoAi(true);
            Component name = Component.literal("M02 " + role.id());
            npc.setCustomName(name);
            GlobalPos home = npc.getHomeAnchor();
            npc.moveTo(helper.absoluteVec(new Vec3(3.5 + index * 5, 1.0, 11.5)));
            CompoundTag tag = save(helper, npc, role);
            helper.assertTrue(tag.contains(ANCHOR_TAG, Tag.TAG_COMPOUND), "Normal saving includes the family-specific anchor key");
            helper.assertValueEqual(GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound(ANCHOR_TAG)).result().orElseThrow(), home,
                "Serialized home remains separate from the changed entity position");
            first.add(new Saved(role, tag, npc.getUUID(), home, npc.position(), name));
            npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }
        helper.runAfterDelay(2, () -> {
            List<Saved> second = new ArrayList<>();
            for (Saved saved : first) {
                helper.assertTrue(helper.getLevel().getEntity(saved.uuid()) == null, "Previous identity is absent from the live entity index");
                CompoundTag witness = saved.tag().copy();
                FamilyResidentEntity npc = load(helper, saved.tag(), saved.role());
                helper.assertTrue(helper.getLevel().addFreshEntity(npc), "First NBT reload joins without a UUID collision");
                helper.assertValueEqual(npc.getUUID(), saved.uuid(), "First reload preserves UUID");
                helper.assertValueEqual(npc.getHomeAnchor(), saved.home(), "First reload keeps original home");
                helper.assertValueEqual(npc.position(), saved.position(), "First reload keeps the separately saved position");
                helper.assertValueEqual(saved.tag(), witness, "Loading does not mutate the source save tag");
                second.add(new Saved(saved.role(), save(helper, npc, saved.role()), saved.uuid(), saved.home(), saved.position(), saved.name()));
                npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            }
            helper.runAfterDelay(2, () -> {
                List<FamilyResidentEntity> restored = new ArrayList<>();
                try {
                    for (Saved saved : second) {
                        FamilyResidentEntity npc = load(helper, saved.tag(), saved.role());
                        restored.add(npc);
                        helper.assertTrue(helper.getLevel().addFreshEntity(npc), "Second NBT reload also joins successfully");
                        helper.assertTrue(helper.getLevel().getEntity(saved.uuid()) == npc, "Only the current instance owns the persistent UUID");
                        helper.assertValueEqual(npc.getHomeAnchor(), saved.home(), "Two NBT cycles do not reanchor at the saved offset");
                        helper.assertValueEqual(npc.position(), saved.position(), "Two NBT cycles preserve the saved position");
                        helper.assertValueEqual(npc.getCustomName(), saved.name(), "Ordinary metadata survives both reloads");
                        helper.assertTrue(npc.isPersistenceRequired() && !npc.removeWhenFarAway(1_000_000.0), "Reload keeps persistence policy");
                    }
                    JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS nbt_reload members=3 cycles=2 (not disk restart)");
                } finally { restored.forEach(Entity::discard); }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void malformedForeignAndOutOfBoundsHomesRepairWithoutAffectingNeighbours(GameTestHelper helper) {
        floor(helper);
        for (int index = 0; index < ROLES.size(); index++) {
            Role role = ROLES.get(index);
            FamilyResidentEntity original = spawn(helper, role, 3 + index * 5, 3);
            original.setNoAi(true);
            GlobalPos home = original.getHomeAnchor();
            CompoundTag saved = save(helper, original, role), witness = saved.copy();
            List<CompoundTag> invalid = new ArrayList<>();
            CompoundTag malformed = new CompoundTag();
            malformed.putString("dimension", "not a dimension");
            invalid.add(malformed);
            invalid.add(homeTag(GlobalPos.of(Level.NETHER, home.pos())));
            invalid.add(homeTag(GlobalPos.of(helper.getLevel().dimension(), new BlockPos(31_000_000, home.pos().getY(), 0))));
            invalid.add(homeTag(GlobalPos.of(helper.getLevel().dimension(), new BlockPos(home.pos().getX(), helper.getLevel().getMaxBuildHeight(), home.pos().getZ()))));
            invalid.add(homeTag(GlobalPos.of(helper.getLevel().dimension(), new BlockPos(home.pos().getX(), helper.getLevel().getMinBuildHeight() - 1, home.pos().getZ()))));
            try {
                // Also cover a missing key and a key with the wrong NBT type.
                for (int variant = 0; variant < invalid.size() + 2; variant++) {
                    CompoundTag changed = saved.copy();
                    changed.putUUID("UUID", UUID.randomUUID());
                    if (variant < invalid.size()) changed.put(ANCHOR_TAG, invalid.get(variant));
                    else if (variant == invalid.size()) changed.remove(ANCHOR_TAG);
                    else changed.putString(ANCHOR_TAG, "broken anchor");
                    changed.putBoolean("PersistenceRequired", false);
                    changed.putBoolean("CanPickUpLoot", true);
                    changed.putBoolean("Invulnerable", false);
                    FamilyResidentEntity copy = load(helper, changed, role);
                    try {
                        helper.assertTrue(copy.getHomeAnchor() == null, "Rejected or missing anchor is not trusted before insertion");
                        BlockPos destination = helper.absolutePos(new BlockPos(8, 1, 11));
                        copy.moveTo(Vec3.atBottomCenterOf(destination));
                        helper.assertTrue(helper.getLevel().addFreshEntity(copy), "Repaired resident joins under its own UUID");
                        helper.assertValueEqual(copy.getHomeAnchor(), GlobalPos.of(helper.getLevel().dimension(), destination),
                            "A missing or invalid home repairs at this resident's supported destination");
                        helper.assertTrue(copy.isPersistenceRequired() && !copy.canPickUpLoot(), "Load restores persistent non-looting policy");
                        helper.assertFalse(copy.hurt(helper.getLevel().damageSources().generic(), 1000.0F), "Load restores resident protection");
                        helper.assertValueEqual(original.getHomeAnchor(), home, "Repair does not alter the neighbouring original resident");
                    } finally { copy.discard(); }
                }
                helper.assertValueEqual(saved, witness, "Bad-input repairs cannot mutate the original valid save");
            } finally { original.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 30)
    public static void onlyAuthorizedFamilyCommandChangesOneSupportedHome(GameTestHelper helper) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, PAUL, 3, 5), neighbour = spawn(helper, LILIA, 10, 11);
        npc.setNoAi(true);
        neighbour.setNoAi(true);
        GlobalPos old = npc.getHomeAnchor(), otherHome = neighbour.getHomeAnchor();
        BlockPos destination = helper.absolutePos(new BlockPos(12, 1, 5));
        npc.moveTo(Vec3.atBottomCenterOf(destination));
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertValueEqual(npc.getHomeAnchor(), old, "Ordinary same-dimension relocation does not reanchor across ticks");
                String text = "jr_family anchor " + npc.getUUID();
                boolean rejected = false;
                try { helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, 1)); }
                catch (CommandSyntaxException expected) { rejected = true; }
                helper.assertTrue(rejected, "The registered family command tree rejects permission level one");
                helper.assertValueEqual(npc.getHomeAnchor(), old, "Denied command does not mutate home");
                helper.assertValueEqual(command(helper, 2, text), 1, "Permission level two may deliberately relocate one resident");
                GlobalPos movedHome = GlobalPos.of(helper.getLevel().dimension(), destination);
                helper.assertValueEqual(npc.getHomeAnchor(), movedHome, "Authorized command anchors the current supported position");
                helper.assertValueEqual(neighbour.getHomeAnchor(), otherHome, "UUID-targeted command leaves another family member unchanged");
                npc.setNoGravity(true);
                npc.moveTo(helper.absoluteVec(new Vec3(12.5, 3.0, 5.5)));
                // Direct Brigadier dispatch propagates a rejected command as CommandSyntaxException;
                // it does not pass through the vanilla command runner that converts failures to a zero result.
                boolean airborneRejected = false;
                try { helper.getLevel().getServer().getCommands().getDispatcher().execute(text, source(helper, 2)); }
                catch (CommandSyntaxException expected) {
                    airborneRejected = true;
                    helper.assertValueEqual(expected.getRawMessage(), Component.translatable("message.jobless_reincarnation.family.anchor.invalid_site"),
                        "Unsupported placement is rejected for the specific invalid-site reason");
                }
                helper.assertTrue(airborneRejected, "Even an administrator cannot set an unsupported airborne home");
                helper.assertValueEqual(npc.getHomeAnchor(), movedHome, "Invalid admin destination preserves the previous good home");
                JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS admin_anchor legal_relocation=true airborne_rejected=true neighbour_unchanged=true");
            } finally { npc.discard(); neighbour.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 50)
    public static void allThreeRejectDamageButNativeKillRemovesThemWithoutLoot(GameTestHelper helper) {
        floor(helper);
        List<FamilyResidentEntity> residents = new ArrayList<>();
        AABB area = new AABB(helper.absoluteVec(new Vec3(1, 0, 1)), helper.absoluteVec(new Vec3(15, 5, 15)));
        for (int index = 0; index < ROLES.size(); index++) {
            FamilyResidentEntity npc = spawn(helper, ROLES.get(index), 3 + index * 5, 8);
            residents.add(npc);
            npc.setNoAi(true);
            npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
            npc.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
            float health = npc.getHealth();
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().generic(), 1000.0F), "Ordinary damage is rejected");
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().lava(), 1000.0F), "Environmental damage is rejected");
            helper.assertFalse(npc.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000.0F), "Void bypass damage does not override resident protection");
            helper.assertTrue(npc.getHealth() == health && npc.isAlive(), "Protection preserves resident health");
            helper.assertValueEqual(command(helper, 2, "kill " + npc.getUUID()), 1, "Native kill command accepts the protected family member");
            helper.assertTrue(npc.isDeadOrDying(), "GENERIC_KILL remains effective for administration");
        }
        helper.runAfterDelay(25, () -> {
            for (FamilyResidentEntity npc : residents) {
                helper.assertTrue(npc.isRemoved() && helper.getLevel().getEntity(npc.getUUID()) == null, "Normal death completes entity removal");
            }
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "Even guaranteed equipment creates no resident loot");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, area).isEmpty(), "Family deaths create no experience");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 110)
    public static void validUnloadedHomeDoesNotForceChunksOrTeleport(GameTestHelper helper) {
        floor(helper);
        FamilyResidentEntity original = spawn(helper, PAUL, 8, 8);
        CompoundTag saved = save(helper, original, PAUL);
        original.discard();
        GlobalPos remote = GlobalPos.of(helper.getLevel().dimension(), new BlockPos(20_000_000, helper.absolutePos(new BlockPos(8, 1, 8)).getY(), 20_000_000));
        helper.assertFalse(helper.getLevel().hasChunkAt(remote.pos()), "Remote legal home is genuinely unloaded before the test");
        saved.putUUID("UUID", UUID.randomUUID());
        saved.put(ANCHOR_TAG, homeTag(remote));
        FamilyResidentEntity npc = load(helper, saved, PAUL);
        npc.moveTo(helper.absoluteVec(new Vec3(8.5, 1.0, 8.5)));
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(npc), "Resident with an unloaded but valid home enters the local arena");
        Vec3 start = npc.position();
        int[] samples = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            helper.assertValueEqual(npc.getHomeAnchor(), remote, "Unloaded alone does not invalidate an otherwise valid saved home");
            helper.assertFalse(helper.getLevel().hasChunkAt(remote.pos()), "Return attempts do not force-load the remote home chunk");
            helper.assertTrue(npc.position().distanceToSqr(start) < 0.01, "Missing destination terrain means waiting rather than teleporting or wandering away");
            helper.assertTrue(npc.getNavigation().isDone(), "No route is followed into missing terrain");
            samples[0]++;
        });
        helper.runAfterDelay(90, () -> {
            try { helper.assertTrue(samples[0] >= 80, "Unloaded-home guard is observed across multiple retry intervals"); }
            finally { npc.discard(); }
            helper.succeed();
        });
    }

    private static void returnHome(GameTestHelper helper, Role role) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, role, 3, 8);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        npc.moveTo(helper.absoluteVec(new Vec3(13.5, 1.0, 8.5)));
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        Vec3[] previous = { npc.position() };
        int[] walked = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double step = npc.position().distanceTo(previous[0]);
            helper.assertTrue(step < 0.8, "Family return consists of walking rather than a teleport: " + step);
            if (step > 0.01) walked[0]++;
            previous[0] = npc.position();
            helper.assertValueEqual(npc.getHomeAnchor(), home, "Walking back never replaces the persistent home");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(homePoint) < 2.25, "Actual AI has returned within 1.5 blocks of home");
            helper.assertTrue(walked[0] > 15, "Return was observed across multiple real server ticks");
            JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS return member={} walkedTicks={}", role.id(), walked[0]);
            npc.discard();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 220)
    public static void paulWalksBackToHisOwnHome(GameTestHelper helper) { returnHome(helper, PAUL); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 220)
    public static void zenithWalksBackToHerOwnHome(GameTestHelper helper) { returnHome(helper, ZENITH); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 220)
    public static void liliaWalksBackToHerOwnHome(GameTestHelper helper) { returnHome(helper, LILIA); }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 300)
    public static void blockedReturnWaitsAndRetriesAfterAnExitOpens(GameTestHelper helper) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, LILIA, 3, 8);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3 pushed = helper.absoluteVec(new Vec3(13.5, 1.0, 8.5));
        for (int y = 1; y <= 3; y++) for (int x = 12; x <= 14; x++) for (int z = 7; z <= 9; z++) {
            if (x == 12 || x == 14 || z == 7 || z == 9) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        npc.moveTo(pushed);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        boolean[] opened = { false };
        Vec3[] previous = { pushed };
        int[] blocked = { 0 }, walked = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double step = npc.position().distanceTo(previous[0]);
            helper.assertTrue(step < 0.8, "Blocked recovery does not teleport through the cage");
            previous[0] = npc.position();
            if (!opened[0]) {
                helper.assertTrue(npc.position().distanceToSqr(pushed) < 0.01, "Incomplete return routes are not followed");
                helper.assertTrue(npc.getNavigation().isDone(), "Blocked resident waits without a partial path");
                blocked[0]++;
            } else if (step > 0.01) walked[0]++;
            helper.assertValueEqual(npc.getHomeAnchor(), home, "A blocked path cannot relocate home");
        });
        helper.runAfterDelay(85, () -> {
            helper.assertTrue(blocked[0] >= 75, "Waiting is observed over more than one return retry window");
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(12, y, 8), Blocks.AIR);
            opened[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(opened[0] && npc.distanceToSqr(homePoint) < 2.25, "A later retry follows the newly opened route home");
            helper.assertTrue(walked[0] > 15, "Recovery after obstruction removal is physical walking");
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(14, 1, 8));
            npc.discard();
        });
    }

    private static void stroll(GameTestHelper helper, Role role) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, role, 8, 8);
        npc.getRandom().setSeed(42L);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 centre = Vec3.atBottomCenterOf(home.pos());
        Vec3[] previous = { npc.position() };
        double[] total = { 0.0 }, furthest = { 0.0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double distance = npc.position().distanceTo(centre);
            furthest[0] = Math.max(furthest[0], distance);
            helper.assertTrue(distance <= role.radius() + 0.15, "Live stroll respects this member's 3D home radius");
            helper.assertValueEqual(npc.getHomeAnchor(), home, "Strolling does not drift home");
            Path path = npc.getNavigation().getPath();
            if (path != null && !npc.getNavigation().isDone()) {
                helper.assertTrue(path.canReach(), "An accepted stroll route reaches its target");
                helper.assertTrue(Math.abs(path.getTarget().getY() - home.pos().getY()) <= 1, "Accepted stroll target remains on this floor or one adjacent block");
                for (int node = path.getNextNodeIndex(); node < path.getNodeCount(); node++) {
                    helper.assertTrue(path.getEntityPosAtNode(npc, node).distanceToSqr(centre) <= role.radius() * role.radius() + 0.001,
                        "Every remaining stroll node stays in the member's home volume");
                }
            }
            total[0] += npc.position().distanceTo(previous[0]);
            previous[0] = npc.position();
        });
        helper.runAfterDelay(500, () -> {
            try {
                helper.assertTrue(total[0] > 1.0, "Actual AI selected and walked a route during several independent attempt windows");
                JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS stroll member={} totalWalked={} furthest={} radius={}",
                    role.id(), total[0], furthest[0], role.radius());
            } finally { npc.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 530)
    public static void paulStrollUsesFiveBlockHome(GameTestHelper helper) { stroll(helper, PAUL); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 530)
    public static void zenithStrollUsesFourBlockHome(GameTestHelper helper) { stroll(helper, ZENITH); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 530)
    public static void liliaStrollUsesFourBlockHome(GameTestHelper helper) { stroll(helper, LILIA); }

    private static void conversation(GameTestHelper helper, Role role) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, role, 3, 8);
        GlobalPos home = npc.getHomeAnchor();
        Vec3 homePoint = Vec3.atBottomCenterOf(home.pos());
        Vec3 pushed = helper.absoluteVec(new Vec3(13.5, 1.0, 8.5));
        npc.moveTo(pushed);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setOnGround(true);
        FakePlayer reader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "FamilyAiReader"));
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
                    helper.assertTrue(npc.distanceToSqr(homePoint) > (role.radius() + 0.5) * (role.radius() + 0.5),
                        "Conversation starts during a real required return walk");
                    reader.moveTo(npc.position().add(0.0, 0.0, 2.0));
                    helper.assertTrue(helper.getLevel().getEntity(reader.getUUID()) == reader, "Conversation uses a registered server player");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Reader is genuinely in range and sight");
                    helper.assertTrue(npc.beginConversation(reader), "Walking resident accepts the valid reader");
                    started[0] = helper.getLevel().getGameTime();
                    pauseStart[0] = npc.position();
                    // A transient conversation must not be resurrected by the normal entity loader.
                    FamilyResidentEntity loaded = load(helper, save(helper, npc, role), role);
                    helper.assertTrue(!loaded.isConversing() && loaded.conversationPlayer() == null, "Conversation target is not persistent NBT state");
                    loaded.discard();
                    phase[0] = 1;
                    return;
                }
                long elapsed = helper.getLevel().getGameTime() - started[0];
                if (phase[0] == 1 && elapsed < FamilyResidentEntity.CONVERSATION_TICKS) {
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
                    helper.assertTrue(elapsed == 40 && elapsed == FamilyResidentEntity.CONVERSATION_TICKS, "Conversation expires at the agreed forty-tick boundary");
                    helper.assertFalse(npc.isConversing(), "Conversation ends while the valid reader remains nearby");
                    helper.assertTrue(paused[0] >= 38, "Pause was observed throughout its intended duration");
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Timer expiry caused release, not invalid range or sight");
                    phase[0] = 2;
                    return;
                }
                if (phase[0] == 2 && !npc.getNavigation().isDone() && npc.position().distanceToSqr(settled[0]) > 0.25 * 0.25) {
                    helper.assertFalse(npc.isConversing(), "Expired conversation does not restart itself");
                    JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS conversation member={} pausedTicks={} resumeElapsed={}", role.id(), paused[0], elapsed);
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
            helper.assertTrue(false, "Family conversation did not complete its actual walk-pause-resume lifecycle");
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 210)
    public static void paulConversationPausesFortyTicksThenResumesWalking(GameTestHelper helper) { conversation(helper, PAUL); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 210)
    public static void zenithConversationPausesFortyTicksThenResumesWalking(GameTestHelper helper) { conversation(helper, ZENITH); }
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 210)
    public static void liliaConversationPausesFortyTicksThenResumesWalking(GameTestHelper helper) { conversation(helper, LILIA); }

    /** Real server AI in a furnished arena; this is not a client rendering/animation claim. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 1530)
    public static void strollAmongFurnitureCrossesCarpetWithoutClimbingSeatsOrCounters(GameTestHelper helper) {
        floor(helper);
        List<BlockPos> chairs = List.of(new BlockPos(7, 1, 8), new BlockPos(9, 1, 8),
            new BlockPos(7, 1, 10), new BlockPos(9, 1, 6));
        List<BlockPos> counters = List.of(new BlockPos(10, 1, 9), new BlockPos(6, 1, 7), new BlockPos(8, 1, 11));
        List<BlockPos> furniture = new ArrayList<>(chairs);
        furniture.addAll(counters);
        // The starting square is bare. Every free walking square nearby has a real carpet collision shape.
        for (int x = 3; x <= 13; x++) for (int z = 3; z <= 13; z++) {
            BlockPos pos = new BlockPos(x, 1, z);
            if ((x != 8 || z != 8) && !furniture.contains(pos)) helper.setBlock(pos, Blocks.WHITE_CARPET);
        }
        chairs.forEach(pos -> helper.setBlock(pos, Blocks.OAK_SLAB)); // Default state is the bottom half slab.
        counters.forEach(pos -> helper.setBlock(pos, Blocks.OAK_PLANKS));
        FamilyResidentEntity npc = spawn(helper, ZENITH, 8, 8);
        npc.getRandom().setSeed(42L);
        GlobalPos home = npc.getHomeAnchor();
        double floorY = home.pos().getY();
        Vec3[] previous = { npc.position() };
        double[] traveled = { 0.0 }, highest = { 0.0 };
        int[] carpetMovement = { 0 }, furniturePasses = { 0 };
        helper.onEachTick(() -> {
            if (npc.isRemoved()) return;
            double step = npc.position().distanceTo(previous[0]);
            traveled[0] += step;
            previous[0] = npc.position();
            double rise = npc.getY() - floorY;
            highest[0] = Math.max(highest[0], rise);
            helper.assertTrue(rise >= -0.05 && rise <= 0.15,
                "Actual walking feet remain at floor/carpet height, never the half-block seat or one-block counter: " + rise);
            helper.assertValueEqual(npc.getHomeAnchor(), home, "Furniture avoidance does not silently relocate home");
            if (step > 0.001) {
                if (helper.getLevel().getBlockState(npc.blockPosition()).is(Blocks.WHITE_CARPET)) carpetMovement[0]++;
                for (BlockPos local : furniture) {
                    Vec3 centre = Vec3.atBottomCenterOf(helper.absolutePos(local));
                    double dx = npc.getX() - centre.x, dz = npc.getZ() - centre.z;
                    if (dx * dx + dz * dz <= 1.25 * 1.25) {
                        furniturePasses[0]++;
                        break;
                    }
                }
            }
        });
        helper.runAfterDelay(1500, () -> {
            try {
                helper.assertTrue(traveled[0] > 3.0, "The real random stroll must walk, not pass by standing motionless");
                helper.assertTrue(carpetMovement[0] >= 8, "Actual movement traverses carpet collision surfaces");
                helper.assertTrue(furniturePasses[0] >= 4, "Movement includes close approaches to the furniture under test");
                JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS furniture_stroll walked={} carpetSamples={} furnitureSamples={} maximumRise={}",
                    traveled[0], carpetMovement[0], furniturePasses[0], highest[0]);
            } finally { npc.discard(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 1230)
    public static void strollStepCapPreservesCustomAttributeThroughCompletionAndConversation(GameTestHelper helper) {
        floor(helper);
        FamilyResidentEntity npc = spawn(helper, LILIA, 8, 8);
        npc.getRandom().setSeed(42L);
        var attribute = npc.getAttribute(Attributes.STEP_HEIGHT);
        helper.assertTrue(attribute != null, "Living resident exposes the registered step-height attribute");
        attribute.setBaseValue(0.9);
        helper.assertTrue(Math.abs(npc.maxUpStep() - 0.9) < 0.0001, "Before any stroll, custom normal step height is effective");
        FakePlayer reader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "FamilyStepReader"));
        boolean[] playerAdded = { false }, closed = { false };
        Runnable cleanup = () -> {
            if (closed[0]) return;
            closed[0] = true;
            if (playerAdded[0]) NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(reader));
            reader.discard();
            npc.discard();
        };
        GlobalPos home = npc.getHomeAnchor();
        Vec3 start = npc.position();
        Vec3[] previous = { start }, naturalStop = { null };
        double[] expectedBase = { 0.9 }, traveled = { 0.0 };
        int[] phase = { 0 }, cappedMovement = { 0 };
        long[] conversationStart = { -1L };
        helper.onEachTick(() -> {
            if (closed[0]) return;
            try {
                double step = npc.position().distanceTo(previous[0]);
                traveled[0] += step;
                previous[0] = npc.position();
                helper.assertValueEqual(npc.getHomeAnchor(), home, "Step-height changes and conversation preserve the home");
                helper.assertTrue(Math.abs(attribute.getBaseValue() - expectedBase[0]) < 0.000001,
                    "Stroll mechanics must not overwrite a caller's STEP_HEIGHT base value");
                helper.assertTrue(Math.abs(npc.getAttributeValue(Attributes.STEP_HEIGHT) - expectedBase[0]) < 0.000001,
                    "The effective attribute also remains unchanged while only walking behavior is capped");
                if (phase[0] <= 2 && !npc.getNavigation().isDone() && step > 0.001) {
                    helper.assertTrue(npc.maxUpStep() <= 2.0F / 16.0F + 0.0001F,
                        "During an actual in-home random walk, physical auto-step is capped below furniture height");
                    cappedMovement[0]++;
                }
                if (phase[0] == 0) {
                    if (!npc.getNavigation().isDone() && npc.position().distanceToSqr(start) > 0.04) phase[0] = 1;
                    return;
                }
                if (phase[0] == 1) {
                    // Wait for a naturally completed live path; do not stop or mutate the goal to manufacture this state.
                    if (!npc.getNavigation().isDone() || Math.abs(npc.maxUpStep() - expectedBase[0]) >= 0.0001) return;
                    helper.assertTrue(traveled[0] > 0.2 && cappedMovement[0] >= 2, "A completed moving stroll was observed with the cap in effect");
                    naturalStop[0] = npc.position();
                    phase[0] = 2;
                    return;
                }
                if (phase[0] == 2) {
                    if (npc.getNavigation().isDone() || npc.position().distanceToSqr(naturalStop[0]) <= 0.04) return;
                    // Simulate another system changing a legitimate base value during a real second stroll.
                    expectedBase[0] = 1.05;
                    attribute.setBaseValue(expectedBase[0]);
                    helper.assertTrue(npc.maxUpStep() <= 2.0F / 16.0F + 0.0001F, "A changed base value does not defeat the active stroll cap");
                    reader.moveTo(npc.position().add(0.0, 0.0, 2.0));
                    reader.getAbilities().instabuild = false;
                    helper.getLevel().addNewPlayer(reader);
                    playerAdded[0] = true;
                    helper.assertTrue(reader.canInteractWithEntity(npc, 0.0) && reader.hasLineOfSight(npc), "Interrupting reader is in real range and sight");
                    helper.assertTrue(npc.beginConversation(reader), "A valid conversation interrupts the second live stroll");
                    conversationStart[0] = helper.getLevel().getGameTime();
                    phase[0] = 3;
                    return;
                }
                long elapsed = helper.getLevel().getGameTime() - conversationStart[0];
                if (elapsed < 5) return; // Allow the ordinary goal scheduler to stop the old movement goal.
                helper.assertTrue(npc.isConversing() && npc.conversationPlayer() == reader, "Conversation remains the reason movement stopped");
                helper.assertTrue(npc.getNavigation().isDone(), "Interrupted stroll navigation remains stopped");
                helper.assertTrue(Math.abs(npc.maxUpStep() - expectedBase[0]) < 0.0001,
                    "Conversation interruption restores the current custom normal step height, not a stale previous value");
                JoblessReincarnation.LOGGER.info("[JR_FAMILY_M02_ENTITY] PASS step_attribute natural_completion=true conversation_interrupt=true initialBase=0.9 finalBase={} cappedMovingSamples={} walked={}",
                    expectedBase[0], cappedMovement[0], traveled[0]);
                cleanup.run();
                helper.succeed();
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
        });
        helper.runAfterDelay(1200, () -> {
            if (closed[0]) return;
            cleanup.run();
            helper.assertTrue(false, "Did not witness two genuine strolls, natural step restoration and conversation interruption");
        });
    }
    private FamilyResidentEntityGameTests() { }
}
