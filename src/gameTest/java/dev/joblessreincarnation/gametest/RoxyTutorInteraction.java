package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

/** Transient development scene routing only. It never grants, claims, starts use, or changes attachments. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class RoxyTutorInteraction {
    public static final String PROPERTY = "jobless_reincarnation.roxyTutorPreview";
    public static final int REPLY_INTERVAL_TICKS = 20;
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();
    // Only the client target handler reads this field. Server authorization never uses it.
    @Nullable private static volatile UUID clientTarget;

    public record Receipt(int delivered, RoxyTutorGuidance.Stage stage, long tick) {}

    private static final class Binding {
        final ServerLevel level;
        final RoxyDisplayEntity npc;
        final UUID uuid;
        final Set<UUID> participants;
        final Map<UUID, Receipt> replies = new HashMap<>();

        Binding(ServerLevel level, RoxyDisplayEntity npc, Set<UUID> participants) {
            this.level = level;
            this.npc = npc;
            this.uuid = npc.getUUID();
            this.participants = Set.copyOf(participants);
        }
    }

    public static void bind(ServerLevel level, RoxyDisplayEntity npc, Set<UUID> participants) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Bind on the server thread");
        if (!Boolean.getBoolean(PROPERTY)) throw new IllegalStateException("Roxy tutor preview is disabled");
        if (npc.level() != level || level.getEntity(npc.getUUID()) != npc || !npc.isAlive() || npc.isRemoved())
            throw new IllegalArgumentException("Bind only the exact live scene NPC already in this level");
        Binding previous = BINDINGS.get(npc.getUUID());
        if (previous != null && (previous.level != level || previous.npc != npc))
            throw new IllegalStateException("A different scene entity already owns this UUID");
        BINDINGS.put(npc.getUUID(), new Binding(level, npc, participants));
    }

    public static void unbind(ServerLevel level, RoxyDisplayEntity npc) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Unbind on the server thread");
        Binding binding = BINDINGS.get(npc.getUUID());
        if (binding != null && binding.level == level && binding.npc == npc)
            BINDINGS.remove(binding.uuid);
    }

    /** Client presentation scope from the development scene packet; null clears it on exit. */
    public static void setClientTarget(@Nullable UUID uuid) { clientTarget = uuid; }

    @Nullable
    public static Receipt receipt(ServerPlayer player) {
        Receipt latest = null;
        for (Binding binding : BINDINGS.values()) {
            if (binding.level != player.level()) continue;
            Receipt receipt = binding.replies.get(player.getUUID());
            if (receipt != null && (latest == null || receipt.tick() > latest.tick())) latest = receipt;
        }
        return latest;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!sceneTarget(event.getEntity(), event.getTarget())) return;
        // PASS would let the client start a staff cast or study the book in the same click.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), event.getTarget(), event.getHand());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!sceneTarget(event.getEntity(), event.getTarget())) return;
        // Vanilla sends this before the general interaction; both routes share the per-player limiter.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        reply(event.getEntity(), event.getTarget(), event.getHand());
    }

    private static boolean sceneTarget(Player player, Entity target) {
        if (!Boolean.getBoolean(PROPERTY) || !(target instanceof RoxyDisplayEntity)) return false;
        if (player.level().isClientSide) return target.getUUID().equals(clientTarget);
        Binding binding = BINDINGS.get(target.getUUID());
        // Even invalid actors consume this exact scene target, but receive no guidance below.
        return binding != null && binding.npc == target && binding.uuid.equals(target.getUUID())
                && binding.level == target.level();
    }

    private static void reply(Player actor, Entity target, InteractionHand hand) {
        if (!(actor instanceof ServerPlayer player) || hand != InteractionHand.MAIN_HAND) return;
        Binding binding = BINDINGS.get(target.getUUID());
        if (binding == null || binding.npc != target || binding.level != player.level()
                || binding.level.getEntity(binding.uuid) != target
                || !binding.participants.contains(player.getUUID())
                || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || !target.isAlive() || target.isRemoved()
                || !player.canInteractWithEntity(target, 0.0) || !player.hasLineOfSight(target)) return;
        long tick = binding.level.getGameTime();
        Receipt previous = binding.replies.get(player.getUUID());
        if (previous != null && tick >= previous.tick() && tick - previous.tick() < REPLY_INTERVAL_TICKS) return;
        RoxyTutorGuidance.Snapshot snapshot = RoxyTutorGuidance.snapshot(player);
        player.sendSystemMessage(RoxyTutorGuidance.message(snapshot));
        binding.replies.put(player.getUUID(), new Receipt(previous == null ? 1 : previous.delivered() + 1,
                snapshot.stage(), tick));
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        for (Binding binding : BINDINGS.values()) binding.replies.remove(uuid);
        if (event.getEntity().level().isClientSide) clientTarget = null;
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) { BINDINGS.clear(); }

    private RoxyTutorInteraction() {}
}
