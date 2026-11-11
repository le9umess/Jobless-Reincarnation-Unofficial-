package dev.joblessreincarnation.client.naming;

import dev.joblessreincarnation.naming.Naming;
import dev.joblessreincarnation.naming.NamingChoice;
import dev.joblessreincarnation.network.NamingAnswerPayload;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client end of choosing what the NPCs call you. The server keeps the choice ({@link Naming}) and sends this client
 * its own record on every entry. While that record says the player has not answered yet, the naming screen opens once,
 * as soon as the player is in the world with nothing else on screen; the answer goes back to the server, which checks it
 * again. The magic journal opens the same screen later.
 */
public final class NamingClient {
    /** The screen is open, or an answer is on its way, for the record as it stands; set again only by a fresh "not yet". */
    private static boolean asked;

    /** This player's current choice as the server last sent it ("Rudy" until then). */
    public static NamingChoice current() {
        var player = Minecraft.getInstance().player;
        return player == null ? NamingChoice.DEFAULT : player.getData(ModAttachments.NAMING).choice();
    }

    /** Show the screen with this choice selected. */
    public static void open(NamingChoice choice) {
        Minecraft.getInstance().setScreen(new NamingScreen(choice, Naming.customAllowed(), Naming.maxLength(),
                NamingClient::onChosen, NamingClient::onLater));
    }

    static void onChosen(NamingChoice choice) {
        send(new NamingAnswerPayload(false, choice));
    }

    /** The player closed the screen or chose "later": whatever was chosen before stays ("Rudy" if nothing). */
    static void onLater() {
        send(new NamingAnswerPayload(true, NamingChoice.DEFAULT));
    }

    private static void send(NamingAnswerPayload answer) {
        if (Minecraft.getInstance().getConnection() != null) PacketDistributor.sendToServer(answer);
    }

    /** Ask once per "not yet" from the server, when the player is in the world and no screen or overlay is up. */
    public static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null) {
            asked = false;
            return;
        }
        if (player.getData(ModAttachments.NAMING).settled()) {
            asked = false;
            return;
        }
        if (asked || minecraft.screen != null || minecraft.getOverlay() != null || !player.isAlive()) return;
        asked = true;
        open(current());
    }

    private NamingClient() {}
}
