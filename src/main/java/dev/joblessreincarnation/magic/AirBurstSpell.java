package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One server-owned forward impulse. It has no projectile, damage call or terrain mutation. */
public final class AirBurstSpell {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "air_burst");
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "air_burst_immune"));
    private static final double MIN_ALIGNMENT = Math.cos(Math.toRadians(30.0));

    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }
    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }
    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.AIR_BURST_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }
    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.air_burst.learn_first"), true);
            return false;
        }
        int cost = MagicConfig.AIR_BURST_COST.getAsInt();
        ManaData mana = ManaGrowth.prepare(player);
        if (!mana.canPay(cost, player.getAbilities().instabuild, mode) || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
            if (mana.cooldown() == 0 && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()))
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.not_enough_mana", cost), true);
            return false;
        }
        return true;
    }

    static boolean eligible(ServerPlayer player, LivingEntity target) {
        if (!dev.joblessreincarnation.encounter.PaulDuel.mayAffect(player,target)) return false;
        if (dev.joblessreincarnation.encounter.PaulDuel.spellTarget(player,target)) return !target.isPassenger() && !target.isVehicle();
        return target instanceof Mob && target instanceof Enemy && target.level() == player.level()
                && target.isAlive() && !target.isRemoved() && !target.isSpectator() && !target.isInvulnerable()
                && target.isPushable() && !target.isPassenger() && !target.isVehicle()
                && !target.getType().is(IMMUNE)
                && !(target instanceof OwnableEntity owned && owned.getOwnerUUID() != null)
                && !player.isAlliedTo(target) && !target.isAlliedTo(player);
    }

    /** The same center defines range, cone and obstruction; no target ray requests an unloaded chunk. */
    static boolean visible(ServerPlayer player, Vec3 endpoint) {
        Vec3 eye = player.getEyePosition();
        var level = player.serverLevel();
        BlockPos unloaded = BlockGetter.traverseBlocks(eye, endpoint, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        return unloaded == null && level.clip(new ClipContext(eye, endpoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    static boolean inPulse(ServerPlayer player, LivingEntity target) {
        Vec3 offset = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        double distanceSquared = offset.lengthSqr();
        double range = MagicConfig.AIR_BURST_RANGE.getAsInt();
        // Match vanilla's exact small-ratio threshold; it randomizes smaller horizontal knockback ratios.
        double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
        return Double.isFinite(distanceSquared) && distanceSquared > 1.0E-8 && distanceSquared <= range * range
                && dx * dx + dz * dz >= 1.0E-5F
                && offset.dot(player.getLookAngle()) / Math.sqrt(distanceSquared) >= MIN_ALIGNMENT
                && visible(player, target.getBoundingBox().getCenter());
    }

    static List<Mob> targets(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        return player.serverLevel().getEntitiesOfClass(Mob.class,
                new AABB(eye, eye).inflate(MagicConfig.AIR_BURST_RANGE.getAsInt()),
                target -> eligible(player, target) && inPulse(player, target)).stream()
                .sorted(Comparator.comparingDouble((Mob target) -> target.getBoundingBox().getCenter().distanceToSqr(eye))
                        .thenComparingInt(Mob::getId))
                .limit(MagicConfig.AIR_BURST_MAX_TARGETS.getAsInt()).toList();
    }

    /** Even an empty or fully resisted pulse is a released spell; payment precedes every effect hook. */
    public static boolean cast(ServerPlayer player) {
        return cast(player, SpellPayment.NORMAL);
    }
    static boolean cast(ServerPlayer player, SpellPayment mode) {
        if (!canBeginCast(player, mode)) return false;
        List<Mob> targets = targets(player);
        boolean creative = player.getAbilities().instabuild;
        int cooldown = MagicConfig.AIR_BURST_COOLDOWN.getAsInt();
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment payment = mana.trySpendWithReceipt(MagicConfig.AIR_BURST_COST.getAsInt(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode);
        if (payment == null) return false;
        ManaGrowth.onSpellPaid(player, payment);
        player.syncData(ModAttachments.MANA);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), cooldown);
        if (!creative && mode == SpellPayment.NORMAL) {
            MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
            SpellTraining.Stage previous = SpellTraining.stage(knowledge.practiceCount(SPELL));
            if (knowledge.practice(SPELL)) {
                player.syncData(ModAttachments.KNOWLEDGE);
                SpellTraining.Stage stage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                if (stage != previous) player.displayClientMessage(Component.translatable(
                        "message.jobless_reincarnation.training_improved_named",
                        Component.translatable("spell.jobless_reincarnation.air_burst"), stage.title()), true);
            }
        }
        for (Mob target : targets) {
            // A previous target's hook can remove, tame, relocate or mount later targets.
            if (eligible(player, target) && inPulse(player, target)) target.knockback(MagicConfig.AIR_BURST_STRENGTH.get(),
                    player.getX() - target.getX(), player.getZ() - target.getZ());
        }
        pulseEffects(player);
        return true;
    }

    private static void pulseEffects(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(), reach = player.getLookAngle().scale(MagicConfig.AIR_BURST_RANGE.getAsInt());
        // Count 0 hands the offsets to the client as they are: the wave's direction and reach, the same cone the push uses.
        // The pulse has no entity to fall back on, so its outline goes past the particle setting; its dust and flecks still obey it.
        SpellBurstOptions wave = new SpellBurstOptions(SpellBurstOptions.AIR_WAVE);
        for (ServerPlayer viewer : player.serverLevel().players()) {
            if (viewer.distanceToSqr(eye) <= 64.0 * 64.0) player.serverLevel().sendParticles(viewer, wave, true, eye.x, eye.y - 0.1, eye.z, 0, reach.x, reach.y, reach.z, 1.0);
        }
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 0.65F, 1.3F);
    }
    private AirBurstSpell() {}
}
