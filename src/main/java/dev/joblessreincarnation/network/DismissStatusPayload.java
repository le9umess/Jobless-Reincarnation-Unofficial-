package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Sent only to the requesting player; never exposes another player's identity. */
public record DismissStatusPayload(int nonce, boolean active, boolean ready, int holdTicks, int cost, List<BlockPos> positions) implements CustomPacketPayload {
    public DismissStatusPayload {
        positions = positions.stream().limit(6).map(BlockPos::immutable).toList();
        holdTicks = Math.max(1, holdTicks);
        cost = Math.max(0, cost);
    }
    public static final Type<DismissStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "dismiss_status"));
    public static final StreamCodec<FriendlyByteBuf, DismissStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.nonce()); buffer.writeBoolean(value.active()); buffer.writeBoolean(value.ready());
                buffer.writeVarInt(value.holdTicks()); buffer.writeVarInt(value.cost()); buffer.writeVarInt(value.positions().size());
                value.positions().forEach(buffer::writeBlockPos);
            }, buffer -> {
                int nonce = buffer.readVarInt(); boolean active = buffer.readBoolean(), ready = buffer.readBoolean();
                int ticks = buffer.readVarInt(), cost = buffer.readVarInt(), size = buffer.readVarInt();
                if (size < 0 || size > 6) throw new IllegalArgumentException("Dismiss preview exceeds a six-cell wall");
                List<BlockPos> cells = new ArrayList<>(size);
                for (int i = 0; i < size; i++) cells.add(buffer.readBlockPos());
                return new DismissStatusPayload(nonce, active, ready, ticks, cost, cells);
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}