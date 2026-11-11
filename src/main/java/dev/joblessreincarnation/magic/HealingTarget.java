package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only aim prediction and release-time selection share exactly the same geometric rules. */
public final class HealingTarget {
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "healing_immune"));

    public enum Reason {
        VALID(null), INVALID_TARGET("target_invalid"), FULL_HEALTH("target_full"), UNLOADED("target_unloaded");
        private final String key;
        Reason(String key) { this.key = key; }
        public Component message() {
            return key == null ? Component.empty() : Component.translatable("message.jobless_reincarnation.healing." + key);
        }
    }
    public record Plan(Entity target, boolean self, Reason reason) {
        public boolean valid() { return reason == Reason.VALID; }
        public Component targetName() {
            return self ? Component.translatable("message.jobless_reincarnation.healing.self") : target.getDisplayName();
        }
    }

    static boolean eligible(Player caster, Entity target) {
        return target instanceof LivingEntity living && living.level() == caster.level()
                && living.isAlive() && !living.isRemoved() && !living.isSpectator()
                && !(living instanceof Enemy) && !(living instanceof ArmorStand)
                && !living.isInvertedHealAndHarm() && !living.getType().is(IMMUNE)
                && Float.isFinite(living.getHealth()) && Float.isFinite(living.getMaxHealth())
                && living.getMaxHealth() > 0;
    }

    /** Invalid/full first hits keep the player's intent; they never fall through to somebody else. */
    public static Plan resolve(Player caster) {
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getLookAngle().scale(MagicConfig.HEALING_RANGE.getAsInt()));
        var level = caster.level();
        BlockPos unloaded = BlockGetter.traverseBlocks(eye, end, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        if (unloaded != null) return new Plan(caster, true, Reason.UNLOADED);
        HitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean blocked = block.getType() != HitResult.Type.MISS;
        Vec3 endpoint = blocked ? block.getLocation() : end;
        double limit = eye.distanceToSqr(endpoint);
        Entity selected = null;
        double nearest = limit;
        for (Entity candidate : level.getEntities(caster, new AABB(eye, endpoint).inflate(1.0),
                entity -> entity.isPickable() && !entity.isSpectator())) {
            AABB bounds = candidate.getBoundingBox().inflate(candidate.getPickRadius());
            Optional<Vec3> intersection = bounds.clip(eye, endpoint);
            double distance;
            if (bounds.contains(eye)) distance = 0;
            else if (intersection.isPresent()) distance = eye.distanceToSqr(intersection.get());
            else continue;
            if (!Double.isFinite(distance) || distance > limit || blocked && distance >= limit) continue;
            if (selected == null || distance < nearest || distance == nearest && candidate.getId() < selected.getId()) {
                selected = candidate;
                nearest = distance;
            }
        }
        Entity target = selected == null ? caster : selected;
        Reason reason = !eligible(caster, target) ? Reason.INVALID_TARGET
                : ((LivingEntity) target).getHealth() >= ((LivingEntity) target).getMaxHealth() ? Reason.FULL_HEALTH : Reason.VALID;
        return new Plan(target, selected == null, reason);
    }
    private HealingTarget() {}
}
