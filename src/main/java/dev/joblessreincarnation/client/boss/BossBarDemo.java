package dev.joblessreincarnation.client.boss;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.joblessreincarnation.JoblessReincarnation;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Development only: a client command that shows Paul's boss bar without a fight, to look at it and photograph it before
 * the server side exists. It is not registered in a normal (production) game, and nothing here reaches the server.
 * {@code /jrbossbar show | set <phase> <progress> | hit <amount> | next | say <text> | clear | report}
 */
public final class BossBarDemo {
    private static final UUID ID = UUID.fromString("6d8c1f0e-2b7a-4c55-9d1e-b0557ba7d0e1");
    private static final ResourceLocation EMBLEM = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "hud/boss/emblem_paul");
    private static final int SAY_TICKS = 100, INTRO_TICKS = 60, PAUSE_TICKS = 80;
    private static int phase, transition, lineId;
    private static float progress = 1;
    private static BossBarView.Stage stage = BossBarView.Stage.ACTIVE;
    private static long stageAt, saidAt;
    private static @Nullable Component line;

    public static void register(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.production) return;
        event.getDispatcher().register(Commands.literal("jrbossbar")
                .then(Commands.literal("show").executes(c -> {
                    ClientBossBars.remove(ID);
                    phase = 0; progress = 1; line = null; transition = 0;
                    to(BossBarView.Stage.INTRO);
                    return send();
                }))
                .then(Commands.literal("set").then(Commands.argument("phase", IntegerArgumentType.integer(0, 1))
                        .then(Commands.argument("progress", FloatArgumentType.floatArg(0, 1)).executes(c -> {
                            int next = IntegerArgumentType.getInteger(c, "phase");
                            if (next != phase) transition++;
                            phase = next;
                            progress = FloatArgumentType.getFloat(c, "progress");
                            to(BossBarView.Stage.ACTIVE);
                            return send();
                        }))))
                .then(Commands.literal("hit").then(Commands.argument("amount", FloatArgumentType.floatArg(0, 1)).executes(c -> {
                    progress = Math.max(0, progress - FloatArgumentType.getFloat(c, "amount"));
                    to(BossBarView.Stage.ACTIVE);
                    return send();
                })))
                .then(Commands.literal("next").executes(c -> {
                    phase = Math.min(1, phase + 1);
                    progress = 1;
                    transition++;
                    to(BossBarView.Stage.INTERMISSION);
                    return send();
                }))
                .then(Commands.literal("say").then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    line = Component.literal(StringArgumentType.getString(c, "text"));
                    lineId++;
                    saidAt = Util.getMillis();
                    return send();
                })))
                .then(Commands.literal("clear").executes(c -> { ClientBossBars.remove(ID); return 1; }))
                .then(Commands.literal("report").executes(c -> report()))
                // The vanilla "Force Unicode Font" option, which lets the GUI scale go one step higher on the same window.
                .then(Commands.literal("unicode").then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                        .executes(c -> {
                            Minecraft.getInstance().options.forceUnicodeFont().set(com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on"));
                            return 1;
                        }))));
    }

    /** Write the real window and GUI scale to {@code jobless-dev/gui.json}, so screenshots are labelled with what was drawn. */
    private static int report() {
        var minecraft = Minecraft.getInstance();
        var window = minecraft.getWindow();
        String json = "{\"framebuffer\":[" + window.getWidth() + "," + window.getHeight() + "],\"requestedGuiScale\":"
                + minecraft.options.guiScale().get() + ",\"guiScale\":" + window.getGuiScale() + ",\"gui\":["
                + window.getGuiScaledWidth() + "," + window.getGuiScaledHeight() + "]}";
        try {
            java.nio.file.Path dir = minecraft.gameDirectory.toPath().resolve("jobless-dev");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("gui.json"), json);
        } catch (java.io.IOException e) {
            return 0;
        }
        return 1;
    }

    private static int pollTicks;

    /**
     * Development only, for scripted screenshots: every quarter second, if {@code jobless-dev/bossbar-demo.txt} exists in the
     * game folder, run each of its lines as a {@code /jrbossbar} command and delete it.
     */
    public static void poll(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (FMLEnvironment.production || ++pollTicks % 5 != 0) return;
        java.nio.file.Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("jobless-dev").resolve("bossbar-demo.txt");
        if (!java.nio.file.Files.exists(file)) return;
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.delete(file);
            for (String line : lines) if (!line.isBlank()) net.neoforged.neoforge.client.ClientCommandHandler.runCommand("jrbossbar " + line.trim());
        } catch (java.io.IOException ignored) {
            // Being written right now: try again next time.
        }
    }

    private static void to(BossBarView.Stage next) {
        stage = next;
        stageAt = Util.getMillis();
    }

    private static int send() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        long now = Util.getMillis();
        int length = stage == BossBarView.Stage.INTRO ? INTRO_TICKS : stage == BossBarView.Stage.INTERMISSION ? PAUSE_TICKS : 0;
        int stageLeft = Math.max(0, length - (int) ((now - stageAt) / 50));
        int lineLeft = line == null ? 0 : Math.max(0, SAY_TICKS - (int) ((now - saidAt) / 50));
        ClientBossBars.accept(new BossBarView(ID, player.level().dimension(), player.position(), true,
                Component.literal("保罗·格雷拉特"), Component.literal("剑神流 · 水神流 · 北神流　上级"), EMBLEM, 2, phase, progress,
                new int[] {0x7F9AB8, 0xB8323A}, true, phase == 0, stage, stageLeft, transition,
                lineLeft > 0 ? line : null, lineId, lineLeft, 0));
        return 1;
    }

    private BossBarDemo() {}
}
