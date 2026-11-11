package dev.joblessreincarnation.magic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Bounded identity for one six-cell construction. No global history or unloaded chunk lookup. */
public record WallIdentity(UUID owner, UUID castId, BlockPos bottomCenter, Direction.Axis axis) {
    public WallIdentity {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(castId);
        bottomCenter = Objects.requireNonNull(bottomCenter).immutable();
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) throw new IllegalArgumentException("Wall axis must be horizontal");
    }

    public List<BlockPos> footprint() {
        Direction across = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        BlockPos left = bottomCenter.relative(across, -1);
        BlockPos right = bottomCenter.relative(across);
        return List.of(bottomCenter, bottomCenter.above(), left, left.above(), right, right.above());
    }

    public boolean contains(BlockPos pos) { return footprint().contains(pos); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", owner);
        tag.putUUID("cast", castId);
        tag.putLong("center", bottomCenter.asLong());
        tag.putString("axis", axis.getName());
        return tag;
    }

    public static Optional<WallIdentity> load(CompoundTag tag) {
        if (!tag.hasUUID("owner") || !tag.hasUUID("cast") || !tag.contains("center", Tag.TAG_LONG)) return Optional.empty();
        Direction.Axis axis = switch (tag.getString("axis")) {
            case "x" -> Direction.Axis.X;
            case "z" -> Direction.Axis.Z;
            default -> null;
        };
        if (axis == null) return Optional.empty();
        return Optional.of(new WallIdentity(tag.getUUID("owner"), tag.getUUID("cast"), BlockPos.of(tag.getLong("center")), axis));
    }
}
