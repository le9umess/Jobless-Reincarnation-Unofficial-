package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-owned area and contact accounting. Each mob gets one transient modifier, regardless of how many eligible
 * sources cover it. No effect or modifier is persisted, and removal touches only this mod's private modifier ID.
 */
public final class MireFields {
    public static final ResourceLocation SLOW_ID =
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mire_slow");
    public static final double SLOW_AMOUNT = -0.60;
    public static final int FEEDBACK_INTERVAL_TICKS = 20;
    private static final AttributeModifier SLOW =
            new AttributeModifier(SLOW_ID, SLOW_AMOUNT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final Map<ServerLevel, State> LEVELS = new IdentityHashMap<>();
    // A tracking-end callback can run inside an entity-section stream; discard only after that traversal ends.
    private static final Map<ServerLevel, Set<MireEntity>> PENDING_ENDS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, MireEntity>> OWNERS = new IdentityHashMap<>();

    private static final class State {
        final Set<MireEntity> fields = identitySet();
        final Set<LivingEntity> affected = identitySet();
        final Map<LivingEntity, Long> feedbackAt = new IdentityHashMap<>();
    }

    public static void register(IEventBus bus) {
        bus.addListener(MireFields::onLevelTick);
        bus.addListener(MireFields::onEntityLeave);
        bus.addListener(MireFields::onLevelUnload);
        bus.addListener(MireFields::onServerStopped);
    }

    /**
     * Called only after successful admission, terrain revalidation and payment. No rejection event occurs here.
     * Publish the new identity before discarding the old one, so the old entity's leave callback cannot erase it.
     */
    public static void activate(MireEntity field) {
        if (!(field.level() instanceof ServerLevel level) || field.ownerId() == null
                || field.owner() == null || field.isRemoved() || field.ended()) {
            throw new IllegalArgumentException("Only an admitted, paid, live Mire field can be activated");
        }
        State state = LEVELS.computeIfAbsent(level, ignored -> new State());
        state.fields.add(field);
        Map<UUID, MireEntity> owners = OWNERS.computeIfAbsent(level.getServer(), ignored -> new HashMap<>());
        MireEntity previous = owners.put(field.ownerId(), field);
        if (previous != null && previous != field) end(previous, true);
    }

    static boolean isRegistered(MireEntity field) {
        if (!(field.level() instanceof ServerLevel level) || field.ownerId() == null) return false;
        Map<UUID, MireEntity> owners = OWNERS.get(level.getServer());
        State state = LEVELS.get(level);
        return owners != null && owners.get(field.ownerId()) == field && state != null && state.fields.contains(field);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        drainEnded(level);
        State state = LEVELS.get(level);
        if (state == null) return;
        long now = level.getGameTime();
        // Expiration and unload checks run even when an area's own entity is no longer ticking.
        for (MireEntity field : new ArrayList<>(state.fields)) {
            if (!valid(field, level, now)) end(field, true);
        }

        Map<LivingEntity, Set<MireEntity>> sources = new IdentityHashMap<>();
        for (MireEntity field : new ArrayList<>(state.fields)) {
            ServerPlayer owner = field.owner();
            if (owner == null || !field.activeAt(now)) continue;
            AABB search = new AABB(field.getX() - MireEntity.HALF_EXTENT,
                    field.getY() - MireTargets.FOOT_TOLERANCE, field.getZ() - MireEntity.HALF_EXTENT,
                    field.getX() + MireEntity.HALF_EXTENT, field.getY() + MireTargets.FOOT_TOLERANCE,
                    field.getZ() + MireEntity.HALF_EXTENT);
            for (Mob target : level.getEntitiesOfClass(Mob.class, search,
                    target -> MireTargets.eligible(owner, target) && MireTargets.touches(field, target))) {
                sources.computeIfAbsent(target, ignored -> identitySet()).add(field);
            }
        }

        Set<LivingEntity> next = identitySet();
        Set<MireEntity> effective = identitySet();
        for (Map.Entry<LivingEntity, Set<MireEntity>> entry : sources.entrySet()) {
            LivingEntity target = entry.getKey();
            Set<MireEntity> eligible = identitySet();
            for (MireEntity field : entry.getValue()) {
                ServerPlayer owner = field.owner();
                if (owner != null && isRegistered(field) && field.isAlive() && field.activeAt(now)
                        && MireTargets.eligible(owner, target) && MireTargets.touches(field, target)) eligible.add(field);
            }
            if (eligible.isEmpty() || !applySlow(target)) continue;
            next.add(target);
            effective.addAll(eligible);
            if (!state.affected.contains(target)) {
                Long previous = state.feedbackAt.get(target);
                if (previous == null || now < previous || now - previous >= FEEDBACK_INTERVAL_TICKS) {
                    state.feedbackAt.put(target, now);
                    affectFeedback(level, target);
                }
            }
        }
        for (LivingEntity previous : new ArrayList<>(state.affected)) {
            if (!next.contains(previous)) removeSlow(previous);
        }
        state.affected.clear();
        state.affected.addAll(next);
        state.feedbackAt.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getKey().level() != level
                || now < entry.getValue() || now - entry.getValue() >= FEEDBACK_INTERVAL_TICKS);

        for (MireEntity field : effective) {
            ServerPlayer owner = field.owner();
            if (owner != null && owner.isAlive() && !owner.isRemoved() && owner.serverLevel() == level
                    && isRegistered(field) && field.isAlive() && field.reportFirstEffect()) {
                StoryAdvancements.onMireSlowedTarget(owner);
            }
        }
        if (state.fields.isEmpty() && state.affected.isEmpty() && state.feedbackAt.isEmpty()) LEVELS.remove(level);
    }

    private static boolean valid(MireEntity field, ServerLevel level, long now) {
        ServerPlayer owner = field.owner();
        return isRegistered(field) && !field.ended() && field.level() == level && field.isAlive() && !field.isRemoved()
                && level.getEntity(field.getUUID()) == field
                && owner != null && owner.isAlive() && !owner.isRemoved() && !owner.isSpectator()
                && owner.serverLevel() == level && owner.getUUID().equals(field.ownerId())
                && now >= field.startGameTime() && now - field.startGameTime() < MireEntity.LIFETIME_TICKS
                && MireGround.isValid(level, field.position());
    }

    private static boolean applySlow(LivingEntity target) {
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return false;
        AttributeModifier current = speed.getModifier(SLOW_ID);
        if (!SLOW.equals(current)) {
            if (current != null) speed.removeModifier(SLOW_ID);
            speed.addTransientModifier(SLOW);
        }
        return SLOW.equals(speed.getModifier(SLOW_ID));
    }

    private static void removeSlow(LivingEntity target) {
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW_ID);
    }

    /** The visual delivery adds the server particle/sound call here; callers already deduplicate and rate-limit it. */
    private static void affectFeedback(ServerLevel level, LivingEntity target) { MireVisuals.affect(level, target); }

    private static void end(MireEntity field, boolean discard) {
        field.markEnded();
        if (field.level() instanceof ServerLevel level) {
            State state = LEVELS.get(level);
            if (state != null) state.fields.remove(field);
            Map<UUID, MireEntity> owners = OWNERS.get(level.getServer());
            if (owners != null) {
                owners.remove(field.ownerId(), field);
                if (owners.isEmpty()) OWNERS.remove(level.getServer());
            }
        }
        if (discard && !field.isRemoved()) field.discard();
    }

    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof MireEntity field) {
            end(field, false);
            if (!field.isRemoved()) PENDING_ENDS.computeIfAbsent(level, ignored -> identitySet()).add(field);
        }
        if (event.getEntity() instanceof ServerPlayer owner) {
            Map<UUID, MireEntity> owners = OWNERS.get(level.getServer());
            MireEntity field = owners == null ? null : owners.get(owner.getUUID());
            if (field != null && field.owner() == owner) end(field, true);
        }
        if (event.getEntity() instanceof LivingEntity target) {
            removeSlow(target);
            State state = LEVELS.get(level);
            if (state != null) {
                state.affected.remove(target);
                state.feedbackAt.remove(target);
            }
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) clear(level);
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        for (ServerLevel level : new ArrayList<>(LEVELS.keySet())) {
            if (level.getServer() == event.getServer()) clear(level);
        }
        for (ServerLevel level : new ArrayList<>(PENDING_ENDS.keySet())) {
            if (level.getServer() == event.getServer()) drainEnded(level);
        }
        OWNERS.remove(event.getServer());
    }

    private static void drainEnded(ServerLevel level) {
        Set<MireEntity> ended = PENDING_ENDS.remove(level);
        if (ended == null) return;
        for (MireEntity field : ended) if (!field.isRemoved()) field.discard();
    }

    private static void clear(ServerLevel level) {
        drainEnded(level);
        State state = LEVELS.remove(level);
        if (state == null) return;
        for (LivingEntity target : state.affected) removeSlow(target);
        for (MireEntity field : new ArrayList<>(state.fields)) end(field, true);
        state.fields.clear();
        state.affected.clear();
        state.feedbackAt.clear();
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private MireFields() {}
}