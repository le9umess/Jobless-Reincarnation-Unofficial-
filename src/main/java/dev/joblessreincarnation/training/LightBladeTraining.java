package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.SchoolAnimations;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.magic.SpellBurstOptions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import yesman.epicfight.world.damagesource.StunType;

@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class LightBladeTraining {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void acceptedDamage(LivingDamageEvent.Post event) {
        if (!(event.getNewDamage() > 0 || event.getReduction(net.neoforged.neoforge.common.damagesource.DamageContainer.Reduction.ABSORPTION) > 0)
                || !(event.getSource() instanceof EpicFightDamageSource hit)
                || !(hit.getEntity() instanceof ServerPlayer player) || hit.getDirectEntity() != player
                || !player.hasData(ModAttachments.SCHOOL_COMBAT) || !player.hasData(ModAttachments.SWORD_TRAINING)) return;
        LivingEntity victim = event.getEntity();
        var combat = player.getData(ModAttachments.SCHOOL_COMBAT);
        if (hit.getAnimation() == SchoolAnimations.LIGHT_CUT) {
            if (!combat.isPracticeCut() && combat.takeLightContact(player)) {
                SchoolCues.send(player.serverLevel(), SpellBurstOptions.LIGHT_CUT, null,
                        victim.position().add(0, victim.getBbHeight() * .62, 0));
            }
            return;
        }
        if (hit.getAnimation() != SchoolAnimations.ARM_STRIKE || hit.getStunType() != StunType.SHORT
                || !(victim instanceof Enemy || victim instanceof ServerPlayer) || !victim.isAlive()
                || !combat.allowsContact(player, SchoolForm.ARM_STRIKE, victim)) return;
        var defender = EpicFightCapabilities.getEntityPatch(victim, LivingEntityPatch.class);
        boolean interrupted = defender != null && (defender.isStunned() || defender.getEntityState().hurt());
        // Everyone nearby sees the interruption; the striker always does (the cue overrides their particle setting).
        // Stage 1: a later strike was caught; an earlier one of the same attack may already have hit (GPT 11-07 retest).
        boolean later = combat.takeArmFollowUp();
        if (interrupted) SchoolCues.send(player.serverLevel(), new SpellBurstOptions(SpellBurstOptions.ARM_BREAK, player.getId(), later ? 1 : 0),
                player, armContact(player, victim));
        if (interrupted && player.getData(ModAttachments.SWORD_TRAINING).recordLightInsight()) {
            player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.light.insight"));
        }
    }

    /** Where the blade meets the arm: at the victim's arm height, on the side facing the striker. */
    public static net.minecraft.world.phys.Vec3 armContact(LivingEntity striker, LivingEntity victim) {
        var towards = striker.position().subtract(victim.position()).multiply(1, 0, 1);
        var side = towards.lengthSqr() < 1.0E-6 ? net.minecraft.world.phys.Vec3.ZERO : towards.normalize().scale(victim.getBbWidth() * 0.5);
        return victim.position().add(side).add(0, victim.getBbHeight() * .62, 0);
    }

    /** The page is the student's own observation. A page borrowed from another player cannot supply the insight. */
    public static boolean copyStudyPage(ServerPlayer player) {
        if (!player.hasData(ModAttachments.SWORD_TRAINING) || !player.getData(ModAttachments.SWORD_TRAINING).hasLightInsight()) return false;
        ItemStack paper = player.getOffhandItem();
        if (!paper.is(net.minecraft.world.item.Items.PAPER)) return false;
        if (player.getInventory().countItem(ModContent.LIGHT_BLADE_NOTE.get()) > 0) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.light.already_copied"), true);
            return true;
        }
        var page = new ItemStack(ModContent.LIGHT_BLADE_NOTE.get());
        if (paper.getCount() == 1 && !player.getAbilities().instabuild) {
            player.setItemInHand(InteractionHand.OFF_HAND, page);
        } else {
            if (player.getInventory().getFreeSlot() < 0 || !player.addItem(page)) {
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.school.need_space"), true);
                return true;
            }
            if (!player.getAbilities().instabuild) paper.shrink(1);
        }
        player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.light.copied"));
        return true;
    }

    public static @Nullable PracticeDummyEntity aimedDummy(ServerPlayer player) {
        if (!player.getMainHandItem().is(ModContent.PRACTICE_WOODEN_SWORD.get())) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 ray = player.getViewVector(1).scale(player.entityInteractionRange());
        Vec3 end = eye.add(ray);
        var block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, end,
                player.getBoundingBox().expandTowards(ray).inflate(1), entity -> entity.isPickable() && !entity.isSpectator(),
                eye.distanceToSqr(end));
        if (hit != null && hit.getEntity() instanceof PracticeDummyEntity dummy && dummy.isAlive() && dummy.hasSupport()
                && player.canInteractWithEntity(dummy, 0) && player.hasLineOfSight(dummy)) return dummy;
        return null;
    }

    public static void completeCut(ServerPlayer player, PracticeDummyEntity dummy) {
        var combat = player.getData(ModAttachments.SCHOOL_COMBAT);
        if (combat.currentTrialDummy(player) != dummy || combat.lightTier() != 3 || !combat.takeLightContact(player)) return;
        if (SchoolSkills.completeDrill(player, SchoolForm.LIGHT_BLADE)) {
            player.serverLevel().playSound(null, dummy.blockPosition(), SoundEvents.ARMOR_STAND_HIT, SoundSource.BLOCKS, .8F, 1.1F);
        }
    }

    private LightBladeTraining() {}
}
