package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.encounter.PaulDuel;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

/** Target rules shared by the non-damaging Air Burst and Mire. */
final class ControlSpellTargets {
    static boolean canAffect(ServerPlayer caster, LivingEntity target) {
        if (caster == target || caster.level() != target.level() || !caster.isAlive() || caster.isRemoved()
                || caster.isSpectator() || !target.isAlive() || target.isRemoved() || target.isSpectator()
                || target instanceof ArmorStand || !PaulDuel.mayAffect(caster, target)) return false;

        if (target instanceof FamilyResidentEntity resident)
            return PaulDuel.spellTarget(caster, resident);
        if (target instanceof RoxyTutorEntity || target instanceof SylphietteEntity) return false;
        if (target instanceof Player player)
            return !player.getAbilities().invulnerable && !player.isInvulnerable()
                    && PaulDuel.forPlayer(player) == null && caster.serverLevel().getServer().isPvpAllowed()
                    && caster.canHarmPlayer(player);

        if (target instanceof OwnableEntity pet && pet.getOwnerUUID() != null)
            return canAffectPet(caster, pet, pet.getOwnerUUID());

        var team = caster.getTeam();
        return team == null || team.isAllowFriendlyFire() || !team.isAlliedTo(target.getTeam());
    }

    private static boolean canAffectPet(ServerPlayer caster, OwnableEntity pet, UUID ownerId) {
        var server = caster.serverLevel().getServer();
        if (ownerId.equals(caster.getUUID()) || !server.isPvpAllowed()) return false;
        var casterTeam = caster.getTeam();
        if (casterTeam == null || casterTeam.isAllowFriendlyFire()) return true;

        var owner = pet.getOwner();
        if (owner == null) owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) return !casterTeam.isAlliedTo(owner.getTeam());

        // Teams store player names. For an offline owner use the local UUID cache;
        // if the name is unknown, preserve friendly-fire protection.
        var profiles = server.getProfileCache();
        return profiles != null && profiles.get(ownerId)
                .map(profile -> !casterTeam.isAlliedTo(server.getScoreboard().getPlayersTeam(profile.getName())))
                .orElse(false);
    }

    private ControlSpellTargets() {}
}
