package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.NpcGreetings;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The public greeting sequence the client's eye choice reads (user 11-05: Sylphiette may squeeze her eyes "><" now and then).
 * Each greeting moves the sequence on by two: once when it begins, once when its gesture clears. Server state only.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcEyeExpressionGameTests {
    private static final class Listener extends ServerPlayer {
        Listener(ServerLevel level) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), "Eyes" + UUID.randomUUID().toString().substring(0, 8)), ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}
            };
            setGameMode(GameType.SURVIVAL);
            setNoGravity(true);
        }
    }

    @GameTest(template = "empty", timeoutTicks = NpcGreetings.COOLDOWN_TICKS + 60)
    public static void sylphietteGreetingSequenceStepsByTwo(GameTestHelper h) {
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Listener player = new Listener(h.getLevel());
        player.setPos(h.absoluteVec(new Vec3(5.5, 1, 2.5)));
        h.getLevel().addNewPlayer(player);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
        SylphietteEntity sylph = ModContent.SYLPHIETTE.get().create(h.getLevel());
        if (sylph == null) throw new IllegalStateException("Missing registered Sylphiette");
        Vec3 at = h.absoluteVec(new Vec3(5.5, 1, 4.0));
        sylph.moveTo(at.x, at.y, at.z, 180, 0);
        sylph.setNoAi(true);
        sylph.setNoGravity(true);
        h.getLevel().addFreshEntity(sylph);
        long[] first = new long[1];
        Runnable close = () -> { sylph.discard(); NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); player.discard(); };
        try {
            h.assertTrue(sylph.tryBeginGreeting(player), "First greeting starts");
            first[0] = sylph.getData(ModAttachments.NPC_GESTURE_VISUAL).sequence();
        } catch (RuntimeException | Error failure) { close.run(); throw failure; }
        h.runAfterDelay(NpcGreetings.COOLDOWN_TICKS + 2, () -> {
            try {
                h.assertTrue(sylph.tryBeginGreeting(player), "Second greeting starts after the shared cooldown");
                long second = sylph.getData(ModAttachments.NPC_GESTURE_VISUAL).sequence();
                h.assertValueEqual(second - first[0], 2L, "Each greeting moves the public sequence on by two (first " + first[0] + ", second " + second + ")");
            } finally { close.run(); }
            h.succeed();
        });
    }

    private NpcEyeExpressionGameTests() {}
}
