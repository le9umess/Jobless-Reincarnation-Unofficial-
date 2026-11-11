package dev.joblessreincarnation.client.naming;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Development only: open the naming screen without a server, to look at it and photograph it. Not registered in a normal
 * (production) game, and nothing reaches the server. {@code /jrname open | pick <0-2> | type <text> | confirm | close};
 * a scene script can also write those lines to {@code jobless-dev/naming-demo.txt} in the game folder.
 */
public final class NamingDemo {
    private static int pollTicks;

    public static void register(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.production) return;
        event.getDispatcher().register(Commands.literal("jrname")
                .then(Commands.literal("open").executes(c -> { NamingClient.open(NamingClient.current()); return 1; }))
                .then(Commands.literal("pick").then(Commands.argument("index", IntegerArgumentType.integer(0, 2)).executes(c -> {
                    if (!(Minecraft.getInstance().screen instanceof NamingScreen screen)) return 0;
                    screen.demoPick(IntegerArgumentType.getInteger(c, "index"));
                    return 1;
                })))
                .then(Commands.literal("type").then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    if (!(Minecraft.getInstance().screen instanceof NamingScreen screen)) return 0;
                    screen.demoType(StringArgumentType.getString(c, "text"));
                    return 1;
                })))
                .then(Commands.literal("confirm").executes(c -> {
                    if (!(Minecraft.getInstance().screen instanceof NamingScreen screen)) return 0;
                    screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
                    return 1;
                }))
                .then(Commands.literal("close").executes(c -> {
                    if (Minecraft.getInstance().screen instanceof NamingScreen screen) screen.onClose();
                    return 1;
                })));
    }

    /** Every quarter second, run the lines of {@code jobless-dev/naming-demo.txt}, if it exists, as /jrname commands. */
    public static void poll(ClientTickEvent.Post event) {
        if (FMLEnvironment.production || ++pollTicks % 5 != 0) return;
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("jobless-dev").resolve("naming-demo.txt");
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.delete(file);
            for (String line : lines) if (!line.isBlank()) ClientCommandHandler.runCommand("jrname " + line.trim());
        } catch (IOException ignored) {
            // Being written right now: try again next time.
        }
    }

    private NamingDemo() {}
}
