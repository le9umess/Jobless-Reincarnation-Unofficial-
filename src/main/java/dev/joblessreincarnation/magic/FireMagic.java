package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/** Server-authoritative release. Admission hooks run before payment with a reentry guard. */
public final class FireMagic {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "fire_ball");
    private static final ThreadLocal<Set<UUID>> RELEASING = ThreadLocal.withInitial(HashSet::new);
    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }
    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }
    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.FIRE_BALL_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }
    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                || RELEASING.get().contains(player.getUUID())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.fire.learn_first"), true); return false;
        }
        int cost = MagicConfig.FIRE_BALL_COST.getAsInt();
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
        return cast(player, mode, SpellTraining.formStage(player, SpellCatalog.FIRE_BALL));
    }
    static boolean cast(ServerPlayer player, SpellPayment mode, int formStage) {
        if (!canBeginCast(player, mode)) return false;
        var releases = RELEASING.get();
        releases.add(player.getUUID());
        try {
            var level = player.serverLevel();
            var wand = player.getMainHandItem();
            boolean creative = player.getAbilities().instabuild;
            FireBallProjectile ball = new FireBallProjectile(level, player);
            ball.setFormStage(formStage);
            var trainingOrigin = ball.position(); // Capture before admission hooks can move it.
            ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, MagicConfig.FIRE_BALL_SPEED.get().floatValue(), 0);
            if (!level.addFreshEntity(ball) || ball.isRemoved()) return false;
            ManaData mana = ManaGrowth.prepare(player);
            int cooldown = MagicConfig.FIRE_BALL_COOLDOWN.getAsInt();
            ManaData.Payment payment;
            if (!player.isAlive() || player.isSpectator() || player.serverLevel() != level || player.getMainHandItem() != wand
                    || !isLearned(player) || player.getAbilities().instabuild != creative
                    || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                    || (payment = mana.trySpendWithReceipt(MagicConfig.FIRE_BALL_COST.getAsInt(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode)) == null) {
                ball.discard(); return false;
            }
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
                            "message.jobless_reincarnation.training_improved_named", Component.translatable("spell.jobless_reincarnation.fire_ball"), stage.title()), true);
                }
            }
            if (!creative && mode == SpellPayment.NORMAL) ball.markTrainingShot(trainingOrigin);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.45F, 1.25F);
            return true;
        } finally {
            releases.remove(player.getUUID());
            if (releases.isEmpty()) RELEASING.remove();
        }
    }
    private FireMagic() {}
}
