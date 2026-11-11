package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** A small placement transaction. Third-party changes are not part of its rollback ownership. */
final class EarthWallPlacement {
    @FunctionalInterface
    interface BlockSetter {
        boolean set(ServerLevel level, BlockPos pos, BlockState state, int flags);
    }

    static boolean place(ServerPlayer player, EarthWallSpell.Plan plan, BooleanSupplier settle) {
        return place(player, plan, settle, (level, pos, state, flags) -> level.setBlock(pos, state, flags), () -> {});
    }

    static boolean place(ServerPlayer player, EarthWallSpell.Plan plan, BooleanSupplier settle, Runnable onCommitted) {
        return place(player, plan, settle, (level, pos, state, flags) -> level.setBlock(pos, state, flags), onCommitted);
    }

    // The package-local setter seam exercises partial engine failures without a production global test switch.
    static boolean place(ServerPlayer player, EarthWallSpell.Plan plan, BooleanSupplier settle, BlockSetter setter) {
        return place(player, plan, settle, setter, () -> {});
    }

    /** The callback runs once after payment commits and snapshot capture is restored, before effect notifications. */
    static boolean place(ServerPlayer player, EarthWallSpell.Plan plan, BooleanSupplier settle,
            BlockSetter setter, Runnable onCommitted) {
        Objects.requireNonNull(onCommitted, "onCommitted");
        ServerLevel level = player.serverLevel();
        if (!plan.valid() || !EarthWallSpell.validate(player, plan.positions(), false).valid()
                || !EarthWallSpell.allowed(player, plan.positions())
                || level.captureBlockSnapshots || level.restoringBlockSnapshots) return false;
        BlockPos center = plan.positions().getFirst();
        Direction.Axis axis = plan.positions().get(2).getX() == center.getX() ? Direction.Axis.Z : Direction.Axis.X;
        WallIdentity identity = new WallIdentity(player.getUUID(), UUID.randomUUID(), center, axis);
        if (!new HashSet<>(identity.footprint()).equals(new HashSet<>(plan.positions()))) return false;
        BlockState wall = ModContent.EARTH_WALL.get().defaultBlockState();
        List<BlockSnapshot> snapshots = new ArrayList<>(6);
        List<BlockSnapshot> attempted = new ArrayList<>(6);
        Map<BlockPos, EarthWallBlockEntity> owned = new HashMap<>();
        for (BlockPos pos : plan.positions()) snapshots.add(BlockSnapshot.create(level.dimension(), level, pos, Block.UPDATE_ALL));
        boolean committed = false;
        try {
            level.captureBlockSnapshots = true;
            try {
                for (BlockSnapshot snapshot : snapshots) {
                    if (level.getBlockState(snapshot.getPos()) != snapshot.getState()) return false;
                    attempted.add(snapshot);
                    int priorCaptureSize = level.capturedBlockSnapshots.size();
                    boolean wrote;
                    try {
                        wrote = setter.set(level, snapshot.getPos(), wall, Block.UPDATE_ALL);
                    } finally {
                        // Track a newly created blank BE even if the setter reports failure after writing.
                        if (level.getBlockState(snapshot.getPos()) == wall
                                && level.getBlockEntity(snapshot.getPos()) instanceof EarthWallBlockEntity cell
                                && cell.identity().isEmpty()) {
                            owned.put(snapshot.getPos(), cell);
                            cell.bind(identity);
                        }
                        for (int index = level.capturedBlockSnapshots.size() - 1; index >= priorCaptureSize; index--) {
                            BlockSnapshot captured = level.capturedBlockSnapshots.get(index);
                            if (captured.getPos().equals(snapshot.getPos()) && captured.getState() == snapshot.getState()) {
                                level.capturedBlockSnapshots.remove(index);
                            }
                        }
                    }
                    if (!wrote || !ours(level, snapshot.getPos(), owned, identity)) return false;
                }
            } finally {
                level.captureBlockSnapshots = false;
            }
            if (EventHooks.onMultiBlockPlace(player, snapshots, Direction.UP)) return false;
            if (!EarthWallSpell.validate(player, plan.positions(), true).valid()
                    || !EarthWallSpell.allowed(player, plan.positions())
                    || plan.positions().stream().anyMatch(pos -> !ours(level, pos, owned, identity))
                    || !settle.getAsBoolean()) return false;
            committed = true;
        } finally {
            level.captureBlockSnapshots = false;
            if (!committed) rollback(level, attempted, owned, identity);
        }
        // Settlement is final here. Grow before neighbor/game-event hooks can mutate the payer's state.
        onCommitted.run();
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            if (!ours(level, pos, owned, identity)) continue;
            wall.onPlace(level, pos, snapshot.getState(), false);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), snapshot.getState(), wall, Block.UPDATE_ALL, 512);
            level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(player, wall));
        }
        return true;
    }

    private static boolean ours(ServerLevel level, BlockPos pos, Map<BlockPos, EarthWallBlockEntity> owned, WallIdentity identity) {
        EarthWallBlockEntity cell = owned.get(pos);
        return cell != null && level.getBlockState(pos).is(ModContent.EARTH_WALL.get())
                && level.getBlockEntity(pos) == cell && cell.identity().filter(identity::equals).isPresent();
    }

    private static void rollback(ServerLevel level, List<BlockSnapshot> attempted,
            Map<BlockPos, EarthWallBlockEntity> owned, WallIdentity identity) {
        boolean previousRestoring = level.restoringBlockSnapshots;
        level.restoringBlockSnapshots = true;
        try {
            for (int index = attempted.size() - 1; index >= 0; index--) {
                BlockSnapshot snapshot = attempted.get(index);
                if (ours(level, snapshot.getPos(), owned, identity)) {
                    snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        } finally {
            level.restoringBlockSnapshots = previousRestoring;
        }
    }

    private EarthWallPlacement() {}
}
