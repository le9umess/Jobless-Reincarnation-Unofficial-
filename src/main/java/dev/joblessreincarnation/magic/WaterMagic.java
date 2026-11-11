package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.npc.SylphietteFirstLesson;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/** Server-authoritative release; admission hooks run before payment under a per-player reentry guard. */
public final class WaterMagic {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "water_bolt");
    private static final ThreadLocal<Set<UUID>> RELEASING = ThreadLocal.withInitial(HashSet::new);

    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }

    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }

    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.WATER_BOLT_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }

    public static boolean canBeginCast(ServerPlayer player) {
        return canCast(player, WaterControl.manaCost(0.0F), SpellPayment.NORMAL);
    }

    private static boolean canCast(ServerPlayer player, int cost, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                || RELEASING.get().contains(player.getUUID())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.learn_first"), true);
            return false;
        }
        ManaData mana = ManaGrowth.prepare(player);
        boolean creative = player.getAbilities().instabuild;
        if (!mana.canPay(cost, creative, mode) || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
            if (mana.cooldown() == 0 && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.not_enough_mana", cost), true);
            }
            return false;
        }
        return true;
    }

    /** Unshaped cast for server-side callers. Item controls check chanting before calling. */
    public static boolean cast(ServerPlayer player) {
        return cast(player, 0.0F);
    }

    /** Recheck state and the current cost of the fixed charge after admission; failed releases pay nothing. */
    public static boolean cast(ServerPlayer player, float requestedCharge) {
        return cast(player, requestedCharge, SpellPayment.NORMAL);
    }
    static boolean cast(ServerPlayer player, float requestedCharge, SpellPayment mode) {
        return cast(player, requestedCharge, mode, SpellTraining.formStage(player, SpellCatalog.WATER_BOLT));
    }
    static boolean cast(ServerPlayer player, float requestedCharge, SpellPayment mode, int formStage) {
        float charge = WaterControl.normalize(requestedCharge);
        if (!canCast(player, WaterControl.manaCost(charge), mode)) return false;
        var releases = RELEASING.get();
        UUID casterId = player.getUUID();
        releases.add(casterId);
        try {
            var level = player.serverLevel();
            var wand = player.getMainHandItem();
            boolean creative = player.getAbilities().instabuild;
            WaterBolt bolt = new WaterBolt(level, player);
            bolt.setFormStage(formStage);
            bolt.setCharge(charge);
            bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, WaterControl.speed(charge), 0.0F);
            // Preserve the release-origin snapshot even if an admission hook moves the projectile.
            if (!creative && mode == SpellPayment.NORMAL) bolt.markTrainingShot();
            if (!level.addFreshEntity(bolt) || bolt.isRemoved()) {
                bolt.discard();
                return false;
            }
            if (!player.isAlive() || player.isSpectator() || player.serverLevel() != level
                    || player.getMainHandItem() != wand || !wand.is(ModContent.TRAINING_STAFF.get()) || !isLearned(player)
                    || player.getAbilities().instabuild != creative
                    || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                bolt.discard();
                return false;
            }
            // Admission may replace the attachment or reload balance values; charge itself remains fixed.
            ManaData mana = ManaGrowth.prepare(player);
            int cost = WaterControl.manaCost(charge);
            int cooldown = MagicConfig.WATER_BOLT_COOLDOWN.getAsInt();
            ManaData.Payment payment = mana.trySpendWithReceipt(cost, MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode);
            if (payment == null) {
                bolt.discard();
                return false;
            }
            ManaGrowth.onSpellPaid(player, payment);
            player.syncData(ModAttachments.MANA);
            player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), cooldown);
            if (!creative && mode == SpellPayment.NORMAL) {
                MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
                SpellTraining.Stage previousStage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                if (knowledge.practice(SPELL)) {
                    player.syncData(ModAttachments.KNOWLEDGE);
                    SpellTraining.Stage stage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                    if (stage != previousStage) {
                        player.displayClientMessage(Component.translatable("message.jobless_reincarnation.training_improved", stage.title()), true);
                    }
                }
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.PLAYERS, 0.65F, 1.35F - charge * 0.25F);
            if (!player.getAbilities().instabuild && !player.isFakePlayer()) SylphietteFirstLesson.onWaterCast(player);
            return true;
        } finally {
            releases.remove(casterId);
            if (releases.isEmpty()) RELEASING.remove();
        }
    }

    private WaterMagic() {}
}
