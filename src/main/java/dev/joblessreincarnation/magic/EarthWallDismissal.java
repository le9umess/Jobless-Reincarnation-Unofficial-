package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

/** Server-authoritative removal of the remaining cells from exactly one owned construction. */
public final class EarthWallDismissal {
    public record Plan(List<BlockPos> positions, boolean valid, @Nullable WallIdentity identity, Component reason) {
        public Plan { positions = positions.stream().map(BlockPos::immutable).toList(); }
    }

    @FunctionalInterface
    interface BlockSetter {
        boolean set(ServerLevel level, BlockPos pos, BlockState state, int flags);
    }

    private static Plan result(List<BlockPos> positions, @Nullable WallIdentity identity, String reason) {
        return new Plan(positions, reason.equals("ready"), identity, message(reason));
    }

    private static Component message(String reason, Object... arguments) {
        return Component.translatable("message.jobless_reincarnation.dismiss." + reason, arguments);
    }

    /** No ownership data is trusted from the client and no unloaded chunk is requested. */
    public static Plan plan(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(MagicConfig.EARTH_WALL_RANGE.getAsInt()));
        BlockPos unloaded = BlockGetter.traverseBlocks(eye, end, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        if (unloaded != null) return result(List.of(), null, "unloaded");
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !level.getBlockState(hit.getBlockPos()).is(ModContent.EARTH_WALL.get())) {
            return result(List.of(), null, "range");
        }
        if (!(level.getBlockEntity(hit.getBlockPos()) instanceof EarthWallBlockEntity target) || target.identity().isEmpty()) {
            return result(List.of(), null, "unowned");
        }
        WallIdentity identity = target.identity().orElseThrow();
        if (!identity.owner().equals(player.getUUID())) return result(List.of(), null, "not_owner");
        for (BlockPos pos : identity.footprint()) {
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(new AABB(pos))) return result(List.of(), identity, "invalid");
            if (!level.hasChunkAt(pos)) return result(List.of(), identity, "unloaded");
        }
        List<BlockPos> remaining = new ArrayList<>(6);
        for (BlockPos pos : identity.footprint()) {
            if (level.getBlockState(pos).is(ModContent.EARTH_WALL.get())
                    && level.getBlockEntity(pos) instanceof EarthWallBlockEntity cell
                    && cell.identity().filter(identity::equals).isPresent()) remaining.add(pos);
        }
        return result(remaining, identity, remaining.isEmpty() ? "changed" : "ready");
    }

    private static boolean eligible(ServerPlayer player, ItemStack originalWand, boolean creative, ServerLevel expectedLevel, int expectedSlot) {
        return player.serverLevel() == expectedLevel && player.getInventory().selected == expectedSlot
                && player.isAlive() && !player.isSpectator() && !player.isShiftKeyDown()
                && !player.isUsingItem() && SpellCatalog.active(player) == null
                && player.containerMenu == player.inventoryMenu && player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE
                && player.getAbilities().mayBuild && player.getAbilities().instabuild == creative
                && player.getMainHandItem() == originalWand && originalWand.is(ModContent.TRAINING_STAFF.get())
                && EarthWallSpell.isLearned(player) && SpellCatalog.selected(player) == SpellCatalog.EARTH_WALL
                && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                && player.getData(ModAttachments.MANA).canCast(MagicConfig.EARTH_DISMISS_COST.getAsInt(), creative);
    }

    private static boolean allowed(ServerPlayer player, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (!player.serverLevel().mayInteract(player, pos)
                    || !player.mayUseItemAt(pos, Direction.UP, player.getMainHandItem())) return false;
        }
        return true;
    }

    public static boolean dismiss(ServerPlayer player, WallIdentity expected, ItemStack originalWand) {
        return dismiss(player, expected, originalWand, (level, pos, state, flags) -> level.setBlock(pos, state, flags));
    }

    // Partial-write failures are testable without changing production state or global event behavior.
    static boolean dismiss(ServerPlayer player, WallIdentity expected, ItemStack originalWand, BlockSetter setter) {
        ServerLevel level = player.serverLevel();
        boolean creative = player.getAbilities().instabuild;
        int selectedSlot = player.getInventory().selected;
        if (!eligible(player, originalWand, creative, level, selectedSlot)) return fail(player, message("invalid"));
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots) return fail(player, message("protected"));
        Plan plan = plan(player);
        if (!plan.valid()) return fail(player, plan.reason());
        if (!expected.equals(plan.identity())) return fail(player, message("changed"));
        if (!allowed(player, plan.positions())) return fail(player, message("protected"));
        Map<BlockPos, EarthWallBlockEntity> cells = new LinkedHashMap<>();
        for (BlockPos pos : plan.positions()) cells.put(pos, (EarthWallBlockEntity) level.getBlockEntity(pos));
        boolean denied = false;
        for (BlockPos pos : plan.positions()) {
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
            NeoForge.EVENT_BUS.post(event);
            denied |= event.isCanceled();
        }
        if (denied) return fail(player, message("protected"));
        Plan afterEvents = plan(player);
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots
                || !eligible(player, originalWand, creative, level, selectedSlot) || !allowed(player, plan.positions())
                || !afterEvents.valid() || !expected.equals(afterEvents.identity()) || !plan.positions().equals(afterEvents.positions())
                || !intact(level, cells, expected)) return fail(player, message("changed"));
        List<BlockSnapshot> snapshots = new ArrayList<>(cells.size());
        for (BlockPos pos : cells.keySet()) snapshots.add(BlockSnapshot.create(level.dimension(), level, pos, Block.UPDATE_ALL));
        List<BlockSnapshot> removed = new ArrayList<>(cells.size());
        boolean committed = false;
        try {
            level.captureBlockSnapshots = true;
            for (BlockSnapshot snapshot : snapshots) {
                BlockPos pos = snapshot.getPos();
                if (!sameCell(level, pos, cells.get(pos), expected)) return fail(player, message("changed"));
                int priorCaptureSize = level.capturedBlockSnapshots.size();
                boolean wrote;
                try {
                    wrote = setter.set(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                } finally {
                    if (level.getBlockState(pos).isAir()) removed.add(snapshot);
                    for (int index = level.capturedBlockSnapshots.size() - 1; index >= priorCaptureSize; index--) {
                        BlockSnapshot captured = level.capturedBlockSnapshots.get(index);
                        if (captured.getPos().equals(pos) && captured.getState() == snapshot.getState()) level.capturedBlockSnapshots.remove(index);
                    }
                }
                if (!wrote || !level.getBlockState(pos).isAir()) return fail(player, message("changed"));
            }
            if (snapshots.stream().anyMatch(snapshot -> !level.getBlockState(snapshot.getPos()).isAir())
                    || !eligible(player, originalWand, creative, level, selectedSlot)
                    || !player.getData(ModAttachments.MANA).trySpend(MagicConfig.EARTH_DISMISS_COST.getAsInt(),
                            MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), MagicConfig.EARTH_DISMISS_COOLDOWN.getAsInt(), creative)) {
                return fail(player, message("changed"));
            }
            committed = true;
        } finally {
            level.captureBlockSnapshots = false;
            if (!committed) rollback(level, removed);
        }
        player.syncData(ModAttachments.MANA);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), MagicConfig.EARTH_DISMISS_COOLDOWN.getAsInt());
        BlockState wall = ModContent.EARTH_WALL.get().defaultBlockState();
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            if (!level.getBlockState(pos).isAir()) continue;
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, 512);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, wall));
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall), pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5, 3, 0.25, 0.25, 0.25, 0.02);
        }
        level.playSound(null, plan.positions().getFirst(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 0.7F, 0.75F);
        player.displayClientMessage(message("success", snapshots.size()), true);
        return true;
    }

    private static boolean intact(ServerLevel level, Map<BlockPos, EarthWallBlockEntity> cells, WallIdentity identity) {
        return cells.entrySet().stream().allMatch(entry -> sameCell(level, entry.getKey(), entry.getValue(), identity));
    }

    private static boolean sameCell(ServerLevel level, BlockPos pos, EarthWallBlockEntity cell, WallIdentity identity) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).is(ModContent.EARTH_WALL.get())
                && level.getBlockEntity(pos) == cell && cell.identity().filter(identity::equals).isPresent();
    }

    private static void rollback(ServerLevel level, List<BlockSnapshot> removed) {
        boolean previousRestoring = level.restoringBlockSnapshots;
        level.restoringBlockSnapshots = true;
        try {
            for (int index = removed.size() - 1; index >= 0; index--) {
                BlockSnapshot snapshot = removed.get(index);
                if (level.getBlockState(snapshot.getPos()).isAir()) {
                    snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        } finally {
            level.restoringBlockSnapshots = previousRestoring;
        }
    }

    private static boolean fail(ServerPlayer player, Component reason) {
        player.displayClientMessage(reason, true);
        return false;
    }

    private EarthWallDismissal() {}
}

