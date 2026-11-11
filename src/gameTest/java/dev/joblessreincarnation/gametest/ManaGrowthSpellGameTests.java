package dev.joblessreincarnation.magic;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Actual five-spell services, item cancellation and external-hook integration; excluded from release jars. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaGrowthSpellGameTests {
    private static final class Caster extends FakePlayer {
        final List<ClientboundSystemChatPacket> growthMessages = new ArrayList<>();
        final List<ClientboundSoundPacket> privateSounds = new ArrayList<>();
        Caster(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "GrowthCast"));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                    this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { capture(packet); }
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { capture(packet); }
            };
        }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket message
                    && message.content().getContents() instanceof TranslatableContents content
                    && (content.getKey().equals("message.jobless_reincarnation.mana_growth.gained")
                        || content.getKey().equals("message.jobless_reincarnation.mana_growth.complete")))
                growthMessages.add(message);
            if (packet instanceof ClientboundSoundPacket sound) privateSounds.add(sound);
        }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }

    private static Caster caster(GameTestHelper h) {
        Caster p = new Caster(h.getLevel());
        p.setPos(h.absoluteVec(new Vec3(5.5, 2, 2.5)));
        p.setYRot(0); p.setXRot(0);
        p.getAbilities().instabuild = false; p.getAbilities().mayBuild = true;
        p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        for (SpellCatalog spell : SpellCatalog.values()) p.getData(ModAttachments.KNOWLEDGE).learn(spell.id());
        p.setData(ModAttachments.MANA, ManaData.full(100));
        p.setData(ModAttachments.MANA_GROWTH, new ManaGrowthData());
        p.setHealth(10);
        return p;
    }
    private static ManaGrowthData growth(Caster p) { return p.getData(ModAttachments.MANA_GROWTH); }
    private static void aim(Caster p, Vec3 target) {
        Vec3 delta = target.subtract(p.getEyePosition());
        p.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }
    private static EarthWallSpell.Plan wallSite(GameTestHelper h, Caster p) {
        for (int x = 4; x <= 6; x++) h.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
        aim(p, h.absoluteVec(new Vec3(5.5, 2, 6.5)));
        var plan = EarthWallSpell.plan(p);
        h.assertTrue(plan.valid(), "Fixture provides a valid six-cell wall site");
        return plan;
    }
    /** FakePlayer is not world-registered: advance real resource/growth handlers without replacing mana. */
    private static void tickResources(Caster p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            p.getCooldowns().tick();
            ManaEvents.onPlayerTick(new PlayerTickEvent.Post(p));
        }
    }
    private static void awaitSpellCooldown(Caster p) {
        for (int ticks = 0; ticks < 100; ticks++) {
            if (p.getData(ModAttachments.MANA).cooldown() == 0
                    && !p.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) return;
            tickResources(p, 1);
        }
        throw new IllegalStateException("Default spell cooldown failed to finish");
    }
    private static void hold(Caster p, SpellCatalog spell, int ticks) {
        p.getData(ModAttachments.CASTING).select(spell.id());
        ModContent.TRAINING_STAFF.get().use(p.level(), p, InteractionHand.MAIN_HAND);
        for (int i = 0; i < ticks; i++) p.tickUse();
    }
    private static void deplete(GameTestHelper h, SpellCatalog spell) {
        Caster caster = caster(h);
        if (spell == SpellCatalog.EARTH_WALL) wallSite(h, caster);
        if (spell == SpellCatalog.MIRE) {
            caster.setPos(h.absoluteVec(new Vec3(8.5, 2, 3.5)));
            aim(caster, h.absoluteVec(new Vec3(8.5, 2, 8.5)));
        }
        caster.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        int held = ManaDepletion.confirmTicks(caster, spell);
        hold(caster, spell, spell == SpellCatalog.WATER_BOLT ? held + 50 : held);
        h.assertTrue(caster.getData(ModAttachments.CASTING).overcasting(), "Real staff input locks an overdraft chant");
        caster.releaseUsingItem();
        ManaData mana = caster.getData(ModAttachments.MANA);
        h.assertTrue(mana.overcastLocked() && mana.cooldown() > 0 && mana.recoveryDelay() > 0, spell + " committed a single overdraft");
        h.assertValueEqual(mana.mana(), 0, "No phantom mana at any balance, including a zero starting pool");
        h.assertTrue(caster.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Shared item cooldown began");
        h.assertValueEqual(spell.practiceCount(caster), 0, "No practice from overdraft");
        h.assertValueEqual(growth(caster).paidInRound(), 0, "No capacity growth from overdraft");
        h.assertTrue(ManaDepletion.isDepleted(caster), "Exhaustion applied");
        var effect = caster.getEffect(ModContent.MANA_DEPLETION);
        var blind = caster.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        h.assertValueEqual(effect.getDuration(), MagicConfig.DEPLETION_TICKS.getAsInt(), "Configured exhaustion duration");
        h.assertTrue(effect.showIcon() && blind != null && !blind.showIcon(), "One visible status icon, with a short hidden onset");
        h.assertFalse(effect.getCures().contains(net.neoforged.neoforge.common.EffectCures.MILK), "Milk cannot remove depletion");
        caster.removeEffectsCuredBy(net.neoforged.neoforge.common.EffectCure.get("milk"));
        h.assertTrue(ManaDepletion.isDepleted(caster), "Actually curing with milk leaves depletion in place");
        if (spell == SpellCatalog.WATER_BOLT) {
            var bolts = h.getLevel().getEntitiesOfClass(WaterBolt.class, h.getBounds().inflate(3), bolt -> bolt.getOwner() == caster);
            h.assertValueEqual(bolts.size(), 1, "Exactly one real projectile even on a long hold");
            h.assertValueEqual(bolts.getFirst().charge(), 0F, "Borrowing never pays the charge surcharge");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesWaterBolt(GameTestHelper h) { deplete(h, SpellCatalog.WATER_BOLT); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesWaterShield(GameTestHelper h) { deplete(h, SpellCatalog.WATER_SHIELD); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesEarthWall(GameTestHelper h) { deplete(h, SpellCatalog.EARTH_WALL); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesAirBurst(GameTestHelper h) { deplete(h, SpellCatalog.AIR_BURST); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesHealing(GameTestHelper h) { deplete(h, SpellCatalog.HEALING); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesFireBall(GameTestHelper h) { deplete(h, SpellCatalog.FIRE_BALL); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesMire(GameTestHelper h) { deplete(h, SpellCatalog.MIRE); }

    @GameTest(template = "empty")
    public static void confirmedOverdraftReleasesStoneCannon(GameTestHelper h) { deplete(h, SpellCatalog.STONE_CANNON); }

    @GameTest(template = "empty")
    public static void earlySneakSwitchAndJournalCancelOverdraftForFree(GameTestHelper h) {
        Caster p = caster(h);
        p.setData(ModAttachments.MANA, new ManaData(3, 100, 0, 0));
        int boundary = ManaDepletion.confirmTicks(p, SpellCatalog.WATER_BOLT);
        hold(p, SpellCatalog.WATER_BOLT, boundary - 1);
        p.releaseUsingItem();
        hold(p, SpellCatalog.WATER_BOLT, boundary);
        p.setShiftKeyDown(true);
        p.releaseUsingItem();
        p.setShiftKeyDown(false);
        hold(p, SpellCatalog.WATER_BOLT, boundary);
        SpellCasting.select(p, SpellCatalog.FIRE_BALL.id());
        p.releaseUsingItem();
        hold(p, SpellCatalog.WATER_BOLT, boundary);
        SpellCasting.openJournal(p);
        p.releaseUsingItem();
        ManaData mana = p.getData(ModAttachments.MANA);
        h.assertTrue(mana.mana() == 3 && mana.cooldown() == 0 && !mana.overcastLocked() && !ManaDepletion.isDepleted(p), "Cancelled holds neither pay nor penalize");
        h.assertValueEqual(SpellCatalog.WATER_BOLT.practiceCount(p), 0, "No study credit for cancelling");
        h.assertEntityNotPresent(ModContent.WATER_BOLT.get());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedOverdraftWallKeepsOriginalManaAndTerrain(GameTestHelper h) {
        Caster p = caster(h);
        var plan = wallSite(h, p);
        p.setData(ModAttachments.MANA, new ManaData(3, 100, 0, 0));
        AtomicInteger attempts = new AtomicInteger();
        Consumer<BlockEvent.EntityPlaceEvent> deny = event -> {
            if (event.getEntity() == p) { attempts.incrementAndGet(); event.setCanceled(true); }
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, deny);
        try {
            hold(p, SpellCatalog.EARTH_WALL, ManaDepletion.confirmTicks(p, SpellCatalog.EARTH_WALL));
            p.releaseUsingItem();
        } finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertTrue(attempts.get() > 0, "Build protection really ran");
        var mana = p.getData(ModAttachments.MANA);
        h.assertTrue(mana.mana() == 3 && !mana.overcastLocked() && mana.cooldown() == 0 && mana.recoveryDelay() == 0, "Rejected release keeps the prior pool and timers");
        for (var pos : plan.positions()) h.assertTrue(h.getLevel().getBlockState(pos).isAir(), "Rejected wall is rolled back");
        h.assertFalse(ManaDepletion.isDepleted(p), "Rejected wall has no debuff");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void overdraftLocksSurviveSaveAndUnlockAtThirtyFivePercent(GameTestHelper h) {
        Caster p = caster(h), other = caster(h);
        p.setData(ModAttachments.MANA, new ManaData(2, 100, 0, 0));
        hold(p, SpellCatalog.AIR_BURST, ManaDepletion.confirmTicks(p, SpellCatalog.AIR_BURST));
        p.releaseUsingItem();
        var saved = ManaData.CODEC.encodeStart(NbtOps.INSTANCE, p.getData(ModAttachments.MANA)).getOrThrow();
        ManaData mana = ManaData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(mana.overcastLocked() && !other.getData(ModAttachments.MANA).overcastLocked(), "Only the caster carries the saved lock");
        h.assertFalse(ManaDepletion.isDepleted(other), "Other player has no exhaustion");
        var buf = io.netty.buffer.Unpooled.buffer();
        try { ManaData.STREAM_CODEC.encode(buf, mana); h.assertTrue(ManaData.STREAM_CODEC.decode(buf).overcastLocked(), "Owner wire data includes lock"); }
        finally { buf.release(); }
        p.setData(ModAttachments.MANA, mana);
        int delay = mana.recoveryDelay();
        for (int i = 0; i < delay; i++) mana.tick(100, 1, 0.5);
        h.assertValueEqual(mana.mana(), 0, "One odd regen pulse banks half a mana");
        for (int i = 0; i < 20; i++) mana.tick(100, 1, 0.5);
        h.assertValueEqual(mana.mana(), 1, "Two half pulses restore exactly one mana");
        for (int i = 0; i < 660; i++) mana.tick(100, 1);
        h.assertTrue(mana.mana() == 34 && mana.overcastLocked(), "Still locked at 34 percent");
        for (int i = 0; i < 20; i++) mana.tick(100, 1);
        h.assertTrue(mana.mana() == 35 && !mana.overcastLocked(), "Exactly 35 percent unlocks");
        Caster revived = caster(h);
        revived.setData(ModAttachments.MANA, ManaData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
        h.assertTrue(revived.getData(ModAttachments.MANA).overcastLocked(), "Pre-death fixture still carries a lock");
        ManaGrowth.onClone(new net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone(revived, p, true));
        h.assertFalse(revived.getData(ModAttachments.MANA).overcastLocked(), "Death creates a clean pool");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientNormalChantCannotTurnIntoOverdraftOrChargeOnFailure(GameTestHelper h) {
        Caster p = caster(h);
        hold(p, SpellCatalog.FIRE_BALL, ManaDepletion.confirmTicks(p, SpellCatalog.FIRE_BALL) + 5);
        p.setData(ModAttachments.MANA, new ManaData(1, 100, 0, 0));
        p.releaseUsingItem();
        var mana = p.getData(ModAttachments.MANA);
        h.assertTrue(mana.mana() == 1 && !mana.overcastLocked() && mana.cooldown() == 0, "Mana removed during an ordinary chant cannot trigger borrowing");
        p.setHealth(p.getMaxHealth());
        hold(p, SpellCatalog.HEALING, ManaDepletion.confirmTicks(p, SpellCatalog.HEALING));
        p.releaseUsingItem();
        h.assertTrue(mana.mana() == 1 && !mana.overcastLocked() && !ManaDepletion.isDepleted(p), "Invalid healing target cannot exhaust its caster");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeAndCooldownCannotBorrowButOrdinaryFundedSpellsStillWork(GameTestHelper h) {
        Caster p = caster(h);
        p.getAbilities().instabuild = true;
        p.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        h.assertFalse(ManaDepletion.canOvercast(p, SpellCatalog.FIRE_BALL), "Creative never borrows");
        hold(p, SpellCatalog.FIRE_BALL, SpellCatalog.FIRE_BALL.chantTicks(p));
        p.releaseUsingItem();
        h.assertFalse(ManaDepletion.isDepleted(p), "Creative still casts normally");
        p.getAbilities().instabuild = false;
        h.assertFalse(SpellCasting.canBegin(p), "Borrowing cannot bypass a cooldown");
        Caster low = caster(h);
        low.setData(ModAttachments.MANA, new ManaData(2, 100, 0, 0));
        hold(low, SpellCatalog.FIRE_BALL, ManaDepletion.confirmTicks(low, SpellCatalog.FIRE_BALL));
        low.setData(ModAttachments.MANA, new ManaData(30, 100, 0, 0));
        low.releaseUsingItem();
        h.assertValueEqual(low.getData(ModAttachments.MANA).mana(), 30 - SpellCatalog.FIRE_BALL.manaCost(0), "A fully confirmed chant uses real funds if replenished before release");
        h.assertFalse(ManaDepletion.isDepleted(low), "No exhaustion when the full fee can be paid");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void containerAndChangedStaffInvalidateConfirmedOverdraft(GameTestHelper h) {
        Caster p = caster(h);
        p.setData(ModAttachments.MANA, new ManaData(1, 100, 0, 0));
        int ticks = ManaDepletion.confirmTicks(p, SpellCatalog.FIRE_BALL);
        hold(p, SpellCatalog.FIRE_BALL, ticks);
        p.containerMenu = net.minecraft.world.inventory.ChestMenu.threeRows(42, p.getInventory());
        ManaEvents.onPlayerTick(new PlayerTickEvent.Post(p));
        p.containerMenu = p.inventoryMenu;
        p.releaseUsingItem();
        h.assertFalse(p.isUsingItem() || ManaDepletion.isDepleted(p), "A container interrupts and closing cannot resume a borrowed cast");
        hold(p, SpellCatalog.FIRE_BALL, ticks);
        p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.releaseUsingItem();
        h.assertTrue(!p.getData(ModAttachments.MANA).overcastLocked() && p.getData(ModAttachments.MANA).cooldown() == 0, "A different stack of the same staff is not the original hold");
        h.succeed();
    }

    private static void noGrowth(GameTestHelper h, Caster p, String label) {
        h.assertValueEqual(growth(p).earnedBonus(), 0, label + ": no permanent bonus");
        h.assertValueEqual(growth(p).paidInRound(), 0, label + ": no training spending");
        h.assertValueEqual(growth(p).roundMaximum(), 0, label + ": no active round");
        h.assertValueEqual(growth(p).cooldownRemaining(), 0, label + ": no growth cooldown");
        h.assertTrue(p.growthMessages.isEmpty() && p.privateSounds.isEmpty(), label + ": no growth notification");
    }
    private static void paid(GameTestHelper h, Caster p, int total, String label) {
        h.assertValueEqual(growth(p).paidInRound(), total, label + ": count committed spending once");
        h.assertValueEqual(growth(p).roundMaximum(), 100, label + ": original round capacity retained");
        h.assertValueEqual(growth(p).earnedBonus(), 0, label + ": no premature award");
    }
    /** Four real services pay 74; ordinary recovery during the wall's 40-tick cooldown leaves 28. */
    private static void prepareHealingFinish(GameTestHelper h, Caster p) {
        h.assertTrue(WaterMagic.cast(p), "Water released"); paid(h, p, 12, "Water");
        h.assertFalse(WaterMagic.cast(p), "Immediate duplicate blocked"); paid(h, p, 12, "Duplicate");
        awaitSpellCooldown(p);
        h.assertTrue(FireMagic.cast(p), "Fire released"); paid(h, p, 28, "Fire");
        awaitSpellCooldown(p);
        h.assertTrue(AirBurstSpell.cast(p), "Empty air pulse is still a released spell"); paid(h, p, 44, "Air");
        awaitSpellCooldown(p);
        var plan = wallSite(h, p);
        h.assertTrue(EarthWallSpell.cast(p), "Wall transaction committed"); paid(h, p, 74, "Earth");
        for (var pos : plan.positions())
            h.assertTrue(h.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get()), "Six actual wall cells");
        awaitSpellCooldown(p);
        p.setYRot(0); p.setXRot(0);
        h.assertTrue(HealingTarget.resolve(p).self(), "Empty or blocked ray selects injured caster");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 28, "Only configured ordinary recovery occurred");
    }
    private static void oneGainedMessage(GameTestHelper h, Caster p) {
        h.assertValueEqual(p.growthMessages.size(), 1, "Exactly one real owner growth chat packet");
        var packet = p.growthMessages.getFirst();
        h.assertFalse(packet.overlay(), "Growth does not overwrite the action bar");
        h.assertTrue(packet.content().getContents() instanceof TranslatableContents, "Growth remains translatable");
        var content = (TranslatableContents) packet.content().getContents();
        h.assertValueEqual(content.getKey(), "message.jobless_reincarnation.mana_growth.gained",
                "First award uses gained, not the reached-cap message");
        h.assertValueEqual(content.getArgs().length, 0, "Growth announces progress without revealing individual mana values");
    }
    private static void oneAward(GameTestHelper h, Caster p) {
        h.assertValueEqual(growth(p).earnedBonus(), 2, "One round earns exactly two");
        h.assertValueEqual(p.getData(ModAttachments.MANA).maximum(), 102, "Capacity increases immediately");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 4, "Growth never refills paid mana");
        h.assertValueEqual(growth(p).cooldownRemaining(), 6000, "Default online cooldown starts");
        h.assertValueEqual(growth(p).roundMaximum(), 0, "Completed round cleared");
        h.assertValueEqual(growth(p).paidInRound(), 0, "No spending banked into next round");
        oneGainedMessage(h, p);
        h.assertValueEqual(p.privateSounds.size(), 1, "Exactly one private sound packet");
    }

    @GameTest(template = "empty")
    public static void allFiveSpellsCompleteOneRoundWithoutRefillingOrDuplicateRewards(GameTestHelper h) {
        Caster p = caster(h); prepareHealingFinish(h, p);
        h.assertTrue(HealingSpell.cast(p), "Real healing fee crosses low threshold");
        h.assertValueEqual(p.getHealth(), 14F, "Ordinary healing effect remains intact"); oneAward(h, p);
        h.assertFalse(HealingSpell.cast(p), "Duplicate release cannot settle again");
        h.assertFalse(FireMagic.cast(p), "Spell switch cannot bypass cooldown"); oneAward(h, p);
        // The five spells this round releases; Water Shield is not part of it.
        for (SpellCatalog spell : List.of(SpellCatalog.WATER_BOLT, SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.HEALING, SpellCatalog.FIRE_BALL))
            h.assertValueEqual(spell.practiceCount(p), 1, "Each service practiced once");
        h.assertValueEqual(SpellCatalog.WATER_SHIELD.practiceCount(p), 0, "An unreleased spell gains no practice");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullyShapedWaterCountsItsActualLargerPayment(GameTestHelper h) {
        Caster p = caster(h);
        h.assertTrue(WaterMagic.cast(p, 1), "Fully shaped water admitted");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 76, "Full shape costs twenty-four");
        paid(h, p, 24, "Full water shape");
        h.assertValueEqual(WaterMagic.practiceCount(p), 1, "One shaped release is one practice"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void deniedWaterEntityAdmissionNeverStartsTraining(GameTestHelper h) {
        Caster p = caster(h); AtomicInteger calls = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> deny = e -> {
            if (e.getEntity() instanceof WaterBolt b && b.getOwner() == p) { calls.incrementAndGet(); e.setCanceled(true); }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, deny);
        try { h.assertFalse(WaterMagic.cast(p, 1), "Denied entity is not a paid release"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertValueEqual(calls.get(), 1, "Real admission hook reached");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "Denied water is free");
        noGrowth(h, p, "Denied water"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void deniedFireEntityAdmissionNeverStartsTraining(GameTestHelper h) {
        Caster p = caster(h); AtomicInteger calls = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> deny = e -> {
            if (e.getEntity() instanceof FireBallProjectile b && b.getOwner() == p) { calls.incrementAndGet(); e.setCanceled(true); }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, deny);
        try { h.assertFalse(FireMagic.cast(p), "Denied fire entity cannot settle"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertValueEqual(calls.get(), 1, "Real fire admission reached");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "Denied fire is free");
        noGrowth(h, p, "Denied fire"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void deniedWallTransactionRestoresAllCellsAndAwardsNothing(GameTestHelper h) {
        Caster p = caster(h); var plan = wallSite(h, p); AtomicInteger calls = new AtomicInteger();
        Consumer<BlockEvent.EntityMultiPlaceEvent> deny = e -> {
            if (e.getEntity() == p) {
                calls.incrementAndGet();
                h.assertValueEqual(e.getReplacedBlockSnapshots().size(), 6, "Real six-cell transaction");
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, deny);
        try { h.assertFalse(EarthWallSpell.cast(p), "Protection veto rolls back transaction"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertValueEqual(calls.get(), 1, "Protection saw tentative wall");
        for (var pos : plan.positions()) h.assertTrue(h.getLevel().isEmptyBlock(pos), "No partial wall");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "Rollback pays nothing");
        noGrowth(h, p, "Rolled-back wall"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void placementHookChangingModeCannotTurnAnUncommittedWallIntoGrowth(GameTestHelper h) {
        Caster p = caster(h); var plan = wallSite(h, p); AtomicInteger calls = new AtomicInteger();
        Consumer<BlockEvent.EntityMultiPlaceEvent> change = e -> {
            if (e.getEntity() == p) { calls.incrementAndGet(); p.getAbilities().instabuild = true; }
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityMultiPlaceEvent.class, change);
        try { h.assertFalse(EarthWallSpell.cast(p), "Changed payment mode invalidates transaction"); }
        finally { NeoForge.EVENT_BUS.unregister(change); }
        h.assertValueEqual(calls.get(), 1, "Actual mode-changing callback ran");
        for (var pos : plan.positions()) h.assertTrue(h.getLevel().isEmptyBlock(pos), "Changed mode leaves no wall");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "No fee after invalidation");
        noGrowth(h, p, "Invalidated wall"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void realItemEarlySneakSlotAndJournalCancellationsNeverCount(GameTestHelper h) {
        for (SpellCatalog spell : SpellCatalog.values()) {
            Caster p = caster(h); hold(p, spell, spell.chantTicks(p) - 1);
            h.assertTrue(p.isUsingItem(), "Actual item lifecycle began"); p.releaseUsingItem();
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "Early release costs nothing");
            noGrowth(h, p, "Early " + spell);
        }
        Caster p = caster(h);
        hold(p, SpellCatalog.FIRE_BALL, FireMagic.chantTicks(p));
        p.setShiftKeyDown(true); p.releaseUsingItem(); p.setShiftKeyDown(false); noGrowth(h, p, "Sneak");
        hold(p, SpellCatalog.FIRE_BALL, FireMagic.chantTicks(p));
        p.getInventory().selected = 1;
        p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.releaseUsingItem(); noGrowth(h, p, "Slot");
        hold(p, SpellCatalog.FIRE_BALL, FireMagic.chantTicks(p));
        SpellCasting.openJournal(p); p.releaseUsingItem(); noGrowth(h, p, "Journal");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "All cancellations preserve mana");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownAndUnaffordableSpellsNeverCountAnyOfFiveServices(GameTestHelper h) {
        for (SpellCatalog spell : SpellCatalog.values()) {
            Caster unknown = caster(h); unknown.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
            h.assertFalse(spell.cast(unknown, 0), "Unknown " + spell + " rejected"); noGrowth(h, unknown, "Unknown");
            Caster poor = caster(h); poor.setData(ModAttachments.MANA, new ManaData(1, 100, 0, 0));
            h.assertFalse(spell.cast(poor, 0), "Unaffordable " + spell + " rejected");
            h.assertValueEqual(poor.getData(ModAttachments.MANA).mana(), 1, "No partial payment"); noGrowth(h, poor, "Unaffordable");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullHealthAndInvalidHealingTargetsDoNotCreatePaidProgress(GameTestHelper h) {
        Caster p = caster(h); p.setHealth(p.getMaxHealth());
        h.assertFalse(HealingSpell.cast(p), "Full-health self cannot be farmed"); noGrowth(h, p, "Full health");
        p.setHealth(10);
        var enemy = EntityType.HUSK.create(h.getLevel());
        enemy.setPos(p.position().add(0, 0, 2)); enemy.setNoAi(true); enemy.setNoGravity(true); enemy.setHealth(8);
        h.getLevel().addFreshEntity(enemy);
        try {
            h.assertValueEqual(HealingTarget.resolve(p).target(), enemy, "Ray reaches actual hostile body");
            h.assertFalse(HealingSpell.cast(p), "Invalid target cannot fall back to injured caster");
            noGrowth(h, p, "Invalid target"); h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "No invalid heal fee");
        } finally { enemy.discard(); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeSuccessfulSpellsNeverEarnGrowth(GameTestHelper h) {
        for (SpellCatalog spell : SpellCatalog.values()) {
            Caster p = caster(h); p.getAbilities().instabuild = true;
            if (spell == SpellCatalog.EARTH_WALL) wallSite(h, p);
            if (spell == SpellCatalog.MIRE) {
                BlockPos center = new BlockPos(11, 2, 5);
                for (BlockPos offset : MireEntity.CELL_OFFSETS) {
                    BlockPos feet = center.offset(offset);
                    h.setBlock(feet.below(), Blocks.STONE);
                    h.setBlock(feet, Blocks.AIR);
                    h.setBlock(feet.above(), Blocks.AIR);
                }
                aim(p, h.absoluteVec(new Vec3(11.5, 2, 5.5)));
                h.assertTrue(MireGround.findBase(h.getLevel(), p) != null, "Creative Mire has a valid visible fixture");
            }
            h.assertTrue(spell.cast(p, 0), "Creative " + spell + " releases");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 100, "Creative spell is free"); noGrowth(h, p, "Creative " + spell);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void maximumPracticeCountersDoNotDisablePaidCapacityGrowth(GameTestHelper h) {
        Caster p = caster(h); CompoundTag spells = new CompoundTag();
        for (SpellCatalog spell : SpellCatalog.values()) spells.putInt(spell.id().toString(), MagicKnowledge.MAX_PRACTICE);
        CompoundTag root = new CompoundTag(); root.put("spells", spells);
        p.setData(ModAttachments.KNOWLEDGE, MagicKnowledge.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow());
        prepareHealingFinish(h, p);
        h.assertTrue(HealingSpell.cast(p), "Paid healing independent of saturated practice"); oneAward(h, p);
        for (SpellCatalog spell : SpellCatalog.values())
            h.assertValueEqual(spell.practiceCount(p), MagicKnowledge.MAX_PRACTICE, "Saturated practice unchanged");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualHDismissalCrossingLowManaDoesNotCompleteTraining(GameTestHelper h) {
        Caster p = caster(h); var plan = wallSite(h, p);
        h.assertTrue(EarthWallSpell.cast(p), "Real owned wall built"); awaitSpellCooldown(p);
        p.setYRot(0); p.setXRot(0);
        for (int i = 0; i < 3; i++) { h.assertTrue(AirBurstSpell.cast(p), "Valid empty pulse"); awaitSpellCooldown(p); }
        paid(h, p, 78, "Wall and three pulses");
        p.getData(ModAttachments.CASTING).select(EarthWallSpell.SPELL);
        aim(p, Vec3.atCenterOf(plan.positions().getFirst().above()));
        h.assertTrue(EarthWallDismantling.begin(p, 77), "Actual H session starts");
        int held = MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt();
        h.runAfterDelay(held, () -> {
            try {
                tickResources(p, held);
                int before = p.getData(ModAttachments.MANA).mana();
                h.assertTrue(before > 20 && before - MagicConfig.EARTH_DISMISS_COST.getAsInt() <= 20,
                        "Actual H fee crosses low boundary after normal recovery");
                h.assertTrue(EarthWallDismantling.release(p, 77), "Full server-time H removes wall");
                h.assertFalse(EarthWallDismantling.release(p, 77), "H nonce consumed once");
                h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), before - MagicConfig.EARTH_DISMISS_COST.getAsInt(), "One real H fee");
                paid(h, p, 78, "H excluded despite crossing low with enough prior spending");
                h.assertTrue(p.growthMessages.isEmpty(), "No growth notification");
                for (var pos : plan.positions()) h.assertTrue(h.getLevel().isEmptyBlock(pos), "Wall actually removed");
                h.succeed();
            } finally { EarthWallDismantling.cancel(p); }
        });
    }

    @GameTest(template = "empty")
    public static void cancelledHealingEffectStillCountsItsOneCommittedPayment(GameTestHelper h) {
        Caster p = caster(h); prepareHealingFinish(h, p); AtomicInteger calls = new AtomicInteger();
        Consumer<LivingHealEvent> cancel = e -> {
            if (e.getEntity() != p) return;
            calls.incrementAndGet(); oneAward(h, p);
            h.assertFalse(HealingSpell.cast(p), "Paid healing hook cannot reenter"); e.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, cancel);
        try { h.assertTrue(HealingSpell.cast(p), "Cancelled effect does not undo paid release"); }
        finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertValueEqual(calls.get(), 1, "One healing event");
        h.assertValueEqual(p.getHealth(), 10F, "Veto preserves health"); oneAward(h, p); h.succeed();
    }

    @GameTest(template = "empty")
    public static void healingHookCannotTurnReplacedManaIntoExtraPaidProgress(GameTestHelper h) {
        Caster p = caster(h); AtomicInteger calls = new AtomicInteger();
        Consumer<LivingHealEvent> replace = e -> {
            if (e.getEntity() != p) return;
            calls.incrementAndGet(); paid(h, p, 24, "Payment finalized before healing callback");
            p.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        };
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, replace);
        try { h.assertTrue(HealingSpell.cast(p), "Legal heal survives later external resource replacement"); }
        finally { NeoForge.EVENT_BUS.unregister(replace); }
        h.assertValueEqual(calls.get(), 1, "Replacement actually ran");
        h.assertValueEqual(growth(p).earnedBonus(), 0, "External zeroing is not hundred-mana spending");
        h.assertValueEqual(growth(p).paidInRound(), 24, "Only the pre-hook payment counted");
        ManaGrowth.prepare(p); noGrowth(h, p, "Next prepare cancels detached half-round"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeHealHookSwitchingToSurvivalCannotMakeFreeMagicPaid(GameTestHelper h) {
        Caster p = caster(h); p.getAbilities().instabuild = true; AtomicInteger calls = new AtomicInteger();
        Consumer<LivingHealEvent> change = e -> {
            if (e.getEntity() != p) return;
            calls.incrementAndGet(); noGrowth(h, p, "Free payment resolved before hook");
            p.getAbilities().instabuild = false; p.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
        };
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, change);
        try { h.assertTrue(HealingSpell.cast(p), "Creative effect remains valid"); }
        finally { NeoForge.EVENT_BUS.unregister(change); }
        h.assertValueEqual(calls.get(), 1, "External switch reached");
        h.assertValueEqual(p.getHealth(), 14F, "Ordinary creative heal applies"); noGrowth(h, p, "Later mode cannot fabricate payment");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void waterAdmissionUsesCurrentManaAndClearsReplacedHalfRound(GameTestHelper h) {
        Caster p = caster(h); h.assertTrue(WaterMagic.cast(p, 1), "Actual first shaped release"); awaitSpellCooldown(p);
        ManaData old = p.getData(ModAttachments.MANA), replacement = ManaData.full(100); AtomicInteger calls = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> replace = e -> {
            if (e.getEntity() instanceof WaterBolt b && b.getOwner() == p) { calls.incrementAndGet(); p.setData(ModAttachments.MANA, replacement); }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, replace);
        try { h.assertTrue(WaterMagic.cast(p), "New full object can afford current release"); }
        finally { NeoForge.EVENT_BUS.unregister(replace); }
        h.assertValueEqual(calls.get(), 1, "Real admission replaced object");
        h.assertValueEqual(old.mana(), 76, "Detached object is not charged");
        h.assertValueEqual(replacement.mana(), 88, "Current object pays"); paid(h, p, 12, "New half-round replaces old twenty-four");
        Caster denied = caster(h); ManaData detached = denied.getData(ModAttachments.MANA);
        AtomicInteger zeroCalls = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> zero = e -> {
            if (e.getEntity() instanceof WaterBolt b && b.getOwner() == denied) {
                zeroCalls.incrementAndGet(); denied.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, zero);
        try { h.assertFalse(WaterMagic.cast(denied), "New empty object cannot afford release"); }
        finally { NeoForge.EVENT_BUS.unregister(zero); }
        h.assertValueEqual(zeroCalls.get(), 1, "Empty replacement callback reached");
        h.assertValueEqual(detached.mana(), 100, "Rejected release does not charge old object");
        noGrowth(h, denied, "Admission-time resource loss"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void waterAdmissionRecursionAndEntityRemovalCannotDuplicateGrowth(GameTestHelper h) {
        Caster p = caster(h); AtomicInteger calls = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> recurse = e -> {
            if (e.getEntity() instanceof WaterBolt b && b.getOwner() == p) {
                calls.incrementAndGet(); h.assertFalse(WaterMagic.cast(p), "Same-player recursion rejected");
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, recurse);
        try { h.assertTrue(WaterMagic.cast(p), "Outer release commits"); }
        finally { NeoForge.EVENT_BUS.unregister(recurse); }
        h.assertValueEqual(calls.get(), 1, "Exactly one admission"); paid(h, p, 12, "Reentry guard");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 88, "One fee");
        Caster removed = caster(h); AtomicInteger removals = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> discard = e -> {
            if (e.getEntity() instanceof WaterBolt b && b.getOwner() == removed) { removals.incrementAndGet(); b.discard(); }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, discard);
        try { h.assertFalse(WaterMagic.cast(removed), "Removed admission is not a release"); }
        finally { NeoForge.EVENT_BUS.unregister(discard); }
        h.assertValueEqual(removals.get(), 1, "Real hook removed projectile");
        h.assertValueEqual(removed.getData(ModAttachments.MANA).mana(), 100, "Removed projectile free");
        noGrowth(h, removed, "Removed water"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void committedWallAwardsBeforeNeighborHookCanReplaceMana(GameTestHelper h) {
        Caster p = caster(h);
        // Five real basic water releases spend sixty; their 20-tick cooldowns finish before recovery.
        for (int i = 0; i < 5; i++) { h.assertTrue(WaterMagic.cast(p), "Actual preparation release"); awaitSpellCooldown(p); }
        paid(h, p, 60, "Pre-wall spending");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 40, "No synthetic low-mana setup");
        var plan = wallSite(h, p); AtomicInteger calls = new AtomicInteger();
        Consumer<BlockEvent.NeighborNotifyEvent> replace = e -> {
            if (!plan.positions().contains(e.getPos()) || calls.getAndIncrement() != 0) return;
            h.assertValueEqual(growth(p).earnedBonus(), 2, "Committed payment awarded before first neighbor hook");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 10, "Wall payment already finalized");
            h.assertValueEqual(p.getData(ModAttachments.MANA).maximum(), 102, "New capacity precedes notifications");
            h.assertFalse(h.getLevel().captureBlockSnapshots, "External callback runs after capture restored");
            p.setData(ModAttachments.MANA, new ManaData(0, 102, 0, 0));
        };
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, replace);
        try { h.assertTrue(EarthWallSpell.cast(p), "Committed wall survives later resource replacement"); }
        finally { NeoForge.EVENT_BUS.unregister(replace); }
        h.assertTrue(calls.get() > 0, "Actual neighbor notifications reached hook");
        h.assertValueEqual(growth(p).earnedBonus(), 2, "No lost or duplicate award after external mutation");
        oneGainedMessage(h, p);
        h.assertValueEqual(p.privateSounds.size(), 1, "One private sound");
        h.assertValueEqual(p.getData(ModAttachments.MANA).mana(), 0, "External resource mutation preserved");
        for (var pos : plan.positions()) h.assertTrue(h.getLevel().getBlockState(pos).is(ModContent.EARTH_WALL.get()), "Whole wall remains committed");
        h.succeed();
    }

    private ManaGrowthSpellGameTests() {}
}
