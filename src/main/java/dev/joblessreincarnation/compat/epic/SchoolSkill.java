package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.TearPouchItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.event.types.player.SkillCastEvent;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillBuilder;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

final class SchoolSkill extends Skill {
    static final class Builder extends SkillBuilder<Builder> {
        Builder(SchoolForm form) { super(b -> new SchoolSkill(b, form)); }
    }

    private final SchoolForm form;
    private SchoolSkill(Builder builder, SchoolForm form) { super(builder); this.form = form; }

    @Override public ResourceLocation getSkillTexture() {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/gui/skills/" + form.id + ".png");
    }

    @Override public boolean canExecute(SkillContainer container) {
        var patch = container.getExecutor();
        var player = patch.getOriginal();
        if (!patch.isEpicFightMode() || container.getSlot() != SchoolSkills.Slot.JOBLESS_PREPARED_FORM
                || !player.isAlive() || player.isSpectator() || !SchoolForm.isSword(player.getMainHandItem())) return false;
        boolean afterGuard = form == SchoolForm.FLOW_COUNTER && patch.isHoldingSkill(EpicFightSkills.PARRYING.get());
        if ((player.isUsingItem() || patch.isHoldingAny()) && !afterGuard) return false;
        if (!(player instanceof ServerPlayer server)) return true;
        if (server.containerMenu != server.inventoryMenu || !server.hasData(ModAttachments.SWORD_TRAINING)
                || !server.getData(ModAttachments.SWORD_TRAINING).mastered(form) || !patch.getPlayerSkills().hasLearned(this)) return false;
        var state = server.getData(ModAttachments.SCHOOL_COMBAT);
        if (!state.recovered(server)) return false;
        return switch (form) {
            case ARM_STRIKE -> true;
            case FLOW_COUNTER -> state.counterTarget(server) != null;
            case TEAR_POUCH -> TearPouchItem.available(server);
            case LIGHT_BLADE -> false; // Registered as a separate, held skill.
        };
    }

    @Override public boolean isExecutableState(PlayerPatch<?> patch) {
        if (super.isExecutableState(patch)) return true;
        // A successful EF parry is itself an action. Accept its immediate follow-up, never a hurt/knockdown state.
        return form == SchoolForm.FLOW_COUNTER && patch.isHoldingSkill(EpicFightSkills.PARRYING.get())
                && !patch.getOriginal().isSpectator() && !patch.isInAir() && !patch.getEntityState().hurt()
                && !patch.getEntityState().knockDown();
    }

    @Override public boolean resourcePredicate(PlayerPatch<?> patch, SkillCastEvent event) {
        if (patch.isLogicalClient()) return super.resourcePredicate(patch, event);
        // Server payment is committed below, after a throwable has been admitted to the level.
        return getResourceType().predicate.canExecute(event.getSkillContainer(), patch, getDefaultConsumptionAmount(patch));
    }

    @Override public void executeOnServer(SkillContainer container, CompoundTag arguments) {
        var patch = container.getServerExecutor();
        var player = patch.getOriginal();
        if (!canExecute(container) || !isExecutableState(patch)) return;
        var state = player.getData(ModAttachments.SCHOOL_COMBAT);
        LivingEntity target = form == SchoolForm.FLOW_COUNTER ? state.counterTarget(player) : null;
        var safeArguments = new CompoundTag();
        java.util.function.BooleanSupplier pay = () -> player.getAbilities().instabuild || patch.consumeForSkill(this, getResourceType(), safeArguments);
        if (form == SchoolForm.TEAR_POUCH) {
            ItemStack bag = TearPouchItem.findBag(player);
            if (!TearPouchItem.launch(player, bag, pay)) return;
        } else if (!pay.getAsBoolean()) return;
        state.start(player, form, target);
        super.executeOnServer(container, safeArguments);
        switch (form) {
            case ARM_STRIKE -> patch.playAnimationSynchronized(SchoolAnimations.ARM_STRIKE, 0);
            case FLOW_COUNTER -> patch.playAnimationSynchronized(SchoolAnimations.FLOW_COUNTER, 0);
            case TEAR_POUCH -> patch.playAnimationSynchronized(SchoolAnimations.TEAR_THROW, 0);
            case LIGHT_BLADE -> throw new IllegalStateException("Light Blade has its own hold/release handler");
        }
    }

    @Override public void onRemoved(SkillContainer container) {
        super.onRemoved(container);
        if (container.getExecutor().getOriginal() instanceof ServerPlayer server && server.hasData(ModAttachments.SCHOOL_COMBAT)) {
            server.getData(ModAttachments.SCHOOL_COMBAT).unequip();
        }
    }
}
