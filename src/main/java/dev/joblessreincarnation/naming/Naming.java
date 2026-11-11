package dev.joblessreincarnation.naming;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Collection;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The server side of what the NPCs call each player (design N01). The server alone keeps the choice. A player new to the
 * world has not answered yet; their client sees that in its copy of the record, which the server sends on every entry, and
 * opens the naming screen. Whatever comes back is cleaned and checked here again: a name of one's own that is empty, or that
 * this server does not allow, becomes Rudy. "Later" keeps what the player had. Either answer means they are not asked
 * again on entry; the magic journal reopens the screen, and {@code /jr_naming reset} asks them afresh.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class Naming {
    private static final String KEY = "screen.jobless_reincarnation.naming.";

    public static PlayerNaming of(Player player) {
        return player.getData(ModAttachments.NAMING);
    }

    /** What the family calls this player for short: Zenith's "Rudy". Any choice but Rudy is the same both ways. */
    public static Component shortName(Player player) {
        NamingChoice choice = of(player).choice();
        return switch (choice.mode()) {
            case RUDY -> Component.translatable(KEY + "rudy_short");
            case ACCOUNT -> Component.literal(player.getGameProfile().getName());
            case CUSTOM -> Component.literal(choice.custom());
        };
    }

    /** What the family calls this player in full: Lilia's "Rudeus". */
    public static Component fullName(Player player) {
        return of(player).choice().mode() == NamingChoice.Mode.RUDY ? Component.translatable(KEY + "rudy_full") : shortName(player);
    }

    public static boolean customAllowed() {
        return MagicConfig.NAMING_CUSTOM.getAsBoolean();
    }

    public static int maxLength() {
        return MagicConfig.NAMING_MAX_LENGTH.getAsInt();
    }

    /** The server's own check of a choice: a name of one's own cleaned and cut to this server's length; empty or not allowed is Rudy. */
    public static NamingChoice accept(NamingChoice wanted) {
        if (wanted.mode() != NamingChoice.Mode.CUSTOM) return new NamingChoice(wanted.mode(), "");
        if (!customAllowed()) return NamingChoice.DEFAULT;
        String name = NamingChoice.clean(wanted.custom(), maxLength());
        return name.isEmpty() ? NamingChoice.DEFAULT : new NamingChoice(NamingChoice.Mode.CUSTOM, name);
    }

    /** The answer from the naming screen: a choice, or {@code null} for "later", which keeps the current one. */
    public static void answer(ServerPlayer player, @Nullable NamingChoice chosen) {
        PlayerNaming before = of(player);
        PlayerNaming after = new PlayerNaming(chosen == null ? before.choice() : accept(chosen), true);
        if (after.equals(before)) return;
        player.setData(ModAttachments.NAMING, after);
        player.syncData(ModAttachments.NAMING);
    }

    /** Back to Rudy, and asked again: at once if they are in the world, otherwise on their next entry. */
    public static void reset(ServerPlayer player) {
        player.setData(ModAttachments.NAMING, PlayerNaming.NEW);
        player.syncData(ModAttachments.NAMING);
    }

    // The client keeps no record of its own: every entry sends it the server's, so a new player is asked and a known one is not.
    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) { send(event.getEntity()); }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) { send(event.getEntity()); }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { send(event.getEntity()); }

    private static void send(Player player) {
        if (!(player instanceof ServerPlayer server)) return;
        of(server);
        server.syncData(ModAttachments.NAMING);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jr_naming").requires(source -> source.hasPermission(2))
                .then(Commands.literal("reset").then(Commands.argument("targets", EntityArgument.players()).executes(context -> {
                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
                    for (ServerPlayer target : targets) reset(target);
                    context.getSource().sendSuccess(() -> targets.size() == 1
                            ? Component.translatable("message.jobless_reincarnation.naming.reset", targets.iterator().next().getDisplayName())
                            : Component.translatable("message.jobless_reincarnation.naming.reset_many", targets.size()), true);
                    return targets.size();
                }))));
    }

    private Naming() {}
}
