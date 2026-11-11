package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/** One modest recovery, using vanilla healing hooks without potion inversion or status removal. */
public final class HealingSpell {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "healing");
    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }
    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }
    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.HEALING_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }
    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.healing.learn_first"), true);
            return false;
        }
        int cost = MagicConfig.HEALING_COST.getAsInt();
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
        HealingTarget.Plan plan = HealingTarget.resolve(player);
        if (!plan.valid()) {
            player.displayClientMessage(plan.reason().message(), true);
            return false;
        }
        LivingEntity target = (LivingEntity) plan.target();
        boolean creative = player.getAbilities().instabuild;
        int cooldown = MagicConfig.HEALING_COOLDOWN.getAsInt();
        ManaData mana = ManaGrowth.prepare(player);
        ManaData.Payment payment = mana.trySpendWithReceipt(MagicConfig.HEALING_COST.getAsInt(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode);
        if (payment == null) return false;
        ManaGrowth.onSpellPaid(player, payment);
        // Set both cooldowns before invoking an external healing hook; recursive casts cannot spend again.
        player.syncData(ModAttachments.MANA);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), cooldown);
        target.heal(MagicConfig.HEALING_AMOUNT.get().floatValue());
        // A hook may reduce or cancel recovery, but cannot unrelease the already paid spell.
        if (!creative && mode == SpellPayment.NORMAL) {
            MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
            SpellTraining.Stage previous = SpellTraining.stage(knowledge.practiceCount(SPELL));
            if (knowledge.practice(SPELL)) {
                player.syncData(ModAttachments.KNOWLEDGE);
                SpellTraining.Stage stage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                if (stage != previous) player.displayClientMessage(Component.translatable(
                        "message.jobless_reincarnation.training_improved_named",
                        Component.translatable("spell.jobless_reincarnation.healing"), stage.title()), true);
            }
        }
        var center = target.getBoundingBox().getCenter();
        player.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                center.x, center.y, center.z, 5, 0.18, 0.25, 0.18, 0.0);
        player.serverLevel().playSound(null, center.x, center.y, center.z,
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.35F, 1.35F);
        return true;
    }
    private HealingSpell() {}
}
