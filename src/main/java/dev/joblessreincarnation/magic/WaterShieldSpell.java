package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Water Shield, the second water lesson: one short, passable screen that stops arrows. Server-authoritative release
 * in the same order as the other spells: the screen is admitted first, then paid for, and a failed release pays
 * nothing and keeps any earlier screen.
 */
public final class WaterShieldSpell {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "water_shield");
    /** Blocks in front of the caster where the screen stands. */
    public static final double DISTANCE = 2.0;
    /** Older screens of the same caster are looked for within this distance when a new one replaces them. */
    private static final double REPLACE_RANGE = 64.0;
    private static final ThreadLocal<Set<UUID>> RELEASING = ThreadLocal.withInitial(HashSet::new);

    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }

    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }

    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.WATER_SHIELD_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }

    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                || RELEASING.get().contains(player.getUUID())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.water_shield.learn_first"), true);
            return false;
        }
        int cost = MagicConfig.WATER_SHIELD_COST.getAsInt();
        ManaData mana = ManaGrowth.prepare(player);
        if (!mana.canPay(cost, player.getAbilities().instabuild, mode) || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
            if (mana.cooldown() == 0 && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()))
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.not_enough_mana", cost), true);
            return false;
        }
        return true;
    }

    public static boolean cast(ServerPlayer player) {
        return cast(player, SpellPayment.NORMAL);
    }
    static boolean cast(ServerPlayer player, SpellPayment mode) {
        if (!canBeginCast(player, mode)) return false;
        ServerLevel level = player.serverLevel();
        Vec3 base = findBase(level, player);
        if (base == null) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.water_shield.ground"), true);
            return false;
        }
        var releases = RELEASING.get();
        releases.add(player.getUUID());
        try {
            var wand = player.getMainHandItem();
            boolean creative = player.getAbilities().instabuild;
            List<WaterShieldEntity> earlier = level.getEntitiesOfClass(WaterShieldEntity.class,
                    player.getBoundingBox().inflate(REPLACE_RANGE), shield -> player.getUUID().equals(shield.ownerId()));
            WaterShieldEntity shield = new WaterShieldEntity(level, player, base, player.getYRot());
            if (!level.addFreshEntity(shield) || shield.isRemoved()) {
                shield.discard();
                return false;
            }
            ManaData mana = ManaGrowth.prepare(player);
            int cooldown = MagicConfig.WATER_SHIELD_COOLDOWN.getAsInt();
            ManaData.Payment payment;
            if (!player.isAlive() || player.isSpectator() || player.serverLevel() != level || player.getMainHandItem() != wand
                    || !isLearned(player) || player.getAbilities().instabuild != creative
                    || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                    || (payment = mana.trySpendWithReceipt(MagicConfig.WATER_SHIELD_COST.getAsInt(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode)) == null) {
                shield.discard();
                return false;
            }
            // Only a paid release replaces the caster's earlier screen.
            for (WaterShieldEntity old : earlier) old.discard();
            ManaGrowth.onSpellPaid(player, payment);
            player.syncData(ModAttachments.MANA);
            player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), cooldown);
            if (!creative && mode == SpellPayment.NORMAL) {
                MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
                SpellTraining.Stage previous = SpellTraining.stage(knowledge.practiceCount(SPELL));
                if (knowledge.practice(SPELL)) {
                    player.syncData(ModAttachments.KNOWLEDGE);
                    var stage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                    if (stage != previous) player.displayClientMessage(Component.translatable(
                            "message.jobless_reincarnation.training_improved_named", Component.translatable("spell.jobless_reincarnation.water_shield"), stage.title()), true);
                }
            }
            level.playSound(null, base.x, base.y, base.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.PLAYERS, 0.65F, 1.10F);
            return true;
        } finally {
            releases.remove(player.getUUID());
            if (releases.isEmpty()) RELEASING.remove();
        }
    }

    /**
     * Ground for the screen, {@link #DISTANCE} blocks ahead along the caster's horizontal facing: a sturdy, dry top
     * face with room above it. Level with the caster's feet first, then one step up, then one step down, so a single
     * step neither buries the screen in a block nor leaves it hanging over a drop.
     */
    @Nullable
    static Vec3 findBase(ServerLevel level, ServerPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        double x = player.getX() - Math.sin(yaw) * DISTANCE;
        double z = player.getZ() + Math.cos(yaw) * DISTANCE;
        for (int step : new int[] {0, 1, -1}) {
            BlockPos feet = BlockPos.containing(x, player.getY() + step, z);
            BlockPos support = feet.below();
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) continue;
            if (!level.getFluidState(support).isEmpty() || !level.getFluidState(feet).isEmpty()) continue;
            if (level.getBlockState(feet).isCollisionShapeFullBlock(level, feet)) continue;
            return new Vec3(x, feet.getY(), z);
        }
        return null;
    }

    private WaterShieldSpell() {}
}
