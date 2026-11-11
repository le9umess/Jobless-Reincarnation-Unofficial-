package dev.joblessreincarnation.training;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** One personal, server-only guard opening or strike. Nothing is serialized or copied to a new player. */
public final class SchoolCombatState {
    private record Window(ResourceKey<Level> world, int slot, ItemStack sword, @Nullable UUID target, long until) {
        Window(ServerPlayer player, @Nullable LivingEntity target, int duration) {
            this(player.level().dimension(), player.getInventory().selected, player.getMainHandItem(),
                    target == null ? null : target.getUUID(), player.level().getGameTime() + duration);
        }
        boolean valid(ServerPlayer player) {
            return player.level().dimension().equals(world) && player.level().getGameTime() < until
                    && player.getInventory().selected == slot && player.getMainHandItem() == sword && player.isAlive();
        }
    }

    private Window opening;
    private Window strike;
    private SchoolForm active;
    private long recoverAt;
    private int notebookPage = -1;
    private long nextPreparation;
    private int lightPower;
    private UUID trialDummy;
    private boolean lightContact;
    /** Where the current Light Blade cut began, and its dash as offsets from there; see {@code SwordReach}. */
    private net.minecraft.world.phys.Vec3 cutOrigin;
    private net.minecraft.world.phys.Vec3[] cutPath;
    /** Seconds of the cut between two points of its path. */
    public static final float CUT_PATH_STEP = 0.05F;

    public SchoolForm offerNotebook(boolean confirm) {
        var choices = SchoolForm.FIRST_LESSONS;
        if (!confirm || notebookPage < 0) notebookPage = (notebookPage + 1) % choices.size();
        return choices.get(notebookPage);
    }

    public boolean canPrepare(ServerPlayer player) {
        return recovered(player) && player.level().getGameTime() >= nextPreparation;
    }

    public void prepared(ServerPlayer player, int ticks) {
        nextPreparation = player.level().getGameTime() + ticks;
    }

    public boolean recovered(ServerPlayer player) { return player.level().getGameTime() >= recoverAt; }
    public void parried(ServerPlayer player, LivingEntity attacker) { opening = new Window(player, attacker, 12); }

    public @Nullable LivingEntity counterTarget(ServerPlayer player) {
        if (opening == null || !opening.valid(player) || opening.target == null) return null;
        return player.serverLevel().getEntity(opening.target) instanceof LivingEntity enemy && inReach(player, enemy) ? enemy : null;
    }

    private static boolean inReach(ServerPlayer player, LivingEntity enemy) {
        return dev.joblessreincarnation.encounter.PaulDuel.acceptsMelee(player, enemy) && enemy != player && enemy.isAlive() && !enemy.isRemoved() && !enemy.isSpectator()
                && dev.joblessreincarnation.compat.epic.SwordReach.reaches(player, enemy) && !player.isAlliedTo(enemy);
    }

    public void start(ServerPlayer player, SchoolForm form, @Nullable LivingEntity target) {
        opening = null;
        trialDummy = null;
        lightPower = 0;
        lightContact = false;
        cutOrigin = null;
        cutPath = null;
        active = form;
        strike = new Window(player, target, 24);
        recoverAt = player.level().getGameTime() + (form == SchoolForm.TEAR_POUCH ? 16 : 13);
    }

    public void startLightCut(ServerPlayer player, int tier, @Nullable UUID dummy) {
        start(player, SchoolForm.LIGHT_BLADE, null);
        lightPower = tier;
        trialDummy = dummy;
        recoverAt = player.level().getGameTime() + 16;
    }

    public int lightTier() { return lightPower; }

    /** The dash of the Light Blade cut just started: where it began and the offsets the server expects (SwordReach.dashPath). */
    public void traceLightCut(net.minecraft.world.phys.Vec3 origin, net.minecraft.world.phys.Vec3[] path) {
        if (active != SchoolForm.LIGHT_BLADE || path.length == 0) return;
        cutOrigin = origin;
        cutPath = path.clone();
    }

    /** Where the current Light Blade cut's dash is, this many seconds into the cut; null when there is none. */
    public @Nullable net.minecraft.world.phys.Vec3 lightCutAt(float seconds) {
        if (active != SchoolForm.LIGHT_BLADE || cutOrigin == null || cutPath == null) return null;
        float step = Math.max(0.0F, seconds) / CUT_PATH_STEP;
        int i = (int) step;
        if (i >= cutPath.length - 1) return cutOrigin.add(cutPath[cutPath.length - 1]);
        return cutOrigin.add(cutPath[i].lerp(cutPath[i + 1], step - i));
    }
    public boolean isPracticeCut() { return active == SchoolForm.LIGHT_BLADE && trialDummy != null; }

    public @Nullable PracticeDummyEntity currentTrialDummy(ServerPlayer p) {
        if (!isPracticeCut() || strike == null || !strike.valid(p)) return null;
        return p.serverLevel().getEntity(trialDummy) instanceof PracticeDummyEntity d && d.hasSupport()
                && !d.isRemoved() && dev.joblessreincarnation.compat.epic.SwordReach.reaches(p, d) ? d : null;
    }

    public boolean takeLightContact(ServerPlayer p) {
        if (active != SchoolForm.LIGHT_BLADE || strike == null || !strike.valid(p) || lightContact) return false;
        lightContact = true;
        return true;
    }

    public boolean allowsContact(ServerPlayer player, SchoolForm form, LivingEntity target) {
        if (active != form || trialDummy != null || strike == null || !strike.valid(player) || !inReach(player, target)) return false;
        return strike.target == null || strike.target.equals(target.getUUID());
    }

    private boolean armFollowUp;

    /** Whether the Arm Strike about to land interrupts a later strike of the opponent's attack. */
    public void armFollowUp(boolean later) { armFollowUp = later; }

    /** The same, once: the interruption cue reads it. */
    public boolean takeArmFollowUp() {
        boolean later = armFollowUp;
        armFollowUp = false;
        return later;
    }

    public void unequip() { opening = null; strike = null; active = null; }
}
