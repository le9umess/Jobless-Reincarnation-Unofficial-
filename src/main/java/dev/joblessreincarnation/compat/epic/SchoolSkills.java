package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.SpellBurstOptions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.SchoolForm;
import dev.joblessreincarnation.training.SchoolCues;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.api.event.types.entity.TakeDamageEvent;
import yesman.epicfight.api.utils.AttackResult;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.network.server.SPAddLearnedSkill;
import yesman.epicfight.network.server.SPChangeSkill;
import yesman.epicfight.network.server.SPSetRemotePlayerSkill;
import yesman.epicfight.registry.EpicFightRegistries;
import yesman.epicfight.skill.*;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/** EF owns the learned skills and editable slot. Personal practice authorizes learning and server execution. */
public final class SchoolSkills {
    public enum Category implements SkillCategory {
        JOBLESS_SWORD_SCHOOL;
        private final int id = SkillCategory.ENUM_MANAGER.assign(this);
        @Override public int universalOrdinal() { return id; }
        @Override public boolean shouldSave() { return true; }
        @Override public boolean shouldSynchronize() { return true; }
        @Override public boolean learnable() { return true; }
        @Override public Component getTranslationKey() { return Component.translatable("skill.jobless_reincarnation.category.sword_school"); }
    }

    public enum Slot implements SkillSlot {
        JOBLESS_PREPARED_FORM;
        private final int id = SkillSlot.ENUM_MANAGER.assign(this);
        @Override public int universalOrdinal() { return id; }
        @Override public SkillCategory category() { return Category.JOBLESS_SWORD_SCHOOL; }
    }

    private static final DeferredRegister<Skill> SKILLS = DeferredRegister.create(EpicFightRegistries.Keys.SKILL, JoblessReincarnation.MOD_ID);
    private static final DeferredHolder<Skill, SchoolSkill> ARM_STRIKE = register(SchoolForm.ARM_STRIKE);
    private static final DeferredHolder<Skill, SchoolSkill> FLOW_COUNTER = register(SchoolForm.FLOW_COUNTER);
    private static final DeferredHolder<Skill, SchoolSkill> TEAR_POUCH = register(SchoolForm.TEAR_POUCH);
    private static final DeferredHolder<Skill, LightBladeSkill> LIGHT_BLADE = SKILLS.register("light_blade", id ->
            new LightBladeSkill.Builder().setCategory(Category.JOBLESS_SWORD_SCHOOL).setActivateType(Skill.ActivateType.HELD)
                    .setResource(Skill.Resource.NONE).build(id));

    private static DeferredHolder<Skill, SchoolSkill> register(SchoolForm form) {
        return SKILLS.register(form.id, id -> new SchoolSkill.Builder(form).setCategory(Category.JOBLESS_SWORD_SCHOOL)
                .setActivateType(Skill.ActivateType.ONE_SHOT).setResource(Skill.Resource.STAMINA).build(id));
    }

    public static void install(IEventBus bus) {
        SkillCategory.ENUM_MANAGER.registerEnumCls(JoblessReincarnation.MOD_ID, Category.class);
        SkillSlot.ENUM_MANAGER.registerEnumCls(JoblessReincarnation.MOD_ID, Slot.class);
        SKILLS.register(bus);
        EpicFightEventHooks.Player.CAST_SKILL.registerEvent(event -> {
            SkillSlot slot = event.getSkillContainer().getSlot();
            if (slot == SkillSlots.WEAPON_INNATE && inputSlot(event.getPlayerPatch(), slot) != slot) event.cancel();
        });
    }

    public static Skill skill(SchoolForm form) {
        return switch (form) {
            case ARM_STRIKE -> ARM_STRIKE.get();
            case FLOW_COUNTER -> FLOW_COUNTER.get();
            case TEAR_POUCH -> TEAR_POUCH.get();
            case LIGHT_BLADE -> LIGHT_BLADE.get();
        };
    }

    /** Only redirects the native innate input. Charging and storage of the weapon's original skill remain in EF. */
    public static SkillSlot inputSlot(PlayerPatch<?> patch, SkillSlot requested) {
        if (patch != null && requested == SkillSlots.WEAPON_INNATE && patch.isEpicFightMode()
                && SchoolForm.isSword(patch.getOriginal().getMainHandItem())
                && patch.getSkill(Slot.JOBLESS_PREPARED_FORM).getSkill() != null) return Slot.JOBLESS_PREPARED_FORM;
        return requested;
    }

    public static boolean completeDrill(ServerPlayer player, SchoolForm form) {
        if (!player.isAlive() || player.isFakePlayer() || player.isSpectator() || player.getAbilities().instabuild
                || !player.hasData(ModAttachments.SWORD_TRAINING)) return false;
        var training = player.getData(ModAttachments.SWORD_TRAINING);
        if (!training.hasPractice(form)) return false;
        var patch = EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
        if (patch == null) return false;
        Skill skill = skill(form);
        boolean first = !training.mastered(form) || !patch.getPlayerSkills().hasLearned(skill);
        if (!first) return false;
        training.completePractice(form);
        patch.getPlayerSkills().addLearnedSkill(skill);
        EpicFightNetworkManager.sendToPlayer(new SPAddLearnedSkill(java.util.List.of(skill.holder())), player);
        player.sendSystemMessage(Component.translatable("message.jobless_reincarnation.school.learned", skillName(form)));
        return true;
    }

    public static boolean toggleFromNote(ServerPlayer player, SchoolForm form) {
        var patch = EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
        Skill skill = skill(form);
        if (patch == null || !player.isAlive() || player.isSpectator() || !player.hasData(ModAttachments.SWORD_TRAINING)) return false;
        var training = player.getData(ModAttachments.SWORD_TRAINING);
        boolean trial = form == SchoolForm.LIGHT_BLADE && training.hasLightInsight() && training.hasPractice(form) && !training.mastered(form);
        if (!trial && (!training.mastered(form) || !patch.getPlayerSkills().hasLearned(skill))) return false;
        var slot = patch.getSkill(Slot.JOBLESS_PREPARED_FORM);
        var runtime = player.getData(ModAttachments.SCHOOL_COMBAT);
        if (!runtime.canPrepare(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.school.wait"), true);
            return true;
        }
        Skill next = slot.getSkill() == skill ? null : skill;
        if (!slot.setSkill(next)) return false;
        runtime.prepared(player, MagicConfig.SCHOOL_PREPARATION_TICKS.getAsInt());
        var holder = next == null ? null : next.holder();
        EpicFightNetworkManager.sendToPlayer(new SPChangeSkill(Slot.JOBLESS_PREPARED_FORM, player.getId(), holder), player);
        EpicFightNetworkManager.sendToAllPlayerTrackingThisEntity(new SPSetRemotePlayerSkill(Slot.JOBLESS_PREPARED_FORM, player.getId(), holder), player);
        player.displayClientMessage(Component.translatable(next == null ? "message.jobless_reincarnation.school.unprepared" :
                trial ? "message.jobless_reincarnation.light.trial_prepared" : "message.jobless_reincarnation.school.prepared", skillName(form)), true);
        return true;
    }

    private static Component skillName(SchoolForm form) { return Component.translatable("skill.jobless_reincarnation." + form.id); }

    public static void afterParry(ServerPlayerPatch patch, TakeDamageEvent.Income hit) {
        ServerPlayer player = patch.getOriginal();
        var source = hit.getDamageSource();
        if (!hit.isParried() || hit.getResult() != AttackResult.ResultType.BLOCKED
                || !(source.getDirectEntity() instanceof LivingEntity attacker) || attacker != source.getEntity()
                || !(source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO))
                || !attacker.isAlive() || attacker == player || player.isAlliedTo(attacker)
                || !SchoolForm.isSword(player.getMainHandItem()) || !player.canInteractWithEntity(attacker, 0) || !player.hasLineOfSight(attacker)) return;
        completeDrill(player, SchoolForm.FLOW_COUNTER);
        if (patch.getSkill(Slot.JOBLESS_PREPARED_FORM).getSkill() == skill(SchoolForm.FLOW_COUNTER)) {
            player.getData(ModAttachments.SCHOOL_COMBAT).parried(player, attacker);
            // Anchor the signal in world space at the defender's main hand; both fighters see the same opening.
            var ahead = net.minecraft.world.phys.Vec3.directionFromRotation(0, player.getYRot());
            double side = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? .27 : -.27;
            var hand = player.position().add(ahead.scale(.40)).add(-ahead.z * side, player.getBbHeight() * .66, ahead.x * side);
            SchoolCues.send(player.serverLevel(), SpellBurstOptions.FLOW_OPENING, player, hand);
        }
    }

    private SchoolSkills() {}
}
