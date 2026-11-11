package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.nbt.CompoundTag;
import yesman.epicfight.network.client.CPSkillRequest;
import org.jetbrains.annotations.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import yesman.epicfight.api.event.types.player.SkillCastEvent;
import yesman.epicfight.client.events.engine.ControlEngine;
import yesman.epicfight.client.input.EpicFightKeyMappings;
import yesman.epicfight.skill.*;
import yesman.epicfight.skill.modules.ChargeableSkill;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

public final class LightBladeSkill extends Skill implements ChargeableSkill, ChargeBarSkill {
    public static final String PRESS_ID = "jr_light_press";
    public static long pressId(@Nullable CompoundTag tag) { return tag == null ? 0 : tag.getLong(PRESS_ID); }

    public static CPSkillRequest request(SkillSlot slot, CPSkillRequest.WorkType type, long press) {
        var data = new CompoundTag();
        data.putLong(PRESS_ID, press);
        return new CPSkillRequest(slot, type, data);
    }

    private long localSerial, localPress;
    private net.minecraft.world.item.ItemStack localWeapon;
    private int localSlot;

    static final class Builder extends SkillBuilder<Builder> {
        Builder() { super(LightBladeSkill::new); }
    }
    private LightBladeSkill(Builder builder) { super(builder); }

    @Override public ResourceLocation getSkillTexture() {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/gui/skills/light_blade.png");
    }

    @OnlyIn(Dist.CLIENT)
    @Override public KeyMapping getKeyMapping() { return EpicFightKeyMappings.WEAPON_INNATE_SKILL; }

    @OnlyIn(Dist.CLIENT)
    @Override public void gatherHoldArguments(SkillContainer c, ControlEngine engine, CompoundTag tag) {
        localSerial = localSerial == Long.MAX_VALUE ? 1 : localSerial + 1;
        localPress = localSerial;
        localWeapon = c.getExecutor().getOriginal().getMainHandItem();
        localSlot = c.getExecutor().getOriginal().getInventory().selected;
        // EF's native HOLD_START uses a null tag. The outgoing packet hook adds it in that case.
        if (tag != null) tag.putLong(PRESS_ID, localPress);
    }

    @OnlyIn(Dist.CLIENT)
    public long localPressId() { return localPress; }

    @OnlyIn(Dist.CLIENT)
    @Override public void gatherArguments(SkillContainer c, ControlEngine engine, CompoundTag tag) { if (tag != null) tag.putLong(PRESS_ID, localPress); }

    @OnlyIn(Dist.CLIENT)
    public long takeLocalPressId() {
        long id = localPress;
        localPress = 0;
        localWeapon = null;
        return id;
    }

    @OnlyIn(Dist.CLIENT)
    public void cancelLocalIfInvalid(yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch patch, boolean lostFocus) {
        if (localPress == 0) return;
        var player = patch.getOriginal();
        boolean earlyRelease = !patch.isHoldingSkill(this) && !yesman.epicfight.api.client.input.InputManager
                .isActionActive(yesman.epicfight.api.client.input.action.EpicFightInputAction.WEAPON_INNATE_SKILL);
        // Too tired for even the first tier: say so at once instead of charging for nothing.
        boolean tired = patch.getStamina() < LightBladeTiming.staminaCost(1);
        if (!tired && !lostFocus && patch.isEpicFightMode() && player.isAlive() && !player.isSpectator() && !earlyRelease
                && player.getMainHandItem() == localWeapon && player.getInventory().selected == localSlot) return;
        var cancel = request(SchoolSkills.Slot.JOBLESS_PREPARED_FORM, CPSkillRequest.WorkType.CANCEL, takeLocalPressId());
        yesman.epicfight.network.EpicFightNetworkManager.sendToServer(cancel);
        if (patch.isHoldingSkill(this)) patch.resetHolding();
        if (tired) player.displayClientMessage(Component.translatable("message.jobless_reincarnation.light.no_stamina"), true);
    }

    @Override public net.minecraft.network.protocol.common.custom.CustomPacketPayload getExecutionPacket(SkillContainer container, CompoundTag arguments) {
        var packet = super.getExecutionPacket(container, arguments);
        if (container.getExecutor().isLogicalClient()) {
            localPress = 0;
            localWeapon = null;
        }
        return packet;
    }

    @Override public int getMinChargingTicks() { return 0; } // Send short releases promptly; the server decides if they produce a cut.
    @Override public int getMaxChargingTicks() { return LightBladeTiming.FULL_TICKS; }
    @Override public int getAllowedMaxChargingTicks() { return LightBladeTiming.MAX_HOLD_TICKS + 40; }

    // The sword charge bar: one gem per tier, and the tiers the stamina cannot pay for shown as such.
    @Override public int[] chargeTiers() {
        return new int[] {LightBladeTiming.TIER_ONE_TICKS, LightBladeTiming.TIER_TWO_TICKS, LightBladeTiming.FULL_TICKS};
    }
    @Override public int affordableTier(PlayerPatch<?> patch, int reached) { return LightBladeTiming.affordableTier(reached, patch.getStamina()); }

    @Override public boolean canExecute(SkillContainer container) {
        var patch = container.getExecutor();
        var p = patch.getOriginal();
        if (container.getSlot() != SchoolSkills.Slot.JOBLESS_PREPARED_FORM || !patch.isEpicFightMode()
                || !p.isAlive() || p.isSpectator() || p.getAbilities().instabuild || p.isUsingItem()
                || !SchoolForm.isSword(p.getMainHandItem())) return false;
        if (!(p instanceof ServerPlayer player)) return true;
        if (player.containerMenu != player.inventoryMenu || !player.hasData(ModAttachments.SWORD_TRAINING)
                || !BattleAura.hasBodyGuard(player) || !player.getData(ModAttachments.SCHOOL_COMBAT).recovered(player)) return false;
        var training = player.getData(ModAttachments.SWORD_TRAINING);
        if (!training.hasLightInsight() || !training.hasPractice(SchoolForm.LIGHT_BLADE)) return false;
        return training.mastered(SchoolForm.LIGHT_BLADE)
                ? patch.getPlayerSkills().hasLearned(this) : LightBladeTraining.aimedDummy(player) != null;
    }

    /**
     * Epic Fight refused the press on this client before any hold began. When stamina was the reason, say so (user 11-07);
     * this runs once per real Light Blade attempt: a separate key's press, or a shared attack key held past its threshold.
     */
    @Override public void validationFeedback(SkillContainer container) {
        var patch = container.getExecutor();
        if (patch.isLogicalClient() && patch.getStamina() < LightBladeTiming.staminaCost(1) && canExecute(container) && isExecutableState(patch))
            patch.getOriginal().displayClientMessage(Component.translatable("message.jobless_reincarnation.light.no_stamina"), true);
    }

    @Override public boolean resourcePredicate(PlayerPatch<?> patch, SkillCastEvent event) {
        return patch.getStamina() >= LightBladeTiming.staminaCost(1);
    }

    @Override public void startHolding(SkillContainer container) {
        if (!(container.getExecutor() instanceof ServerPlayerPatch patch)) return;
        var p = patch.getOriginal();
        var trial = !p.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.LIGHT_BLADE);
        var dummy = trial ? LightBladeTraining.aimedDummy(p) : null;
        if (trial && dummy == null) return;
        if (p.getData(ModAttachments.LIGHT_BLADE_SESSION).begin(p, dummy)) patch.playAnimationSynchronized(SchoolAnimations.LIGHT_GATHER, 0);
    }

    private boolean valid(ServerPlayerPatch patch, LightBladeState.Hold hold) {
        var p = patch.getOriginal();
        return hold != null && patch.isEpicFightMode() && p.containerMenu == p.inventoryMenu && !p.isUsingItem()
                && !patch.getEntityState().hurt() && !patch.isStunned() && BattleAura.holdsGathering(p, hold.gathering())
                && p.level().getGameTime() - hold.startedAt() < LightBladeTiming.MAX_HOLD_TICKS;
    }

    @Override public void holdTick(SkillContainer container) {
        ChargeableSkill.super.holdTick(container);
        if (!(container.getExecutor() instanceof ServerPlayerPatch patch)) return;
        var p = patch.getOriginal();
        var state = p.getData(ModAttachments.LIGHT_BLADE_SESSION);
        if (!valid(patch, state.current())) {
            abort(container);
            return;
        }
        if (state.startLoop(p.level().getGameTime())) patch.playAnimationSynchronized(SchoolAnimations.LIGHT_HOLD, 0);
    }

    @Override public void executeOnServer(SkillContainer container, CompoundTag arguments) {
        var patch = container.getServerExecutor();
        var p = patch.getOriginal();
        var state = p.getData(ModAttachments.LIGHT_BLADE_SESSION);
        if (!patch.isHoldingSkill(this) || !state.matches(pressId(arguments))) return;
        var hold = state.current();
        if (!valid(patch, hold)) { abort(container); return; }
        // A charge past what the stamina can pay for cuts at the highest tier it can; none at all, then it says so.
        int reached = LightBladeTiming.tierAt(p.level().getGameTime() - hold.startedAt());
        int tier = LightBladeTiming.affordableTier(reached, patch.getStamina());
        if (tier == 0 || (hold.dummy() != null && reached == 3 && tier < 3)) {
            if (reached > 0) p.displayClientMessage(Component.translatable("message.jobless_reincarnation.light.no_stamina"), true);
            abort(container);
            return;
        }
        if (hold.dummy() != null) {
            var aimed = LightBladeTraining.aimedDummy(p);
            if (tier != 3 || aimed == null || !hold.dummy().equals(aimed.getUUID())) {
                p.displayClientMessage(Component.translatable("message.jobless_reincarnation.light.trial_retry"), true);
                abort(container);
                return;
            }
        }
        // Consume the exact server session before payment: a re-entrant or duplicate release has no active ticket.
        var released = state.finish(p);
        if (released == null || !patch.consumeForSkill(this, Resource.STAMINA, LightBladeTiming.staminaCost(tier), false, new CompoundTag())) {
            abort(container);
            return;
        }
        super.executeOnServer(container, arguments);
        p.getData(ModAttachments.SCHOOL_COMBAT).startLightCut(p, tier, released.dummy());
        // The dash is judged on the server's own clock along this path; the client moves the player only on its own screen.
        p.getData(ModAttachments.SCHOOL_COMBAT).traceLightCut(p.position(), SwordReach.dashPath(patch, p));
        patch.playAnimationSynchronized(SchoolAnimations.LIGHT_CUT, 0);
        // The dash itself is the clip's root motion; this is its light, seen by everyone near, bigger with the tier.
        SchoolCues.send(p.serverLevel(), new dev.joblessreincarnation.magic.SpellBurstOptions(
                dev.joblessreincarnation.magic.SpellBurstOptions.LIGHT_DASH, p.getId(), tier - 1), p, p.position().add(0, p.getBbHeight() * .55, 0));
    }

    private void abort(SkillContainer container) {
        var patch = container.getServerExecutor();
        if (patch.isHoldingSkill(this)) patch.resetHolding();
        super.cancelOnServer(container, new CompoundTag());
    }

    @Override public void cancelOnServer(SkillContainer container, CompoundTag arguments) {
        var p = container.getServerExecutor().getOriginal();
        long token = pressId(arguments);
        if (token <= 0) return;
        var state = p.getData(ModAttachments.LIGHT_BLADE_SESSION);
        boolean current = state.matches(token);
        state.cancelled(token);
        if (current) abort(container);
    }

    @Override public void resetHolding(SkillContainer container) {
        if (!(container.getExecutor() instanceof ServerPlayerPatch patch)) return;
        var p = patch.getOriginal();
        if (p.hasData(ModAttachments.LIGHT_BLADE_SESSION)) p.getData(ModAttachments.LIGHT_BLADE_SESSION).finish(p);
        var active = patch.getAnimator().getPlayerFor(null).getRealAnimation();
        if (active == SchoolAnimations.LIGHT_GATHER || active == SchoolAnimations.LIGHT_HOLD) patch.stopPlaying(active);
    }

    @Override public void onRemoved(SkillContainer container) {
        if (container.getExecutor() instanceof ServerPlayerPatch patch) {
            if (patch.isHoldingSkill(this)) abort(container);
            var p = patch.getOriginal();
            if (p.hasData(ModAttachments.SCHOOL_COMBAT)) p.getData(ModAttachments.SCHOOL_COMBAT).unequip();
        }
        super.onRemoved(container);
    }
}
