package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.network.DismissPayload;
import dev.joblessreincarnation.network.DismissStatusPayload;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Runtime-only server lock. No session survives a player entity, logout, death or dimension change. */
public final class EarthWallDismantling {
    private static final int TIMEOUT = 200;
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        final int nonce, slot, holdTicks;
        final ItemStack wand;
        final ResourceKey<Level> dimension;
        final WallIdentity identity;
        final long started;
        final boolean creative;
        boolean readySent;
        Session(ServerPlayer player, int nonce, WallIdentity identity) {
            this.nonce = nonce; this.identity = identity;
            slot = player.getInventory().selected; wand = player.getMainHandItem();
            dimension = player.level().dimension(); started = player.level().getGameTime();
            creative = player.getAbilities().instabuild; holdTicks = MagicConfig.EARTH_DISMISS_HOLD_TICKS.getAsInt();
        }
    }
    public static boolean active(ServerPlayer player) { return SESSIONS.containsKey(player); }
    public static int heldTicks(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        return session == null ? 0 : (int)Math.max(0, player.level().getGameTime() - session.started);
    }
    public static void handle(ServerPlayer player, DismissPayload payload) {
        switch (payload.action()) {
            case DismissPayload.BEGIN -> begin(player, payload.nonce());
            case DismissPayload.RELEASE -> release(player, payload.nonce());
            case DismissPayload.CANCEL -> cancel(player, payload.nonce());
            default -> {} // Unknown actions never change a lock or resolve a spell.
        }
    }
    public static boolean begin(ServerPlayer player, int nonce) {
        // Repeated BEGIN never restarts a timer or replaces a live lock.
        if (active(player)) return false;
        if (!eligible(player)) return rejected(player, nonce, "unavailable");
        ManaData mana = player.getData(ModAttachments.MANA);
        if (!mana.canCast(MagicConfig.EARTH_DISMISS_COST.getAsInt(), player.getAbilities().instabuild)
                || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()))
            return rejected(player, nonce, "resources");
        var plan = EarthWallDismissal.plan(player);
        if (!plan.valid() || plan.identity() == null) {
            player.displayClientMessage(plan.reason(), true);
            status(player, nonce, false, false, 1, List.of());
            return false;
        }
        Session session = new Session(player, nonce, plan.identity());
        SESSIONS.put(player, session);
        status(player, nonce, true, false, session.holdTicks, plan.positions());
        return true;
    }
    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild
                && !player.isUsingItem() && SpellCatalog.active(player) == null && !player.isShiftKeyDown()
                && player.containerMenu == player.inventoryMenu
                && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                && SpellCatalog.selected(player) == SpellCatalog.EARTH_WALL && SpellCatalog.EARTH_WALL.knows(player);
    }
    private static boolean matches(ServerPlayer player, Session session) {
        return eligible(player) && session.wand == player.getMainHandItem()
                && session.slot == player.getInventory().selected && session.dimension.equals(player.level().dimension())
                && session.creative == player.getAbilities().instabuild;
    }
    public static boolean release(ServerPlayer player, int nonce) {
        Session session = SESSIONS.get(player);
        if (session == null || session.nonce != nonce) return false;
        SESSIONS.remove(player); // Consume before any event callback can re-enter.
        long held = player.level().getGameTime() - session.started;
        boolean valid = matches(player, session) && held >= session.holdTicks && held < TIMEOUT;
        boolean success = valid && EarthWallDismissal.dismiss(player, session.identity, session.wand);
        status(player, nonce, false, false, session.holdTicks, List.of());
        if (!valid) player.displayClientMessage(Component.translatable("message.jobless_reincarnation.dismiss.cancelled"), true);
        return success;
    }
    public static void tick(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session == null) return;
        long elapsed = player.level().getGameTime() - session.started;
        if (!matches(player, session) || elapsed < 0 || elapsed >= TIMEOUT) { cancel(player); return; }
        if (elapsed >= session.holdTicks && !session.readySent) {
            var plan = EarthWallDismissal.plan(player);
            if (!plan.valid() || !session.identity.equals(plan.identity())) {
                player.displayClientMessage(plan.valid()
                        ? Component.translatable("message.jobless_reincarnation.dismiss.changed") : plan.reason(), true);
                cancel(player); return;
            }
            session.readySent = true;
            status(player, session.nonce, true, true, session.holdTicks, plan.positions());
        }
    }
    public static void cancel(ServerPlayer player, int nonce) {
        Session session = SESSIONS.get(player);
        if (session != null && session.nonce == nonce) cancel(player);
    }
    public static void cancel(ServerPlayer player) {
        Session session = SESSIONS.remove(player);
        if (session != null) status(player, session.nonce, false, false, session.holdTicks, List.of());
    }
    private static boolean rejected(ServerPlayer player, int nonce, String reason) {
        status(player, nonce, false, false, 1, List.of());
        player.displayClientMessage(Component.translatable("message.jobless_reincarnation.dismiss." + reason), true);
        return false;
    }
    private static void status(ServerPlayer player, int nonce, boolean active, boolean ready, int ticks, List<net.minecraft.core.BlockPos> cells) {
        if (player instanceof FakePlayer || player.connection == null) return;
        PacketDistributor.sendToPlayer(player, new DismissStatusPayload(nonce, active, ready, ticks,
                player.getAbilities().instabuild ? 0 : MagicConfig.EARTH_DISMISS_COST.getAsInt(), cells));
    }
    private EarthWallDismantling() {}
}