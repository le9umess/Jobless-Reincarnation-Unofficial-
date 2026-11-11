package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative, paid ground control; failed releases preserve the previous mire. */
public final class MireSpell {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mire");
    /** Eye-to-ground-hit reach; the fixed patch may extend beyond that point. */
    public static final int RANGE = 12;
    private static final ThreadLocal<Set<UUID>> RELEASING = ThreadLocal.withInitial(HashSet::new);

    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }

    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }

    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.MIRE_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }

    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                || RELEASING.get().contains(player.getUUID())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.mire.learn_first"), true);
            return false;
        }
        int cost = MagicConfig.MIRE_COST.getAsInt();
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
        Vec3 base = MireGround.findBase(level, player);
        if (base == null) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.mire.ground"), true);
            return false;
        }
        var releases = RELEASING.get();
        releases.add(player.getUUID());
        try {
            var wand = player.getMainHandItem();
            boolean creative = player.getAbilities().instabuild;
            MireEntity mire = new MireEntity(level, player, base);
            if (!level.addFreshEntity(mire) || mire.isRemoved()) {
                mire.discard();
                return false;
            }
            ManaData mana = ManaGrowth.prepare(player);
            int cooldown = MagicConfig.MIRE_COOLDOWN.getAsInt();
            ManaData.Payment payment;
            if (!player.isAlive() || player.isRemoved() || player.isSpectator() || player.serverLevel() != level
                    || player.getMainHandItem() != wand || !wand.is(ModContent.TRAINING_STAFF.get())
                    || !mire.isAlive() || mire.isRemoved() || mire.level() != level
                    || level.getEntity(mire.getUUID()) != mire || !mire.position().equals(base)
                    || !MireGround.isValid(level, base)
                    || !isLearned(player) || player.getAbilities().instabuild != creative
                    || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                    || (payment = mana.trySpendWithReceipt(MagicConfig.MIRE_COST.getAsInt(), MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode)) == null) {
                mire.discard();
                return false;
            }
            // Publish the paid field before removing the previous identity; failures above keep the old field.
            MireFields.activate(mire);
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
                            "message.jobless_reincarnation.training_improved_named", Component.translatable("spell.jobless_reincarnation.mire"), stage.title()), true);
                }
            }
            MireVisuals.cast(level, base);
            return true;
        } finally {
            releases.remove(player.getUUID());
            if (releases.isEmpty()) RELEASING.remove();
        }
    }

    private MireSpell() {}
}
